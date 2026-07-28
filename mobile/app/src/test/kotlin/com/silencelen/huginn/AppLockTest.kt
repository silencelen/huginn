package com.silencelen.huginn

import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.Heartbeat
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
