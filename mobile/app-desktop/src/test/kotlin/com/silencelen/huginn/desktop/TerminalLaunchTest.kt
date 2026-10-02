package com.silencelen.huginn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Open in PowerShell" on a session's name. The spawn itself is a ProcessBuilder
 * nobody can run on a test box; what is pinned is everything up to it — the argv,
 * the encoding that keeps `$HOME` and its spaces intact, and the gate on names.
 */
class TerminalLaunchTest {

    @Test
    fun `windows opens a profile-loading window through an ENCODED command`() {
        val argv = TerminalLaunch.argv("jtyper", TerminalLaunch.Os.WINDOWS)!!
        assertEquals("powershell.exe", argv[0])
        // The OUTER shell is -NoProfile like every spawn in this client; the
        // window it starts must NOT be, because `huginn` lives in the profile.
        assertTrue("-NoProfile" in argv)
        val command = argv.last()
        assertTrue(command.startsWith("Start-Process powershell.exe -ArgumentList '-NoExit','-EncodedCommand','"), command)
        assertTrue("-NoProfile" !in command.substringAfter("-ArgumentList"), "the window itself loads the profile")
        val b64 = command.substringAfterLast("','").removeSuffix("'")
        val script = TerminalLaunch.decoded(b64)
        assertEquals(TerminalLaunch.script("jtyper"), script)
        assertTrue(script.endsWith("huginn 'jtyper'"), script)
        // Dot-sourced explicitly, so a profile that forgot the CLI still gets it.
        assertTrue("\$HOME\\.huginn\\huginn.ps1" in script, script)
    }

    @Test
    fun `the encoding is UTF-16LE base64, which is what -EncodedCommand reads`() {
        // "hi" in UTF-16LE is 68 00 69 00.
        assertEquals("aABpAA==", TerminalLaunch.encoded("hi"))
        assertEquals("hi", TerminalLaunch.decoded("aABpAA=="))
    }

    @Test
    fun `mac and linux open a terminal running the same line`() {
        val mac = TerminalLaunch.argv("main", TerminalLaunch.Os.MAC)!!
        assertEquals("osascript", mac[0])
        assertTrue(mac.any { "huginn 'main'" in it })
        val linux = TerminalLaunch.argv("main", TerminalLaunch.Os.LINUX)!!
        assertEquals("x-terminal-emulator", linux[0])
        assertTrue(linux.last().startsWith("huginn 'main'"))
    }

    @Test
    fun `a name a shell could read is never opened, and says so without spawning`() {
        for (bad in listOf("", "a b", "x;rm", "\$(id)", "-flag", "n'ame", ".".repeat(70))) {
            assertNull(TerminalLaunch.argv(bad, TerminalLaunch.Os.WINDOWS), bad)
            var spawned = false
            val why = TerminalLaunch.open(bad, TerminalLaunch.Os.WINDOWS) { spawned = true }
            assertTrue(why != null && "not opening" in why, bad)
            assertTrue(!spawned, bad)
        }
        var got: List<String>? = null
        assertNull(TerminalLaunch.open("widgetshub-core", TerminalLaunch.Os.LINUX) { got = it })
        assertEquals("x-terminal-emulator", got!![0])
    }

    @Test
    fun `the label names the shell the OS has`() {
        assertEquals("Open in PowerShell", TerminalLaunch.label(TerminalLaunch.Os.WINDOWS))
        assertEquals("Open in terminal", TerminalLaunch.label(TerminalLaunch.Os.LINUX))
    }

    @Test
    fun `a spawn that throws is one sentence, not a crash`() {
        val why = TerminalLaunch.open("main", TerminalLaunch.Os.WINDOWS) { throw java.io.IOException("no powershell here") }
        assertTrue(why != null && "no powershell here" in why, why)
    }
}
