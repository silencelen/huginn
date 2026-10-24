package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ContextBreakdown
import com.silencelen.huginn.data.ContextCategory
import com.silencelen.huginn.data.ContextUsage
import com.silencelen.huginn.data.Queued
import kotlin.math.roundToLong

/**
 * The context card's words, shared by both shells and asserted in `:core`.
 *
 * The token figures follow Claude Code's own `/context` table (verified on
 * 2.1.306: `28.7k/1m tokens (3%)`, `3.7k tokens (0.4%)`, `938.3k (93.8%)`,
 * `33k tokens (3.3%)`): one decimal, the `.0` dropped, `k` to a million and `m`
 * past it. NOT [PlanFormat.compactTokens], which prints `1.2M tokens` for a
 * plan's volume — a card that quotes Claude Code's table beside Claude Code's
 * own numbers has to spell them the way the table does, or the reader sees two
 * answers to one question.
 */
object ContextFormat {

    /** `671` · `3.7k` · `33k` · `938.3k` · `1m` · `1.2m`. */
    fun tokens(n: Long): String = when {
        n < 0L -> "0"
        n < 1_000L -> n.toString()
        // ⚠ THE UNIT IS CHOSEN AFTER THE ROUNDING, as in PlanFormat: 999_950
        // rounds to 1000.0k, which is 1m, not "1000k".
        n < 1_000_000L && oneDecimal(n / 1_000.0) != "1000" -> oneDecimal(n / 1_000.0) + "k"
        else -> oneDecimal(n / 1_000_000.0) + "m"
    }

    /** `28.7k / 1m tokens`. */
    fun usedOfWindow(used: Long, window: Long): String = "${tokens(used)} / ${tokens(window)} tokens"

    /** A row's share — `0.4%` · `2.1%` · `93.8%`: one decimal, the `.0` dropped, like the table's rows. */
    fun percent(part: Long, whole: Long): String {
        if (whole <= 0L) return "0%"
        return oneDecimal(part * 100.0 / whole) + "%"
    }

    /** The total's share — `3%`: a whole number, like the table's own total line (`28.7k/1m tokens (3%)`). */
    fun wholePercent(part: Long, whole: Long): String {
        if (whole <= 0L) return "0%"
        return (part * 100.0 / whole).roundToLong().toString() + "%"
    }

    /** The whole-number reading the meter and the title share. */
    fun percentOf(usage: ContextUsage): Int =
        if (usage.window <= 0L) usage.percent else (usage.used * 100.0 / usage.window).roundToLong().toInt()

    /**
     * The table's own total line: `28.7k / 1m tokens · 3%`. From the live
     * reading when there is one — it moves every turn, while the table is as
     * old as the last `/context` — and from the table otherwise.
     */
    fun totalLine(usage: ContextUsage?, breakdown: ContextBreakdown?): String? = when {
        usage != null && usage.window > 0L -> usedOfWindow(usage.used, usage.window) + " · " + wholePercent(usage.used, usage.window)
        breakdown != null && breakdown.maxTokens > 0L ->
            usedOfWindow(breakdown.totalTokens, breakdown.maxTokens) + " · " + wholePercent(breakdown.totalTokens, breakdown.maxTokens)
        usage != null -> "${usage.percent}% of the window"
        else -> null
    }

    /**
     * The categories a bar draws, in the table's order with the free space last,
     * each with its share of [ContextBreakdown.maxTokens]. Rows with no tokens
     * are kept: a table that says "MCP tools: 0" is saying something.
     */
    fun rows(b: ContextBreakdown): List<Row> {
        val whole = if (b.maxTokens > 0L) b.maxTokens else b.categories.sumOf { it.tokens }.coerceAtLeast(1L)
        val (free, rest) = b.categories.partition { it.kind == "free" }
        return (rest + free).map { c -> Row(c, c.tokens.toDouble() / whole, percent(c.tokens, whole)) }
    }

    data class Row(val category: ContextCategory, val share: Double, val percentWords: String)

    /** `as of 14m ago` · `as of just now`; empty when the table has no stamp. */
    fun asOf(asOfMs: Long, nowMs: Long): String =
        if (asOfMs <= 0L) "" else "as of " + TimeWords.agoMs(asOfMs, nowMs)

    /**
     * What the card says back after a refresh (or a `/fast` toggle). A 202 is
     * typed now, or held while the composer holds unsent text (`blockedBy:
     * draft`). A 409 shows the daemon's own sentence where it gave one, else a
     * sentence per reason word (`busy` · `shell` · `dialog`). `busy` is the same
     * command already waiting — NOT mid-turn: a refresh asked mid-turn is a 202,
     * typed for the end of the turn.
     */
    fun refreshNote(q: Queued): String {
        val said = q.error?.trim().orEmpty()
        return when {
            q.queued && q.delivered == false && q.blockedBy == "draft" -> "held · the composer has unsent text"
            q.queued -> "queued · appears when the session is idle"
            said.isNotEmpty() -> said
            q.reason == "busy" -> "not now · already queued for this session"
            q.reason == "shell" -> "not here · this pane is a shell, not Claude"
            q.reason == "dialog" -> "not now · a question is waiting in the pane"
            !q.reason.isNullOrBlank() -> "not now · ${q.reason}"
            else -> "could not ask for a refresh"
        }
    }

    /** The same from the two words alone, for a caller with no daemon sentence in hand. */
    fun refreshNote(queued: Boolean, reason: String?): String = refreshNote(Queued(queued = queued, reason = reason))

    /** The kinds a bar colours by, in the order the legend lists them. */
    val KINDS: List<String> = listOf("used", "buffer", "deferred", "free")

    private fun oneDecimal(v: Double): String {
        val scaled = (v * 10).roundToLong()
        val whole = scaled / 10
        val tenth = scaled % 10
        return if (tenth == 0L) whole.toString() else "$whole.$tenth"
    }
}
