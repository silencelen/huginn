package com.silencelen.huginn.ui

import com.silencelen.huginn.data.EstCost
import com.silencelen.huginn.data.GraphRate
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.PlanLimit
import kotlin.math.roundToLong

/**
 * The words on the overview: how long, how fast, what that would have billed, and
 * where the pace lands.
 *
 * Shared for the same reason [PlanFormat] is: the desktop and the phone are
 * describing one session and must not describe it differently. And this screen is
 * the one most easily made dishonest, so the two rules that keep it honest live
 * here rather than in either renderer:
 *
 *  * **Money never appears without saying what it is.** The figure is the
 *    daemon's per-model pricing of tokens that were already spent at list rates,
 *    and this account is on a subscription — so it is what the session WOULD have
 *    billed on the API, not a charge. It travels with [COST_CAPTION] attached,
 *    and a renderer that shows the number without the caption is the bug this
 *    rule exists to prevent. (An earlier version of this file banned money on a
 *    per-session screen outright, which was right while the client was inventing
 *    the number from a blended rate and wrong once the daemon priced it per
 *    model and named what it could not price.)
 *  * **The estimate says it is one.** A projection is a straight line drawn
 *    through a burn rate measured over ten minutes; the sentence carries "at this
 *    pace" so it cannot be read as a forecast. The cost figure carries its own
 *    `~` for the same reason.
 */
object OverviewFormat {

    /**
     * The one sentence that makes a dollar figure allowable on this screen.
     *
     * Not a hedge about accuracy — the arithmetic is exact for the rates it used.
     * It is about WHOSE money it is: nobody was billed this, and a number on a
     * screen with no such line is one somebody would act on.
     */
    const val COST_CAPTION: String =
        "what this session's tokens would bill at API list rates — covered by the subscription, not a bill"

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

    // ------------------------------------------------------------------ money

    /**
     * A dollar amount: `457.0` → `"$457.00"`, `1234.5` → `"$1,234.50"`.
     *
     * Routed through [PlanFormat.minorAmount] rather than formatted again here, so
     * the two places this app prints money group their thousands identically and
     * a fix to one is a fix to both. Cents, because this is a bill-shaped figure
     * and a bill-shaped figure with no cents reads as rounded.
     *
     * Two amounts get their own words:
     *
     *  * **Under a cent but not nothing** is `"<$0.01"`. A subagent that answered
     *    in four tokens really did cost something, and `"$0.00"` over it is the
     *    formatter claiming a fact the arithmetic never had.
     *  * **Exactly nothing** is `"$0.00"` — which is what a session priced
     *    entirely on models the daemon has never seen gets, and it must not read
     *    as "too small to show". The tokens it could not price are said out loud
     *    beside it instead.
     */
    fun usd(amount: Double): String {
        // A NaN cannot ride the daemon's JSON (kotlinx rejects the literal), so
        // this is a guard rather than a case: it exists because roundToLong THROWS
        // on one, and a crashed overview would be a worse answer than a zero.
        if (!amount.isFinite()) return "$0.00"
        if (amount > 0.0 && amount < 0.01) return "<$0.01"
        return PlanFormat.minorAmount((amount * 100).roundToLong(), 2, "USD")
    }

    /**
     * Everything the cost stat SAYS, decided here so neither renderer decides it.
     *
     * The pieces are separate because they land in two places — the chip and the
     * quiet line under the header — but [statValue] and [captionLine] are what a
     * renderer actually draws, so no client is left composing a money string.
     */
    data class CostStat(
        /** The figure, hedge included: `"~$457.00"`. */
        val value: String,
        /** `" · ~$112.02 of it in agents"`, or null when a fan-out cost nothing. */
        val agentsShare: String?,
        /** `" · 1.2M tokens unpriced"`, or null when the table priced everything. */
        val unpriced: String?,
        /** Always [COST_CAPTION]; a field so a renderer cannot draw the stat without it. */
        val caption: String,
    ) {
        /** The chip: `"~$457.00 · ~$112.02 of it in agents"`. */
        val statValue: String get() = value + agentsShare.orEmpty()

        /** The quiet line: the caption, plus what could not be priced. */
        val captionLine: String get() = caption + unpriced.orEmpty()
    }

    /**
     * The stat, or null when there is no estimate — which is what a session whose
     * transcript carried no usage at all gets. A stat reading "$0.00" over a
     * session nobody could price is a claim; absence is not.
     *
     * The agents' share is stated only when there IS one: the daemon sends 0.0 for
     * a run that never fanned out, and " · $0.00 of it in agents" is a clause
     * about nothing.
     */
    fun costStat(estCost: EstCost?, agentEstCostUsd: Double? = null): CostStat? {
        val est = estCost ?: return null
        return CostStat(
            value = "~" + usd(est.usd),
            agentsShare = agentEstCostUsd
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { " · ~${usd(it)} of it in agents" },
            unpriced = est.unpricedTokens
                .takeIf { it > 0L }
                ?.let { " · ${PlanFormat.compactTokens(it)} unpriced" },
            caption = COST_CAPTION,
        )
    }
}
