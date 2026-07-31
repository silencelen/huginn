package com.silencelen.huginn

import com.silencelen.huginn.ui.LocalEcho
import com.silencelen.huginn.ui.LocalEcho.Echo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The judgment calls of optimistic echo. A wrong guess here is a ghost
 * character floating in a live pane — worse than the latency it hides — so most
 * of these tests are about NOT rendering.
 */
class LocalEchoTest {

    @Test
    fun `typed characters accumulate and are visible`() {
        var e = Echo()
        e = LocalEcho.typed(e, "l")
        e = LocalEcho.typed(e, "s")
        assertEquals("ls", e.text)
        assertTrue(e.visible)
    }

    @Test
    fun `backspace eats the last pending character`() {
        var e = LocalEcho.typed(Echo(), "ab")
        e = LocalEcho.backspace(e)
        assertEquals("a", e.text)
    }

    @Test
    fun `backspace past the buffer mutes - the effect on screen is unknowable`() {
        val e = LocalEcho.backspace(Echo())
        assertTrue(e.muted)
        assertFalse(e.visible)
    }

    @Test
    fun `unpredictable keys mute until a frame settles things`() {
        val e = LocalEcho.otherKey(LocalEcho.typed(Echo(), "half"))
        assertTrue(e.muted)
        assertFalse(e.visible)
    }

    @Test
    fun `typing while muted stays muted - no guessing on unknown ground`() {
        val e = LocalEcho.typed(Echo(muted = true), "x")
        assertTrue(e.muted)
        assertEquals("", e.text)
    }

    @Test
    fun `a frame consumes exactly the cursor's advance`() {
        val e = LocalEcho.typed(Echo(), "abc")
        // The pane confirmed two characters: cursor moved 2 cells on the same row.
        val after = LocalEcho.frame(e, prev = 10 to 5, cur = 12 to 5)
        assertEquals("c", after.text)
        assertTrue(after.visible)
    }

    @Test
    fun `a frame that consumes everything leaves nothing pending`() {
        val e = LocalEcho.typed(Echo(), "ab")
        assertEquals("", LocalEcho.frame(e, 4 to 2, 6 to 2).text)
    }

    @Test
    fun `a row change clears - wraps are exactly where ghosts come from`() {
        val e = LocalEcho.typed(Echo(), "abcdef")
        assertEquals("", LocalEcho.frame(e, 78 to 5, 4 to 6).text)
    }

    @Test
    fun `a backwards cursor clears - the pane redrew, all bets off`() {
        val e = LocalEcho.typed(Echo(), "abc")
        assertEquals("", LocalEcho.frame(e, 10 to 5, 2 to 5).text)
    }

    @Test
    fun `an advance longer than the buffer clears rather than inventing text`() {
        val e = LocalEcho.typed(Echo(), "ab")
        assertEquals("", LocalEcho.frame(e, 10 to 5, 20 to 5).text)
    }

    @Test
    fun `a frame lifts a mute - it is the resolution being waited for`() {
        val e = LocalEcho.frame(Echo(muted = true), 3 to 1, 4 to 1)
        assertFalse(e.muted)
        assertEquals("", e.text)
    }

    @Test
    fun `the first frame ever clears rather than guessing at history`() {
        val e = LocalEcho.typed(Echo(), "abc")
        assertEquals("", LocalEcho.frame(e, prev = null, cur = 5 to 5).text)
    }

    @Test
    fun `a runaway buffer mutes instead of building a longer ghost`() {
        var e = Echo()
        repeat(LocalEcho.MAX_PENDING + 1) { e = LocalEcho.typed(e, "x") }
        assertTrue(e.muted)
    }
}
