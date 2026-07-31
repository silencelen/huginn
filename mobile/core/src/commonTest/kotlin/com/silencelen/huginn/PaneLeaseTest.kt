package com.silencelen.huginn

import com.silencelen.huginn.data.Backoff
import com.silencelen.huginn.data.PaneLease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The lease rule is the one piece of this client that can damage someone else's
 * work: a window that keeps reporting geometry holds another operator's tmux
 * window at a shape chosen by a window they cannot see. Every one of these is a
 * release path, and a release path that is only exercised by hand is a release
 * path that stops working.
 */
class PaneLeaseTest {

    private val open = PaneLease.wanted("sess", visible = true, wantsGrid = true, cols = 120, rows = 40)

    @Test
    fun aVisibleGridViewWantsItsMeasuredGeometry() {
        assertEquals(PaneLease.Want("sess", 120, 40), open)
    }

    @Test
    fun aHiddenWindowWantsNothing() {
        // The failure this whole object exists for: minimize, keep polling, pin
        // someone else's window for as long as the app runs.
        assertNull(PaneLease.wanted("sess", visible = false, wantsGrid = true, cols = 120, rows = 40))
    }

    @Test
    fun aConversationViewWantsNothing() {
        // Reading the transcript does not need tmux reshaped, so it must not lease.
        assertNull(PaneLease.wanted("sess", visible = true, wantsGrid = false, cols = 120, rows = 40))
    }

    @Test
    fun noSessionWantsNothing() {
        assertNull(PaneLease.wanted(null, visible = true, wantsGrid = true, cols = 120, rows = 40))
    }

    @Test
    fun anUnmeasuredGridWantsNothing() {
        // Before the first layout pass there is no honest answer, and guessing one
        // leases a size, then leases a second size a frame later.
        assertNull(PaneLease.wanted("sess", visible = true, wantsGrid = true, cols = null, rows = null))
    }

    @Test
    fun geometryIsClampedToWhatTheDaemonAccepts() {
        val small = PaneLease.wanted("s", true, true, 4, 2)
        assertEquals(PaneLease.Want("s", 20, 10), small)
        val huge = PaneLease.wanted("s", true, true, 9_000, 9_000)
        assertEquals(PaneLease.Want("s", 300, 200), huge)
    }

    @Test
    fun holdingNothingReleasesNothing() {
        assertNull(PaneLease.toRelease(null, open))
        assertNull(PaneLease.toRelease(null, null))
    }

    @Test
    fun wantingNothingReleasesWhatIsHeld() {
        // Leaving the view, hiding the window, switching to the conversation tab:
        // all three arrive here as `wanted == null`.
        assertEquals("sess", PaneLease.toRelease("sess", null))
    }

    @Test
    fun switchingSessionReleasesThePreviousOne() {
        val other = PaneLease.wanted("other", true, true, 80, 24)
        assertEquals("sess", PaneLease.toRelease("sess", other))
    }

    @Test
    fun aResizeOfTheSameSessionIsNotARelease() {
        // The daemon replaces the geometry in place; releasing between sizes would
        // hand the window back and re-take it on every drag step.
        val resized = PaneLease.wanted("sess", true, true, 100, 30)
        assertNull(PaneLease.toRelease("sess", resized))
    }
}

/** The anti-hammer ladders, pinned against the values the shipped clients use. */
class BackoffTest {

    @Test
    fun screenStartsAtItsFloorAndCapsAtFifteenSeconds() {
        assertEquals(1_000, Backoff.screen(1))
        assertEquals(2_000, Backoff.screen(2))
        assertEquals(4_000, Backoff.screen(3))
        assertEquals(8_000, Backoff.screen(4))
        assertEquals(15_000, Backoff.screen(5))
        assertEquals(15_000, Backoff.screen(400))
    }

    @Test
    fun aHealthyTranscriptPollKeepsItsOrdinaryTick() {
        assertEquals(2_500, Backoff.transcript(0))
    }

    @Test
    fun theFirstTranscriptFailureAlreadyCosts() {
        // A session that never prompted Claude 409s forever; at a flat tick that is
        // ~24 daemon errors a minute for as long as the view stays open.
        assertEquals(5_000, Backoff.transcript(1))
        assertEquals(10_000, Backoff.transcript(2))
        assertEquals(20_000, Backoff.transcript(3))
        assertEquals(40_000, Backoff.transcript(4))
        assertEquals(60_000, Backoff.transcript(5))
    }

    @Test
    fun aViewLeftOpenOvernightDoesNotOverflowIntoNonsense() {
        assertEquals(60_000, Backoff.transcript(62))
        assertEquals(60_000, Backoff.transcript(Int.MAX_VALUE))
        assertEquals(15_000, Backoff.screen(Int.MAX_VALUE))
    }
}
