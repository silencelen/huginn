package com.silencelen.huginn

import com.silencelen.huginn.ui.Suggest
import com.silencelen.huginn.ui.SuggestionCue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When suggestions may be shown, and when they may be asked for. Both are cheap
 * rules with expensive failure modes: shown at the wrong moment they cover a
 * composer someone is typing in, and fetched at the wrong moment they spend a
 * model call per poll.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuggestTest {

    private val three = listOf("a", "b", "c")

    @Test
    fun `chips show when the turn is over and nothing is typed`() {
        assertTrue(Suggest.visible(three, busy = false, draft = ""))
    }

    @Test
    fun `a running turn hides them`() {
        assertFalse(Suggest.visible(three, busy = true, draft = ""))
    }

    @Test
    fun `a typed draft outranks them`() {
        assertFalse(Suggest.visible(three, busy = false, draft = "what about"))
        // Whitespace is not a draft.
        assertTrue(Suggest.visible(three, busy = false, draft = "   "))
    }

    @Test
    fun `an empty set draws nothing`() {
        assertFalse(Suggest.visible(emptyList(), busy = false, draft = ""))
    }

    @Test
    fun `the same offset is never fetched twice`() {
        assertTrue(Suggest.shouldFetch(120L, lastOffset = -1L, busy = false, inFlight = false))
        assertFalse(Suggest.shouldFetch(120L, lastOffset = 120L, busy = false, inFlight = false))
        assertTrue(Suggest.shouldFetch(140L, lastOffset = 120L, busy = false, inFlight = false))
    }

    @Test
    fun `nothing is fetched while a turn runs, or while one is already in flight`() {
        assertFalse(Suggest.shouldFetch(120L, -1L, busy = true, inFlight = false))
        assertFalse(Suggest.shouldFetch(120L, -1L, busy = false, inFlight = true))
        assertFalse(Suggest.shouldFetch(null, -1L, busy = false, inFlight = false))
    }

    @Test
    fun `the cue fetches once per turn and drops the set when the next one starts`() = runTest {
        var calls = 0
        val cue = SuggestionCue(this) { calls++; three }

        cue.onTurnBoundary(offset = 100L, busy = false)
        advanceUntilIdle()
        assertEquals(three, cue.suggestions.value)
        assertEquals(1, calls)

        // A poll with nothing new must not ask again.
        cue.onTurnBoundary(offset = 100L, busy = false)
        advanceUntilIdle()
        assertEquals(1, calls)

        // A new turn starts: what we have is about the previous answer.
        cue.onTurnBoundary(offset = 100L, busy = true)
        assertEquals(emptyList(), cue.suggestions.value)

        cue.onTurnBoundary(offset = 180L, busy = false)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(three, cue.suggestions.value)
    }

    @Test
    fun `a failed fetch leaves the surface silent`() = runTest {
        val cue = SuggestionCue(this) { error("no transcript") }
        cue.onTurnBoundary(offset = 10L, busy = false)
        advanceUntilIdle()
        assertEquals(emptyList(), cue.suggestions.value)
    }

    @Test
    fun `clearing forgets the offset so the next visit asks again`() = runTest {
        var calls = 0
        val cue = SuggestionCue(this) { calls++; three }

        cue.onTurnBoundary(offset = 100L, busy = false)
        advanceUntilIdle()
        cue.clear()
        assertEquals(emptyList(), cue.suggestions.value)

        cue.onTurnBoundary(offset = 100L, busy = false)
        advanceUntilIdle()
        assertEquals(2, calls)
    }
}
