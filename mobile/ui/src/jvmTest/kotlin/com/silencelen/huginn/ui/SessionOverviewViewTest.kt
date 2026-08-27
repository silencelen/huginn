package com.silencelen.huginn.ui

import com.silencelen.huginn.data.SessionMetaSaver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the session map is SAYING with a tint, and the one line under the notes.
 *
 * Colour is the whole vocabulary of that gutter, so getting it wrong is a map
 * that reads confidently and is not true — a stalled agent drawn in its own hue
 * says it finished, and a running one drawn as settled says the fan-out is over.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionOverviewViewTest {

    @Test
    fun `the four kinds of block are four different things`() {
        assertEquals(BlockTone.SAID, blockTone("user"))
        assertEquals(BlockTone.WORK, blockTone("action"))
        assertEquals(BlockTone.SPOKE, blockTone("response"))
        assertEquals(BlockTone.BREAK, blockTone("compact"))
    }

    @Test
    fun `a kind this client has never heard of is still drawn`() {
        // Wire models are nullable-with-default so an older client keeps parsing a
        // newer daemon; the same tolerance has to reach the renderer, or a fifth
        // block kind arrives as a hole in the middle of the map.
        assertEquals(BlockTone.WORK, blockTone("something-new"))
        assertEquals(BlockTone.WORK, blockTone(""))
    }

    @Test
    fun `a running agent is the only one that gets the live tint`() {
        assertEquals(LaneTone.LIVE, laneTone("running"))
        assertEquals(LaneTone.OWN, laneTone("done"))
    }

    @Test
    fun `a failure is the error tint and nothing else is`() {
        assertEquals(LaneTone.FAILED, laneTone("failed"))
        assertTrue(laneTone("stalled") != LaneTone.FAILED, "stopping without a result is not an error")
        assertTrue(laneTone("orphan") != LaneTone.FAILED, "a join lost to a compaction is not an error")
    }

    @Test
    fun `a loose end is muted rather than coloured`() {
        // Its own hue would claim it finished. The error tint would claim it broke.
        assertEquals(LaneTone.LOOSE, laneTone("stalled"))
        assertEquals(LaneTone.LOOSE, laneTone("orphan"))
    }

    @Test
    fun `an unknown status settles rather than alarming`() {
        assertEquals(LaneTone.OWN, laneTone("something-new"))
    }

    @Test
    fun `the words for a status never overstate it`() {
        assertEquals("working", statusWords("running"))
        assertEquals("settled", statusWords("done"))
        assertEquals("failed", statusWords("failed"))
        assertEquals("stopped without a result", statusWords("stalled"))
        assertEquals("ran, unplaced", statusWords("orphan"))
    }

    @Test
    fun `the save line says nothing about text nobody has touched`() {
        // "Saved" over an untouched page is a claim about work that never happened
        // — the same rule the scratchpad editor's line follows.
        assertEquals("", metaSaveWords(SessionMetaSaver.State.IDLE))
        assertEquals("Editing…", metaSaveWords(SessionMetaSaver.State.PENDING))
        assertEquals("Saving…", metaSaveWords(SessionMetaSaver.State.SAVING))
        assertEquals("Saved", metaSaveWords(SessionMetaSaver.State.SAVED))
        assertEquals("Not saved", metaSaveWords(SessionMetaSaver.State.FAILED))
    }
}
