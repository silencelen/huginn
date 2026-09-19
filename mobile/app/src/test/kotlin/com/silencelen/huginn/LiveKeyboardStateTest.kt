package com.silencelen.huginn

import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.ui.LiveKeyboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FAST BURST. This is the regression that cost the 3.6.1 review its highest
 * phone finding: `ZZZZZ` typed in one burst arrived in the pane as fifteen Z.
 *
 * The shape of the bug is the thing to hold: `onValueChange` fires again before
 * the snap-back to the sentinel has travelled out to the IME, so the second
 * change carries the CUMULATIVE buffer. Every test here feeds two or more
 * changes with NO reset between them, which is the state the old code could not
 * represent and the only state in which it was wrong.
 *
 * NOTE this module is on org.junit, whose three-argument order is (message,
 * expected, actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class LiveKeyboardStateTest {

    private val S = LiveInput.SENTINEL

    /** Every op the field would have handed the pane, flattened in order. */
    private fun LiveKeyboardState.emit(next: String): List<LiveInput.Op> = change(next).ops()

    private fun text(vararg ops: LiveInput.Op): String =
        ops.filterIsInstance<LiveInput.Op.Text>().joinToString("") { it.text }

    @Test
    fun `two changes before any snap-back emit each character exactly once`() {
        val f = LiveKeyboardState()
        assertEquals("1", text(*f.emit(S + "1").toTypedArray()))
        // ⚠ NO RESET HERE. The IME is still working from the buffer it grew.
        assertEquals("2", text(*f.emit(S + "12").toTypedArray()))
    }

    @Test
    fun `a five character burst delivers five characters, not fifteen`() {
        val f = LiveKeyboardState()
        val delivered = buildString {
            for (n in 1..5) append(text(*f.emit(S + "Z".repeat(n)).toTypedArray()))
        }
        assertEquals("ZZZZZ", delivered)
    }

    @Test
    fun `a burst of whole words is the words, in order`() {
        val f = LiveKeyboardState()
        val typed = "echo live-typed-ok"
        val delivered = buildString {
            for (n in 1..typed.length) append(text(*f.emit(S + typed.take(n)).toTypedArray()))
        }
        assertEquals(typed, delivered)
    }

    @Test
    fun `a change that adds nothing emits nothing`() {
        val f = LiveKeyboardState()
        f.change(S + "ls")
        assertTrue(f.change(S + "ls").isNothing)
    }

    // ------------------------------------------------------- backspace

    @Test
    fun `deleting typed text mid-burst is one backspace per character`() {
        val f = LiveKeyboardState()
        f.change(S + "abc")
        assertEquals(1, f.change(S + "ab").backspaces)
        assertEquals(1, f.change(S + "a").backspaces)
        assertEquals(1, f.change(S).backspaces)
    }

    @Test
    fun `deleting the sentinel is a backspace into the pane and asks for a new runway`() {
        val f = LiveKeyboardState()
        assertFalse(f.needsRunway)
        val t = f.change("")
        assertEquals(1, t.backspaces)
        assertEquals("", t.insert)
        assertTrue("an emptied field cannot report the next delete", f.needsRunway)
    }

    /**
     * ⚠ THE RESET AFTER AN EMPTIED FIELD IS THE SAFE ONE. A stale IME holding the
     * empty buffer can only deliver another delete, and "" read against a restored
     * sentinel is exactly one more backspace — which is what a rapid DEL burst
     * must be, and what the review measured working at ten in a row.
     */
    @Test
    fun `a rapid backspace burst is one backspace per press`() {
        val f = LiveKeyboardState()
        var presses = 0
        repeat(10) {
            presses += f.change("").backspaces
            if (f.needsRunway) f.reset()
        }
        assertEquals(10, presses)
    }

    // ------------------------------------------------------- Enter and paste

    @Test
    fun `Enter mid-burst is a Return after the text it follows`() {
        val f = LiveKeyboardState()
        f.change(S + "make")
        val t = f.change(S + "make\n")
        assertEquals("", t.insert)
        assertTrue(t.enter)
        assertFalse(t.enterFirst)
    }

    @Test
    fun `an edit that opens with a newline presses Return before its text`() {
        val f = LiveKeyboardState()
        val t = f.change(S + "\nls")
        assertEquals(
            listOf(LiveInput.Op.Key(listOf("Enter")), LiveInput.Op.Text("ls")),
            t.ops(),
        )
    }

    @Test
    fun `a paste on top of typed text keeps its interior newlines`() {
        val f = LiveKeyboardState()
        f.change(S + "x")
        val t = f.change(S + "xgit status\ngit log\n")
        assertEquals("git status\ngit log", t.insert)
        assertTrue("the paste ENDED with a newline", t.enter)
    }

    @Test
    fun `an autocorrect that rewrote the whole field deletes what it replaced`() {
        val f = LiveKeyboardState()
        f.change(S + "teh")
        // Sentinel and all: three typed characters plus the runway.
        val t = f.change("the")
        assertEquals(4, t.backspaces)
        assertEquals("the", t.insert)
    }

    @Test
    fun `an emoji is never split in half`() {
        val f = LiveKeyboardState()
        f.change(S + "😀")
        val t = f.change(S + "😀!")
        assertEquals(0, t.backspaces)
        assertEquals("!", t.insert)
    }
}
