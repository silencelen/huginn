package com.silencelen.huginn

import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.Foreground
import com.silencelen.huginn.notify.Heartbeat
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.appendDictation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The when-to-lock rule, tested apart from any Android machinery. */
class AppLockTest {

    private val NOW = 1_800_000_000_000L

    @Test
    fun `disabled never locks`() {
        assertFalse(AppLock.shouldLock(enabled = false, awayAt = 0L, now = NOW))
    }

    @Test
    fun `a cold start with the lock on always locks`() {
        // Process death erases any memory of a recent unlock. Guessing in the
        // user's favour would mean the lock not applying exactly when the phone
        // was away long enough for the process to die.
        assertTrue(AppLock.shouldLock(enabled = true, awayAt = 0L, now = NOW))
    }

    @Test
    fun `a quick hop to another app comes back unlocked`() {
        assertFalse(AppLock.shouldLock(enabled = true, awayAt = NOW - 10_000, now = NOW))
    }

    @Test
    fun `a minute away locks`() {
        assertTrue(AppLock.shouldLock(enabled = true, awayAt = NOW - AppLock.GRACE_MS, now = NOW))
    }

    @Test
    fun `just inside the grace stays open`() {
        assertFalse(AppLock.shouldLock(enabled = true, awayAt = NOW - AppLock.GRACE_MS + 1, now = NOW))
    }

    // ------------------------------------------------- #68 a clock that went back

    @Test
    fun `a reading EARLIER than the stamp locks`() {
        // The grace used to be `now - awayAt >= GRACE_MS` on the WALL clock, so a
        // backward jump larger than the real time away made the difference
        // negative and skipped the lock entirely — three hours backgrounded with
        // the date set back a day drew the whole app with no credential prompt,
        // at both gates. The stamps are SystemClock.elapsedRealtime() now, which
        // cannot go backwards; a reading that does is a fault, and a lock that
        // fails open on a fault is not a lock.
        assertTrue(AppLock.shouldLock(enabled = true, awayAt = NOW, now = NOW - 86_400_000L))
        assertTrue(AppLock.shouldLock(enabled = true, awayAt = NOW, now = NOW - 1L))
        assertTrue(AppLock.shouldLock(enabled = true, awayAt = NOW, now = 0L))
    }

    @Test
    fun `a clock that does not move still locks once the grace is up`() {
        // A frozen reading was the other half: `now` never advancing meant the
        // difference never reached the grace. A monotonic source cannot freeze,
        // and the boundary is unchanged.
        assertFalse(AppLock.shouldLock(enabled = true, awayAt = NOW, now = NOW))
        assertTrue(AppLock.shouldLock(enabled = true, awayAt = NOW, now = NOW + AppLock.GRACE_MS))
    }

    @Test
    fun `a backward reading with the lock OFF is still not a lock`() {
        assertFalse(AppLock.shouldLock(enabled = false, awayAt = NOW, now = NOW - 86_400_000L))
    }

    // ------------------------------------------------------------- dictation

    @Test
    fun `dictation into an empty draft is just the words`() {
        assertEquals("hello world", appendDictation("", "hello world"))
    }

    @Test
    fun `dictation appends with exactly one space`() {
        assertEquals("check the logs then restart", appendDictation("check the logs", "then restart"))
        assertEquals("check the logs then restart", appendDictation("check the logs ", " then restart "))
    }

    @Test
    fun `hearing nothing changes nothing`() {
        assertEquals("draft", appendDictation("draft", "   "))
    }
}

/**
 * The wake-up cadence policy — the whole battery story in one function.
 *
 * Push measured at 17-86ms on real hardware in every state including deep Doze
 * with the process killed and the app off the battery allowlist. The alarm is
 * therefore a safety net, not the delivery path, and should cost accordingly.
 */
class HeartbeatIntervalTest {

    @Test
    fun `never received a push - stay on the tight safety-net cadence`() {
        // Unproven path. Relaxing has to be earned by an arrival, not assumed,
        // or a fresh install where FCM is unavailable checks in once an hour.
        assertEquals(Heartbeat.INTERVAL_MS, Heartbeat.intervalFor(0L, 0L))
    }

    @Test
    fun `nothing dropped - the relaxed hourly cadence`() {
        assertEquals(Heartbeat.RELAXED_INTERVAL_MS, Heartbeat.intervalFor(1L, 1L))
        assertEquals(Heartbeat.RELAXED_INTERVAL_MS, Heartbeat.intervalFor(40L, 40L))
    }

    @Test
    fun `a silent night stays relaxed - REGRESSION, measured 2026-07-28`() {
        // The bug this rule replaced. The old policy relaxed only while a push had
        // arrived within two hours, so an idle night — no chats finishing, nothing
        // asking anything, therefore no pushes — tightened the alarm to ten
        // minutes. Observed on the owner's phone: 33 wake-ups between 02:00 and
        // 06:00, in the hours with the least to report. Silence is not failure;
        // only a push that was SENT and never arrived is.
        assertEquals(Heartbeat.RELAXED_INTERVAL_MS, Heartbeat.intervalFor(12L, 12L))
    }

