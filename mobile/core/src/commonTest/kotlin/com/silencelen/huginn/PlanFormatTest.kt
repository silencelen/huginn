package com.silencelen.huginn

import com.silencelen.huginn.data.ExtraUsage
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.Spend
import com.silencelen.huginn.ui.PlanFormat
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The plan card's words. NOTE kotlin.test's argument order is
 * (expected, actual, message).
 *
 * The timestamps here are the ones the daemon really sent on 2026-08-26, and the
 * expected epochs come from an independent parser rather than from this one.
 */
class PlanFormatTest {

    // ------------------------------------------------------------ the parser

    @Test
    fun `a real reset timestamp parses to the instant it names`() {
        assertEquals(
            1_788_195_600_296L,
            PlanFormat.parseIsoToEpochMs("2026-08-31T17:00:00.296172+00:00"),
            "microseconds truncate to millis, offset applied",
        )
        assertEquals(
            1_787_817_000_296L,
            PlanFormat.parseIsoToEpochMs("2026-08-27T07:50:00.296153+00:00"),
        )
    }

    @Test
    fun `Z and a zero offset are the same instant`() {
        assertEquals(
            PlanFormat.parseIsoToEpochMs("2026-08-31T17:00:00.296+00:00"),
            PlanFormat.parseIsoToEpochMs("2026-08-31T17:00:00.296Z"),
        )
    }

    @Test
    fun `the offset is applied, not discarded`() {
        // The desktop bug in one assertion: sliced with a regex, both of these
        // read as "17:00" / "10:00" wall clock and the +/- was thrown away.
        assertEquals(1_788_195_600_000L, PlanFormat.parseIsoToEpochMs("2026-08-31T10:00:00-07:00"))
        assertEquals(1_788_195_600_000L, PlanFormat.parseIsoToEpochMs("2026-08-31T22:30:00+05:30"))
        assertNotEquals(
            PlanFormat.parseIsoToEpochMs("2026-08-31T17:00:00+00:00"),
            PlanFormat.parseIsoToEpochMs("2026-08-31T17:00:00-07:00"),
            "same wall clock, different offset, different instant",
        )
    }

    @Test
    fun `the compact offset form and lowercase markers parse`() {
        assertEquals(1_788_195_600_000L, PlanFormat.parseIsoToEpochMs("2026-08-31T22:30:00+0530"))
        assertEquals(1_788_195_600_000L, PlanFormat.parseIsoToEpochMs("2026-08-31t17:00:00z"))
    }

    @Test
    fun `seconds and fractions are optional and a missing offset means UTC`() {
        assertEquals(1_788_195_600_000L, PlanFormat.parseIsoToEpochMs("2026-08-31T17:00Z"))
        assertEquals(1_788_195_600_000L, PlanFormat.parseIsoToEpochMs("2026-08-31T17:00:00"))
    }

    @Test
    fun `the epoch and a leap day land where an independent parser puts them`() {
        assertEquals(0L, PlanFormat.parseIsoToEpochMs("1970-01-01T00:00:00Z"))
        assertEquals(951_825_600_000L, PlanFormat.parseIsoToEpochMs("2000-02-29T12:00:00Z"))
    }

    @Test
    fun `anything that is not a timestamp is null rather than a wrong number`() {
        assertNull(PlanFormat.parseIsoToEpochMs(null))
        assertNull(PlanFormat.parseIsoToEpochMs(""))
        assertNull(PlanFormat.parseIsoToEpochMs("   "))
        assertNull(PlanFormat.parseIsoToEpochMs("soon"))
        assertNull(PlanFormat.parseIsoToEpochMs("2026-13-01T00:00:00Z"), "month 13")
        assertNull(PlanFormat.parseIsoToEpochMs("2026-02-30T00:00:00Z"), "February has no 30th")
        assertNull(PlanFormat.parseIsoToEpochMs("2026-08-31T25:00:00Z"), "hour 25")
        assertNull(PlanFormat.parseIsoToEpochMs("2026-08-31T17:00:00+99:00"), "offset 99h")
        assertNull(PlanFormat.parseIsoToEpochMs("2026-08-31 17:00:00 UTC"), "a name, not an offset")
    }

