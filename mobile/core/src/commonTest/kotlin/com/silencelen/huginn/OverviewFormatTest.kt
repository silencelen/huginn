package com.silencelen.huginn

import com.silencelen.huginn.data.GraphRate
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.PlanLimit
import com.silencelen.huginn.ui.OverviewFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The projection card's arithmetic and its two house rules: no dollar figures on
 * a per-session screen, and every estimate says out loud that it is one.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class OverviewFormatTest {

    private val now = 1_756_000_000_000L
    private fun iso(offsetMs: Long): String {
        // Hand-built so the test does not depend on a date library the common
        // source set does not have. 2025-08-24T02:26:40Z plus the offset.
        val secs = (now + offsetMs) / 1000
        val days = secs / 86_400
        val rem = secs % 86_400
        // 1756000000 is 2025-08-24; only the time of day changes inside a day.
        val h = rem / 3600
        val m = (rem % 3600) / 60
        val s = rem % 60
        val dayOffset = days - 1_756_000_000L / 86_400
        return "2025-08-${(24 + dayOffset).toString().padStart(2, '0')}T" +
            "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}Z"
    }

    @Test
    fun `a duration reads in two units, never three`() {
        assertEquals("0s", OverviewFormat.durationWords(0))
        assertEquals("40s", OverviewFormat.durationWords(40_000))
        assertEquals("2m 5s", OverviewFormat.durationWords(125_000))
        assertEquals("2h 15m", OverviewFormat.durationWords(8_100_000))
        assertEquals("3d 4h", OverviewFormat.durationWords(273_600_000))
    }

    @Test
    fun `the pace is projected over the minutes that are actually left`() {
        // 1000/min for two hours is 120,000 more tokens.
        assertEquals(120_000L, OverviewFormat.projectedTokens(1_000, now, iso(7_200_000)))
    }

    @Test
    fun `a session that is not burning gets no projection at all`() {
        // Zero times a countdown is zero, and "adds about 0 tokens" is a sentence
        // nobody needs on a screen they opened to rest at.
        assertNull(OverviewFormat.projectedTokens(0, now, iso(7_200_000)))
        assertNull(OverviewFormat.projectedTokens(-5, now, iso(7_200_000)))
    }

    @Test
    fun `a window with nothing to count down to projects nothing`() {
        assertNull(OverviewFormat.projectedTokens(1_000, now, null))
        assertNull(OverviewFormat.projectedTokens(1_000, now, "not a timestamp"))
        assertNull(OverviewFormat.projectedTokens(1_000, now, iso(-60_000)), "a window that has already reset")
    }

    private fun plan(vararg limits: PlanLimit) = Plan(limits = limits.toList())

    @Test
    fun `the weekly window is the one a long run threatens`() {
        val p = plan(
            PlanLimit(kind = "session", group = "session", label = "Current session", resetsAt = iso(3_600_000)),
            PlanLimit(kind = "weekly_all", group = "weekly", label = "Current week, all models", resetsAt = iso(86_400_000)),
        )
        assertEquals("weekly_all", OverviewFormat.weeklyWindow(p)?.kind, "the five-hour one resets while you watch it")
    }

    @Test
    fun `a scoped weekly row wins, because it runs out first`() {
        val p = plan(
            PlanLimit(kind = "weekly_all", group = "weekly", label = "Current week, all models", resetsAt = iso(86_400_000)),
            PlanLimit(kind = "weekly_scoped", group = "weekly", label = "Current week (Fable)", resetsAt = iso(86_400_000)),
        )
        assertEquals("Current week (Fable)", OverviewFormat.weeklyWindow(p)?.label)
    }

    @Test
    fun `no plan and no window means no card`() {
        assertNull(OverviewFormat.weeklyWindow(null))
        assertNull(OverviewFormat.weeklyWindow(plan()))
        assertNull(OverviewFormat.weeklyWindow(plan(PlanLimit(kind = "weekly_all", resetsAt = null))))
    }

    @Test
    fun `the sentence hedges, names the window, and carries no money`() {
        val p = plan(PlanLimit(kind = "weekly_all", group = "weekly", label = "Current week, all models", resetsAt = iso(7_200_000)))
        val line = OverviewFormat.paceLine(GraphRate(tokensPerMin10 = 1_000), p, now)!!
        assertTrue(line.startsWith("At this pace,"), line)
        assertTrue(line.contains("120.0k tokens"), line)
        assertTrue(line.contains("Current week, all models"), line)
        assertTrue(line.contains("2h 0m"), line)
        assertTrue(!line.contains("$"), "a per-session screen never prices anything: $line")
    }

    @Test
    fun `the hour window carries the estimate when the ten-minute one is empty`() {
        // A session that paused for a coffee has no 10-minute rate. Falling back
        // beats printing nothing on a screen somebody opened to see the pace.
        val p = plan(PlanLimit(kind = "weekly_all", group = "weekly", label = "Current week", resetsAt = iso(3_600_000)))
        val line = OverviewFormat.paceLine(GraphRate(tokensPerMin10 = 0, tokensPerMin60 = 600), p, now)
        assertTrue(line!!.contains("36.0k tokens"), line)
    }

    @Test
    fun `an idle session says idle rather than zero`() {
        assertEquals("idle", OverviewFormat.burnWords(0))
        assertEquals("12.4k/min", OverviewFormat.burnWords(12_400))
        assertEquals("900/min", OverviewFormat.burnWords(900))
    }

    @Test
    fun `the cache share is stated, because it is most of the total and none of the work`() {
        assertEquals("99%", OverviewFormat.cacheShare(616_881_280, 623_402_413))
        assertEquals("0%", OverviewFormat.cacheShare(0, 0), "a session that has not started is not 100% cache")
    }
}
