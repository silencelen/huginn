package com.silencelen.huginn

import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.widget.askMaySend
import com.silencelen.huginn.widget.askSheetLocks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ask sheet and the app lock: the rule [com.silencelen.huginn.widget.AskActivity]
 * applies every time it comes to the foreground, and again at Send.
 *
 * The sheet decided once, at creation, and a sheet left open behind Home is
 * brought forward without being created again — so one unlocked a while ago came
 * back unlocked while the app's own door had locked. These are the decisions it
 * makes now, without a Context. AskLockPlaceTest holds the activity to them.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class AskLockTest {

    private val now = 1_800_000_000_000L

    private fun locks(enabled: Boolean = true, lockedNow: Boolean = false, awayAt: Long = now - 5_000) =
        askSheetLocks(lockEnabled = enabled, lockedNow = lockedNow, awayAt = awayAt, now = now)

    @Test
    fun `a sheet brought back past the grace is locked, though it was open when it left`() {
        // The defect, as the lifecycle saw it: unlocked, Home, a phone handed
        // over, the bar tapped minutes later — and the same sheet, open.
        assertTrue(locks(awayAt = now - AppLock.GRACE_MS))
        assertTrue(locks(awayAt = now - 10 * 60_000L))
    }

    @Test
    fun `inside the grace it comes back as it was left`() {
        // The front door's answer too: hopping to another app and straight back
        // is the commonest gesture, and a lock that punishes it gets turned off.
        assertFalse(locks(awayAt = now - AppLock.GRACE_MS + 1))
        assertFalse(locks(awayAt = now))
    }

    @Test
    fun `a process with no memory of an unlock is locked`() {
        assertTrue(locks(awayAt = 0L))
    }

    @Test
    fun `a clock reading earlier than the stamp is locked`() {
        assertTrue(locks(awayAt = now + 1))
    }

    @Test
    fun `a locked app locks the sheet whatever the clock says`() {
        // "Lock now", or a return the front door already locked.
        assertTrue(locks(lockedNow = true, awayAt = now - 1_000))
        assertTrue(locks(enabled = false, lockedNow = true))
    }

    @Test
    fun `with the lock off the clock never locks the sheet`() {
        assertFalse(locks(enabled = false, awayAt = 0L))
        assertFalse(locks(enabled = false, awayAt = now - 3_600_000L))
    }

    @Test
    fun `send goes only while neither the sheet nor the app is locked`() {
        assertTrue(askMaySend(sheetLocked = false, lockedNow = false))
        assertFalse("the lock came back on this sheet", askMaySend(sheetLocked = true, lockedNow = false))
        assertFalse("the app was locked meanwhile", askMaySend(sheetLocked = false, lockedNow = true))
        assertFalse(askMaySend(sheetLocked = true, lockedNow = true))
    }
}