    @Test
    fun `a non-leap century February 29 is not a date`() {
        assertNull(PlanFormat.parseIsoToEpochMs("1900-02-29T00:00:00Z"))
        assertEquals(true, PlanFormat.parseIsoToEpochMs("2024-02-29T00:00:00Z") != null)
    }

    // --------------------------------------------------------- the countdown

    @Test
    fun `a passed reset says it is happening now`() {
        assertEquals("resetting now", PlanFormat.resetCountdown(1_000L, 1_000L))
        assertEquals("resetting now", PlanFormat.resetCountdown(2_000L, 1_000L))
    }

    @Test
    fun `inside an hour the countdown is minutes`() {
        assertEquals("resets in 34m", PlanFormat.resetCountdown(0L, 34 * 60_000L))
        assertEquals("resets in 0m", PlanFormat.resetCountdown(0L, 59_000L), "under a minute is 0m, as the phone has always said")
        assertEquals("resets in 59m", PlanFormat.resetCountdown(0L, 59 * 60_000L))
    }

    @Test
    fun `inside a day the countdown is hours and minutes`() {
        assertEquals("resets in 1h 0m", PlanFormat.resetCountdown(0L, 3_600_000L))
        assertEquals("resets in 5h 12m", PlanFormat.resetCountdown(0L, (5 * 3600 + 12 * 60) * 1000L))
        assertEquals("resets in 23h 59m", PlanFormat.resetCountdown(0L, (23 * 3600 + 59 * 60) * 1000L))
    }

    @Test
    fun `past a day the countdown is days and hours`() {
        assertEquals("resets in 1d 1h", PlanFormat.resetCountdown(0L, 25 * 3_600_000L))
        assertEquals("resets in 2d 4h", PlanFormat.resetCountdown(0L, (2 * 86_400 + 4 * 3600) * 1000L))
    }

    @Test
    fun `the label parses and counts in one step`() {
        val now = 1_788_195_600_296L - 2 * 3_600_000L
        assertEquals("resets in 2h 0m", PlanFormat.resetLabel("2026-08-31T17:00:00.296172+00:00", now))
        assertNull(PlanFormat.resetLabel("whenever", now))
        assertNull(PlanFormat.resetLabel(null, now))
    }

    // -------------------------------------------------------------- the money

    @Test
    fun `cents become dollars`() {
        assertEquals("$100.55", PlanFormat.minorAmount(10_055L, 2, "USD"))
        assertEquals("$100.00", PlanFormat.minorAmount(10_000L, 2, "USD"))
        assertEquals("$0.05", PlanFormat.minorAmount(5L, 2, "USD"))
        assertEquals("$0.00", PlanFormat.minorAmount(0L, 2, "USD"))
    }

    @Test
    fun `a zero-exponent currency is not divided`() {
        assertEquals("5,000 JPY", PlanFormat.minorAmount(5_000L, 0, "JPY"))
    }

    @Test
    fun `three decimal places keep all three`() {
        assertEquals("1.234 KWD", PlanFormat.minorAmount(1_234L, 3, "KWD"))
    }

    @Test
    fun `only the dollar gets a symbol`() {
        assertEquals("100.55 EUR", PlanFormat.minorAmount(10_055L, 2, "EUR"))
        assertEquals("$100.55", PlanFormat.minorAmount(10_055L, 2, "usd"), "case is not a different currency")
        assertEquals("$100.55", PlanFormat.minorAmount(10_055L, 2, ""), "a missing currency is the one we bill in")
    }

    @Test
    fun `thousands are grouped and a negative keeps its sign in front`() {
        assertEquals("$1,234.56", PlanFormat.minorAmount(123_456L, 2, "USD"))
        assertEquals("$12,345,678.90", PlanFormat.minorAmount(1_234_567_890L, 2, "USD"))
        assertEquals("-$1.00", PlanFormat.minorAmount(-100L, 2, "USD"))
    }

