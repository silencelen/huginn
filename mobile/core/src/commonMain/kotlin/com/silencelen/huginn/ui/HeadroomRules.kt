package com.silencelen.huginn.ui

import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.HeadroomWorst
import com.silencelen.huginn.data.SessionHeadroom
import com.silencelen.huginn.data.StatusHeadroom
import kotlin.math.roundToInt

/**
 * Everything the headroom surfaces SAY, decided once for both clients.
 *
 * The daemon owns every headroom *decision* — one subsystem, one arbiter — and
 * this object owns none of them. What lives here is the other half: turning the
 * daemon's answer into the handful of words a pill, a session mark and a limit
 * notice show. That is exactly the judgment `PlanFormat` exists for, and for the
 * same reason: the phone and the desktop already disagreed once about the same
 * plan reading, and a second hand-written copy of these strings is a second
 * place for them to drift.
 *
 * Two rules run through all of it:
 *
 * 1. **Never render a 1970 reset.** Every `resetsAt` on the wire is nullable and
 *    every `at` defaults to 0, so "absent" reaches here as an epoch of zero. A
 *    countdown from that is "resets in 20800d", which is worse than saying
 *    nothing — the reader cannot tell it from a real reading. An epoch at or
 *    below zero is treated as no timestamp at all.
 * 2. **A clock time is the daemon's, never ours.** `:core` is commonMain: it has
 *    no timezone database, and the one time a shell formatted an instant into a
 *    wall clock by hand it printed UTC as if it were local. So "10:10pm" is only
 *    ever shown when the daemon lifted it verbatim out of Claude Code's own
 *    error text; everything else we derive is a COUNTDOWN, which is arithmetic
 *    on two instants and immune to the whole question.
 */
object HeadroomRules {

    /** Modes, worst first. The daemon's vocabulary; nothing here invents one. */
    const val OK = "ok"
    const val WARN = "warn"
    const val RED = "red"
    const val EXHAUSTED = "exhausted"

    /**
     * The mode a worst-window reading implies.
     *
     * Mirrors the daemon's `classify` so a client that has `/v1/headroom.worst`
     * but not its `mode` — a status pill built from a stale status poll, say —
     * reaches the same answer rather than a second one. When [settings] is null
     * the daemon's own defaults apply, which is what [HeadroomSettings] holds.
     */
    fun modeOf(worst: HeadroomWorst?, settings: HeadroomSettings? = null): String {
        if (worst == null) return OK
        val s = settings ?: HeadroomSettings()
        val pct = worst.percent
        return when {
            pct >= 100.0 -> EXHAUSTED
            pct >= s.ladderPct -> RED
            pct >= s.headsUpPct -> WARN
            else -> OK
        }
    }

    /**
     * The same, from a severity word the daemon already attached.
     *
     * `exceeded` is Claude's own word for a window that is spent, and it can
     * arrive while the percentage still reads 99.4 — rounding, on a number the
     * server computed. The word wins when there is one.
     */
    fun modeOf(percent: Double, severity: String?, settings: HeadroomSettings? = null): String {
        if (severity == "exceeded") return EXHAUSTED
        return modeOf(HeadroomWorst(percent = percent), settings)
    }

    /**
     * The severity key a meter should colour by.
     *
     * Returns the vocabulary `PlanLimit.severity` already uses, so the existing
     * `meterColor` in `:ui` answers for headroom too. Inventing a second colour
     * scale for the same idea is how one screen ends up calling 92 % red while
     * the bar right under it calls it amber.
     */
    fun severityColorKey(mode: String?): String = when (mode) {
        EXHAUSTED -> "critical"
        RED -> "high"
        WARN -> "warning"
        else -> "normal"
    }

    /**
     * The pill: `Fable 92% · resets 3h`.
     *
     * Null when there is nothing to report — no headroom block at all (an older
     * daemon) or no reading in it. The pill is HIDDEN in that case; a pill
     * drawn at 0 % would claim the daemon had looked and found plenty.
     *
     * The reset clause is dropped rather than faked when the timestamp is
     * missing or is the epoch, so the pill degrades to `Fable 92%`.
     */
    fun pillText(status: StatusHeadroom?, nowMs: Long): String? {
        val pct = status?.worstPercent ?: return null
        val head = windowWords(status.worstLabel) + " " + percentWords(pct)
        val until = coarseUntil(status.nextResetAt, nowMs) ?: return head
        return "$head · resets $until"
    }

