package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.ScratchpadSaver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two lines a person reads about a page: what is on it, and whether it is
 * safely written down. Both clients draw them from here.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ScratchpadViewsTest {

    /** Epoch SECONDS, like every record timestamp the daemon stamps. */
    private val nowMs = 1_800_000_000_000L
    private val nowSec = nowMs / 1000

    private fun pad(size: Int, updatedSecAgo: Long = 0) =
        Scratchpad(id = "p1", name = "Deploy notes", size = size, updatedAt = nowSec - updatedSecAgo)

    @Test
    fun `an empty page says so rather than counting nothing`() {
        // Whether there is anything on it is what a picker is actually being
        // asked, and "0 characters" answers that in the least direct way there is.
        assertTrue(padSubtitle(pad(size = 0), nowMs).startsWith("empty"), padSubtitle(pad(0), nowMs))
    }

    @Test
    fun `a written page carries its size and when it last changed`() {
        assertEquals("128 characters · 2h ago", padSubtitle(pad(size = 128, updatedSecAgo = 7_200), nowMs))
    }

    @Test
    fun `a page with no timestamp shows no dangling separator`() {
        // A 1970 stamp or a missing one must not render as "empty · " — the same
        // rule the device line follows about a bare "last seen".
        val never = Scratchpad(id = "p1", name = "New", size = 0, updatedAt = 0)
        assertEquals("empty", padSubtitle(never, nowMs))
    }

    // -------------------------------------------------------- the save line

    @Test
    fun `an untouched page claims nothing`() {
        // "Saved" over text nobody has typed into is a claim about work that never
        // happened, and it is the state a page is in every time one is opened.
        assertEquals("", saveWords(ScratchpadSaver.State.IDLE))
    }

    @Test
    fun `every other state says something, and says it quietly`() {
        assertEquals("Editing…", saveWords(ScratchpadSaver.State.PENDING))
        assertEquals("Saving…", saveWords(ScratchpadSaver.State.SAVING))
        assertEquals("Saved", saveWords(ScratchpadSaver.State.SAVED))
        // The one that matters: there is no Save button, so a write that did not
        // land has to be visible or the work is silently at risk.
        assertEquals("Not saved", saveWords(ScratchpadSaver.State.FAILED))
    }
}
