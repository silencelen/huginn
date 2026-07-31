package com.silencelen.huginn

import com.silencelen.huginn.notify.WatchCycle
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The rule that decides whether a chat finished while nobody was looking.
 *
 * Worth its own tests because the background check runs every ten minutes, so "a
 * chat that started and finished between two observations" is the normal case rather
 * than an edge case — and the obvious implementation, diffing the set of running
 * chats, silently reports nothing at all for it.
 */
class WatchCycleTest {

    @Test
    fun `a chat seen running and then not has finished`() {
        val finished = WatchCycle.finishedSince(
            runsBefore = mapOf("c1" to 0L),
            runsNow = mapOf("c1" to 0L),
            previouslyRunning = setOf("c1"),
            running = emptySet(),
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `a chat that ran entirely between two looks still counts as finished`() {
        val finished = WatchCycle.finishedSince(
            runsBefore = mapOf("c1" to 3L),
            runsNow = mapOf("c1" to 4L),
            // Never observed running: the set diff alone would report nothing.
            previouslyRunning = emptySet(),
            running = emptySet(),
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `several runs inside one gap report the chat once, not once per run`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 1L), mapOf("c1" to 5L), emptySet(), emptySet(),
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `a chat that finished and immediately started again is reported`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 1L), mapOf("c1" to 2L),
            previouslyRunning = setOf("c1"),
            running = setOf("c1"),          // still running, so no edge to see
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `a chat still running with no completed run is not reported`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 2L), mapOf("c1" to 2L), setOf("c1"), setOf("c1"),
        )
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `nothing happening reports nothing`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 7L), mapOf("c1" to 7L), emptySet(), emptySet(),
        )
        assertTrue(finished.isEmpty())
    }

    /**
     * The trap this guards: a chat with no recorded baseline has a counter describing
     * its whole history. Treating that as news would make the first look after an
     * install announce every chat ever run.
     */
    @Test
    fun `a chat never seen before is not announced on its history`() {
        val finished = WatchCycle.finishedSince(
            runsBefore = emptyMap(),
            runsNow = mapOf("old-chat" to 42L),
            previouslyRunning = emptySet(),
            running = emptySet(),
        )
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `a deleted chat that was running is still reported as finished`() {
        val finished = WatchCycle.finishedSince(
            runsBefore = mapOf("c1" to 1L),
            runsNow = emptyMap(),               // gone from the snapshot entirely
            previouslyRunning = setOf("c1"),
            running = emptySet(),
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `two chats finishing at once are both reported`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 0L, "c2" to 0L),
            mapOf("c1" to 1L, "c2" to 0L),
            previouslyRunning = setOf("c2"),
            running = emptySet(),
        )
        assertEquals(setOf("c1", "c2"), finished)
    }

    @Test
    fun `a counter that somehow went backwards is not a finish`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 5L), mapOf("c1" to 2L), emptySet(), emptySet(),
        )
        assertTrue(finished.isEmpty())
    }
}
