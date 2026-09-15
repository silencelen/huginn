package com.silencelen.huginn

import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.HeadroomWorst
import com.silencelen.huginn.data.SessionHeadroom
import com.silencelen.huginn.data.StatusHeadroom
import com.silencelen.huginn.ui.HeadroomRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The words the headroom pill and the session mark say. NOTE kotlin.test's
 * argument order is (expected, actual, message).
 *
 * The instants are real: RESET is `2026-09-15T10:30:00Z` and every `now` below
 * is that instant minus an exact interval, so the expected countdown is
 * arithmetic rather than a guess.
 */
class HeadroomRulesTest {

    private companion object {
        const val RESET = "2026-09-15T10:30:00Z"
        const val RESET_MS = 1_789_468_200_000L
        const val NOW_3H12M = 1_789_456_680_000L
        const val NOW_12M = 1_789_467_480_000L
        const val NOW_2D4H = 1_789_281_000_000L
        const val NOW_PAST = 1_789_468_260_000L
        const val FABLE = "Current week (Fable)"
    }

    private fun status(
        percent: Double? = 92.0,
        label: String? = FABLE,
        resetsAt: String? = RESET,
        mode: String = "red",
    ) = StatusHeadroom(worstPercent = percent, worstLabel = label, nextResetAt = resetsAt, mode = mode)

    // ------------------------------------------------------------- modeOf

    @Test
    fun `no worst window is not a problem`() {
        assertEquals(HeadroomRules.OK, HeadroomRules.modeOf(null), "absent means unknown, not spent")
    }

    @Test
    fun `the heads-up boundary is inclusive`() {
        assertEquals(HeadroomRules.OK, HeadroomRules.modeOf(HeadroomWorst(percent = 84.9)))
        assertEquals(HeadroomRules.WARN, HeadroomRules.modeOf(HeadroomWorst(percent = 85.0)))
    }

    @Test
    fun `the ladder boundary is inclusive and outranks warn`() {
        assertEquals(HeadroomRules.WARN, HeadroomRules.modeOf(HeadroomWorst(percent = 91.9)))
        assertEquals(HeadroomRules.RED, HeadroomRules.modeOf(HeadroomWorst(percent = 92.0)))
    }

    @Test
    fun `a full window is exhausted, not merely red`() {
        assertEquals(HeadroomRules.EXHAUSTED, HeadroomRules.modeOf(HeadroomWorst(percent = 100.0)))
        assertEquals(HeadroomRules.EXHAUSTED, HeadroomRules.modeOf(HeadroomWorst(percent = 140.0)))
    }

    @Test
    fun `the owner's thresholds win over the defaults`() {
        val s = HeadroomSettings(headsUpPct = 50, ladderPct = 60)
        assertEquals(HeadroomRules.WARN, HeadroomRules.modeOf(HeadroomWorst(percent = 55.0), s))
        assertEquals(HeadroomRules.RED, HeadroomRules.modeOf(HeadroomWorst(percent = 60.0), s))
    }

    @Test
    fun `Claude's own exceeded word beats the rounded percentage`() {
        // The server rounds; 99.4 is spent when it says so.
        assertEquals(HeadroomRules.EXHAUSTED, HeadroomRules.modeOf(99.4, "exceeded"))
        assertEquals(HeadroomRules.RED, HeadroomRules.modeOf(99.4, "critical"))
    }

    @Test
    fun `the colour key stays in the meter's existing vocabulary`() {
        assertEquals("critical", HeadroomRules.severityColorKey(HeadroomRules.EXHAUSTED))
        assertEquals("high", HeadroomRules.severityColorKey(HeadroomRules.RED))
        assertEquals("warning", HeadroomRules.severityColorKey(HeadroomRules.WARN))
        assertEquals("normal", HeadroomRules.severityColorKey(HeadroomRules.OK))
        assertEquals("normal", HeadroomRules.severityColorKey(null), "an unknown mode is not an alarm")
    }

    // ------------------------------------------------------------ pillText

    @Test
    fun `no headroom block hides the pill`() {
        assertNull(HeadroomRules.pillText(null, NOW_3H12M), "an older daemon draws no pill")
        assertNull(
            HeadroomRules.pillText(StatusHeadroom(worstPercent = null), NOW_3H12M),
            "a block with no reading is not a reading of zero",
        )
    }

    @Test
    fun `the pill names the window, the percentage and the countdown`() {
        assertEquals("Fable 92% · resets 3h", HeadroomRules.pillText(status(), NOW_3H12M))
    }

    @Test
    fun `the pill drops the countdown rather than faking one`() {
        assertEquals("Fable 92%", HeadroomRules.pillText(status(resetsAt = null), NOW_3H12M))
        assertEquals("Fable 92%", HeadroomRules.pillText(status(resetsAt = "soon"), NOW_3H12M))
    }

    @Test
    fun `never render a 1970 reset`() {
        // Every `resetsAt` on the wire is nullable and every `at` defaults to 0,
        // so "absent" reaches here as the epoch. Counting from it prints five
        // decades, which the reader cannot tell from a real reading.
        assertEquals(
            "Fable 92%",
            HeadroomRules.pillText(status(resetsAt = "1970-01-01T00:00:00Z"), NOW_3H12M),
            "the epoch is absence, not a reset in 20800 days",
        )
        assertNull(HeadroomRules.coarseUntil("1970-01-01T00:00:00Z", NOW_3H12M))
        assertNull(HeadroomRules.shortUntil("1970-01-01T00:00:00+00:00", NOW_3H12M))
    }

