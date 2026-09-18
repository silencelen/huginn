package com.silencelen.huginn

import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.HeadroomStall
import com.silencelen.huginn.data.HeadroomWorst
import com.silencelen.huginn.data.KeepAwakeStatus
import com.silencelen.huginn.data.SessionHeadroom
import com.silencelen.huginn.data.StatusHeadroom
import com.silencelen.huginn.data.UndoResult
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

    // -------------------------------------------------------- keepAwakeLine

    private fun ka(
        running: Boolean? = false,
        resetsAt: String? = null,
        enabled: Boolean = false,
        today: Int = 0,
        clock: String? = null,
    ) = StatusHeadroom(
        mode = "ok",
        windowRunning = running,
        windowResetsAt = resetsAt,
        keepAwake = KeepAwakeStatus(enabled = enabled, keptAwakeToday = today, lastAtClock = clock),
    )

    /**
     * ⚠ ABSENT IS NOT "NO WINDOW". A daemon older than the feature reports
     * nothing here, and a line stating "no window running" with total confidence
     * on behalf of a host that was never asked is worse than no line at all.
     */
    @Test
    fun `an older daemon draws no line rather than a confident wrong one`() {
        assertNull(HeadroomRules.keepAwakeLine(null, RESET_MS))
        assertNull(HeadroomRules.keepAwakeLine(StatusHeadroom(worstPercent = 51.0), RESET_MS))
    }

    @Test
    fun `no window running says exactly that`() {
        assertEquals("no window running", HeadroomRules.keepAwakeLine(ka(running = false), NOW_3H12M))
    }

    /**
     * The window's OWN reset, never the worst window's — they are different
     * instants, and `nextResetAt` is usually the week. Counting down to the wrong
     * one produces a perfectly renderable number that is hours out.
     */
    @Test
    fun `a running window counts down to its own reset`() {
        assertEquals(
            "window running · resets in 3h 12m",
            HeadroomRules.keepAwakeLine(ka(running = true, resetsAt = RESET), NOW_3H12M),
        )
    }

    @Test
    fun `a running window with no reset on the wire still says it is running`() {
        assertEquals("window running", HeadroomRules.keepAwakeLine(ka(running = true), NOW_3H12M))
    }

    /** The epoch guard, here as everywhere: no 1970 countdowns. */
    @Test
    fun `an unparseable reset drops the clause rather than faking one`() {
        assertEquals(
            "window running",
            HeadroomRules.keepAwakeLine(ka(running = true, resetsAt = "soon"), NOW_3H12M),
        )
    }

    /**
     * The spend clause only appears when the feature is ON. A line about what
     * keep-awake spent, on a host that spends nothing, is noise — and the window
     * half is worth showing either way.
     */
    @Test
    fun `nothing is said about keep-awake while it is switched off`() {
        assertEquals(
            "window running · resets in 3h 12m",
            HeadroomRules.keepAwakeLine(ka(running = true, resetsAt = RESET, enabled = false, today = 4), NOW_3H12M),
        )
    }

    @Test
    fun `switched on and never fired today says nothing about it either`() {
        // "kept awake 0× today" is a statistic about an absence.
        assertEquals(
            "no window running",
            HeadroomRules.keepAwakeLine(ka(running = false, enabled = true, today = 0), NOW_3H12M),
        )
    }

    /**
     * ⚠ THE CLOCK IS THE DAEMON'S, verbatim. `:core` is commonMain and has no
     * timezone database; the one time a shell formatted an instant into a wall
     * clock by hand it printed UTC as if it were local.
     */
    @Test
    fun `one ping today names the time the host gave`() {
        assertEquals(
            "window running · resets in 3h 12m · kept awake at 14:32",
            HeadroomRules.keepAwakeLine(
                ka(running = true, resetsAt = RESET, enabled = true, today = 1, clock = "14:32"),
                NOW_3H12M,
            ),
        )
    }

    @Test
    fun `one ping with no clock on the wire still counts itself`() {
        assertEquals(
            "no window running · kept awake once today",
            HeadroomRules.keepAwakeLine(ka(running = false, enabled = true, today = 1), NOW_3H12M),
        )
    }

    /** Past one, the COUNT is the point and the individual times are not. */
    @Test
    fun `several pings today are counted rather than listed`() {
        assertEquals(
            "window running · resets in 3h 12m · kept awake 3× today",
            HeadroomRules.keepAwakeLine(
                ka(running = true, resetsAt = RESET, enabled = true, today = 3, clock = "14:32"),
                NOW_3H12M,
            ),
        )
    }

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

    // ------------------------------------------- the stall record (3.0.0)

    private fun stall(
        at: Long? = 1_789_450_000_000L,
        resetsAt: Long? = RESET_MS,
        resumedAt: Long? = null,
        why: String? = null,
        text: String? = null,
    ) = HeadroomStall(at = at, window = "weekly_fable", resetsAt = resetsAt, resumedAt = resumedAt, why = why, text = text)

    @Test
    fun `the stall record's own text supplies the clock`() {
        val h = SessionHeadroom(family = "fable", stalled = true)
        assertEquals(
            "waiting for the limit to reset (10:10pm)",
            HeadroomRules.sessionMark(
                h, status(), NOW_3H12M,
                stall = stall(text = "You've hit your usage limit · resets 10:10pm (America/Los_Angeles)"),
            ),
            "the one wall clock this object may show is the one that arrived as text",
        )
    }

    @Test
    fun `the stall's own reset is counted down in MILLISECONDS`() {
        val h = SessionHeadroom(family = "fable", stalled = true)
        assertEquals(
            "waiting for the limit to reset (in 3h 12m)",
            HeadroomRules.sessionMark(h, status(resetsAt = null), NOW_3H12M, stall = stall()),
            "`stall.resetsAt` is a millisecond epoch on /v1/headroom, not an ISO string",
        )
    }

    @Test
    fun `a refusal is said in the daemon's own words`() {
        val h = SessionHeadroom(family = "fable", stalled = true, autoResume = false)
        assertEquals(
            "auto-resume is off for this session",
            HeadroomRules.sessionMark(h, status(), NOW_3H12M, stall = stall(why = "auto-resume is off for this session")),
            "`why` is the only place a refusal is ever written down",
        )
        assertEquals(
            "gave up after 3 attempts",
            HeadroomRules.sessionMark(h, status(), NOW_3H12M, stall = stall(why = "gave up after 3 attempts")),
        )
        // No reason recorded: the old sentence, which is still true.
        assertEquals(
            "stopped at the usage limit",
            HeadroomRules.sessionMark(h, status(), NOW_3H12M, stall = stall()),
        )
    }

    @Test
    fun `a resumed stall is not waiting for anything`() {
        assertNull(
            HeadroomRules.stallWords(stall(resumedAt = 1_789_460_000_000L), autoResume = true, nowMs = NOW_3H12M),
            "it was picked back up; the mark falls through to the ladder",
        )
        assertNull(HeadroomRules.stallWords(null, autoResume = true, nowMs = NOW_3H12M))
        assertNull(
            HeadroomRules.stallWords(stall(at = null), autoResume = true, nowMs = NOW_3H12M),
            "the daemon seeds a blank stall on every session it has ever seen",
        )
    }

    @Test
    fun `no stall record falls back to the list cell's sentence`() {
        val h = SessionHeadroom(family = "fable", stalled = true)
        assertEquals(
            "waiting for the limit to reset (in 3h 12m)",
            HeadroomRules.sessionMark(h, status(), NOW_3H12M, stall = null),
            "a caller with only /v1/sessions still gets the worst window's reset",
        )
    }

    // -------------------------------------------------- millisecond clocks

    @Test
    fun `the millisecond countdowns agree with the ISO ones`() {
        assertEquals(HeadroomRules.coarseUntil(RESET, NOW_3H12M), HeadroomRules.coarseUntilMs(RESET_MS, NOW_3H12M))
        assertEquals("3h", HeadroomRules.coarseUntilMs(RESET_MS, NOW_3H12M))
        assertEquals("12m", HeadroomRules.coarseUntilMs(RESET_MS, NOW_12M))
        assertEquals("2d", HeadroomRules.coarseUntilMs(RESET_MS, NOW_2D4H))
        assertEquals("now", HeadroomRules.coarseUntilMs(RESET_MS, NOW_PAST))
        assertEquals("2d 4h", HeadroomRules.shortUntilMs(RESET_MS, NOW_2D4H))
        assertEquals("3h 12m", HeadroomRules.shortUntilMs(RESET_MS, NOW_3H12M))
    }

    @Test
    fun `a millisecond epoch of zero or null renders nothing at all`() {
        assertNull(HeadroomRules.coarseUntilMs(null, NOW_3H12M), "absent is not 1970")
        assertNull(HeadroomRules.coarseUntilMs(0L, NOW_3H12M))
        assertNull(HeadroomRules.shortUntilMs(0L, NOW_3H12M))
        assertNull(HeadroomRules.shortUntilMs(-1L, NOW_3H12M))
    }

    // ---------------------------------------------------- the clock parse

    @Test
    fun `the reset clock parse is the one implementation`() {
        assertEquals(
            "10:10pm",
            HeadroomRules.resetClockOf("You've hit your usage limit · resets 10:10pm (America/Los_Angeles)"),
        )
        assertEquals("3am", HeadroomRules.resetClockOf("resets 3am."))
        assertNull(HeadroomRules.resetClockOf("Claude is having trouble responding."))
        assertNull(HeadroomRules.resetClockOf(null))
        assertNull(HeadroomRules.resetClockOf("resets "), "a clause with nothing in it says nothing")
    }

    // --------------------------------------------------------- undo words

    @Test
    fun `an applied undo and a queued one are different sentences`() {
        assertEquals(
            "put back on fable",
            HeadroomRules.undoWords(UndoResult(ok = true, applied = true, to = "fable")),
        )
        // ⚠ THE BUG: both answers are `ok:true`, and this one was announced as done.
        assertEquals(
            "will go back to fable at the next turn boundary",
            HeadroomRules.undoWords(UndoResult(ok = true, applied = false, queued = true, to = "fable")),
        )
        assertEquals(
            "will go back at the next turn boundary",
            HeadroomRules.undoWords(UndoResult(ok = true, queued = true)),
        )
        assertEquals(
            "dropped: the session moved on",
            HeadroomRules.undoWords(UndoResult(ok = true, to = "fable", delivery = "dropped: the session moved on")),
            "neither applied nor queued: the daemon's own reason, verbatim",
        )
        assertEquals("could not undo", HeadroomRules.undoWords(UndoResult(ok = true)))
    }

    // ------------------------------------------------------ refresh words

    @Test
    fun `the refresh word is the daemon's except for the one that misreads`() {
        assertEquals(
            "that is the active login",
            HeadroomRules.refreshWords("active_skipped"),
            "not a failure and not a refresh — the route never refuses the active login",
        )
        assertEquals("refreshed", HeadroomRules.refreshWords("refreshed"))
        assertEquals("refresh_token_expired", HeadroomRules.refreshWords("refresh_token_expired"))
        assertEquals("lock_busy", HeadroomRules.refreshWords("lock_busy"))
        assertEquals(
            "some_word_added_later",
            HeadroomRules.refreshWords("some_word_added_later"),
            "an unknown word shown as itself beats a wrong one",
        )
        assertEquals("no answer", HeadroomRules.refreshWords("  "))
    }
}
