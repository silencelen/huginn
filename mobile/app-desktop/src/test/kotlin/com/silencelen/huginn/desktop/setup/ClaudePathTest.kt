package com.silencelen.huginn.desktop.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finding `claude`, asserted on a machine that is not the one it will run on.
 *
 * THE FAILURE THIS PREVENTS IS NAMED IN THE CODE THAT SUFFERS IT. `DeviceRunner`
 * calls a missing claude "the most likely first-run failure by a wide margin",
 * and until this existed nothing looked: the Settings field was free text, never
 * probed, and a wrong value surfaced at the first job as a spawn failure posted
 * into somebody's chat.
 *
 * Every case here is one this box cannot reproduce by running the real thing —
 * it is Linux, and the interesting half is Windows. So the candidate list takes
 * its environment as a parameter and the shim rule is asserted as text, which is
 * the only way any of it is checked before it reaches PRESTIGE.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ClaudePathTest {

    private val winEnv = mapOf(
        "LOCALAPPDATA" to """C:\Users\jacob\AppData\Local""",
        "APPDATA" to """C:\Users\jacob\AppData\Roaming""",
        "ProgramFiles" to """C:\Program Files""",
    )

    private fun win(key: String): String? = winEnv[key]
    private fun none(@Suppress("UNUSED_PARAMETER") key: String): String? = null

    @Test
    fun `PATH is always tried first, on both platforms`() {
        assertEquals("claude.exe", ClaudePath.candidates(true, ::win, """C:\Users\jacob""").first())
        assertEquals("claude", ClaudePath.candidates(false, ::none, "/home/jacob").first())
    }

    @Test
    fun `the windows list covers the places a GUI app cannot see`() {
        val list = ClaudePath.candidates(true, ::win, """C:\Users\jacob""")
        // A GUI app's PATH is whatever the desktop session had AT LOGIN, so a
        // fresh per-user install is invisible to it — the same trap that forced
        // LocalServe.findNode() to hand-probe for node.
        assertTrue(
            list.any { it == """C:\Users\jacob\AppData\Local\Programs\claude\claude.exe""" },
            "the per-user install location is missing from $list",
        )
        // The npm-global shim, which on Windows lives under %APPDATA%\npm and
        // NOT beside node.
        assertTrue(
            list.any { it == """C:\Users\jacob\AppData\Roaming\npm\claude.cmd""" },
            "the npm shim is missing from $list",
        )
        assertTrue(list.any { it.endsWith("""\.claude\local\claude.exe""") }, "missing from $list")
        // Nothing invented from an environment variable that is not set: an
        // unset %ProgramFiles% must drop its candidate rather than produce
        // "null\claude\claude.exe" and probe a literal path called null.
        val bare = ClaudePath.candidates(true, ::none, null)
        assertFalse(bare.any { it.contains("null") }, "a null env leaked into $bare")
        assertEquals(listOf("claude.exe"), bare, "with no environment there is only PATH")
    }

    @Test
    fun `the unix list covers the installs a session PATH misses`() {
        val list = ClaudePath.candidates(false, ::none, "/home/jacob")
        for (expected in listOf(
            "/usr/local/bin/claude",
            "/usr/bin/claude",
            "/home/jacob/.local/bin/claude",
            "/home/jacob/.claude/local/claude",
        )) {
            assertTrue(list.contains(expected), "$expected is missing from $list")
        }
        // Homebrew on Apple silicon, which is not on a GUI app's PATH either.
        assertTrue(list.contains("/opt/homebrew/bin/claude"), "missing from $list")
        assertFalse(list.any { it.contains("null") }, "a null home leaked into $list")
    }

    @Test
    fun `no candidate appears twice, on either platform`() {
        for (windows in listOf(true, false)) {
            val list = ClaudePath.candidates(windows, if (windows) ::win else ::none, "/home/jacob")
            assertEquals(list.size, list.toSet().size, "duplicates in $list")
        }
    }

    @Test
    fun `a cmd shim is run through the command processor, not spawned`() {
        // ⚠ THE ONE THAT BITES. Windows resolves .cmd/.bat through the command
        // processor rather than CreateProcess, so handing one to a ProcessBuilder
        // dies with "error=193, %1 is not a valid Win32 application" — which
        // reads like a corrupt binary rather than like a file that needs cmd /c.
        // The npm install of claude IS a .cmd, so this is the ordinary case.
        assertEquals(
            listOf("cmd", "/c", """C:\npm\claude.cmd""", "--version"),
            ClaudePath.commandFor("""C:\npm\claude.cmd"""),
        )
        assertEquals(
            listOf("cmd", "/c", """C:\npm\CLAUDE.BAT""", "--version"),
            ClaudePath.commandFor("""C:\npm\CLAUDE.BAT"""),
            "the extension test must not be case sensitive",
        )
        // A PowerShell shim needs its own host, and -File rather than -Command
        // so a path with a space stays one argument.
        assertEquals(
            listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "/x/claude.ps1", "--version"),
            ClaudePath.commandFor("/x/claude.ps1"),
        )
        // Everything else is spawned directly, which is the common case and must
        // NOT acquire a shell it does not need.
        assertEquals(listOf("claude", "--version"), ClaudePath.commandFor("claude"))
        assertEquals(
            listOf("""C:\p\claude.exe""", "--version"),
            ClaudePath.commandFor("""C:\p\claude.exe"""),
        )
    }

    @Test
    fun `a version is taken from the first line that says anything`() {
        assertEquals("2.1.4", ClaudePath.parseVersion("2.1.4 (Claude Code)"))
        assertEquals("1.0.0-rc.2", ClaudePath.parseVersion("claude 1.0.0-rc.2"))
        // Leading blank lines and a node deprecation banner are both ordinary
        // output from a shim, and neither is the version.
        assertEquals("2.1.4", ClaudePath.parseVersion("\n\n  2.1.4 (Claude Code)\n"))
        // A shape this build has never seen is still EVIDENCE that something
        // answered, which is all this step needs. Forgiving on purpose: the
        // version line has changed format before and will again.
        assertEquals("Claude Code, build 2026.09", ClaudePath.parseVersion("Claude Code, build 2026.09"))
    }

    @Test
    fun `silence is not a version`() {
        // ⚠ A SHIM THAT SWALLOWS ITS ARGUMENTS EXITS 0 SAYING NOTHING, and
        // reporting that as a working claude is the original failure all over
        // again — a green step over a machine that cannot run work.
        assertNull(ClaudePath.parseVersion(null))
        assertNull(ClaudePath.parseVersion(""))
        assertNull(ClaudePath.parseVersion("   \n\n  \n"))
    }

    @Test
    fun `a name nothing can run fails rather than falling through to PATH`() {
        // An override is PROVEN, not trusted. Somebody who typed a path typed it
        // because something was wrong, so a silent fall-through to PATH would
        // leave the person most likely to be mistaken with no feedback — and
        // would then run a different binary than the one on their screen.
        val outcome = ClaudePath.detect(override = "/nonexistent/huginn-test-claude")
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("/nonexistent/huginn-test-claude"), "the refusal names it: $message")
        assertTrue(message.contains("Clear the field"), "the refusal says the way out: $message")
    }

    @Test
    fun `a machine with no claude anywhere is told how to get one`() {
        // Probing a candidate list that cannot match: the point is the SENTENCE,
        // which is the only thing a person on a machine with no claude has.
        val outcome = ClaudePath.detect(
            windows = false,
            env = { null },
            home = "/nonexistent/huginn-test-home",
        )
        // This box may genuinely have a claude on PATH — the CI host does — so
        // the assertion is about which sentence a failure carries rather than
        // about failing.
        if (outcome.isSuccess) {
            val found = assertNotNull(outcome.getOrNull())
            assertTrue(found.version.isNotBlank(), "a pass must carry the proof")
        } else {
            val message = outcome.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("claude.ai/download"), "no install instruction in: $message")
        }
        assertTrue(ClaudePath.NOT_FOUND_WINDOWS.contains("claude.exe"))
        assertFalse(ClaudePath.NOT_FOUND_UNIX.contains("claude.exe"), "the unix line must not name an exe")
    }
}
