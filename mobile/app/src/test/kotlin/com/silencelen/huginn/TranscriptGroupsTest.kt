package com.silencelen.huginn

import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.TranscriptGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folding rule for subagent runs. A wrong boundary here swallows events into
 * the wrong card, which nobody reports because nobody can see it.
 */
class TranscriptGroupsTest {

    private fun ev(seq: Int, kind: String, side: Boolean = false, text: String? = null) =
        TranscriptEvent(seq = seq, kind = kind, sidechain = side, text = text)

    @Test
    fun `a plain conversation stays row for row`() {
        val rows = TranscriptGroups.group(
            listOf(ev(1, "user"), ev(2, "assistant"), ev(3, "tool")),
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { it is TranscriptGroups.Row.Single })
    }

    @Test
    fun `consecutive sidechain events fold into one group`() {
        val rows = TranscriptGroups.group(
            listOf(
                ev(1, "assistant"),
                ev(2, "user", side = true, text = "Search the tree for FIXME"),
                ev(3, "thinking", side = true),
                ev(4, "tool", side = true),
                ev(5, "assistant", side = true),
                ev(6, "assistant"),
            ),
        )
        assertEquals(3, rows.size)
        val group = rows[1] as TranscriptGroups.Row.Subagents
        assertEquals(4, group.events.size)
        assertEquals("Search the tree for FIXME", group.task)
        assertEquals(3, group.steps)   // the prompt is the task, not a step
    }

    @Test
    fun `two separated fan-outs are two groups, not one`() {
        val rows = TranscriptGroups.group(
            listOf(
                ev(1, "tool", side = true),
                ev(2, "assistant"),
                ev(3, "tool", side = true),
            ),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0] is TranscriptGroups.Row.Subagents)
        assertTrue(rows[1] is TranscriptGroups.Row.Single)
        assertTrue(rows[2] is TranscriptGroups.Row.Subagents)
    }

    @Test
    fun `a transcript ending mid-fan-out still shows the group`() {
        // The tail case matters most: it is what a live session looks like while
        // subagents are running right now.
        val rows = TranscriptGroups.group(
            listOf(ev(1, "assistant"), ev(2, "tool", side = true), ev(3, "tool", side = true)),
        )
        assertEquals(2, rows.size)
        assertEquals(2, (rows[1] as TranscriptGroups.Row.Subagents).events.size)
    }

    @Test
    fun `a group with no user event has no task line`() {
        val rows = TranscriptGroups.group(listOf(ev(1, "tool", side = true)))
        assertNull((rows[0] as TranscriptGroups.Row.Subagents).task)
    }

    @Test
    fun `the group key is stable while the group grows`() {
        // Expansion state is remembered by key; a key that changed as events
        // streamed in would collapse the card the reader just opened.
        val first = TranscriptGroups.group(listOf(ev(7, "tool", side = true)))
        val grown = TranscriptGroups.group(
            listOf(ev(7, "tool", side = true), ev(8, "assistant", side = true)),
        )
        assertEquals(
            (first[0] as TranscriptGroups.Row.Subagents).key,
            (grown[0] as TranscriptGroups.Row.Subagents).key,
        )
    }

    @Test
    fun `an empty transcript groups to nothing`() {
        assertTrue(TranscriptGroups.group(emptyList()).isEmpty())
    }

    @Test
    fun `a blank task is treated as absent`() {
        val rows = TranscriptGroups.group(listOf(ev(1, "user", side = true, text = "   ")))
        assertNull((rows[0] as TranscriptGroups.Row.Subagents).task)
    }

    @Test
    fun `row keys are distinct, and a Single never collides with a Subagents run`() {
        // LazyColumn throws on a duplicate key, so this is a crash guard as much as
        // an identity check.
        val rows = TranscriptGroups.group(
            listOf(ev(1, "user"), ev(2, "tool", side = true), ev(3, "assistant")),
        )
        val keys = TranscriptGroups.keys(rows)
        assertEquals(rows.size, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a repeated seq costs one row its anchoring, not the screen`() {
        // Uniqueness holds by construction upstream; if that ever breaks, the list
        // must still render.
        val rows = TranscriptGroups.group(listOf(ev(4, "assistant"), ev(4, "assistant")))
        val keys = TranscriptGroups.keys(rows)
        assertEquals(2, keys.size)
        assertEquals(2, keys.toSet().size)
        assertEquals("s4", keys[0])
    }
}
