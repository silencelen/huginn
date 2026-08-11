package com.silencelen.huginn

import com.silencelen.huginn.ui.HistoryWalk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class HistoryWalkTest {

    private val entries = listOf("oldest", "middle", "newest")

    @Test
    fun `canEnter - empty composer yes`() {
        assertTrue(HistoryWalk.canEnter("", 0, 0))
    }

    @Test
    fun `canEnter - caret on line one of a multiline draft yes`() {
        val text = "first line\nsecond"
        assertTrue(HistoryWalk.canEnter(text, 5, 5))
    }

    @Test
    fun `canEnter - caret on line two no (Up should move the caret)`() {
        val text = "first line\nsecond"
        val caret = text.indexOf("second") + 2
        assertFalse(HistoryWalk.canEnter(text, caret, caret))
    }

    @Test
    fun `canEnter - a range selection no`() {
        assertFalse(HistoryWalk.canEnter("hello", 1, 3))
    }

    @Test
    fun `enter starts at the newest and stashes the draft`() {
        val c = HistoryWalk.enter(entries, "half-typed")!!
        assertEquals("newest", HistoryWalk.text(c))
        assertEquals("half-typed", c.stash)
    }

    @Test
    fun `enter with no history is null`() {
        assertNull(HistoryWalk.enter(emptyList(), "draft"))
    }

    @Test
    fun `up walks older and clamps at the oldest`() {
        var c = HistoryWalk.enter(entries, "")!!
        c = HistoryWalk.up(c); assertEquals("middle", HistoryWalk.text(c))
        c = HistoryWalk.up(c); assertEquals("oldest", HistoryWalk.text(c))
        c = HistoryWalk.up(c); assertEquals("oldest", HistoryWalk.text(c))   // clamped, no wrap
    }

    @Test
    fun `down past the newest exits (null) so the stash is restored`() {
        var c = HistoryWalk.enter(entries, "stash")!!
        c = HistoryWalk.up(c)
        val back = HistoryWalk.down(c)!!
        assertEquals("newest", HistoryWalk.text(back))
        assertNull(HistoryWalk.down(back))
    }
}
