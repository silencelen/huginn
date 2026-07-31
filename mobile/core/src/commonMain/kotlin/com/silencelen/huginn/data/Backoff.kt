package com.silencelen.huginn.data

/**
 * How long a failing poll waits before trying again.
 *
 * Two ladders rather than one, because the two loops fail for different reasons
 * and the existing clients already answer them differently — this states both
 * answers once instead of letting a third client invent a third.
 *
 * A screen poll fails when the network blinks and should come back quickly, so it
 * starts at its floor. A transcript poll can fail FOREVER and still be correct: a
 * session that has never prompted Claude, or whose transcript file is gone,
 * answers 409 to every request for as long as the view stays open — at the flat
 * 2.5s tick that is ~24 daemon errors a minute for nothing, which is the shape of
 * a client hammering the host it depends on. So its first failure already costs a
 * doubling.
 *
 * [failures] counts CONSECUTIVE failures including the one being backed off from;
 * any success resets it to zero.
 */
object Backoff {

    /** Screen long poll: quick recovery, low ceiling — a parked poll costs nothing. */
    const val SCREEN_MIN_MS: Long = 1_000
    const val SCREEN_MAX_MS: Long = 15_000

    /** Transcript tail: the interval when healthy, and the ceiling when not. */
    const val TRANSCRIPT_MS: Long = 2_500
    const val TRANSCRIPT_MAX_MS: Long = 60_000

    /**
     * The shift is clamped as well as the result. `base shl failures` on a `Long`
     * turns to nonsense past 63, and a view left open overnight against a session
     * that no longer exists gets there.
     */
    private const val MAX_SHIFT: Int = 6

    /** 1s, 2s, 4s, 8s, then the 15s ceiling. */
    fun screen(failures: Int): Long =
        if (failures <= 1) SCREEN_MIN_MS else shift(SCREEN_MIN_MS, SCREEN_MAX_MS, failures - 1)

    /** 2.5s while healthy, then 5s, 10s, 20s, 40s, then the 60s ceiling. */
    fun transcript(failures: Int): Long =
        if (failures <= 0) TRANSCRIPT_MS else shift(TRANSCRIPT_MS, TRANSCRIPT_MAX_MS, failures)

    private fun shift(base: Long, max: Long, by: Int): Long {
        val step = base shl (if (by > MAX_SHIFT) MAX_SHIFT else by)
        return if (step > max) max else step
    }
}