    @Test
    fun `a weekly all-models window is not a scope`() {
        assertEquals(
            "Weekly 88% · resets 12m",
            HeadroomRules.pillText(status(percent = 87.6, label = "Current week (all models)"), NOW_12M),
        )
    }

    @Test
    fun `the window words keep the scope and drop the scaffolding`() {
        assertEquals("Fable", HeadroomRules.windowWords(FABLE))
        assertEquals("Session", HeadroomRules.windowWords("Current session"))
        assertEquals("Weekly", HeadroomRules.windowWords("Current week"))
        assertEquals("Usage", HeadroomRules.windowWords(null), "never a blank chip")
        assertEquals("Fable", HeadroomRules.windowKeyWords("weekly_fable"))
        assertEquals("Session", HeadroomRules.windowKeyWords("session"))
        assertEquals("Usage", HeadroomRules.windowKeyWords("something_new"))
    }

    // --------------------------------------------------------- countdowns

    @Test
    fun `a coarse countdown is one unit and a short one is two`() {
        assertEquals("3h", HeadroomRules.coarseUntil(RESET, NOW_3H12M))
        assertEquals("3h 12m", HeadroomRules.shortUntil(RESET, NOW_3H12M))
        assertEquals("2d", HeadroomRules.coarseUntil(RESET, NOW_2D4H))
        assertEquals("2d 4h", HeadroomRules.shortUntil(RESET, NOW_2D4H))
        assertEquals("12m", HeadroomRules.coarseUntil(RESET, NOW_12M))
        assertEquals("12m", HeadroomRules.shortUntil(RESET, NOW_12M))
    }

    @Test
    fun `a reset already past reads as now, not as a negative`() {
        assertEquals("now", HeadroomRules.coarseUntil(RESET, NOW_PAST))
        assertEquals("now", HeadroomRules.shortUntil(RESET, RESET_MS))
    }

    @Test
    fun `a percentage is rounded, because a pill is not a gauge`() {
        assertEquals("92%", HeadroomRules.percentWords(91.5))
        assertEquals("91%", HeadroomRules.percentWords(91.4))
        assertEquals("0%", HeadroomRules.percentWords(0.0))
    }

    // --------------------------------------------------------- sessionMark

    @Test
    fun `a laddered session says what it is on and what it was moved for`() {
        val h = SessionHeadroom(family = "fable", ladder = "opus")
        assertEquals("on opus · Fable 92%", HeadroomRules.sessionMark(h, status(), NOW_3H12M))
    }

    @Test
    fun `without a reading the mark is shorter, not wrong`() {
        val h = SessionHeadroom(family = "fable", ladder = "opus")
        assertEquals("on opus", HeadroomRules.sessionMark(h))
    }

    @Test
    fun `a stalled session shows the clock Claude itself printed`() {
        val h = SessionHeadroom(family = "fable", stalled = true)
        assertEquals(
            "waiting for the limit to reset (10:10pm)",
            HeadroomRules.sessionMark(h, status(), NOW_3H12M, resetClock = "10:10pm"),
            "the clock is the daemon's, lifted from the error text",
        )
    }

    @Test
    fun `without a clock a stalled session counts down instead`() {
        val h = SessionHeadroom(family = "fable", stalled = true)
        assertEquals(
            "waiting for the limit to reset (in 3h 12m)",
            HeadroomRules.sessionMark(h, status(), NOW_3H12M),
        )
    }

    @Test
    fun `knowing nothing about the reset still promises the resume`() {
        val h = SessionHeadroom(family = "fable", stalled = true)
        assertEquals("resumes on reset", HeadroomRules.sessionMark(h, status(resetsAt = null), NOW_3H12M))
    }

    @Test
    fun `auto-resume off is a different sentence, not a missing one`() {
        val h = SessionHeadroom(family = "fable", stalled = true, autoResume = false)
        assertEquals(
            "stopped at the usage limit",
            HeadroomRules.sessionMark(h, status(), NOW_3H12M, resetClock = "10:10pm"),
            "stopped and waiting are opposite things to do about it",
        )
    }

    @Test
    fun `a stall outranks a ladder move`() {
        val h = SessionHeadroom(family = "fable", ladder = "opus", stalled = true)
        assertEquals("resumes on reset", HeadroomRules.sessionMark(h, status(resetsAt = null), NOW_3H12M))
    }

    @Test
    fun `an ordinary session has no mark at all`() {
        assertNull(HeadroomRules.sessionMark(null), "an older daemon sends no cell")
        assertNull(HeadroomRules.sessionMark(SessionHeadroom(family = "fable")))
        assertNull(HeadroomRules.sessionMark(SessionHeadroom(family = "fable", ladder = "  ")))
    }

    @Test
    fun `resume words are null for a session that is not stalled`() {
        assertNull(HeadroomRules.resumeWords(stalled = false, autoResume = true, resetClock = "10:10pm"))
    }
}
