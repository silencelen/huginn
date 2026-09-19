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

    private val open =
        PaneLease.wanted("sess", visible = true, wantsGrid = true, live = true, cols = 120, rows = 40)

    @Test
    fun aVisibleGridViewWantsItsMeasuredGeometry() {
        assertEquals(PaneLease.Want("sess", 120, 40), open)
    }

    @Test
    fun aHiddenWindowWantsNothing() {
        // The failure this whole object exists for: minimize, keep polling, pin
        // someone else's window for as long as the app runs.
        assertNull(PaneLease.wanted("sess", visible = false, wantsGrid = true, live = true,
            cols = 120, rows = 40))
    }

    @Test
    fun aConversationViewWantsNothing() {
        // Reading the transcript does not need tmux reshaped, so it must not lease.
        assertNull(PaneLease.wanted("sess", visible = true, wantsGrid = false, live = true,
            cols = 120, rows = 40))
    }

    @Test
    fun noSessionWantsNothing() {
        assertNull(PaneLease.wanted(null, visible = true, wantsGrid = true, live = true,
            cols = 120, rows = 40))
    }

    @Test
    fun anUnmeasuredGridWantsNothing() {
        // Before the first layout pass there is no honest answer, and guessing one
        // leases a size, then leases a second size a frame later.
        assertNull(PaneLease.wanted("sess", visible = true, wantsGrid = true, live = true,
            cols = null, rows = null))
    }

    @Test
    fun geometryIsClampedToWhatTheDaemonAccepts() {
        val small = PaneLease.wanted("s", true, true, true, 4, 2)
        assertEquals(PaneLease.Want("s", 20, 10), small)
        val huge = PaneLease.wanted("s", true, true, true, 9_000, 9_000)
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
        val other = PaneLease.wanted("other", true, true, true, 80, 24)
        assertEquals("sess", PaneLease.toRelease("sess", other))
    }

    @Test
    fun aResizeOfTheSameSessionIsNotARelease() {
        // The daemon replaces the geometry in place; releasing between sizes would
        // hand the window back and re-take it on every drag step.
        val resized = PaneLease.wanted("sess", true, true, true, 100, 30)
        assertNull(PaneLease.toRelease("sess", resized))
    }

    // -------------------------------------------- live view is what leases

    /**
     * OWNER DECISION 52, as the one rule everything else reduces to.
     *
     * Two clients with the same session open, neither typing, walked the owner's
     * real pane 152x44 <-> 107x44 three times in ninety seconds — each poll
     * reporting its own geometry, the last poll winning. Watching a pane is not a
     * reason to reshape somebody's terminal.
     */
    @Test
    fun aGridThatIsOnlyBeingWatchedLeasesNothing() {
        assertNull(
            PaneLease.wanted("sess", visible = true, wantsGrid = true, live = false,
                cols = 120, rows = 40),
            "a visible, measured, foreground pane still may not claim the window",
        )
    }

    @Test
    fun enteringLiveViewIsWhatTakesTheLease() {
        assertEquals(
            PaneLease.Want("sess", 120, 40),
            PaneLease.wanted("sess", visible = true, wantsGrid = true, live = true,
                cols = 120, rows = 40),
        )
    }

    @Test
    fun leavingLiveViewReleasesWhatWasHeld() {
        // The exit path: the reader taps out of the keyboard mode but stays on the
        // tab. `wanted` goes null, and `toRelease` therefore hands the window back
        // without the view being torn down at all.
        val watching = PaneLease.wanted("sess", true, true, live = false, cols = 120, rows = 40)
        assertEquals("sess", PaneLease.toRelease("sess", watching))
    }

    @Test
    fun aWatchedGridStillReportsItsSize() {
        // REPORTING IS NOT CLAIMING. The daemon captures the pane as it is; the
        // geometry still travels so a client can say what it can draw, and the
        // separation is what lets the lease go without the view going blind.
        assertEquals(
            PaneLease.Want("sess", 120, 40),
            PaneLease.reported("sess", visible = true, wantsGrid = true, cols = 120, rows = 40),
        )
        assertNull(PaneLease.reported("sess", visible = false, wantsGrid = true, 120, 40))
    }

    // ------------------------------------------- the phone's shape of it

    @Test
    fun aPhonePollReportsItsGridAndClaimsNothingUntilLive() {
        val watching = PaneLease.poll(cols = 60, rows = 30, liveView = false)
        assertEquals(PaneLease.Poll(60, 30, live = false), watching,
            "the Screen tab merely on display must not lease")
        val typing = PaneLease.poll(cols = 60, rows = 30, liveView = true)
        assertEquals(PaneLease.Poll(60, 30, live = true), typing)
    }

    @Test
    fun aPhonePollWithNoMeasurementClaimsNothing() {
        // Live mode entered before the first layout pass: there is no geometry to
        // lease at, and claiming one at null would be claiming a guess.
        assertEquals(PaneLease.Poll(null, null, live = false),
            PaneLease.poll(cols = null, rows = null, liveView = true))
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
