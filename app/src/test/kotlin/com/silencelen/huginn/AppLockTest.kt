package com.silencelen.huginn

import com.silencelen.huginn.notify.AppLock
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
