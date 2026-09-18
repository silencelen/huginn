package com.silencelen.huginn.desktop.notify

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE REGISTRATION THAT WAS HALF-DONE.
 *
 * Field report: a toast button on Windows answered with "don't know how to open
 * the link huginn". The cause was not the URL — [Activations] builds a perfectly
 * well-formed `huginn://answer?…` and [ActivationTest] has always covered it —
 * but the registry. [SchemeRegistrar] wrote the scheme with three `reg add`
 * invocations, and the third one, the only one that matters, could never succeed:
 *
 * ```
 * reg add …\shell\open\command /ve /d "\"C:\…\app.exe\" \"%1\"" /f
 * ```
 *
 * That `/d` value holds both a space and interior double quotes. Java's Windows
 * `ProcessImpl` quotes a space-containing argument WITHOUT escaping the interior
 * ones (the default `allowAmbiguousCommands` mode), so reg.exe received the path
 * and `%1` as two separate arguments and answered `Invalid syntax` with exit 1.
 * Reproduced under wine against a real Windows JDK 17, where `reg query` then
 * confirmed the key absent — and the same two-value, no-command shape was found
 * sitting in this repo's own release wine prefix, written by the shipped build.
 *
 * The first two writes SUCCEEDED, which is why the symptom is a dialog rather
 * than silence: the machine claimed the scheme (`URL Protocol` present) with
 * nothing to run. Half a registration is worse than none.
 *
 * So the values now travel in a `.reg` file imported by path, and this file
 * asserts that file's exact bytes — because there is no Windows in this dev loop
 * and the text IS the contract.
 */
class SchemeRegistrarTest {

    // -------------------------------------------------------------- escaping