    /**
     * The state mark beside a session's model chip, or null when there is
     * nothing worth saying.
     *
     * Three things can be true of a session and each needs different words:
     * it was moved to another model, it is sitting on a limit, or it is fine.
     * A stall outranks a ladder move — a session that is not running at all is
     * the more urgent fact, and it is usually laddered as well.
     *
     * @param status the pill's reading, used only to name the window and the
     *   percentage a laddered session was moved for. Optional: without it the
     *   mark is still correct, just shorter (`on opus`).
     */
    fun sessionMark(
        session: SessionHeadroom?,
        status: StatusHeadroom? = null,
        nowMs: Long = 0L,
        resetClock: String? = null,
    ): String? {
        val h = session ?: return null
        if (h.stalled) return resumeWords(h.stalled, h.autoResume, resetClock, status?.nextResetAt, nowMs)
        val to = h.ladder?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pct = status?.worstPercent
        if (pct == null) return "on $to"
        return "on $to · ${windowWords(status.worstLabel)} ${percentWords(pct)}"
    }

    /**
     * What a session stalled on a usage limit is waiting for.
     *
     * Null when it is not stalled. The three answers are deliberately different
     * sentences rather than one with a blank in it: "stopped" and "waiting" are
     * opposite things to do about it, and a reader who cannot tell them apart
     * will leave a session idle all night believing it will pick itself up.
     *
     * @param resetClock the clock time Claude Code itself printed, passed
     *   through verbatim by the daemon. Never formatted here — see this object's
     *   header.
     */
    fun resumeWords(
        stalled: Boolean,
        autoResume: Boolean,
        resetClock: String? = null,
        resetsAt: String? = null,
        nowMs: Long = 0L,
    ): String? {
        if (!stalled) return null
        if (!autoResume) return "stopped at the usage limit"
        val clock = resetClock?.trim()?.takeIf { it.isNotEmpty() }
        if (clock != null) return "waiting for the limit to reset ($clock)"
        val until = shortUntil(resetsAt, nowMs)
        if (until != null) return "waiting for the limit to reset (in $until)"
        return "resumes on reset"
    }

    // ------------------------------------------------------------- pieces

    /**
     * `Current week (Fable)` → `Fable`.
     *
     * The daemon's labels are written for a full-width bar; a pill has room for
     * one word. The parenthesised scope IS the distinguishing part — the whole
     * point of the pill is which limit — so it is what survives.
     */
    fun windowWords(label: String?): String {
        val raw = label?.trim().orEmpty()
        if (raw.isEmpty()) return "Usage"
        val open = raw.indexOf('(')
        val close = raw.lastIndexOf(')')
        if (open in 0 until close) {
            val inner = raw.substring(open + 1, close).trim()
            if (inner.isNotEmpty()) {
                // "all models" is the absence of a scope, not a scope.
                return if (inner.equals("all models", ignoreCase = true)) "Weekly" else inner
            }
        }
        return when {
            raw.startsWith("Current session", ignoreCase = true) -> "Session"
            raw.startsWith("Current week", ignoreCase = true) -> "Weekly"
            else -> raw
        }
    }

    /** The same, from a window KEY when no label came down the wire. */
    fun windowKeyWords(window: String?): String = when (window) {
        "session" -> "Session"
        "weekly_all" -> "Weekly"
        "weekly_fable" -> "Fable"
        else -> "Usage"
    }

    /** `92.4` → `92%`. Rounded, because a pill is not a gauge. */
    fun percentWords(percent: Double): String = "${percent.roundToInt()}%"

    /**
     * One unit, for a pill: `3h`, `12m`, `2d`, `now`.
     *
     * Null when the timestamp is absent, unparseable, or at/below the epoch —
     * rule 1 in this object's header.
     */
    fun coarseUntil(iso: String?, nowMs: Long): String? {
        val secs = secondsUntil(iso, nowMs) ?: return null
        return when {
            secs <= 0 -> "now"
            secs >= 86_400 -> "${secs / 86_400}d"
            secs >= 3_600 -> "${secs / 3_600}h"
            secs >= 60 -> "${secs / 60}m"
            else -> "now"
        }
    }

    /** Two units, for a line with room: `3h 12m`, `12m`, `2d 4h`, `now`. */
    fun shortUntil(iso: String?, nowMs: Long): String? {
        val secs = secondsUntil(iso, nowMs) ?: return null
        if (secs <= 0) return "now"
        val d = secs / 86_400
        val h = (secs % 86_400) / 3_600
        val m = (secs % 3_600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "now"
        }
    }

    /**
     * Seconds from [nowMs] to the instant [iso] names, or null when there is no
     * instant to count to.
     *
     * The epoch guard is here rather than at each caller on purpose: it is the
     * single place a missing timestamp turns into a missing countdown, and every
     * way of getting it wrong renders 1970.
     */
    private fun secondsUntil(iso: String?, nowMs: Long): Long? {
        val at = PlanFormat.parseIsoToEpochMs(iso) ?: return null
        if (at <= 0L) return null
        return (at - nowMs) / 1000L
    }
}
