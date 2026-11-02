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
        assertTrue(ShellMode.isShellCommand("!!"), "a second bang is still not whitespace")
    }

    @Test
    fun `a bang alone, or one followed by a space, is a message`() {
        // What a person typing an exclamation mark looks like, mid-thought.
        assertFalse(ShellMode.isShellCommand("!"))
        assertFalse(ShellMode.isShellCommand("! ls"))
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
    }

    @Test
    fun `the tint follows the rule exactly`() {
        assertTrue(ShellMode.tinted("!make"))
        assertFalse(ShellMode.tinted("make!"))
        assertTrue(ShellMode.HINT.isNotBlank())
    }
}
