package com.silencelen.huginn.desktop.notify

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REGISTERED IS NOT RESOLVED — the Linux half of [SchemeRegistrar], with the
 * machine stubbed.
 *
 * The Omarchy field report (Hyprland/Wayland, portable install, 2026-11-07) had
 * the per-user entry written, `mimeapps.list` naming it and `mimeinfo.cache`
 * indexing it, and every notification click opened a blank browser tab: the
 * entry's Exec was quoted, xdg-utils' resolver `command -v`s the first
 * whitespace-split word of Exec, and `"/home/…` with its quote is not a
 * binary. The old registrar wrote the file, ran two commands and reported
 * "registered". These tests pin the contract instead: verify, register when
 * the machine does not name us, verify again, and the same first-word check
 * xdg-open makes — with one honest line when the link still will not open the
 * app.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class LinuxSchemeTest {

    private val dirs = mutableListOf<File>()

    private fun appsDir(): File = Files.createTempDirectory("huginn-apps").toFile().also { dirs += it }

    @AfterTest
    fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    private val portable = "/home/alex/.local/share/huginn-desktop/current/bin/huginn-desktop-kt"
    private val ours = SchemeRegistrar.LINUX_DESKTOP_FILE
    private val mime = SchemeRegistrar.LINUX_MIME_TYPE

    /**
     * The machine as a table: what xdg-mime answers, which tools exist, and
     * everything that was run. [stuck] is a machine where `xdg-mime default`
     * does not take (a read-only config, another handler pinned by the desktop).
     */
    private class FakeXdg(
        var handler: String?,
        val tools: Set<String> = setOf("xdg-mime", "update-desktop-database"),
        val stuck: Boolean = false,
    ) {
        val ran = mutableListOf<List<String>>()
        fun run(cmd: List<String>): Boolean {
            ran += cmd
            if (cmd.take(2) == listOf("xdg-mime", "default") && !stuck) handler = cmd[2]
            return true
        }
        fun query(cmd: List<String>): String? {
            ran += cmd
            return if (cmd.take(3) == listOf("xdg-mime", "query", "default")) (handler ?: "") + "\n" else null
        }
        fun onPath(bin: String): Boolean = bin in tools
        val queries: Int get() = ran.count { it.take(3) == listOf("xdg-mime", "query", "default") }
        val defaults: List<List<String>> get() = ran.filter { it.take(2) == listOf("xdg-mime", "default") }
        val indexed: List<List<String>> get() = ran.filter { it.firstOrNull() == "update-desktop-database" }
    }

    private fun scheme(dir: File, xdg: FakeXdg, canExecute: (String) -> Boolean = { it == portable }) =
        SchemeRegistrar.LinuxScheme(dir, xdg::run, xdg::query, xdg::onPath, canExecute)

    // ------------------------------------------------------- exec argument

    @Test
    fun `the launcher stands bare unless the spec reserves a character in it, then quoted with the spec's escapes`() {
        assertEquals(portable, SchemeRegistrar.execArgument(portable))
        assertEquals("\"/home/Alex Doe/bin/huginn-desktop-kt\"", SchemeRegistrar.execArgument("/home/Alex Doe/bin/huginn-desktop-kt"))
        assertEquals("\"/home/a\\\$b/x\"", SchemeRegistrar.execArgument("/home/a\$b/x"))
        assertEquals("\"/home/a\\\"b/x\"", SchemeRegistrar.execArgument("/home/a\"b/x"))
        assertEquals("\"/home/a\\\\b/x\"", SchemeRegistrar.execArgument("/home/a\\b/x"))
        assertEquals("\"/home/a\\`b/x\"", SchemeRegistrar.execArgument("/home/a`b/x"))
        assertEquals("\"/home/a(b)/x\"", SchemeRegistrar.execArgument("/home/a(b)/x"))
        // % is the field-code escape and is doubled in both forms.
        assertEquals("/home/a%%b/x", SchemeRegistrar.execArgument("/home/a%b/x"))
        assertEquals("\"/home/a%%b c/x\"", SchemeRegistrar.execArgument("/home/a%b c/x"))
    }

    // ------------------------------------------------ verify, register, verify

    @Test
    fun `a machine that names another handler is registered and read back as ours`() {
        val dir = appsDir()
        val xdg = FakeXdg(handler = "microsoft-edge.desktop")
        val status = scheme(dir, xdg).register(portable)

        assertTrue(status.ok, status.sentence)
        assertEquals(SchemeRegistrar.linuxDesktopEntry(portable), File(dir, ours).readText())
        assertEquals(listOf(listOf("xdg-mime", "default", ours, mime)), xdg.defaults)
        assertEquals(listOf(listOf("update-desktop-database", dir.absolutePath)), xdg.indexed, "indexed once, not once per step")
        assertEquals(2, xdg.queries, "asked before and after")
        assertEquals(ours, xdg.handler)
    }

    @Test
    fun `a machine that already names us, with the entry current, is left alone`() {
        val dir = appsDir()
        File(dir, ours).writeText(SchemeRegistrar.linuxDesktopEntry(portable))
        val before = File(dir, ours).lastModified()
        val xdg = FakeXdg(handler = ours)
        val status = scheme(dir, xdg).register(portable)

        assertTrue(status.ok, status.sentence)
        assertTrue(xdg.defaults.isEmpty(), "no re-registration: ${xdg.ran}")
        assertTrue(xdg.indexed.isEmpty(), "no re-index: ${xdg.ran}")
        assertEquals(before, File(dir, ours).lastModified(), "the file was not rewritten")
    }

    @Test
    fun `an older quoted entry is repaired in place and re-indexed`() {
        val dir = appsDir()
        // What every launch before this fix wrote — the field report's file.
        File(dir, ours).writeText(
            "[Desktop Entry]\nType=Application\nName=Huginn\nComment=huginn desktop client\n" +
                "Exec=\"$portable\" %u\nIcon=/home/alex/.local/share/huginn-desktop/current/lib/huginn-desktop-kt.png\n" +
                "Terminal=false\nCategories=Development;Utility;\nMimeType=$mime;\n",
        )
        val xdg = FakeXdg(handler = ours)
        val status = scheme(dir, xdg).register(portable)

        assertTrue(status.ok, status.sentence)
        assertEquals(SchemeRegistrar.linuxDesktopEntry(portable), File(dir, ours).readText())
        assertTrue(File(dir, ours).readText().contains("Exec=$portable %u"))
        assertEquals(1, xdg.indexed.size, "the index must learn the new Exec: ${xdg.ran}")
    }

    @Test
    fun `still unresolved after registering - the contract's one line, with what was found`() {
        val dir = appsDir()
        val xdg = FakeXdg(handler = "microsoft-edge.desktop", stuck = true)
        val status = scheme(dir, xdg).register(portable)

        assertFalse(status.ok)
        assertTrue(status.relevant)
        assertEquals(
            "Notification links will not open the app: huginn:// has no handler " +
                "(xdg-mime names 'microsoft-edge.desktop' for x-scheme-handler/huginn)",
            status.sentence,
        )
        assertTrue(File(dir, ours).isFile, "the entry is still written: a desktop that scans the dir may pick it up")
    }

    @Test
    fun `nothing named at all reads as nothing`() {
        val xdg = FakeXdg(handler = null, stuck = true)
        val status = scheme(appsDir(), xdg).register(portable)
        assertFalse(status.ok)
        assertTrue(status.sentence.endsWith("(xdg-mime names nothing for x-scheme-handler/huginn)"), status.sentence)
    }

    @Test
    fun `no xdg-utils - the entry is written, nothing is run, and the link is said to be unrouted`() {
        val dir = appsDir()
        val xdg = FakeXdg(handler = null, tools = emptySet())
        val status = scheme(dir, xdg).register(portable)

        assertTrue(File(dir, ours).isFile)
        assertTrue(xdg.ran.isEmpty(), "nothing to run: ${xdg.ran}")
        assertFalse(status.ok)
        assertTrue(status.sentence.startsWith("Notification links will not open the app: "), status.sentence)
        assertTrue(status.sentence.contains("xdg-utils is not installed"), status.sentence)
    }

    // ------------------------------------------------- the first-word check

    /**
     * ⚠ THE QUERY ALONE PASSED THE FIELD REPORT. `xdg-mime query default` found
     * our file through `mimeinfo.cache` and answered it, while `xdg-open` could
     * not run the quoted Exec and opened a browser. So the registrar makes the
     * same first-word check xdg-open makes, and says so when it fails.
     */
    @Test
    fun `a handler xdg-mime names but xdg-open cannot run is reported broken, with the remedy`() {
        val spaced = "/home/Alex Doe/.local/share/huginn-desktop/current/bin/huginn-desktop-kt"
        val xdg = FakeXdg(handler = ours)
        val status = scheme(appsDir(), xdg) { true }.register(spaced)
        assertFalse(status.ok, status.sentence)
        assertTrue(status.sentence.startsWith("Notification links will not open the app: "), status.sentence)
        assertTrue(status.sentence.contains("quoted Exec") && status.sentence.contains("no spaces or quotes"), status.sentence)

        val notThere = scheme(appsDir(), FakeXdg(handler = ours)) { false }.register(portable)
        assertFalse(notThere.ok)
        assertTrue(notThere.sentence.contains("'$portable', which is not an executable file"), notThere.sentence)
    }

    @Test
    fun `the first-word rule is xdg-utils' own - whitespace split, percent unescaped, quotes fatal`() {
        val launch = SchemeRegistrar.LinuxScheme.Companion
        assertNull(launch.launchProblem("Exec=$portable %u") { it == portable })
        assertNull(launch.launchProblem("Exec=/opt/a%%b/bin/x %u") { it == "/opt/a%b/bin/x" }, "xdg-utils hands the first word through its own % handling")
        assertTrue(launch.launchProblem("Exec=\"$portable\" %u") { true }!!.contains("quoted Exec"))
        assertTrue(launch.launchProblem("Exec=/home/alex/x %u") { false }!!.contains("not an executable file"))
        assertTrue(launch.launchProblem("[Desktop Entry]\nName=Huginn\n") { true }!!.contains("no Exec line"))
    }

    // ----------------------------------------------------------- the surface

    @Test
    fun `a broken link is one line on the notification path row, and a working one adds nothing`() {
        val broken = SchemeRegistrar.broken("huginn:// has no handler (xdg-mime names nothing for $mime)")
        val base = Notifiers.pathSummary("libnotify")
        val said = Notifiers.pathSummary("libnotify", broken)
        assertTrue(said.startsWith(base), said)
        assertTrue(said.endsWith(broken.sentence), said)
        assertEquals(base, Notifiers.pathSummary("libnotify", SchemeRegistrar.LinkStatus(ok = true, sentence = "registered")))
        assertEquals(base, Notifiers.pathSummary("libnotify", SchemeRegistrar.LinkStatus(ok = false, sentence = "unpackaged run", relevant = false)))
        assertEquals(base, Notifiers.pathSummary("libnotify", null))
    }
}
