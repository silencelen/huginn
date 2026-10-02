package com.silencelen.huginn.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2026-10-02 desktop breaker round on "Open in PowerShell / terminal".
 *
 * Three defects, one theme: the launcher assumed `huginn '<name>'` means "attach
 * to <name>", and it does not always.
 *  - A session NAMED like a CLI verb ran the verb: `update` replaced the installed
 *    client, `solo` threw every other device off main, `uninstall` started the
 *    uninstall flow.
 *  - The name gate was the launcher's own (no leading '_', '.' allowed, 64 long,
 *    case ignored), so it refused sessions the daemon made and opened ones the CLI
 *    then refused or rewrote, and reported success either way.
 *  - On Linux the window relied on a login shell finding `huginn`, which a
 *    ~/.bash_profile + Debian .bashrc guard defeats; and a machine with no CLI at
 *    all still reported success, so the clipboard fallback never fired.
 */
class TerminalLaunchNamesTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "client/huginn.sh").isFile }
            ?: error("cannot find the repo root from ${File("").absolutePath}")

    /** A home that HAS the CLI, so open() gets as far as the spawn. */
    private fun homeWithCli(): File = Files.createTempDirectory("tl-home").toFile().also {
        File(it, ".huginn").mkdirs()
        File(it, ".huginn/huginn.sh").writeText("# stub\n")
        File(it, ".huginn/huginn.ps1").writeText("# stub\n")
        it.deleteOnExit()
    }

    /** Every word the CLI's dispatcher treats as a verb, read from BOTH clients. */
    private fun cliVerbs(): Set<String> {
        val sh = File(root(), "client/huginn.sh").readText()
        val dispatcher = sh.substringAfter("\nhuginn() {").substringBefore("\n}\n")
        val shVerbs = Regex("""(?m)^    ([a-z'?/|-]+)\)""").findAll(dispatcher)
            .flatMap { it.groupValues[1].split('|') }.map { it.trim('\'') }
        val ps = File(root(), "client/huginn.ps1").readText()
        val psVerbs = Regex("""\${'$'}args\[0\] -(?:eq|in) ([^)]*)""").findAll(ps)
            .flatMap { m -> Regex("'([^']*)'").findAll(m.groupValues[1]).map { it.groupValues[1] } }
        // Only the words a session could actually be called matter here.
        return (shVerbs + psVerbs).filter { TerminalLaunch.problem(it) == null }.toSet()
    }

    @Test
    fun `the verb list is the CLI's own, read from both clients`() {
        val verbs = cliVerbs()
        assertTrue("update" in verbs && "solo" in verbs && "list" in verbs, "parsed nothing useful: $verbs")
        assertEquals(verbs, TerminalLaunch.CLI_VERBS, "TerminalLaunch.CLI_VERBS drifted from the CLI dispatchers")
    }

    @Test
    fun `a session named like a CLI verb attaches, it never runs the verb`() {
        for (v in cliVerbs()) {
            val win = TerminalLaunch.decoded(TerminalLaunch.argv(v, TerminalLaunch.Os.WINDOWS)!!.last()
                .substringAfterLast("','").removeSuffix("'"))
            assertTrue("huginn attach '$v'" in win, "windows '$v': $win")
            assertFalse(Regex("""(^|[;{}]\s*)huginn '$v'""").containsMatchIn(win), "windows runs the bare verb for '$v': $win")
            for (os in listOf(TerminalLaunch.Os.LINUX, TerminalLaunch.Os.MAC)) {
                val line = TerminalLaunch.argv(v, os)!!.joinToString(" ")
                assertTrue("huginn attach '$v'" in line, "$os '$v': $line")
                assertFalse(Regex("""(then|else|;)\s*huginn '$v'""").containsMatchIn(line), "$os runs the bare verb for '$v': $line")
            }
            assertEquals("huginn attach '$v'", TerminalLaunch.shellLine(v))
        }
    }

    @Test
    fun `an ordinary name still uses the plain form an older CLI understands`() {
        val win = TerminalLaunch.script("jtyper")
        assertTrue("huginn attach 'jtyper'" in win && "huginn 'jtyper'" in win, win)
        assertEquals("huginn 'jtyper'", TerminalLaunch.shellLine("jtyper"))
    }

    @Test
    fun `the gate is the daemon's name rule, with the reason the daemon would give`() {
        val home = homeWithCli()
        // appd NAME_RE: ^[A-Za-z0-9_][A-Za-z0-9_-]{0,49}$ — and the CLI folds case.
        for (good in listOf("_x", "_build", "a", "build-box", "a".repeat(50), "9lives")) {
            assertNull(TerminalLaunch.problem(good), good)
            var spawned = false
            assertNull(TerminalLaunch.open(good, TerminalLaunch.Os.WINDOWS, home) { spawned = true }, good)
            assertTrue(spawned, good)
        }
        val reasons = mapOf(
            "a.b" to "\".\"",
            "9.9.9" to "\".\"",
            "a".repeat(51) to "at most 50",
            "A".repeat(64) to "at most 50",
            "Foo" to "'foo'",
            "-x" to "starts with",
        )
        for ((bad, why) in reasons) {
            var spawned = false
            val said = TerminalLaunch.open(bad, TerminalLaunch.Os.WINDOWS, home) { spawned = true }
            assertNotNull(said, bad)
            assertTrue(why in said, "'$bad' said: $said")
            assertFalse("characters a shell would read" in said, "'$bad' blamed the shell: $said")
            assertFalse(spawned, bad)
        }
    }

    @Test
    fun `a name the CLI cannot open is offered as a copy, not as an open`() {
        val ok = TerminalLaunch.offer("main", TerminalLaunch.Os.WINDOWS)
        assertTrue(ok.opens)
        assertEquals("Open in PowerShell", ok.label)
        val upper = TerminalLaunch.offer("Foo", TerminalLaunch.Os.WINDOWS)
        assertFalse(upper.opens)
        assertEquals("ssh -t huginn \"tmux attach -t '=Foo'\"", upper.copy)
        // Nothing shell-unsafe is ever put on the clipboard either.
        assertNull(TerminalLaunch.offer("x;rm", TerminalLaunch.Os.WINDOWS).copy)
    }

    @Test
    fun `linux sources the CLI itself, the way the windows script does`() {
        val line = TerminalLaunch.argv("main", TerminalLaunch.Os.LINUX)!!.last()
        assertTrue(line.startsWith("[ -f ~/.huginn/huginn.sh ] && . ~/.huginn/huginn.sh; "), line)
        assertTrue(line.endsWith("; exec bash"), line)
    }

    @Test
    fun `no CLI installed is a failure the caller hears about, not a silent success`() {
        val empty = Files.createTempDirectory("tl-empty").toFile().also { it.deleteOnExit() }
        for (os in TerminalLaunch.Os.entries) {
            var spawned = false
            val said = TerminalLaunch.open("main", os, empty) { spawned = true }
            assertNotNull(said, "$os")
            assertTrue("not installed" in said, "$os said: $said")
            assertFalse(spawned, "$os")
        }
        var got: List<String>? = null
        assertNull(TerminalLaunch.open("main", TerminalLaunch.Os.LINUX, homeWithCli()) { got = it })
        assertEquals("x-terminal-emulator", got!![0])
    }

    @Test
    fun `the session header asks for the offer instead of opening every name`() {
        // Source shape, because the menu lives inside a composable that needs a
        // whole session to render: the header must decide from offer() — the
        // only place that knows a name the CLI cannot open — and must not call
        // open() outside the `opens` branch.
        val view = File(root(), "mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SessionView.kt").readText()
        assertTrue("TerminalLaunch.offer(name)" in view, "SessionView does not consult TerminalLaunch.offer")
        val opensBranch = view.substringAfter("offer.opens -> ", "").substringBefore("offer.copy != null ->")
        assertTrue("TerminalLaunch.open(name)" in opensBranch, "open() is not confined to the opens branch")
        assertEquals(1, Regex("""TerminalLaunch\.open\(""").findAll(view).count(), "a second open() call bypasses the offer")
    }
}
