package com.silencelen.huginn.ui

import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.HeadroomStall
import com.silencelen.huginn.data.HeadroomWorst
import com.silencelen.huginn.data.SessionHeadroom
import com.silencelen.huginn.data.StatusHeadroom
import com.silencelen.huginn.data.UndoResult
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
     * @param stall the session's own record from `/v1/headroom`, when the caller
     *   has it. It carries the two things the list row cannot: the clock time
     *   Claude Code printed, and the daemon's reason for not resuming. Without
     *   it the mark falls back to the worst window's reset, which is the right
     *   window most of the time and the wrong one when a session stalled on the
     *   5-hour cap while the week is the fuller one.
     */
    fun sessionMark(
        session: SessionHeadroom?,
        status: StatusHeadroom? = null,
        nowMs: Long = 0L,
        resetClock: String? = null,
        stall: HeadroomStall? = null,
    ): String? {
        val h = session ?: return null
        if (h.stalled) {
            stallWords(stall, h.autoResume, status?.nextResetAt, nowMs)?.let { return it }
            return resumeWords(h.stalled, h.autoResume, resetClock, status?.nextResetAt, nowMs)
        }
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

    /**
     * The same sentence, read off the daemon's own stall record.
     *
     * Preferred over [resumeWords] wherever the record is in hand, because the
     * record is where the two facts actually live:
     *
     * - [HeadroomStall.text] is Claude Code's own limit sentence, and the clock
     *   time in it ("resets 10:10pm") is the ONLY wall clock anywhere in this
     *   object — lifted verbatim, never formatted here (header rule 2).
     * - [HeadroomStall.why] is the only place a REFUSAL is written down
     *   ("auto-resume is off for this session", "gave up after 3 attempts").
     *   A reader told "stopped at the usage limit" with no reason has to go and
     *   find out which of those it was.
     *
     * Null when there is no stall to describe, or when it has already been
     * resumed — a session that was picked back up is not waiting for anything.
     */
    fun stallWords(
        stall: HeadroomStall?,
        autoResume: Boolean,
        resetsAt: String? = null,
        nowMs: Long = 0L,
    ): String? {
        val st = stall ?: return null
        if ((st.at ?: 0L) <= 0L) return null
        if ((st.resumedAt ?: 0L) > 0L) return null
        if (!autoResume) {
            val why = st.why?.trim()?.takeIf { it.isNotEmpty() }
            return why ?: "stopped at the usage limit"
        }
        val clock = resetClockOf(st.text)
        if (clock != null) return "waiting for the limit to reset ($clock)"
        // The stall's OWN reset, in milliseconds, before the worst window's ISO.
        shortUntilMs(st.resetsAt, nowMs)?.let { return "waiting for the limit to reset (in $it)" }
        shortUntil(resetsAt, nowMs)?.let { return "waiting for the limit to reset (in $it)" }
        return "resumes on reset"
    }

    /**
     * The clock time out of a Claude Code usage-limit sentence, or null.
     *
     * `You've hit your session limit · resets 10:10pm (America/Los_Angeles)` →
     * `10:10pm`. Everything after the clause — the parenthesised timezone, a
     * following sentence — is dropped: it does not fit on a one-line mark and
     * the zone is already implied by it being the host's clock.
     *
     * ⚠ THE ONE IMPLEMENTATION. `:ui`'s `limitResetClock` delegates here; a
     * second copy is a second set of words for the same reading, which is the
     * failure this whole object exists to prevent.
     */
    fun resetClockOf(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val at = raw.indexOf("resets ", ignoreCase = true)
        if (at < 0) return null
        val rest = raw.substring(at + 7).trimStart()
        if (rest.isEmpty()) return null
        val end = rest.indexOfFirst { it == '\n' || it == '(' || it == '.' }
        val clock = (if (end < 0) rest else rest.substring(0, end)).trim()
        return clock.takeIf { it.isNotEmpty() && it.length <= 24 }
    }

    /**
     * What an Undo actually did, said in the toast that offered it.
     *
     * ⚠ THE ROUTE ANSWERS `ok:true` EITHER WAY. The `/model` picker cannot be
     * opened inside a running turn, so a mid-turn undo is held to the next turn
     * boundary like any other automated send — and the toast built on `ok`
     * alone announced "put back on its own model" over a session still running
     * on the model it was moved to, under the same key as the downgrade it
     * claimed to have reversed. [UndoResult.applied] is the fact; `queued` is
     * the promise; neither is `ok`.
     */
    fun undoWords(result: UndoResult): String {
        val to = result.to?.trim()?.takeIf { it.isNotEmpty() }
        if (result.applied) return if (to == null) "put back on its own model" else "put back on $to"
        if (result.queued) {
            return if (to == null) "will go back at the next turn boundary"
            else "will go back to $to at the next turn boundary"
        }
        // Neither applied nor queued and not an exception: the send was dropped.
        // The daemon says why in `delivery` ("dropped: <reason>"), verbatim.
        return result.delivery?.trim()?.takeIf { it.isNotEmpty() } ?: "could not undo"
    }

    /**
     * What a Refresh button says after the daemon answers.
     *
     * The STATUS WORD, verbatim, because the words are the daemon's vocabulary
     * and a reader who has to act on `refresh_token_expired` is not helped by
     * "failed". The one word that needs translating is `active_skipped`: it is
     * not a failure and it is not a refresh either — the route never refuses the
     * active login, it declines to touch a token a running `claude` holds in
     * memory — and "active_skipped" reads as something going wrong.
     *
     * Everything else passes through, including words added to the daemon after
     * this was written: an unknown word shown as itself is a worse sentence than
     * a known one, and a much better one than a wrong one.
     */
    fun refreshWords(status: String?): String {
        val word = status?.trim()?.takeIf { it.isNotEmpty() } ?: return "no answer"
        return if (word == "active_skipped") "that is the active login" else word
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
     * [coarseUntil], from an epoch in MILLISECONDS.
     *
     * Every `at` on `/v1/headroom` is milliseconds; only `resetsAt` on a window
     * is an ISO string. Two entry points rather than one guessing function: a
     * magnitude heuristic that decides a unit for you is how a 1970 countdown
     * gets rendered with nothing on screen to say it was guessed.
     */
    fun coarseUntilMs(atMs: Long?, nowMs: Long): String? {
        val secs = secondsUntilMs(atMs, nowMs) ?: return null
        return when {
            secs <= 0 -> "now"
            secs >= 86_400 -> "${secs / 86_400}d"
            secs >= 3_600 -> "${secs / 3_600}h"
            secs >= 60 -> "${secs / 60}m"
            else -> "now"
        }
    }

    /** [shortUntil], from an epoch in MILLISECONDS. */
    fun shortUntilMs(atMs: Long?, nowMs: Long): String? {
        val secs = secondsUntilMs(atMs, nowMs) ?: return null
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

    /** The epoch guard of [secondsUntil], for a millisecond epoch. */
    private fun secondsUntilMs(atMs: Long?, nowMs: Long): Long? {
        val at = atMs ?: return null
        if (at <= 0L) return null
        return (at - nowMs) / 1000L
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
