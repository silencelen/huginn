package com.silencelen.huginn.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auto-scroll latch, which has now been wrong four times.
 *
 * Each of the four is a test here rather than a memory, because until this rule
 * was lifted out of `FollowNewest` there was nowhere to put one: the whole thing
 * was a composable entangled with LazyListState, and every bug in it was found by
 * the owner, live, mid-conversation.
 *
 * NOTE the argument order — kotlin.test is `assertEquals(expected, actual, msg)`,
 * JUnit is `assertEquals(msg, expected, actual)`.
 */
class FollowTest {

    /**
     * BUG 1, the original: following was decided by measuring at the moment
     * content arrived, and the measurement was taken after the new rows were laid
     * out — so a reader sitting at the tail measured as scrolled away and
     * following stopped on its own.
     */
    @Test
    fun contentArrivingNeverStopsFollowing() {
        var s = Follow.State()
        repeat(50) { i -> s = Follow.arrived(s, grew = i % 3 == 0) }
        assertTrue(s.following, "arrivals alone can never unlatch — they measure nothing")
        assertFalse(s.unseen, "and there is nothing unseen while the view is following it")
    }

    /**
     * BUG 2: the follower's own scrolling looked exactly like the reader leaving,
     * so the first scroll it performed turned itself off. Nothing but the reader's
     * input reaches [Follow.tookControl] — a programmatic scroll ends in
     * [Follow.settled] like any other, and must survive landing mid-item.
     */
    @Test
    fun onlyTheReaderUnlatches() {
        var s = Follow.State()
        s = Follow.arrived(s, grew = true)
        s = Follow.settled(s, atTail = true)
        s = Follow.settled(s, atTail = false)
        assertTrue(s.following, "a scroll the view performed itself must not unlatch it")
    }

    /** BUG 3: a tap that moved nothing counted as leaving, so tapping the transcript stopped it following. */
    @Test
    fun anInputThatGoesNowhereIsNotLeaving() {
        var s = Follow.tookControl(Follow.State())
        s = Follow.settled(s, atTail = true)
        assertTrue(s.following, "released still at the tail: they never left")
        assertFalse(s.unseen)
    }

    /**
     * BUG 4: a mouse wheel emits no DragInteraction, so on the desktop the latch
     * could only ever be armed — scrolling up to re-read something was undone by
     * the next token, forever. A wheel tick has to unlatch exactly like a finger.
     */
    @Test
    fun aWheelTickUnlatchesTheSameWayAFingerDoes() {
        var s = Follow.tookControl(Follow.State())
        s = Follow.settled(s, atTail = false)
        assertFalse(s.following, "they scrolled away and stopped there")
        s = Follow.arrived(s, grew = true)
        assertTrue(s.unseen, "new content below the fold is what the pill is for")
        assertFalse(s.following, "and it does NOT drag them back to read it")
    }

    /**
     * The other half of bug 4's fix, and the way to reintroduce it: a wheel tick
     * at the very bottom scrolls nothing, so if it unlatched for good the reader
     * would be pinned at the tail watching a conversation that had quietly stopped
     * following.
     */
    @Test
    fun aWheelTickAtTheBottomReArms() {
        var s = Follow.tookControl(Follow.State())
        s = Follow.settled(s, atTail = true)
        assertTrue(s.following, "nothing moved, so nothing was left")
    }

    /** Tokens growing into the block at the bottom are not "new messages", and must not raise the pill. */
    @Test
    fun aStreamGrowingIntoTheLastRowIsNotSomethingNew() {
        var s = Follow.tookControl(Follow.State())
        repeat(20) { s = Follow.arrived(s, grew = false) }
        assertFalse(s.unseen, "only a new ROW is unseen content")
    }

    /** Scrolling back to the tail is the reader saying they have caught up. */
    @Test
    fun reachingTheTailClearsThePill() {
        var s = Follow.arrived(Follow.tookControl(Follow.State()), grew = true)
        assertTrue(s.unseen)
        s = Follow.settled(s, atTail = true)
        assertFalse(s.unseen, "caught up: the pill has nothing left to announce")
        assertTrue(s.following)
    }

    /** Coming to rest anywhere else leaves them where they put themselves. */
    @Test
    fun settlingAwayFromTheTailChangesNothing() {
        val away = Follow.State(following = false, unseen = true, atTail = false)
        assertTrue(
            Follow.settled(away, atTail = false) == away,
            "a scroll that ends short of the tail is not a decision to follow again",
        )
    }

