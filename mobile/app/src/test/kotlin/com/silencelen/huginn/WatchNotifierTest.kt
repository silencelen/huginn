package com.silencelen.huginn

import com.silencelen.huginn.notify.ReplyStep
import com.silencelen.huginn.notify.WatchNotifier
import com.silencelen.huginn.notify.needsRevival
import com.silencelen.huginn.ui.TimeFormat
import com.silencelen.huginn.ui.TimeWords
import com.silencelen.huginn.widget.FLEET_STALE_MS
import com.silencelen.huginn.widget.fleetIsStale
import com.silencelen.huginn.notify.replyStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification layer's decisions, without a Context.
 *
 * Posting needs Android; what to post does not, and everything asserted here is
 * a branch that was taken wrongly in a receiver or a watch cycle. Same
 * arrangement the rest of this source set uses.
 */
class ReplyReceiverRulesTest {

    @Test
    fun `a whitespace-only reply is not a send, and the notification comes back`() {
        // SystemUI enables the send button on RAW length, so every one of these
        // is submittable from the shade — and each returned before goAsync() and
        // before any update(), leaving the spinner running for good.
        assertEquals(ReplyStep.RESTORE, replyStep(" "))
        assertEquals(ReplyStep.RESTORE, replyStep("\t"))
        assertEquals(ReplyStep.RESTORE, replyStep("\n"))
        assertEquals(ReplyStep.RESTORE, replyStep(" "))
        assertEquals(ReplyStep.RESTORE, replyStep("   \t \n "))
    }

    @Test
    fun `a missing RemoteInput result is the same dead end`() {
        assertEquals(ReplyStep.RESTORE, replyStep(null))
        assertEquals(ReplyStep.RESTORE, replyStep(""))
    }

    @Test
    fun `an ordinary reply sends`() {
        assertEquals(ReplyStep.SEND, replyStep("ok"))
        assertEquals(ReplyStep.SEND, replyStep("  ok  "))
    }
}

/**
 * THE OBSERVATION GATE.
 *
 * `WatchNotifier.apply` itself needs a Context; what it needed and did not have
 * is mutual exclusion, and that is assertable on its own. Five in-process
 * callers reach the cycle — the watch service, the heartbeat, the session
 * worker, the widget worker and the push reconcile — and each one reads three
 * persisted baselines, makes network calls, then writes them back.
 */
class WatchNotifierTest {

    @Test
    fun `two cycles observing the same transition do not interleave`() = runTest {
        // A baseline read-modify-write with a suspension in the middle, exactly
        // like the real one: read notifiedSessions, fetch prompts, write it back.
        var baseline = 0
        var bothInsideAtOnce = false
        var inside = 0

        suspend fun cycle() = WatchNotifier.guarded {
            inside++
            if (inside > 1) bothInsideAtOnce = true
            val seen = baseline
            yield()
            yield()
            baseline = seen + 1
            inside--
        }

        launch { cycle() }
        launch { cycle() }

        runCurrent()
        assertEquals("both cycles must land", 2, baseline)
        assertEquals(false, bothInsideAtOnce)
    }

    @Test
    fun `the gate is not held once a cycle is done`() = runTest {
        var ran = 0
        WatchNotifier.guarded { ran++ }
        WatchNotifier.guarded { ran++ }
        assertEquals(2, ran)
    }
}

/**
 * The watch service's revival gate.
 *
 * The service itself is an Android component; whether it should start its loop
 * is one comparison, and it was the wrong one.
 */
class WatchServiceRevivalTest {

    @Test
    fun `a job that has finished is not a job that is running`() {
        val live = Job()
        assertEquals(false, needsRevival(live))

        // The silent variant: the loop coroutine COMPLETED (cancelled, or
        // returned) while `job` stayed non-null, because only onDestroy ever
        // nulled it. Every later start() was then a permanent no-op with the
        // service still alive and its notification still up.
        val done = Job().apply { complete() }
        assertEquals(true, needsRevival(done))

        val cancelled = Job().apply { cancel() }
        assertEquals(true, needsRevival(cancelled))

        assertEquals(true, needsRevival(null))
    }
}

/**
 * What the home-screen widget says about HOW OLD what it is showing is.
 *
 * The widget is Glance; the two rules behind its header and its counts are not.
 */
class FleetWidgetAgeTest {

    private val fmt = TimeFormat(tzOffsetSec = 0, hour24 = true)

    /** 2026-09-18 14:30:00Z. */
    private val now = 1_789_655_400_000L

    /** What the header used to print: a bare SHORT time-of-day, no date. */
    private fun bareShortTime(atMs: Long): String {
        val f = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, java.util.Locale.UK)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(atMs))
    }

    @Test
    fun `a day-old snapshot does not read like a two-hour-old one`() {
        val recent = now - 2 * 3_600_000L
        val aDayOlder = recent - 86_400_000L
        val aWeekOlder = recent - 7 * 86_400_000L

        // The defect, pinned: one string for all three, while CountsLine went on
        // asserting the stale counts beneath it.
        assertEquals(bareShortTime(recent), bareShortTime(aDayOlder))
        assertEquals(bareShortTime(recent), bareShortTime(aWeekOlder))

        val a = TimeWords.stampMs(recent, now, fmt)
        val b = TimeWords.stampMs(aDayOlder, now, fmt)
        val c = TimeWords.stampMs(aWeekOlder, now, fmt)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(b, c)
        assertTrue("a day-old snapshot says so: $b", b.startsWith("Yesterday"))
    }

    @Test
    fun `a stamp that is not one says nothing at all`() {
        assertEquals("", TimeWords.stampMs(0L, now, fmt))
    }

    @Test
    fun `the counts stop reading as current once the snapshot is half a day old`() {
        assertFalse(fleetIsStale(now - 3_600_000L, now))
        assertFalse(fleetIsStale(now - (FLEET_STALE_MS - 1), now))
        assertTrue(fleetIsStale(now - FLEET_STALE_MS, now))
        assertTrue(fleetIsStale(now - 7 * 86_400_000L, now))
        // Nothing recorded is not a stale reading.
        assertFalse(fleetIsStale(0L, now))
    }
}