    // ------------------------------------------------------------ the tokens

    @Test
    fun `token counts compact by magnitude`() {
        assertEquals("847 tokens", PlanFormat.compactTokens(847L))
        assertEquals("1.2k tokens", PlanFormat.compactTokens(1_234L))
        assertEquals("1.2M tokens", PlanFormat.compactTokens(1_234_567L))
        assertEquals("1.23B tokens", PlanFormat.compactTokens(1_234_567_890L))
        assertEquals("0 tokens", PlanFormat.compactTokens(0L))
        assertEquals("1.0k tokens", PlanFormat.compactTokens(1_000L), "the boundary belongs to the bigger unit")
    }

    @Test
    fun `a share is a whole percent and zero of nothing is zero`() {
        assertEquals("83%", PlanFormat.sharePercent(83L, 100L))
        assertEquals("0%", PlanFormat.sharePercent(5L, 0L), "no division by an empty total")
        assertEquals("67%", PlanFormat.sharePercent(2L, 3L))
    }

    @Test
    fun `the ccusage figure is always hedged and always grouped`() {
        assertEquals("approx \$4,210", PlanFormat.approxDollars(4209.61))
        assertEquals("approx \$0", PlanFormat.approxDollars(0.0))
    }

    // -------------------------------------------------------- the state line

    @Test
    fun `off is three different states`() {
        assertEquals(
            "paused until the monthly reset",
            PlanFormat.extraUsageState(enabled = false, spendLimitReached = true, disabledReason = "org_level_disabled_until", userDisabled = false),
        )
        assertEquals(
            "turned off",
            PlanFormat.extraUsageState(enabled = false, disabledReason = "org_level_disabled_until", userDisabled = true),
            "the owner's own switch outranks the org reason",
        )
        assertEquals("limit reached, now paused", PlanFormat.extraUsageState(enabled = false, spendLimitReached = true))
        assertEquals("paused", PlanFormat.extraUsageState(enabled = false, disabledReason = "some_future_reason"))
        assertEquals("off", PlanFormat.extraUsageState(enabled = false))
    }

    @Test
    fun `on is either working or spent`() {
        assertEquals("on", PlanFormat.extraUsageState(enabled = true))
        assertEquals("limit reached", PlanFormat.extraUsageState(enabled = true, spendLimitReached = true))
    }

    // ------------------------------------------------------------- the card

    private val liveExtra = ExtraUsage(
        utilization = 100.0,
        usedCredits = 10055.0,
        monthlyLimit = 10000.0,
        currency = "USD",
        spendLimitReached = true,
        isEnabled = false,
        creditsEverEnabled = true,
        decimalPlaces = 2,
        disabledReason = "org_level_disabled_until",
        userDisabled = false,
    )

    private val liveSpend = Spend(
        usedMinor = 10_055L,
        limitMinor = 10_000L,
        exponent = 2,
        currency = "USD",
        percent = 100.0,
        severity = "critical",
        enabled = false,
        disabledReason = "org_level_disabled_until",
    )

    @Test
    fun `the live account shows the bill it actually ran up`() {
        val card = PlanFormat.extraUsageCard(Plan(extraUsage = liveExtra, spend = liveSpend))
        assertNotNull(card)
        assertEquals(100.0, card.percent)
        assertEquals("$100.55 of $100.00 used", card.amountLine)
        assertEquals("paused until the monthly reset", card.state)
        assertEquals("critical", card.severity)
    }

    @Test
    fun `without a spend block the credit figures say the same thing`() {
        // They are minor units too, at decimalPlaces — which is how the phone
        // came to print "100% of 10000 USD" for a hundred-dollar cap.
        val card = PlanFormat.extraUsageCard(Plan(extraUsage = liveExtra))
        assertNotNull(card)
        assertEquals("$100.55 of $100.00 used", card.amountLine)
        assertEquals(100.0, card.percent, "utilization stands in for spend.percent")
    }

