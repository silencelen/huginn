package com.silencelen.huginn

import com.silencelen.huginn.notify.PushAnnounce
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ THE ORDER A PUSH DOES ITS TWO THINGS IN, and the fact that nothing may get
 * between them.
 *
 * The defect: [com.silencelen.huginn.notify.HuginnMessagingService] posted the
 * notification synchronously and claimed the session in a coroutine it launched
 * afterwards. In the gap, any of the five same-process callers of
 * `WatchNotifier.apply` could see the identical transition as fresh — the
 * `notified` baseline still did not name it — and post its OWN notification
 * under the same per-session id. Whichever landed second won, and a cycle whose
 * prompt fetch comes back empty posts the generic "Waiting for your answer":
 * the question and its answer buttons, silently replaced. Observed on-device,
 * and the daemon meanwhile reported the session unclaimed.
 *
 * Pure: the rule is about sequence, so it is asserted with lambdas rather than
 * with a Context, a DataStore and a notification manager. This source set has no
 * Robolectric and does not need it here.
 */
class PushAnnounceTest {

    @Test
    fun `the claim happens before the notification`() = runTest {
        val order = mutableListOf<String>()
        PushAnnounce.announce(
            claim = { order += "claim" },
            post = { order += "post" },
            guard = { it() },
        )
        assertEquals(listOf("claim", "post"), order)
    }

    /**
     * ⚠ AND BOTH INSIDE ONE HOLD. Claiming first without the gate only narrows
     * the window: a cycle already inside `applyNow` has read the baseline and is
     * on its way to posting regardless of what we do afterwards. The gate is what
     * makes this a rule.
     */
    @Test
    fun `nothing gets between them - both run inside one hold of the gate`() = runTest {
        val order = mutableListOf<String>()
        PushAnnounce.announce(
            claim = { order += "claim" },
            post = { order += "post" },
            guard = { body -> order += "gate in"; body(); order += "gate out" },
        )
        assertEquals(listOf("gate in", "claim", "post", "gate out"), order)
    }

    /**
     * The gate is the REAL one's shape: a Mutex a concurrent observation cycle
     * may already be holding across network calls. A push must wait for it —
     * that is the whole point — and must therefore be seen to.
     */
    @Test
    fun `a push waits for a cycle that is already observing`() = runTest {
        val gate = Mutex()
        val order = mutableListOf<String>()
        val cycleInside = CompletableDeferred<Unit>()
        val letCycleGo = CompletableDeferred<Unit>()

        val cycle = launch {
            gate.withLock {
                order += "cycle in"
                cycleInside.complete(Unit)
                letCycleGo.await()
                order += "cycle out"
            }
        }
        cycleInside.await()

        val push = launch {
            PushAnnounce.announce(
                claim = { order += "claim" },
                post = { order += "post" },
                guard = { body -> gate.withLock { body() } },
            )
        }
        // Nothing of the push has happened while the cycle holds the gate.
        assertEquals(listOf("cycle in"), order)

        letCycleGo.complete(Unit)
        cycle.join()
        push.join()
        assertEquals(listOf("cycle in", "cycle out", "claim", "post"), order)
    }

    /**
     * ⚠ THE ALERT STILL GOES OUT. The gate can be held across up to three
     * `client.screen()` round trips, and a notification is the one thing here
     * that must not queue behind the network — the post used to happen before
     * anything suspended at all. Past the budget, an alert that might duplicate
     * (same per-session id, so it replaces itself) beats an alert that never
     * arrives.
     */
    @Test
    fun `a gate that will not come free still lets the notification out`() = runTest {
        val order = mutableListOf<String>()
        val held = PushAnnounce.announce(
            claim = { order += "claim" },
            post = { order += "post" },
            waitMs = 50,
            // Never returns — a cycle wedged on a dead socket.
            guard = { delay(Long.MAX_VALUE) },
        )
        assertFalse("it says it could not hold the gate", held)
        assertEquals(listOf("post"), order)
    }

    @Test
    fun `holding the gate is reported as holding it`() = runTest {
        assertTrue(PushAnnounce.announce(claim = {}, post = {}, guard = { it() }))
    }

    /**
     * A push with nothing to claim — a finished chat, a proposal — still posts.
     * The rule is an order, not a precondition.
     */
    @Test
    fun `a push with no claim to make still announces`() = runTest {
        var posted = false
        PushAnnounce.announce(claim = {}, post = { posted = true }, guard = { it() })
        assertTrue(posted)
    }
}
