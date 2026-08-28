package com.silencelen.huginn

import com.silencelen.huginn.data.EstCost
import com.silencelen.huginn.data.GraphRate
import com.silencelen.huginn.data.ModelCost
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.PlanLimit
import com.silencelen.huginn.data.SessionOverview
import com.silencelen.huginn.ui.OverviewFormat
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The overview header's arithmetic and its house rule: every estimate says out
 * loud that it is one — the pace line in words, and the cost figure both with its
 * `~` and with the caption that says whose money it is not.
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

    // ------------------------------------------------------------------ money

    @Test
    fun `money groups its thousands the way the plan card's does`() {
        // The same helper, so the two places this app prints money cannot drift:
        // a four-figure estimate unreadable as "$1234.5" beside a plan card
        // reading "$1,234.50" is one app answering in two formats.
        assertEquals("$457.00", OverviewFormat.usd(457.0))
        assertEquals("$1,234.50", OverviewFormat.usd(1234.5))
        assertEquals("$1,000,000.00", OverviewFormat.usd(1_000_000.0))
        assertEquals(
            com.silencelen.huginn.ui.PlanFormat.minorAmount(123_450, 2, "USD"),
            OverviewFormat.usd(1234.5),
            "the same money through both formatters, byte for byte",
        )
    }

    @Test
    fun `a real total rounds to the cents a person would read`() {
        // The daemon's own figure for the 33.8MB session, carried at six decimal
        // places because the arithmetic has them; the screen has two.
        assertEquals("$457.00", OverviewFormat.usd(456.999628))
        assertEquals("$112.02", OverviewFormat.usd(112.022884))
        assertEquals("$0.02", OverviewFormat.usd(0.02365), "the 5.6KB haiku session")
    }

    @Test
    fun `a figure too small to show is not shown as nothing`() {
        // A subagent that answered in four tokens really did cost something, and
        // "$0.00" over it is the formatter claiming a fact it never had.
        assertEquals("<$0.01", OverviewFormat.usd(0.004))
        assertEquals("<$0.01", OverviewFormat.usd(0.000336), "the daemon's own smallest priced walk")
        assertEquals("$0.01", OverviewFormat.usd(0.01), "a cent is a cent, not less than one")
    }

    @Test
    fun `exactly nothing is nothing, and must not read as too small to show`() {
        // This is what a session priced entirely on models the table has never
        // seen gets: usd 0, with every token of it in unpricedTokens. "<$0.01"
        // there would claim spend the estimate explicitly could not price.
        assertEquals("$0.00", OverviewFormat.usd(0.0))
    }

    @Test
    fun `the stat says the figure, the agents' share, and what the figure is`() {
        // The real 33.8MB session: 66 turns, 24 agents, one model.
        val stat = OverviewFormat.costStat(
            EstCost(
                usd = 456.999628,
                byModel = listOf(ModelCost("claude-opus-5", 456.999628)),
                unpricedTokens = 0,
            ),
            agentEstCostUsd = 112.022884,
        )
        assertNotNull(stat)
        assertEquals("~$457.00", stat.value)
        assertEquals(" · ~$112.02 of it in agents", stat.agentsShare)
        assertEquals("~$457.00 · ~$112.02 of it in agents", stat.statValue)
        assertNull(stat.unpriced, "every token of that session was on the price table")
        assertEquals(
            "what this session's tokens would bill at API list rates — " +
                "covered by the subscription, not a bill",
            stat.captionLine,
        )
        assertTrue(stat.value.startsWith("~"), "an estimate never renders as an exact amount: ${stat.value}")
    }

    @Test
    fun `a run that fanned out to nothing claims no share`() {
        // The daemon sends 0.0 rather than null for a session with no agents, so
        // the gate has to be on the VALUE — " · $0.00 of it in agents" is a clause
        // about nothing, and a null-only check would print it.
        val est = EstCost(usd = 548.637882, byModel = listOf(ModelCost("claude-opus-5", 548.637882)))
        assertNull(OverviewFormat.costStat(est, 0.0)?.agentsShare)
        assertNull(OverviewFormat.costStat(est, null)?.agentsShare, "an older daemon omits the field entirely")
        assertEquals("~$548.64", OverviewFormat.costStat(est, 0.0)?.statValue, "and the chip is just the figure")
    }

    @Test
    fun `tokens nobody could price are named rather than dropped`() {
        // A session that ran partly on the local tier. The dollar figure is true
        // for what it covers and silent about the rest, so the rest is said.
        val stat = OverviewFormat.costStat(
            EstCost(usd = 12.5, byModel = listOf(ModelCost("claude-opus-5", 12.5)), unpricedTokens = 1_200_000),
            agentEstCostUsd = 0.0,
        )
        assertNotNull(stat)
        assertEquals(" · 1.2M tokens unpriced", stat.unpriced)
        assertEquals("~$12.50", stat.statValue, "the note is a caption's clause, not part of the chip")
        assertTrue(stat.captionLine.endsWith(" · 1.2M tokens unpriced"), stat.captionLine)
    }

    @Test
    fun `no estimate at all means no stat, rather than a zero`() {
        // Null is what a transcript with no usage records gets. "$0.00 api cost"
        // over one is a claim; showing nothing is not.
        assertNull(OverviewFormat.costStat(null))
        assertNull(OverviewFormat.costStat(null, 112.02), "not even a stray agent figure resurrects it")
    }

    @Test
    fun `the daemon's own overview body decodes straight into the stat`() {
        // Not hand-written: this is what `sessionOverview` returned on 2026-08-27
        // for the 33.8MB transcript on this host, pasted verbatim. It is the only
        // test that fails when the two halves stop agreeing on a field NAME, and
        // that failure is silent — a renamed key decodes to the default and the
        // header quietly loses the stat.
        val wire = """
            {"v":1,"sessionId":"f1f9f1a1-43bf-4f9e-b566-ee68ed016244","generatedAt":1787880668,"totals":{"wallMs":246734000,"startedAt":1787557007,"lastActivityTs":1787803741,"turns":66,"userMessages":54,"toolCalls":1123,"errors":23,"tokens":{"input":2226,"output":1125192,"cacheRead":530134922,"cacheCreation":5310414},"agentCount":24,"agentTokens":{"input":2326,"output":144849,"cacheRead":165051358,"cacheCreation":4138296},"estCost":{"usd":456.999628,"byModel":[{"model":"claude-opus-5","usd":456.999628}],"unpricedTokens":0},"agentEstCostUsd":112.022884,"activeAgents":0,"compactions":2,"droppedTokens":2702263,"filesTouched":12,"models":["claude-opus-5"],"efforts":["xhigh"]},"rate":{"tokensPerMin10":2138,"tokensPerMin60":3689,"allTokensPerMin10":236830,"allTokensPerMin60":493422,"lastActivityTs":1787803741,"activeRecently":false},"cursor":{"size":33823721,"agentBytes":15839354}}
        """.trimIndent()
        // The same decoder HuginnClient uses. `v` rides the wire unmodelled and
        // must not throw, and `name`/`meta` are absent here — the route adds them
        // — which is the case the nullable-with-default house rule exists for.
        val overview = Json { ignoreUnknownKeys = true; explicitNulls = false }
            .decodeFromString<SessionOverview>(wire)

        val est = overview.totals.estCost
        assertNotNull(est, "the estimate has to survive the decode to be worth pricing")
        assertEquals(456.999628, est.usd)
        assertEquals(1, est.byModel.size)
        assertEquals("claude-opus-5", est.byModel[0].model)
        assertEquals(456.999628, est.byModel[0].usd)
        assertEquals(0L, est.unpricedTokens)
        assertEquals(112.022884, overview.totals.agentEstCostUsd)

        val stat = OverviewFormat.costStat(est, overview.totals.agentEstCostUsd)
        assertNotNull(stat)
        assertEquals("~$457.00 · ~$112.02 of it in agents", stat.statValue)
    }
}
