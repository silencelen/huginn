package com.silencelen.huginn

import com.silencelen.huginn.ui.ShellMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** NOTE kotlin.test's argument order is (expected, actual, message). */
class ShellModeTest {

    @Test
    fun `a leading bang followed by a command is a shell command`() {
        assertTrue(ShellMode.isShellCommand("!ls -la"))
        assertTrue(ShellMode.isShellCommand("!git status"))
        assertTrue(ShellMode.isShellCommand("!!"), "a second bang is still something to run")
    }

    /**
     * ⚠ "! echo spaced" WAS A MESSAGE HERE, AND THE DAEMON REFUSED IT (2026-11-03,
     * the Fold's cover screen and the desktop). Claude Code enters shell mode on
     * ANY `!` typed into an empty box, and the daemon's `shell: true` drops the `!`
     * and trims, so the space was never ours to read as "a person typing an
     * exclamation mark": the box got no tint, the send went without the flag, and
     * the daemon answered 409 because the text began with `!`.
     */
    @Test
    fun `a bang then a space then a command is a shell command`() {
        assertTrue(ShellMode.isShellCommand("! echo spaced"))
        assertTrue(ShellMode.isShellCommand("! ls"))
        assertTrue(ShellMode.isShellCommand("!   pwd  "))
        assertTrue(ShellMode.isShellCommand("!\tmake"))
        assertTrue(ShellMode.tinted("! echo spaced"), "the tint follows the same rule")
    }

    @Test
    fun `a bang with nothing to run is not a shell command`() {
        // Nothing after the `!` but whitespace: there is no command to run, and
        // the daemon would refuse it ("a shell command needs text").
        assertFalse(ShellMode.isShellCommand("!"))
        assertFalse(ShellMode.isShellCommand("! "))
        assertFalse(ShellMode.isShellCommand("!    "))
        assertFalse(ShellMode.isShellCommand("!\t"))
        assertFalse(ShellMode.tinted("! "))
    }

    @Test
    fun `text that does not open with a bang is a message`() {
        assertFalse(ShellMode.isShellCommand(""))
        assertFalse(ShellMode.isShellCommand("hello!"))
        assertFalse(ShellMode.isShellCommand(" !ls"), "a leading space is a message, as the daemon reads it")
    }

    @Test
    fun `a multi-line bang message is not a shell command`() {
        // The daemon refuses a multi-line shell send with 400; the composer must
        // not tint it as one and then send the flag.
        assertFalse(ShellMode.isShellCommand("!ls\npwd"))
        assertFalse(ShellMode.isShellCommand("!ls\r\npwd"))
        assertFalse(ShellMode.isShellCommand("! ls\npwd"))
        assertFalse(ShellMode.isShellCommand("!\n"), "a newline is not a command either")
    }

    @Test
    fun `the tint follows the rule exactly`() {
        assertTrue(ShellMode.tinted("!make"))
        assertFalse(ShellMode.tinted("make!"))
        assertTrue(ShellMode.HINT.isNotBlank())
    }
}
