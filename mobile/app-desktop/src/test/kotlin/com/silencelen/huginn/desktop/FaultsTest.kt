package com.silencelen.huginn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * THE STATUS LINE MUST BE TRUE RIGHT NOW.
 *
 * The owner's report was an "unauthorized" bar at the foot of a window that was
 * visibly streaming a session. The cause was a single nullable string that every
 * failure wrote and only a click ever cleared — so the first 401 of a run (and
 * after the 0.3.0 token wipe there were a great many) sat there for the rest of
 * the process, on top of every true thing the bar could otherwise have said.
 *
 * Every test here is that class of bug: a condition that has ENDED still being
 * reported. None of them would show up in a screenshot taken at the wrong moment,
 * which is exactly why they are asserted rather than looked at.
 *
 * NOTE the kotlin.test argument order: (expected, actual, message).
 */
class FaultsTest {

    /** A hand-wound clock: expiry is a decision about time, so time is an input. */
    private class Clock(var ms: Long = 1_000_000) : () -> Long {
        override fun invoke(): Long = ms
    }

    @Test
    fun `a success clears the failure that came before it`() {
        // THE REGRESSION, in three lines. The poll that came back is the proof the
        // condition is over, and it was being ignored.
        val faults = Faults()
        faults.fail(Faults.CHATS, "unauthorized")
        assertEquals("unauthorized", faults.current.value)

        faults.ok(Faults.CHATS)
        assertNull(faults.current.value, "a poll that returned must retire the fault it retried")
    }

    @Test
    fun `one source succeeding does not hide another still failing`() {
        // With a single slot these two fought every 5 seconds and the bar strobed;
        // worse, whichever polled last decided what the reader saw.
        val faults = Faults()
        faults.fail(Faults.SESSIONS, "no route to host")
        faults.ok(Faults.CHATS)
        assertEquals("no route to host", faults.current.value)
    }

    @Test
    fun `the newest failure is the one shown`() {
        val clock = Clock()
        val faults = Faults(now = clock)
        faults.fail(Faults.CHATS, "unauthorized")
        clock.ms += 10
        faults.fail(Faults.SESSIONS, "timeout")
        assertEquals("timeout", faults.current.value)

        // And when the newer one ends, the older one is still true and comes back
        // rather than the bar going quiet on a condition nobody fixed.
        faults.ok(Faults.SESSIONS)
        assertEquals("unauthorized", faults.current.value)
    }

    @Test
    fun `a fault nothing repeats expires on its own`() {
        // The case with no poll behind it: a rename that 400s is reported once and
        // never again, so a success can never arrive to clear it.
        val clock = Clock()
        val faults = Faults(now = clock)
        faults.fail(Faults.ACTION, "name already in use")

        clock.ms += Faults.TTL_MS - 1
        faults.sweep()
        assertEquals("name already in use", faults.current.value, "not stale yet")

        clock.ms += 2
        faults.sweep()
        assertNull(faults.current.value, "a fault older than the TTL must not still be on screen")
    }

    @Test
    fun `a fault the poll keeps re-reporting does not expire out from under the reader`() {
        val clock = Clock()
        val faults = Faults(now = clock)
        repeat(20) {
            faults.fail(Faults.CHATS, "unauthorized")
            clock.ms += AppStore.POLL_MS
            faults.sweep()
        }
        assertEquals("unauthorized", faults.current.value, "still failing, so still true")
    }

    @Test
    fun `dismissing hides that message and only that message`() {
        val clock = Clock()
        val faults = Faults(now = clock)
        faults.fail(Faults.CHATS, "unauthorized")
        faults.dismiss()
        assertNull(faults.current.value)

        // The same words on the next poll stay dismissed — an acknowledgement that
        // is undone 5 seconds later is not an acknowledgement.
        clock.ms += AppStore.POLL_MS
        faults.fail(Faults.CHATS, "unauthorized")
        assertNull(faults.current.value)

        // A DIFFERENT failure is new information and gets through.
        clock.ms += AppStore.POLL_MS
        faults.fail(Faults.SESSIONS, "connection refused")
        assertEquals("connection refused", faults.current.value)
    }

    @Test
    fun `a success resets the acknowledgement, so a genuine recurrence shows again`() {
        val clock = Clock()
        val faults = Faults(now = clock)
        faults.fail(Faults.CHATS, "unauthorized")
        faults.dismiss()
        faults.ok(Faults.CHATS)

        // The token was fixed, then broke again an hour later. That is news.
        clock.ms += 3_600_000
        faults.fail(Faults.CHATS, "unauthorized")
        assertEquals("unauthorized", faults.current.value)
    }

    @Test
    fun `a blank message still says something`() {
        // Some throwables carry no message at all; an empty status line that is
        // nonetheless tinted red is the worst of both.
        val faults = Faults()
        faults.fail(Faults.CHATS, "   ")
        assertEquals("network error", faults.current.value)
    }

    @Test
    fun `sweeping with nothing to sweep does not disturb what is showing`() {
        val faults = Faults()
        faults.fail(Faults.CHATS, "unauthorized")
        faults.sweep()
        assertEquals("unauthorized", faults.current.value)
    }
}