    @Test
    fun `an account the daemon withheld shows no card`() {
        assertNull(PlanFormat.extraUsageCard(null))
        assertNull(PlanFormat.extraUsageCard(Plan()))
        assertNull(
            PlanFormat.extraUsageCard(Plan(spend = Spend(usedMinor = 0L, percent = 100.0, enabled = false))),
            "a never-enabled account reads 100% against a limit it does not have",
        )
    }

    @Test
    fun `money without an extra-usage block is still shown`() {
        val card = PlanFormat.extraUsageCard(Plan(spend = Spend(usedMinor = 250L, exponent = 2, currency = "USD", percent = 5.0, enabled = true)))
        assertNotNull(card)
        assertEquals("$2.50 used", card.amountLine, "no cap on the wire means no 'of'")
        assertEquals("on", card.state)
        assertNull(card.severity, "no word from Claude means colour by percent")
    }

    @Test
    fun `the daemon's own plan body decodes straight into the card`() {
        // Not hand-written: this is what `normalizePlan` returned for the
        // 2026-08-26 capture, pasted verbatim. It is the only test that fails when
        // the two halves stop agreeing on a field NAME, and the failure it guards
        // against is silent — a renamed key decodes to the default and the card
        // quietly shows nothing, which is the exact bug this whole change is about.
        val wire = """
            {"limits":[{"kind":"session","group":"session","label":"Current session","percent":17,"severity":"normal","resetsAt":"2026-08-27T07:50:00.296153+00:00","isActive":false},{"kind":"weekly_all","group":"weekly","label":"Current week, all models","percent":50,"severity":"normal","resetsAt":"2026-08-31T17:00:00.296172+00:00","isActive":true},{"kind":"weekly_scoped","group":"weekly","label":"Current week (Fable)","percent":50,"severity":"normal","resetsAt":"2026-08-31T17:00:00.296422+00:00","isActive":false}],"extraUsage":{"utilization":100,"usedCredits":10055,"monthlyLimit":10000,"currency":"USD","spendLimitReached":true,"isEnabled":false,"creditsEverEnabled":true,"decimalPlaces":2,"disabledReason":"org_level_disabled_until","userDisabled":false,"daily":null,"weekly":null},"spend":{"usedMinor":10055,"limitMinor":10000,"exponent":2,"currency":"USD","percent":100,"severity":"critical","enabled":false,"disabledReason":"org_level_disabled_until","canPurchaseCredits":false,"canToggle":false},"fetchedAt":1787800000000,"error":null}
        """.trimIndent()
        // The same decoder HuginnClient uses, unknown keys and all — `daily` and
        // `weekly` ride the wire unmodelled and must not throw.
        val plan = Json { ignoreUnknownKeys = true; explicitNulls = false }.decodeFromString<Plan>(wire)

        assertEquals(3, plan.limits.size)
        assertEquals(true, plan.limits[1].isActive, "the weekly window is the active one")
        assertEquals(10_055L, plan.spend?.usedMinor)
        assertEquals("critical", plan.spend?.severity)
        assertEquals(true, plan.extraUsage?.creditsEverEnabled)

        val card = PlanFormat.extraUsageCard(plan)
        assertNotNull(card)
        assertEquals("$100.55 of $100.00 used", card.amountLine)
        assertEquals("paused until the monthly reset", card.state)
        // Four hours before the session resets, whatever timezone the reader is in.
        assertEquals("resets in 4h 0m", PlanFormat.resetLabel(plan.limits[0].resetsAt, 1_787_802_600_296L))
    }

    @Test
    fun `a card with no amounts at all still says where it stands`() {
        val card = PlanFormat.extraUsageCard(Plan(extraUsage = ExtraUsage(utilization = 40.0, isEnabled = true)))
        assertNotNull(card)
        assertEquals(40.0, card.percent)
        assertNull(card.amountLine)
        assertEquals("on", card.state)
    }
}
