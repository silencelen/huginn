package com.silencelen.huginn

import com.silencelen.huginn.ui.LiveInput
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The diff that turns soft-keyboard edits into terminal keystrokes.
 *
 * Worth exhaustive tests because every IME expresses edits differently and none of
 * them can be run on this machine: the diff is the one place where "what the
 * keyboard did" becomes "what the pane receives", and a wrong reading here types
 * garbage into a live Claude Code session.
 */
class LiveInputTest {

    private val S = LiveInput.SENTINEL

    @Test
    fun `a typed character is an insert`() {
        val t = LiveInput.diff(S + "a")
        assertEquals(0, t.backspaces)
        assertEquals("a", t.insert)
        assertFalse(t.enter)
    }

    @Test
    fun `a burst of characters arrives whole`() {
        // Fast typing and glide input commit several characters in one change.
        assertEquals("ls -la", LiveInput.diff(S + "ls -la").insert)
    }

    @Test
    fun `deleting the sentinel means backspace`() {
        val t = LiveInput.diff("")
        assertEquals(1, t.backspaces)
        assertEquals("", t.insert)
        assertFalse(t.enter)
    }

    @Test
    fun `the reset value itself means nothing happened`() {
        assertTrue(LiveInput.diff(S).isNothing)
    }

    @Test
    fun `enter arrives as a newline and is sent after the text`() {
        val t = LiveInput.diff(S + "make\n")
        assertEquals("make", t.insert)
        assertTrue(t.enter)
    }

    @Test
    fun `a bare newline is just enter`() {
        val t = LiveInput.diff(S + "\n")
        assertEquals("", t.insert)
        assertTrue(t.enter)
    }

    /**
     * ⚠ THE NEWLINES STAY IN THE TEXT. This used to `replace("\n", "")` the whole
     * tail and set one trailing Enter, so pasting three commands into live-typing
     * mode delivered `git statusgit log --onelinels -la` AND SUBMITTED IT — a
     * command the person never wrote, run in whatever shell the pane holds. The
     * daemon delivers text by bracketed paste, so interior newlines land in a
     * composer (or a shell line) without submitting anything, which is what the
     * collapse was trying to achieve.
     */
    @Test
    fun `a multi-line paste keeps its lines`() {
        val t = LiveInput.diff(S + "git status\ngit log --oneline\nls -la\n")
        assertEquals("git status\ngit log --oneline\nls -la", t.insert)
        assertTrue(t.enter, "the tail ENDED with a newline, so one Enter follows the text")
        assertFalse(t.enterFirst)
    }

    @Test
    fun `an interior newline is not an Enter`() {
        val t = LiveInput.diff(S + "line one\nline two")
        assertEquals("line one\nline two", t.insert)
        assertFalse(t.enter, "nothing here says submit")
    }

    /**
     * `replace("\n","")` never touched `\r`, so a CRLF clipboard put literal
     * carriage returns inside `tmux send-keys -l` text — delivering the mid-text
     * Return the collapse existed to prevent.
     */
    @Test
    fun `a CRLF paste carries no carriage returns into the pane`() {
        val t = LiveInput.diff(S + "line one\r\nline two")
        assertEquals("line one\nline two", t.insert)
        assertFalse(t.insert.contains('\r'))
        assertFalse(t.enter)

        val old = LiveInput.diff(S + "old mac\rline")
        assertEquals("old mac\nline", old.insert)
    }

    /**
     * ⚠ AND AN EDIT THAT STARTS WITH A NEWLINE PRESSES ENTER FIRST. "\nls" used to
     * mean "type ls, then Return", which submits whatever draft the pane already
     * holds WITH `ls` appended — the opposite of what the keyboard did and the
     * opposite of this function's own kdoc.
     */
    @Test
    fun `a leading newline is an Enter before the text`() {
        val t = LiveInput.diff(S + "\nls")
        assertEquals("ls", t.insert)
        assertTrue(t.enter)
        assertTrue(t.enterFirst)
        assertEquals(
            listOf(LiveInput.Op.Key(listOf("Enter")), LiveInput.Op.Text("ls")),
            t.ops(),
            "the order is the contract; `enter` alone cannot express it",
        )
    }

    @Test
    fun `the ops of an ordinary edit are backspaces, text, then enter`() {
        assertEquals(
            listOf(LiveInput.Op.Key(listOf("BSpace")), LiveInput.Op.Text("make"), LiveInput.Op.Key(listOf("Enter"))),
            LiveInput.diff("make\n").ops(),
        )
        assertEquals(emptyList(), LiveInput.diff(S).ops())
    }

    @Test
    fun `an IME that rewrote the whole field still reads as its parts`() {
        // Voice input and some autocorrections replace everything, sentinel
        // included: the sentinel is gone (one backspace, harmless against a pane
        // holding no draft) and the new text is the insert.
        val t = LiveInput.diff("hello")
        assertEquals(1, t.backspaces)
        assertEquals("hello", t.insert)
    }

    @Test
    fun `unicode input survives untouched`() {
        assertEquals("héllo → 世界", LiveInput.diff(S + "héllo → 世界").insert)
    }

    @Test
    fun `the sentinel is a single invisible character`() {
        assertEquals(1, S.length)
        assertEquals('​', S[0])
    }
}

/**
 * The merge that turns a queued burst into the fewest ordered requests. Ordering
 * is the point: the per-keystroke path this replaced could deliver "ls" as "sl".
 */
class LiveMergeTest {

    private fun t(s: String) = LiveInput.Op.Text(s)
    private fun k(vararg keys: String) = LiveInput.Op.Key(keys.toList())

    @Test
    fun `a typing burst becomes one request`() {
        assertEquals(listOf(t("hello")), LiveInput.merge(listOf(t("h"), t("e"), t("llo"))))
    }

    @Test
    fun `keys between text split the merge, preserving order`() {
        assertEquals(
            listOf(t("ls"), k("Enter"), t("cd")),
            LiveInput.merge(listOf(t("l"), t("s"), k("Enter"), t("c"), t("d"))),
        )
    }

    @Test
    fun `consecutive keys merge into one request too`() {
        assertEquals(
            listOf(k("BSpace", "BSpace", "Enter")),
            LiveInput.merge(listOf(k("BSpace"), k("BSpace"), k("Enter"))),
        )
    }

    @Test
    fun `an empty queue merges to nothing`() {
        assertEquals(emptyList<LiveInput.Op>(), LiveInput.merge(emptyList()))
    }

    @Test
    fun `a single op passes through untouched`() {
        assertEquals(listOf(t("x")), LiveInput.merge(listOf(t("x"))))
    }
}
