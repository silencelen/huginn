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
}