    @Test
    fun `reg escaping doubles backslashes and quotes, and leaves percent alone`() {
        assertEquals("""C:\\Program Files\\huginn""", SchemeRegistrar.regEscape("""C:\Program Files\huginn"""))
        assertEquals("""\"quoted\"""", SchemeRegistrar.regEscape(""""quoted""""))
        // `%1` is a literal in a REG_SZ. Escaping it — the instinct borrowed from
        // .desktop files and from cmd — would register a handler that launches the
        // app with the string "%1" instead of the URL.
        assertEquals("%1", SchemeRegistrar.regEscape("%1"))
    }

    @Test
    fun `backslash is escaped before quote, not after`() {
        // The ordering trap: quotes first would then double the backslash this
        // very function put in front of them, turning \" into \\" — a literal
        // backslash followed by an end-of-string.
        assertEquals("""\\\"""", SchemeRegistrar.regEscape("""\""""))
    }

    // ------------------------------------------------------------- .reg file

    @Test
    fun `the reg file registers all three values`() {
        val reg = SchemeRegistrar.windowsRegFile("""C:\Users\jake\AppData\Local\Programs\huginn-desktop-kt\huginn-desktop-kt.exe""")
        assertEquals(
            "Windows Registry Editor Version 5.00\r\n" +
                "\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Classes\\huginn]\r\n" +
                "@=\"URL:Huginn Protocol\"\r\n" +
                "\"URL Protocol\"=\"\"\r\n" +
                "\r\n" +
                "[HKEY_CURRENT_USER\\Software\\Classes\\huginn\\shell\\open\\command]\r\n" +
                "@=\"\\\"C:\\\\Users\\\\jake\\\\AppData\\\\Local\\\\Programs\\\\huginn-desktop-kt\\\\huginn-desktop-kt.exe\\\" \\\"%1\\\"\"\r\n" +
                "\r\n",
            reg,
        )
    }

    @Test
    fun `THE COMMAND KEY IS PRESENT — the one the old code never wrote`() {
        val reg = SchemeRegistrar.windowsRegFile("""C:\x\app.exe""")
        assertTrue(
            reg.contains("""[HKEY_CURRENT_USER\Software\Classes\huginn\shell\open\command]"""),
            "no shell\\open\\command section — this is the exact hole that produced " +
                "\"don't know how to open the link huginn\"\n$reg",
        )
    }

    @Test
    fun `a path with spaces keeps its quotes so argv is not truncated`() {
        // %LOCALAPPDATA% under a profile named "Firstname Lastname" — the ordinary
        // case, not the exotic one. Unquoted, the shell hands the app a truncated
        // argv and the URL is lost even though the scheme resolved.
        val exe = """C:\Users\Jake Monahan\AppData\Local\Programs\huginn kt\app.exe"""
        val reg = SchemeRegistrar.windowsRegFile(exe)
        val line = reg.lineSequence().first { it.startsWith("@=") && it.contains("app.exe") }
        // Undo the .reg escaping to get what the registry will actually hold.
        val stored = line.removePrefix("@=\"").removeSuffix("\"")
            .replace("\\\"", "\"").replace("\\\\", "\\")
        assertEquals("\"" + exe + "\" \"%1\"", stored)
    }

    @Test
    fun `the banner and CRLF line endings are intact`() {
        val reg = SchemeRegistrar.windowsRegFile("""C:\x\app.exe""")
        // `reg import` refuses a file whose first line is not this, and refuses a
        // LF-only file on some builds. Both failures are exit 1 with no detail.
        assertTrue(reg.startsWith("Windows Registry Editor Version 5.00\r\n"), reg.take(60))
        assertFalse(reg.replace("\r\n", "").contains("\n"), "a bare LF survived: $reg")
    }

    @Test
    fun `the root and command constants agree with the scheme`() {
        assertEquals("""Software\Classes\huginn""", SchemeRegistrar.WINDOWS_ROOT)
        assertEquals("""Software\Classes\huginn\shell\open\command""", SchemeRegistrar.WINDOWS_COMMAND_KEY)
        assertTrue(SchemeRegistrar.WINDOWS_ROOT.endsWith(Activations.SCHEME))
    }

    // --------------------------------------------------- the installer's half

    /**
     * THE INSTALLER MUST WRITE IT TOO, asserted against the source the way
     * `CapBeforeFillTest` does — because the runtime backstop cannot cover the
     * window that matters most. A toast can arrive before the app has ever been
     * launched on a fresh install (the Start Menu shortcut is enough for Windows
     * to file one), and a scheme nobody has claimed fails with no error anywhere.
     *
     * The uninstaller has deleted `Software\Classes\huginn` since the beginning.
     * NOTHING EVER WROTE IT — an asymmetry no test could see, since the only
     * reader was Windows. This is that test.
     */
    @Test
    fun `the NSIS installer registers the scheme and the uninstaller removes it`() {
        val nsi = nsiSource()
        val root = SchemeRegistrar.WINDOWS_ROOT
        val cmd = SchemeRegistrar.WINDOWS_COMMAND_KEY

        assertTrue(
            nsi.contains("WriteRegStr HKCU \"" + root + "\" \"\" \"URL:Huginn Protocol\""),
            "the installer does not write the protocol's display name",
        )
        assertTrue(
            nsi.contains("WriteRegStr HKCU \"" + root + "\" \"URL Protocol\" \"\""),
            "the installer does not write the URL Protocol flag — without it the " +
                "shell does not treat the key as a protocol handler at all",
        )
        // Single-quoted on the NSIS side precisely so the value can carry the
        // double quotes a spaced path needs.
        assertTrue(
            nsi.contains("WriteRegStr HKCU \"" + cmd + "\" \"\" '\"${'$'}INSTDIR\\${'$'}{APP_EXE}\" \"%1\"'"),
            "the installer does not write shell\\open\\command, or writes it without " +
                "the quotes a path containing a space needs",
        )
        assertTrue(
            nsi.contains("DeleteRegKey HKCU \"" + root + "\""),
            "the uninstaller leaves the scheme pointing at an exe it just deleted",
        )
    }

    private fun nsiSource(): String {
        // Walk up: tests run with the module dir as cwd under Gradle, but a rerun
        // from the repo root is a normal thing to do by hand.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app-desktop/packaging/huginn-desktop-kt.nsi")
            if (f.isFile) return f.readText()
            val g = File(dir, "packaging/huginn-desktop-kt.nsi")
            if (g.isFile) return g.readText()
            dir = dir.parentFile
        }
        error("huginn-desktop-kt.nsi not found from ${File(".").absolutePath}")
    }
}
