package com.silencelen.huginn.notify

import kotlinx.coroutines.withTimeoutOrNull

/**
 * The order in which a push's NOTIFICATION and its CLAIM may happen, and the
 * fact that nothing may get between them.
 *
 * ⚠⚠ THE RACE THIS EXISTS FOR. A push arrives for a session that has started
 * waiting. [HuginnMessagingService] posted the notification straight away — the
 * phone may have been woken from Doze with seconds of grace, and an alert that
 * waits on a round trip is an alert that sometimes does not arrive — and then
 * claimed the session in a coroutine it launched afterwards. Between those two
 * moments, any of the FIVE same-process callers of [WatchNotifier.apply] (the
 * watch stream, the heartbeat, the session worker, the widget worker, the push
 * reconcile itself) could observe the very same transition as fresh, because
 * the `notified` baseline still did not name it — and post its own notification
 * under the same per-session id.
 *
 * Whichever landed second won, and the cycle's text is the generic "Waiting for
 * your answer" whenever its own prompt fetch comes back empty: the question and
 * its answer buttons silently replaced by a line that says nothing. Observed
 * on-device. The daemon, meanwhile, reports the session as unclaimed for as long
 * as the gap lasts.
 *
 * THE RULE, and it is the whole of this file: **claim first, post second, both
 * inside one hold of [WatchNotifier]'s observation gate.** The gate is what makes
 * it a rule rather than a hope — claiming first without it only narrows the
 * window, since a cycle already inside `applyNow` has read the baseline and is
 * on its way to posting regardless.
 *
 * Pure, and injected, so the order can be asserted without a Context, a
 * DataStore or a notification manager. The mistakes here are about sequence.
 */
object PushAnnounce {

    /**
     * How long a push will wait for the observation gate before announcing
     * anyway.
     *
     * ⚠ BOUNDED ON PURPOSE. The gate can be held across up to three
     * `client.screen()` round trips, and a notification is the one thing here
     * that must not queue behind the network — the post used to happen before
     * anything suspended at all. Past this, an alert that might duplicate beats
     * an alert that never arrives, which is the trade this file's neighbours
     * already make out loud.
     */
    const val GATE_WAIT_MS: Long = 3_000

    /**
     * @param claim records the transition as already announced, so no later
     *   observation re-announces it. Runs FIRST.
     * @param post draws the notification. Runs second, under the same hold.
     * @param guard the observation gate. Defaults to [WatchNotifier]'s, which is
     *   the one every cycle takes.
     * @return true when both ran inside the gate; false when the gate could not
     *   be had in time and [post] ran on its own.
     */
    suspend fun announce(
        claim: suspend () -> Unit,
        post: suspend () -> Unit,
        waitMs: Long = GATE_WAIT_MS,
        guard: suspend (suspend () -> Unit) -> Unit = { WatchNotifier.guarded(it) },
    ): Boolean {
        val held = withTimeoutOrNull(waitMs) {
            guard {
                claim()
                post()
            }
            true
        }
        if (held == true) return true
        // ⚠ THE ALERT STILL GOES OUT. A duplicate notification replaces itself
        // (same per-session id); a missed one is gone. The claim is not retried
        // here — the reconcile that follows this call writes the baseline anyway.
        post()
        return false
    }
}
