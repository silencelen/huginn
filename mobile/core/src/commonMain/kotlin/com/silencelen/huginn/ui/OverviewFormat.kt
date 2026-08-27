package com.silencelen.huginn.ui

import com.silencelen.huginn.data.GraphRate
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.PlanLimit
import kotlin.math.roundToLong

/**
 * The words on the overview: how long, how fast, and where that pace lands.
 *
 * Shared for the same reason [PlanFormat] is: the desktop and the phone are
 * describing one session and must not describe it differently. And projections
 * are the part of this screen most easily made dishonest, so the two rules that
 * keep them honest live here rather than in either renderer:
 *
 *  * **No money.** ccusage prices at list rates and a Max plan pays nothing like
 *    them, so a dollar figure on a per-session screen is a number somebody would
 *    act on that is not true. Tokens and percentages only.
 *  * **The estimate says it is one.** A projection is a straight line drawn
 *    through a burn rate measured over ten minutes; the sentence carries "at this
 *    pace" so it cannot be read as a forecast.
 */
object OverviewFormat {

    /** "3d 4h" / "2h 15m" / "45m" / "40s". Two units at most; nobody reads three. */
    fun durationWords(ms: Long): String {
        val secs = ms / 1000
        if (secs <= 0) return "0s"
        val d = secs / 86_400
        val h = (secs % 86_400) / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /**
     * The plan window a session's pace should be measured against.
     *
     * The weekly one, because that is the limit a long run actually threatens —
     * the five-hour window resets while you are still watching it. Scoped
     * (per-model) beats all-models when both are present: the scoped row is the
     * one that runs out first for a session pinned to one model.
     */
    fun weeklyWindow(plan: Plan?): PlanLimit? {
        val rows = plan?.limits.orEmpty().filter { it.resetsAt != null }
        return rows.firstOrNull { it.kind == "weekly_scoped" }
            ?: rows.firstOrNull { it.kind == "weekly_all" }
            ?: rows.firstOrNull { it.group == "weekly" }
    }

    /**
     * How many more tokens this session adds before that window resets, at the
     * rate it is going right now. Null when there is no pace or nothing to count
     * down to — a silent card beats a card reading "0 tokens by never".
     */
    fun projectedTokens(perMin: Long, nowMs: Long, resetsAt: String?): Long? {
        if (perMin <= 0) return null
        val at = PlanFormat.parseIsoToEpochMs(resetsAt) ?: return null
        val minutes = (at - nowMs) / 60_000.0
        if (minutes <= 0) return null
        return (minutes * perMin).roundToLong()
    }

    /** The whole projection sentence, hedge included, or null when there is none. */
    fun paceLine(rate: GraphRate, plan: Plan?, nowMs: Long): String? {
        val window = weeklyWindow(plan) ?: return null
        val perMin = if (rate.tokensPerMin10 > 0) rate.tokensPerMin10 else rate.tokensPerMin60
        val projected = projectedTokens(perMin, nowMs, window.resetsAt) ?: return null
        val countdown = PlanFormat.resetLabel(window.resetsAt, nowMs) ?: return null
        // The label is left capitalised: it is the plan row's own name ("Current
        // week (Fable)"), and lowercasing it turns the model into a word.
        val inWords = countdown.removePrefix("resets in ")
        return "At this pace, about ${PlanFormat.compactTokens(projected)} more " +
            "before ${window.label} resets in $inWords"
    }

    /** "12.4k tokens/min" — written tokens, the ones a person is watching. */
    fun burnWords(perMin: Long): String =
        if (perMin <= 0) "idle" else "${PlanFormat.compactTokens(perMin).removeSuffix(" tokens")}/min"

    /**
     * The cache-read share of everything this session moved.
     *
     * Worth a line because it is usually most of the total and almost none of the
     * cost: a header reading "620M tokens" with no note that 99% of it was a
     * re-read of context already paid for is a number that starts arguments.
     */
    fun cacheShare(cacheRead: Long, all: Long): String = PlanFormat.sharePercent(cacheRead, all)
}
