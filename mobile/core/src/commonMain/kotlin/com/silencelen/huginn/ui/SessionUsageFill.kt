package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.StatusHeadroom

/**
 * What the fill line under the Status icon draws: how far through the current
 * 5-hour session window this host is, and which mode that reading is in.
 *
 * [fraction] is 0..1 and already clamped, so a caller can hand it straight to a
 * width without a second opinion about what 103 % means.
 */
data class UsageFill(
    val fraction: Float,
    /** The daemon's mode vocabulary — `ok` | `warn` | `red` | `exhausted`. */
    val modeKey: String,
)

/**
 * The 5-hour session window of the account work is actually running on.
 *
 * NOT the pill's number, and that is the whole point. The pill reports the WORST
 * window anywhere on the host — usually the Fable week, which moves over days —
 * and the thing a person wants to know before starting something is how much of
 * *this afternoon's* session they have left. On a host with two saved logins
 * those are different accounts as well as different windows, so the reading is
 * taken from the account marked [com.silencelen.huginn.data.HeadroomAccount.live]
 * rather than from whichever row happens to be worst.
 *
 * NULL IS THE IMPORTANT ANSWER, the same as the pill's: it means either an older
 * daemon with no headroom subsystem or one that has not read a window yet, and a
 * line drawn empty in either case is a claim that somebody looked and found
 * plenty. No reading, no line.
 */
object SessionUsageFill {

    /**
     * @param headroom the full `/v1/headroom` answer, which is the only place the
     *   per-account session window lives.
     * @param status the summary that rides `/v1/status`, used ONLY as a fallback
     *   when the session window is missing — a daemon that serves a worst-window
     *   summary but no accounts map (or an account whose plan read failed for the
     *   5-hour row alone) still has something true to say, and saying the worst
     *   window instead is a smaller error than saying nothing. The fallback keeps
     *   the daemon's own [StatusHeadroom.mode] rather than re-deriving one.
     */
    fun of(headroom: Headroom?, status: StatusHeadroom?): UsageFill? {
        val live = headroom?.accounts?.values?.firstOrNull { it.live }
        val session = live?.windows?.session
        if (session != null) {
            return UsageFill(
                fraction = fractionOf(session.percent),
                // From the WINDOW's own reading, never from `headroom.mode`: that
                // is the worst window's mode, and painting a session at 31 % red
                // because the Fable week is at 92 % is the exact confusion this
                // line exists to clear up.
                modeKey = HeadroomRules.modeOf(session.percent, session.severity, headroom.settings),
            )
        }
        val fallback = status?.worstPercent ?: return null
        return UsageFill(fraction = fractionOf(fallback), modeKey = status.mode)
    }

    /**
     * A percentage as a 0..1 width.
     *
     * Clamped at BOTH ends. Over 100 is a real reading — Claude's own endpoint
     * reports a spent window as 103.4 — and an unclamped fraction there draws a
     * line wider than its track, which on the phone's bottom bar overruns into
     * the item beside it.
     */
    fun fractionOf(percent: Double): Float = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
}
