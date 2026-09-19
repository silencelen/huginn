package com.silencelen.huginn

import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.ui.StreamPicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE LIVE STRIP HOLDS STILL, AND THE FOLDED LIST IS SEARCHABLE.
 *
 * ⚠⚠ CHIPS MOVED OUT FROM UNDER THE POINTER. The live half was ordered by
 * `updatedAt` descending, and a running agent writes every few seconds — so two
 * live chips swapped places on the next five-second poll, while somebody was
 * reaching for one. A picker whose targets move is a picker that picks the wrong
 * stream, and the two clicks that follow are a transcript yanked away twice.
 * Recency is the right order for a LIST somebody is reading top-down; it is the
 * wrong order for a row of targets. First-seen never changes.
 *
 * Running still outranks settled, because the one settled chip the strip holds
 * open is the transcript on screen and it belongs at the end rather than in the
 * middle of the live ones.
 *
 * The other half is the `…` overflow. It unfolded IN PLACE into dozens of rows
 * of `[Workflow harnes… · a85dfc1f` — every label clipped to the same 28
 * characters, differing only by an opaque hex tail, with no way to search. The
 * sheet gets the UNCLIPPED title and the id, and a filter over both.
 */
class StreamPickerSheetTest {

    private companion object {
        const val NOW = 1_789_460_000L
    }

    private fun agent(
        id: String,
        task: String? = null,
        summary: String? = null,
        active: Boolean = true,
        updatedAt: Long = NOW - 30,
        startedAt: Long = 0,
        status: String? = null,
    ) = AgentRun(
        id = id,
        task = task,
        summary = summary,
        active = active,
        updatedAt = updatedAt,
        startedAt = startedAt,
        status = status,
    )

    // ------------------------------------------------------------- the strip

    @Test
    fun `live chips keep the order they were first seen in`() {
        val first = agent("agent-aaa", task = "older", startedAt = NOW - 900, updatedAt = NOW - 1)
        val second = agent("agent-bbb", task = "newer", startedAt = NOW - 300, updatedAt = NOW - 60)

        // The daemon can list them in any order, and a WRITE by the older agent
        // must not move it: this is the poll that used to reorder the strip.
        val items = StreamPicker.items(listOf(second, first), NOW)
        assertEquals(
            listOf("older", "newer"),
            items.filter { it.agentId != null }.map { it.label },
            "first-seen, not last-written — a chip must not move while it is being aimed at",
        )
    }

    @Test
    fun `two agents that started in the same second still order the same way twice`() {
        val a = agent("agent-bbb", task = "b", startedAt = NOW - 100)
        val b = agent("agent-aaa", task = "a", startedAt = NOW - 100)
        val one = StreamPicker.items(listOf(a, b), NOW).filter { it.agentId != null }.map { it.agentId }
        val two = StreamPicker.items(listOf(b, a), NOW).filter { it.agentId != null }.map { it.agentId }
        assertEquals(one, two, "a tie must break on the id, or the daemon's listing order leaks through")
    }

    @Test
    fun `the settled chip held open for the reader stays at the end`() {
        val live = agent("agent-aaa", task = "working", startedAt = NOW - 10)
        val read = agent("agent-zzz", task = "finished", active = false, startedAt = NOW - 5000)
        val items = StreamPicker.items(listOf(live, read), NOW, selectedKey = "agent-zzz")
        val chips = items.filter { it.agentId != null }
        assertEquals("agent-zzz", chips.last().agentId, "running outranks settled, whatever the clock says")
    }

    // ------------------------------------------------------------- the sheet

    @Test
    fun `a sheet row carries the whole title and the id`() {
        val long = "Workflow harness: prove the resume path end to end on a cold daemon"
        val item = StreamPicker.items(listOf(agent("agent-a85dfc1f22", summary = long)), NOW)
            .single { it.agentId != null }

        assertTrue(item.label.length <= StreamPicker.LABEL_MAX, "the CHIP is still one chip wide")
        val row = StreamPicker.sheetLabel(item)
        assertTrue(row.startsWith(long), "the sheet has the width the chip did not: $row")
        assertTrue(row.endsWith("a85dfc1f"), "and the id, because two runs can share a title: $row")
    }

    @Test
    fun `the filter reads the whole title and the id`() {
        val item = StreamPicker.items(
            listOf(agent("agent-a85dfc1f22", summary = "Workflow harness: prove the resume path")),
            NOW,
        ).single { it.agentId != null }

        assertTrue(StreamPicker.matchesFilter(item, "resume"), "a word past the chip's 28 characters")
        assertTrue(StreamPicker.matchesFilter(item, "A85DFC"), "the id, case-insensitively")
        assertTrue(StreamPicker.matchesFilter(item, "   "), "a blank filter hides nothing")
        assertFalse(StreamPicker.matchesFilter(item, "kerkcraft"))
    }

    @Test
    fun `the fold no longer stops at a screenful now that it is searchable`() {
        // The old cap's argument — "past a screenful the list stops being a way
        // back into a transcript and becomes a wall" — was about a list that
        // unfolded IN PLACE with no search. An overlay with a filter is the way
        // through a wall, and a cap that hid agent 41 of 198 from the filter was
        // hiding exactly what somebody was searching for.
        assertTrue(
            StreamPicker.FOLDED_MAX >= 200,
            "the filter can only find what the fold hands it: ${StreamPicker.FOLDED_MAX}",
        )
    }
}
