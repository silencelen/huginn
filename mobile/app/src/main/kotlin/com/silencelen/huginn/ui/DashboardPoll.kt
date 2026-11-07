package com.silencelen.huginn.ui

/**
 * How long the project page waits between dashboard reads.
 *
 * Five seconds while answers land: a cluster changes at the speed somebody
 * presses something, and the rollup is the daemon's. ⚠ BUT NOT FIVE SECONDS
 * AFTER A FAILURE. The poll swallows a failed read — rightly, a toast every tick
 * over a flaky link is noise — and used to wait the full tick before asking
 * again. The first read of a cold start is the one most likely to fail (the
 * route is being re-resolved, the daemon's queue is full of the start's own
 * fan-out, the handshake timed out), so the page sat on "Nothing to show yet."
 * for a whole tick after an answer had become possible. A failed read is
 * retried after one second, then two, then four, and only then at the tick.
 */
object DashboardPoll {
    const val PERIOD_MS: Long = 5_000

    /** The wait after a read, given how many reads in a row have failed (0 = the last one landed). */
    fun delayAfter(failures: Int): Long {
        if (failures <= 0) return PERIOD_MS
        // 1 s, 2 s, 4 s; the shift is capped so a long outage cannot overflow it.
        return minOf(PERIOD_MS, 1_000L shl minOf(failures - 1, 3))
    }
}
