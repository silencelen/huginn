package com.silencelen.huginn.desktop.device

import com.silencelen.huginn.data.HuginnClient

/**
 * Handing this machine's enrolment back — without ever losing the handle that can.
 *
 * ⚠ THE DEVICE ID IS THE ONLY THING THAT CAN REMOVE THIS ROW. Turning the toggle
 * off used to flip a boolean and nothing else: the runner stopped, the daemon
 * heard nothing, and the row sat in everyone's device list reading "not
 * reachable" for its full thirty days before the prune took it. Then somebody
 * turns the toggle back on, the stored id re-enrols the SAME row, and the ghost
 * was never a ghost — which is why nobody chased it.
 *
 * The fix is not "delete the id when the switch goes off". That is the mistake
 * the CLI already made and already fixed (`huginn-device off` used to log the
 * failed DELETE and clear the config anyway): exactly when someone decommissions
 * a machine — host asleep, VPN down, laptop on a train — the DELETE fails, the
 * handle is destroyed, and the row can now only be retired from the daemon by
 * hand. So the ordering here is SERVER FIRST, and the id survives every failure:
 *
 *   toggle off  →  mark the unenrol PENDING, keep the id
 *   each pass   →  DELETE /v1/devices/<id>
 *   it lands    →  clear the id; a later toggle-on enrols fresh
 *   it does not →  keep the id, keep retrying, say so quietly in Settings
 *
 * Everything here is pure so the state machine can be tested without a daemon,
 * a socket or a clock — [DeviceRunner] supplies those and this decides.
 */
object Unenrol {

    /** What the disabled runner should do on this pass. */
    enum class Step {
        /** Off, and nothing owed. The ordinary resting state. */
        IDLE,

        /** There is a row out there and an id that can retire it. Ask. */
        RETIRE,

        /**
         * Pending, but no id left to ask with. Nothing can be done and nothing
         * should be retried — clear the flag rather than spin forever. Reachable
         * when a settings file is edited by hand, or when a toggle-off raced a
         * runner that had not enrolled yet.
         */
        SETTLE,
    }

    fun step(pending: Boolean, deviceId: String): Step = when {
        !pending -> Step.IDLE
        deviceId.isBlank() -> Step.SETTLE
        else -> Step.RETIRE
    }

    /**
     * Whether a toggle-off leaves anything owed to the daemon.
     *
     * False for a machine that never enrolled — there is no row to retire, and a
     * pending flag on one would make the runner keep running to retry a DELETE
     * against an id it does not have.
     */
    fun owesUnenrol(deviceId: String): Boolean = deviceId.isNotBlank()

    /**
     * The verdict on one DELETE attempt.
     *
     * 404 IS SUCCESS. "No such device" means the row this id names is already
     * gone — retired from another client, pruned after thirty days, or landed by
     * an earlier attempt whose reply was lost — and the whole reason to keep the
     * handle is to remove that row. Treating it as a failure would keep the id
     * and the pending flag forever against something that cannot be deleted
     * twice.
     *
     * Everything else keeps the handle, including 401/403. A wrong token is not
     * evidence the row is gone, and this is the one decision where being wrong
     * costs an enrolment nobody can retire.
     *
     * @param error null for a clean 200.
     */
    fun landed(error: Throwable?): Boolean = error == null || statusOf(error) == 404

    /** The HTTP status behind a failure, or null when it never reached one. */
    fun statusOf(error: Throwable): Int? = (error as? HuginnClient.HuginnException)?.code

    /**
     * How long to wait before asking again, doubling and then holding.
     *
     * The first retry is soon because the commonest failure by far is a daemon
     * that was restarting, and the cap is five minutes because the second
     * commonest is a laptop that has been closed since Tuesday — a machine that
     * has been offline for a day should not have spent that day retrying every
     * five seconds, and it should also not have backed off into next week.
     */
    fun backoffMs(attempts: Int): Long {
        val n = attempts.coerceAtLeast(0).coerceAtMost(BACKOFF_STEPS)
        var ms = FIRST_RETRY_MS
        repeat(n) { ms *= 2 }
        return ms.coerceAtMost(MAX_RETRY_MS)
    }

    /**
     * The one line Settings shows while this is unresolved.
     *
     * Quiet on purpose, and NOT an error: nothing is broken on this machine, the
     * toggle really is off, and the only outstanding fact is that a row somewhere
     * else has not been told yet. Saying it loudly would ask the owner to act on
     * something that resolves itself the moment the daemon answers.
     */
    fun note(step: Step, lastError: String?): String? = when (step) {
        Step.IDLE, Step.SETTLE -> null
        Step.RETIRE -> lastError?.takeIf { it.isNotBlank() }
            ?.let { "Off — unenrol pending, will retry ($it)" }
            ?: "Off — unenrol pending, will retry"
    }

    private const val FIRST_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 5 * 60_000L

    /** Enough doublings to reach the cap; more would only overflow the shift. */
    private const val BACKOFF_STEPS = 7
}
