package com.silencelen.huginn.desktop.device

import com.silencelen.huginn.data.HuginnClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Giving an enrolment back without losing the handle that can.
 *
 * Every case here is one where the naive version — flip the boolean, or delete
 * the id and hope — leaves a row enrolled at the daemon that nothing on this
 * machine can ever retire.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnenrolTest {

    // -------------------------------------------------------------- the step

    @Test
    fun `a toggle-off with nothing owed simply idles`() {
        assertEquals(Unenrol.Step.IDLE, Unenrol.step(pending = false, deviceId = "dev-1"), "nothing owed")
        assertEquals(Unenrol.Step.IDLE, Unenrol.step(pending = false, deviceId = ""), "nothing owed, nothing held")
    }

    @Test
    fun `a pending unenrol with a handle asks the daemon`() {
        assertEquals(Unenrol.Step.RETIRE, Unenrol.step(pending = true, deviceId = "dev-1"))
    }

    // Otherwise the disabled loop spins forever on a DELETE it cannot address.
    @Test
    fun `a pending unenrol with no handle settles instead of spinning`() {
        assertEquals(Unenrol.Step.SETTLE, Unenrol.step(pending = true, deviceId = ""))
        assertEquals(Unenrol.Step.SETTLE, Unenrol.step(pending = true, deviceId = "   "))
    }

    @Test
    fun `a machine that never enrolled owes nothing when it is switched off`() {
        assertFalse(Unenrol.owesUnenrol(""), "no row was ever created")
        assertFalse(Unenrol.owesUnenrol("  "))
        assertTrue(Unenrol.owesUnenrol("dev-1"))
    }

    // ------------------------------------------------------------ the verdict

    @Test
    fun `a clean delete has landed`() {
        assertTrue(Unenrol.landed(null))
    }

    /**
     * ⚠ 404 IS SUCCESS. "No such device" means the row this id names is already
     * gone — retired from the phone, pruned after thirty days, or landed by an
     * earlier attempt whose reply was lost. Reading it as a failure would keep
     * the id and the pending flag forever against something that cannot be
     * deleted twice, and the toggle would never come back to rest.
     */
    @Test
    fun `a row that is already gone counts as landed`() {
        assertTrue(Unenrol.landed(HuginnClient.HuginnException(404, "no such device")))
    }

    /**
     * ⚠ THE ONE DECISION WHERE BEING WRONG COSTS AN ENROLMENT NOBODY CAN RETIRE.
     * The CLI made this mistake and had to be fixed: it logged the failed DELETE
     * and cleared the config anyway, so exactly when someone decommissioned a
     * machine — host asleep, VPN down, wrong url — the row stayed enrolled and
     * the only handle that could remove it was destroyed in the same breath.
     */
    @Test
    fun `every other failure keeps the handle`() {
        assertFalse(Unenrol.landed(IOException("connection refused")), "offline is not gone")
        assertFalse(Unenrol.landed(HuginnClient.HuginnException(401, "unauthorised")), "a bad token is not gone")
        assertFalse(Unenrol.landed(HuginnClient.HuginnException(403, "forbidden")))
        assertFalse(Unenrol.landed(HuginnClient.HuginnException(500, "boom")))
        assertFalse(Unenrol.landed(HuginnClient.HuginnException(502, "bad gateway")))
    }

    @Test
    fun `a failure that never reached HTTP has no status to read`() {
        assertNull(Unenrol.statusOf(IOException("connection refused")))
        assertEquals(404, Unenrol.statusOf(HuginnClient.HuginnException(404, "no such device")))
    }

    // ------------------------------------------------------------ the backoff

    @Test
    fun `the first retry is soon, because the commonest failure is a restart`() {
        assertEquals(5_000L, Unenrol.backoffMs(0))
        assertEquals(10_000L, Unenrol.backoffMs(1))
        assertEquals(20_000L, Unenrol.backoffMs(2))
    }

    /**
     * A laptop closed since Tuesday must not have spent Tuesday retrying every
     * five seconds — and must not have backed off into next week either.
     */
    @Test
    fun `the backoff holds at five minutes rather than growing forever`() {
        assertEquals(5 * 60_000L, Unenrol.backoffMs(20))
        assertEquals(5 * 60_000L, Unenrol.backoffMs(1_000))
        assertEquals(5 * 60_000L, Unenrol.backoffMs(Int.MAX_VALUE), "no overflow, no negative wait")
    }

    @Test
    fun `the backoff never asks a loop to wait a negative time`() {
        assertEquals(5_000L, Unenrol.backoffMs(-1))
        assertEquals(5_000L, Unenrol.backoffMs(Int.MIN_VALUE))
    }

    @Test
    fun `the backoff only ever grows`() {
        var last = 0L
        for (n in 0..12) {
            val ms = Unenrol.backoffMs(n)
            assertTrue(ms >= last, "attempt $n waited ${ms}ms after ${last}ms")
            last = ms
        }
    }

    // ----------------------------------------------- the wait around the backoff

    @Test
    fun `a long backoff still answers the toggle coming back on`() = runTest {
        // ⚠ THE FIVE MINUTES OF NOTHING. The backoff runs INSIDE the loop that
        // also watches the toggle, so a flat `delay(300_000)` meant turning the
        // device back on did nothing at all for up to five minutes — Settings
        // still reading "Off", no way to tell whether the click had registered,
        // and nothing to do but wait it out or restart the app.
        var enabled = false
        val woke = async {
            DeviceRunner.waitOrWake(Unenrol.backoffMs(20), sliceMs = 1_000) { enabled }
        }
        advanceTimeBy(30_000)
        enabled = true
        advanceTimeBy(1_500)
        assertTrue(woke.await(), "the wait was still asleep with the toggle already back on")
        assertTrue(
            currentTime < 60_000,
            "it woke after ${currentTime}ms; a slice is a second, so anything near the full backoff is the bug",
        )
    }

    @Test
    fun `a wait nobody interrupts still waits the whole time`() = runTest {
        // The other half: slicing must not turn a five-minute backoff into a busy
        // loop that retries the DELETE every second.
        val woke = async { DeviceRunner.waitOrWake(20_000, sliceMs = 1_000) { false } }
        advanceUntilIdle()
        assertFalse(woke.await())
        assertEquals(20_000L, currentTime)
    }

    @Test
    fun `a toggle that came back during the last request is not slept on at all`() = runTest {
        val woke = async { DeviceRunner.waitOrWake(300_000, sliceMs = 1_000) { true } }
        advanceUntilIdle()
        assertTrue(woke.await())
        assertEquals(0L, currentTime, "it went to sleep on an answer it already had")
    }

    // --------------------------------------------------------------- the line

    @Test
    fun `a settled toggle says nothing extra`() {
        assertNull(Unenrol.note(Unenrol.Step.IDLE, null), "Off is the whole story")
        assertNull(Unenrol.note(Unenrol.Step.SETTLE, "whatever"))
    }

    @Test
    fun `a pending unenrol says so quietly and promises to retry`() {
        val line = assertNotNull(Unenrol.note(Unenrol.Step.RETIRE, null))
        assertTrue(line.contains("unenrol pending"), line)
        assertTrue(line.contains("will retry"), line)
        // Not an error, not a warning: nothing on this machine is broken, and the
        // toggle really is off. Only a row elsewhere has not been told yet.
        assertTrue(line.startsWith("Off"), line)
    }

    @Test
    fun `the reason rides along when there is one`() {
        val line = assertNotNull(Unenrol.note(Unenrol.Step.RETIRE, "connection refused"))
        assertTrue(line.contains("connection refused"), line)
        assertTrue(line.contains("will retry"), line)
    }

    @Test
    fun `a blank reason is left out rather than shown as empty brackets`() {
        assertEquals(Unenrol.note(Unenrol.Step.RETIRE, null), Unenrol.note(Unenrol.Step.RETIRE, "   "))
    }
}