    // ------------------------------------------- the pill's two readings (2026-10-23)

    /**
     * "Back to bottom": the reader took the list AND the bottom left the screen.
     * Both halves, because the latch alone flips at the first wheel tick, before
     * anything has moved — a reader wheeling down at the very bottom would see
     * the pill flash on every tick.
     */
    @Test
    fun awayIsTheReaderGoneAndTheTailOutOfView() {
        var s = Follow.tookControl(Follow.State())
        assertFalse(s.away, "took the list, but nothing has moved yet: not away")
        s = Follow.settled(s, atTail = false)
        assertTrue(s.away, "came to rest with the bottom out of view: away")
        assertFalse(s.unseen, "and nothing new has arrived, so the pill reads Back to bottom")
    }

    @Test
    fun reachingTheTailClearsAway() {
        val away = Follow.settled(Follow.tookControl(Follow.State()), atTail = false)
        val back = Follow.settled(away, atTail = true)
        assertFalse(back.away)
        assertTrue(back.following)
    }

    /**
     * A following reader is off the tail for a frame whenever rows land below
     * them — and is about to be scrolled to them. The geometry alone would flash
     * the pill there; the latch half of [Follow.State.away] is what stops it.
     */
    @Test
    fun aFollowingReaderIsNeverAwayWhileRowsLandBelowThem() {
        var s = Follow.arrived(Follow.State(), grew = true)
        s = Follow.settled(s, atTail = false) // the new rows measured below the fold
        assertFalse(s.away, "following: the scroll to them is a frame away")
        assertTrue(s.following)
    }

    @Test
    fun unseenOnlyWhileAway() {
        val away = Follow.settled(Follow.tookControl(Follow.State()), atTail = false)
        assertTrue(Follow.arrived(away, grew = true).unseen)
        assertFalse(Follow.arrived(Follow.State(), grew = true).unseen, "following: nothing is unseen")
    }

    // ------------------------------------- BUG 6: earlier pages are not news (2026-10-23)

    /**
     * ⚠ THE BUG: "Load earlier messages" raised the item count, the count read as
     * new content, and a reader at the top of the transcript got a "New
     * messages" pill for pages that landed ABOVE them.
     */
    @Test
    fun anEarlierPageLandingAboveAReaderIsNotUnseen() {
        val reading = Follow.settled(Follow.tookControl(Follow.State()), atTail = false)
        val s = Follow.arrived(reading, grew = true, prepended = true)
        assertFalse(s.unseen, "history above the reader is not new messages below them")
        assertFalse(s.following, "and they are still reading, exactly as before")
        assertFalse(Follow.scrollsOnArrival(s, prepended = true), "nothing moves")
    }

    /**
     * The other half: on a short transcript that still fitted the screen the
     * reader is FOLLOWING when they ask for history. The page lands above them
     * and the list keeps them at its end, so they are still at the tail: nothing
     * scrolls, nothing is announced, and the next real message still comes to
     * them rather than raising a pill.
     */
    @Test
    fun anEarlierPageWhileFollowingNeitherScrollsNorUnlatches() {
        val s = Follow.arrived(Follow.State(), grew = true, prepended = true)
        assertTrue(s.following, "still at the tail: the list kept them at its end")
        assertFalse(s.unseen)
        assertFalse(s.away)
        assertFalse(Follow.scrollsOnArrival(s, prepended = true), "history above is not a reason to scroll")
        // The next genuinely new row is followed, as it was before the page landed.
        val next = Follow.arrived(s, grew = true)
        assertFalse(next.unseen)
        assertTrue(Follow.scrollsOnArrival(next, prepended = false))
    }

    /** And without the prepend flag, the same growth is what it always was. */
    @Test
    fun aGrowthThatIsNotAPrependStillCounts() {
        val reading = Follow.settled(Follow.tookControl(Follow.State()), atTail = false)
        assertTrue(Follow.arrived(reading, grew = true, prepended = false).unseen)
        assertFalse(Follow.scrollsOnArrival(reading, prepended = false), "away: announced, not jumped to")
        assertTrue(Follow.arrived(Follow.State(), grew = true, prepended = false).following)
        assertTrue(Follow.scrollsOnArrival(Follow.State(), prepended = false), "following: scrolled to")
    }

    // ---------------------------------------- BUG 5: the re-measure (2026-10-23)

