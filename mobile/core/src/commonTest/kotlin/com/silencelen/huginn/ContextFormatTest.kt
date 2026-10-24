package com.silencelen.huginn

import com.silencelen.huginn.data.ContextBreakdown
import com.silencelen.huginn.data.ContextCategory
import com.silencelen.huginn.data.ContextUsage
import com.silencelen.huginn.data.Queued
import com.silencelen.huginn.ui.ContextFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The context card's words, held to Claude Code's own `/context` table
 * (2.1.306, 2026-10-23): the figures the card prints beside the table's must be
 * spelled the way the table spells them.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ContextFormatTest {

    /** Every figure in the captured table, reproduced. */
    @Test
    fun `tokens are spelled the way the table spells them`() {
        assertEquals("3.7k", ContextFormat.tokens(3_700))
        assertEquals("20.8k", ContextFormat.tokens(20_800))
        assertEquals("671", ContextFormat.tokens(671))
        assertEquals("717", ContextFormat.tokens(717))
        assertEquals("2.8k", ContextFormat.tokens(2_800))
        assertEquals("10", ContextFormat.tokens(10))
        assertEquals("938.3k", ContextFormat.tokens(938_300))
        assertEquals("33k", ContextFormat.tokens(33_000), "a whole number of thousands drops the .0")
        assertEquals("1m", ContextFormat.tokens(1_000_000))
        assertEquals("28.7k", ContextFormat.tokens(28_700))
    }

    /** The unit is chosen after the rounding, as PlanFormat learnt the hard way. */
    @Test
    fun `a figure that rounds up to the next unit takes that unit`() {
        assertEquals("1m", ContextFormat.tokens(999_950), "not 1000k")
        assertEquals("999.9k", ContextFormat.tokens(999_949))
        assertEquals("1.2m", ContextFormat.tokens(1_234_567))
        assertEquals("1k", ContextFormat.tokens(1_000))
        assertEquals("1k", ContextFormat.tokens(1_049), "1.049k rounds to 1.0k, printed 1k")
    }

    @Test
    fun `just under a thousand stays a plain count`() {
        assertEquals("999", ContextFormat.tokens(999))
        assertEquals("0", ContextFormat.tokens(0))
        assertEquals("0", ContextFormat.tokens(-5), "a negative count is a daemon bug, shown as nothing")
    }

    /** Rows keep one decimal and drop a zero one; the total is a whole number, as in the table. */
    @Test
    fun `row shares keep one decimal and the total is whole`() {
        assertEquals("0.4%", ContextFormat.percent(3_700, 1_000_000))
        assertEquals("2.1%", ContextFormat.percent(20_800, 1_000_000))
        assertEquals("3.3%", ContextFormat.percent(33_000, 1_000_000))
        assertEquals("93.8%", ContextFormat.percent(938_300, 1_000_000))
        assertEquals("0%", ContextFormat.percent(5, 0), "no window, no share")
        assertEquals("3%", ContextFormat.wholePercent(28_700, 1_000_000), "the table's own total line reads (3%)")
        assertEquals("0%", ContextFormat.wholePercent(5, 0))
    }

    /** The live reading wins over the table, which is as old as the last /context. */
    @Test
    fun `the total line prefers the live reading and falls back to the table`() {
        val usage = ContextUsage(used = 28_700, window = 1_000_000, percent = 3)
        val table = ContextBreakdown(totalTokens = 61_700, maxTokens = 1_000_000)
        assertEquals("28.7k / 1m tokens · 3%", ContextFormat.totalLine(usage, table))
        assertEquals("61.7k / 1m tokens · 6%", ContextFormat.totalLine(null, table))
        assertEquals("47% of the window", ContextFormat.totalLine(ContextUsage(percent = 47), null), "an older daemon's bare percent")
        assertNull(ContextFormat.totalLine(null, null))
    }

    @Test
    fun `rows keep the table's order with free space last and share the window`() {
        val b = ContextBreakdown(
            maxTokens = 1_000_000,
            categories = listOf(
                ContextCategory("free_space", "Free space", 938_300, "free"),
                ContextCategory("system_prompt", "System prompt", 3_700, "used"),
                ContextCategory("mcp_tools", "MCP tools", 0, "used"),
                ContextCategory("autocompact_buffer", "Autocompact buffer", 33_000, "buffer"),
            ),
        )
        val rows = ContextFormat.rows(b)
        assertEquals(listOf("system_prompt", "mcp_tools", "autocompact_buffer", "free_space"), rows.map { it.category.id })
        assertEquals("0.4%", rows[0].percentWords)
        assertEquals("0%", rows[1].percentWords, "a zero row is kept — the table said it")
        assertEquals(0.033, rows[2].share, 0.0001)
    }

    /**
     * The 202's two states, then the 409 in the daemon's own words where it gave
     * any (observed appd 3.14.0 bodies), else a sentence per reason word. `busy`
     * is the same command already waiting — a refresh asked mid-turn is a 202.
     */
    @Test
    fun `the refresh answer is what the daemon said, or a sentence for each of its reasons`() {
        assertEquals("queued · appears when the session is idle", ContextFormat.refreshNote(Queued(queued = true, delivered = true)))
        assertEquals("queued · appears when the session is idle", ContextFormat.refreshNote(true, null), "a 202 without the delivery fields")
        assertEquals(
            "held · the composer has unsent text",
            ContextFormat.refreshNote(Queued(queued = true, delivered = false, blockedBy = "draft")),
        )
        assertEquals(
            "/context is already waiting for this session's next turn boundary",
            ContextFormat.refreshNote(Queued(reason = "busy", error = "/context is already waiting for this session's next turn boundary")),
            "a 409 shows the daemon's own sentence",
        )
        assertEquals("not now · already queued for this session", ContextFormat.refreshNote(false, "busy"), "busy is a duplicate, not mid-turn")
        assertEquals("not here · this pane is a shell, not Claude", ContextFormat.refreshNote(false, "shell"))
        assertEquals("not now · a question is waiting in the pane", ContextFormat.refreshNote(false, "dialog"))
        assertEquals(
            "not here · this pane is a shell, not Claude",
            ContextFormat.refreshNote(Queued(reason = "shell", error = "  ")),
            "a blank sentence falls back to the reason's",
        )
        assertEquals("not now · rate limited", ContextFormat.refreshNote(false, "rate limited"), "an unknown reason is still shown")
        assertEquals("could not ask for a refresh", ContextFormat.refreshNote(false, null))
    }

    /** No stamp — 0, as a daemon null decodes, or anything negative — prints no age, never "56 years ago". */
    @Test
    fun `as of names the table's age, or nothing when it has no stamp`() {
        val now = 1_789_460_000_000L
        assertEquals("as of 14m ago", ContextFormat.asOf(now - 14 * 60_000L, now))
        assertEquals("", ContextFormat.asOf(0L, now))
        assertEquals("", ContextFormat.asOf(-1L, now))
        assertEquals("", ContextFormat.asOf(ContextBreakdown().asOf, now), "the model's default is no stamp")
    }
}