    @Test
    fun `a push that was sent but never arrived tightens immediately`() {
        assertEquals(Heartbeat.INTERVAL_MS, Heartbeat.intervalFor(13L, 12L))
        assertEquals(Heartbeat.INTERVAL_MS, Heartbeat.intervalFor(99L, 12L))
    }

    @Test
    fun `catching back up returns to relaxed - self-correcting`() {
        assertEquals(Heartbeat.INTERVAL_MS, Heartbeat.intervalFor(13L, 12L))
        assertEquals(Heartbeat.RELAXED_INTERVAL_MS, Heartbeat.intervalFor(13L, 13L))
    }

    @Test
    fun `a host that forgot its tally does not tighten the phone`() {
        // The daemon's push state can be reset (a wipe, a reinstall) while the
        // phone's count stands. Fewer sent than received means nothing is being
        // dropped, which is the safe reading — not an error to react to.
        assertEquals(Heartbeat.RELAXED_INTERVAL_MS, Heartbeat.intervalFor(0L, 40L))
    }

    @Test
    fun `the relaxed cadence is a real saving, not a token one`() {
        // 144 wake-ups a day becomes 24.
        assertTrue(Heartbeat.RELAXED_INTERVAL_MS >= Heartbeat.INTERVAL_MS * 6)
    }
}

/**
 * What Settings tells you about the wake-up cadence.
 *
 * Derived from the same function the alarm uses rather than restated, so the
 * screen cannot claim one thing while the alarm does another — which is the
 * failure mode for any status display that reimplements the logic it reports on.
 */
class DeliveryHealthTest {

    private fun health(sent: Long, received: Long) =
        HuginnViewModel.DeliveryHealth(pushesSent = sent, pushesReceived = received)

    @Test
    fun `nothing dropped reads as relaxed`() {
        assertTrue(health(40, 40).relaxed)
        assertEquals(0L, health(40, 40).pushesMissing)
    }

    @Test
    fun `a deficit reads as tightened, and counts what is missing`() {
        assertFalse(health(43, 41).relaxed)
        assertEquals(2L, health(43, 41).pushesMissing)
    }

    @Test
    fun `an unproven path reads as tightened`() {
        assertFalse(health(0, 0).relaxed)
    }

    @Test
    fun `a host that forgot its tally never reports negative losses`() {
        // Sent < received after a daemon state reset. "-38 pushes never arrived"
        // would be nonsense on the screen, and it must not read as a fault.
        val h = health(0, 38)
        assertEquals(0L, h.pushesMissing)
        assertEquals("never more arrived than were sent", 0L, h.pushesArrived)
    }

    @Test
    fun `arrivals from a dead epoch no longer prove the push path`() {
        // CHANGED, deliberately, with the "1274 of 916" fix (D2, 3.1.1). The old
        // rule read sent=0 / received=38 as "nothing is being dropped, relax" —
        // which is the same reasoning that printed "1274 of 916 pushes arrived —
        // nothing dropped". Those 38 arrived against a counter the host no longer
        // has; they say nothing about the path as it stands now.
        //
        // So the phone goes back to the tight cadence until one push arrives in
        // THIS epoch and proves it, which is what Heartbeat already does for a
        // fresh install. It self-corrects on the first arrival, and erring toward
        // checking more often is the safe direction for a fallback.
        assertFalse(health(0, 38).relaxed)
        assertTrue("and one arrival is enough to relax again", health(1, 1).relaxed)
    }
}

/**
 * The don't-buzz-about-the-open-screen rule.
 *
 * The `resumed` gate is the part worth pinning: a chat left open when the phone
 * was pocketed is still the composed destination, but nobody is looking at it,
 * and a finish arriving then MUST notify.
 */
class ForegroundTest {

    @org.junit.After
    fun reset() {
        Foreground.resumed = false
        Foreground.chat = null
        Foreground.session = null
    }

    @Test
    fun `the open chat, while resumed, is showing`() {
        Foreground.resumed = true; Foreground.chat = "c1"
        assertTrue(Foreground.showsChat("c1"))
        assertFalse(Foreground.showsChat("c2"))
    }

    @Test
    fun `the same chat, paused, is NOT showing`() {
        Foreground.resumed = false; Foreground.chat = "c1"
        assertFalse(Foreground.showsChat("c1"))
    }

    @Test
    fun `a fresh process shows nothing`() {
        // FCM starts the process; nothing has written here; defaults must read
        // as "not looking", so the push posts normally.
        assertFalse(Foreground.showsChat("c1"))
        assertFalse(Foreground.showsSession("s1"))
    }

    @Test
    fun `null never matches, even against null state`() {
        Foreground.resumed = true
        assertFalse(Foreground.showsChat(null))
        assertFalse(Foreground.showsSession(null))
    }

    @Test
    fun `sessions and chats do not cross-match`() {
        Foreground.resumed = true; Foreground.session = "x"
        assertFalse(Foreground.showsChat("x"))
        assertTrue(Foreground.showsSession("x"))
    }
}
