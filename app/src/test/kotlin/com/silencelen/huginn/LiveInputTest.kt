package com.silencelen.huginn

import com.silencelen.huginn.ui.LiveInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `a multi-line paste keeps its text and presses enter`() {
        // The pane receives the text; the newline count collapses to one Enter,
        // because sending intermediate Enters would submit a half-pasted command.
        val t = LiveInput.diff(S + "line one\nline two")
        assertEquals("line oneline two", t.insert)
        assertTrue(t.enter)
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