    /** The newest two rows on screen, at the bottom of an 800 x 600 viewport. */
    private val opened = Follow.Shape(width = 800, height = 600, rows = mapOf("r38" to 120, "r39" to 200))

    /**
     * BUG 5, the owner's words: "the status and chat suggestion bars load in
     * causing the user to have to scroll a bit down to lock it in to the
     * bottom". The strips arrive under the list after the jump to the newest and
     * take their height out of the viewport; nothing scrolled, so nothing used to
     * follow.
     */
    @Test
    fun barsArrivingUnderTheListKeepItOnTheNewest() {
        val withBars = opened.copy(height = 510)
        assertTrue(Follow.relaid(Follow.State(), opened, withBars, canScrollFurther = true, settling = false))
        // And whenever they arrive — suggestions come seconds after the turn.
        assertTrue(Follow.relaid(Follow.State(), withBars, opened, canScrollFurther = true, settling = false))
    }

    /** The other half of the same rule: a reader who scrolled up is left exactly where they went. */
    @Test
    fun aReaderWhoScrolledAwayIsNeverPulledBackByARemeasure() {
        val away = Follow.settled(Follow.tookControl(Follow.State()), atTail = false)
        assertFalse(Follow.relaid(away, opened, opened.copy(height = 510), canScrollFurther = true, settling = true))
        assertFalse(
            Follow.relaid(away, opened, opened.copy(rows = mapOf("r38" to 120, "r39" to 400)), canScrollFurther = true, settling = true),
        )
    }

    /**
     * A scroll changes WHICH rows are on screen and never how tall one is, nor
     * the viewport — so a scroll in flight, by any hand, is never taken for a
     * strip arriving, and a reader mid-drag is not dragged back to the bottom.
     */
    @Test
    fun aScrollIsNotARemeasure() {
        val scrolledUp = Follow.Shape(800, 600, mapOf("r30" to 90, "r31" to 140, "r38" to 120))
        assertFalse(Follow.relaid(Follow.State(), opened, scrolledUp, canScrollFurther = true, settling = true))
    }

    /** Late layout: the newest row measures taller a moment after it arrived (an image decoding). */
    @Test
    fun aRowStillLayingOutKeepsTheNewestInView() {
        val decoded = opened.copy(rows = mapOf("r38" to 120, "r39" to 480))
        assertTrue(Follow.relaid(Follow.State(), opened, decoded, canScrollFurther = true, settling = true))
    }

    /**
     * Long after it arrived, a row changing height is somebody opening a card,
     * and the bottom of the card they just opened is not where they want to be.
     */
    @Test
    fun aCardOpenedLaterIsNotFollowedToItsBottom() {
        val expanded = opened.copy(rows = mapOf("r38" to 120, "r39" to 900))
        assertFalse(Follow.relaid(Follow.State(), opened, expanded, canScrollFurther = true, settling = false))
    }

    /** A narrower window re-wraps every row: the viewport changed, so the newest stays in view. */
    @Test
    fun aNarrowerWindowKeepsTheNewestInView() {
        assertTrue(Follow.relaid(Follow.State(), opened, opened.copy(width = 500), canScrollFurther = true, settling = false))
    }

    @Test
    fun alreadyAtTheEndThereIsNothingToDo() {
        assertFalse(Follow.relaid(Follow.State(), opened, opened.copy(height = 700), canScrollFurther = false, settling = true))
    }

    /**
     * A wheel-down at the bottom while an answer streams: no scroll begins, and
     * the last row grows past the tail's slack before the start window runs out.
     * Judged by the measure taken then, the reader who moved nothing was "away".
     */
    @Test
    fun aWheelTickThatMovedNothingIsJudgedByWhereItFoundTheReader() {
        var s = Follow.tookControl(Follow.State())
        s = Follow.arrived(s, grew = false)          // a token, while unlatched
        s = Follow.settled(s, atTail = false)        // the row grew past the slack
        assertTrue(s.away, "the old settle: a reader who never moved, now away")
        s = Follow.stoodStill(s, wasAtTail = true)
        assertTrue(s.following, "the tick found them at the tail and moved nothing")
        assertFalse(s.away)
        assertFalse(s.unseen)
    }

    @Test
    fun aTickThatMovedNothingAwayFromTheTailChangesNothing() {
        val away = Follow.settled(Follow.tookControl(Follow.State()), atTail = false)
        assertEquals(away, Follow.stoodStill(away, wasAtTail = false))
    }
}
