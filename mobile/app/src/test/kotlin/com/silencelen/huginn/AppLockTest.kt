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

    private val NOW = 1_800_000_000_000L

    @Test
    fun `never received a push - stay on the tight safety-net cadence`() {
        assertEquals(Heartbeat.INTERVAL_MS, Heartbeat.intervalFor(0L, NOW))
    }

    @Test
    fun `a recent push earns the relaxed hourly cadence`() {
        assertEquals(Heartbeat.RELAXED_INTERVAL_MS, Heartbeat.intervalFor(NOW - 60_000, NOW))
    }

    @Test
    fun `an idle night does not tighten it - trust outlasts a quiet spell`() {
        val ninetyMinutes = 90 * 60 * 1000L
        assertEquals(Heartbeat.RELAXED_INTERVAL_MS, Heartbeat.intervalFor(NOW - ninetyMinutes, NOW))
    }

    @Test
    fun `push gone quiet for hours tightens back up - self-correcting`() {
        assertEquals(Heartbeat.INTERVAL_MS, Heartbeat.intervalFor(NOW - Heartbeat.PUSH_TRUST_MS, NOW))
        assertEquals(Heartbeat.INTERVAL_MS, Heartbeat.intervalFor(NOW - 24 * 60 * 60 * 1000L, NOW))
    }

    @Test
    fun `the relaxed cadence is a real saving, not a token one`() {
        // 144 wake-ups a day becomes 24.
        assertTrue(Heartbeat.RELAXED_INTERVAL_MS >= Heartbeat.INTERVAL_MS * 6)
    }

    @Test
    fun `a clock that jumped backwards does not grant infinite trust`() {
        // lastPush in the future (clock change): now - last is negative, which is
        // < the window, so it relaxes — acceptable, and the next real push or the
        // hourly beat corrects it. Pinned so the behaviour is deliberate.
        assertEquals(Heartbeat.RELAXED_INTERVAL_MS, Heartbeat.intervalFor(NOW + 60_000, NOW))
    }
}
