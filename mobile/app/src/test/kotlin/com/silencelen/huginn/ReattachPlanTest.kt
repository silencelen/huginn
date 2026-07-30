package com.silencelen.huginn

import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.ui.reattachPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Picking a running chat back up without showing its answer twice.
 *
 * Proven against the live daemon first: with a 793-character partial answer on
 * screen, a `since=0` subscription replayed that same text from "1. The loneliest
 * number", so seed+replay doubled it; `since=seq` resumed at character 794. These
 * pin the rule that produced that behaviour.
 */
class ReattachPlanTest {

    private fun meta(
        running: Boolean = true,
        seq: Long? = 11,
        partial: String? = "1. The loneliest number.\n2. Company.",
    ) = ChatDetail(id = "c1", running = running, seq = seq, partialText = partial)

    @Test
    fun `resumes after the text it already shows`() {
        val plan = reattachPlan(meta())!!
        assertEquals("1. The loneliest number.\n2. Company.", plan.seed)
        assertEquals(11L, plan.since)
    }

    @Test
    fun `a daemon that reports no position gets the replay instead of a seed`() {
        // Seeding AND replaying from 0 is the doubling; with no position to resume
        // from, the replay has to be the single account of the text.
        val plan = reattachPlan(meta(seq = null))!!
        assertEquals("", plan.seed)
        assertEquals(0L, plan.since)
    }

    @Test
    fun `nothing to follow when the chat is not running`() {
        assertNull(reattachPlan(meta(running = false)))
        assertNull(reattachPlan(null))
    }

    @Test
    fun `a run with no text yet seeds empty rather than null`() {
        // The bubble is what tells the user their message was received; it has to
        // exist before the first token, so the seed is "" and never null.
        val plan = reattachPlan(meta(partial = null))!!
        assertEquals("", plan.seed)
        assertEquals(11L, plan.since)
    }

    // ---- the transcript merge, which shares this file's concern: identity --------

    @Test
    fun `merging an incremental page renumbers it so seq stays unique`() {
        // The daemon numbers every tail read from 0, so concatenated pages arrive with
        // repeated seqs — and seq is what row state and list keys are keyed on.
        val kept = listOf(tev(0), tev(1), tev(2))
        val incoming = listOf(tev(0), tev(1))          // a fresh page, numbered from 0
        val merged = com.silencelen.huginn.ui.mergeTranscript(kept, incoming, cap = 100)
        assertEquals(listOf(0, 1, 2, 3, 4), merged.map { it.seq })
    }

    @Test
    fun `the window stays capped and keeps climbing across a trim`() {
        var window = listOf(tev(0), tev(1), tev(2))
        repeat(3) {
            window = com.silencelen.huginn.ui.mergeTranscript(window, listOf(tev(0), tev(1)), cap = 4)
            assertEquals(4, window.size)
            assertEquals(window.map { it.seq }.sorted(), window.map { it.seq })
            assertEquals(window.size, window.map { it.seq }.toSet().size)
        }
    }

    @Test
    fun `the first page is taken as the server numbered it`() {
        val merged = com.silencelen.huginn.ui.mergeTranscript(
            emptyList(), listOf(tev(0), tev(1)), cap = 100,
        )
        assertEquals(listOf(0, 1), merged.map { it.seq })
    }

    private fun tev(seq: Int) =
        com.silencelen.huginn.data.TranscriptEvent(seq = seq, kind = "assistant", text = "x")
}
