package com.silencelen.huginn.ui

import kotlin.test.Test
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
        val away = Follow.State(following = false, unseen = true)
        assertTrue(
            Follow.settled(away, atTail = false) == away,
            "a scroll that ends short of the tail is not a decision to follow again",
        )
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
}
