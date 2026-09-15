package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HeadroomHeld
import com.silencelen.huginn.data.HeadroomWorst
import com.silencelen.huginn.data.StatusHeadroom
import com.silencelen.huginn.data.TranscriptEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the two always-on headroom surfaces DRAW: the pill in the status line and
 * the limit notice in the transcript.
 *
 * Both are decided by pure functions beside the composables for the same reason
 * every other view rule in this module is — the composables cannot be asserted
 * without a window, and the mistakes are never in the pixels.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class HeadroomViewsTest {

    private val nowMs = 1_800_000_000_000L

    /**
     * Three hours and ten minutes past [nowMs], as the ISO instant the daemon
     * sends. Built from the clock rather than hard-coded so the arithmetic the
     * countdown is asserting against is visible in the test.
     */
    private val inThreeHours = isoOf(nowMs + 3 * 3_600_000L + 600_000L)

    private fun status(
        percent: Double? = 92.0,
        label: String? = "Current week (Fable)",
        resetsAt: String? = inThreeHours,
        mode: String = "red",
        paused: Int = 0,
    ) = StatusHeadroom(
        worstPercent = percent,
        worstLabel = label,
        nextResetAt = resetsAt,
        mode = mode,
        paused = paused,
    )

    // ------------------------------------------------------------- the pill

    @Test
    fun `the pill hides when the host has no headroom to report`() {
        // The compat answer for a daemon older than 3.0.0: `status.headroom` is
        // absent, and a pill drawn at 0 % would claim somebody looked and found
        // plenty — the one thing this surface must never say by accident.
        assertNull(headroomPill(null, nowMs))
    }

    @Test
    fun `a headroom block with no reading yet also draws nothing`() {
        // Present-but-empty is the other way to get here: the subsystem exists and
        // has not read a window. Same treatment, for the same reason.
        assertNull(headroomPill(StatusHeadroom(), nowMs))
        assertNull(headroomPill(status(percent = null), nowMs))
    }

    @Test
    fun `the pill names the window, the number and the countdown`() {
        val face = headroomPill(status(), nowMs)
        assertEquals("Fable 92% · resets 3h", face?.text)
    }

    @Test
    fun `the pill colours by the daemon's mode, not by a second opinion`() {
        // `exceeded` arrives while the percentage still reads 99.4 — rounding, on a
        // number the server computed. The word has to win or the pill says amber
        // over a window that is spent.
        assertEquals("critical", headroomPill(status(percent = 99.4, mode = "exhausted"), nowMs)?.severity)
        assertEquals("high", headroomPill(status(mode = "red"), nowMs)?.severity)
        assertEquals("warning", headroomPill(status(mode = "warn"), nowMs)?.severity)
        assertEquals("normal", headroomPill(status(percent = 12.0, mode = "ok"), nowMs)?.severity)
    }

    @Test
    fun `a missing reset time shortens the pill rather than inventing one`() {
        // An absent instant reaches here as null or as the epoch, and a countdown
        // from the epoch renders "resets 20800d".
        assertEquals("Fable 92%", headroomPill(status(resetsAt = null), nowMs)?.text)
        assertEquals("Fable 92%", headroomPill(status(resetsAt = isoOf(0)), nowMs)?.text)
    }

    @Test
    fun `held spawns ride along, because nothing else on screen would say so`() {
        assertEquals(2, headroomPill(status(paused = 2), nowMs)?.paused)
        assertEquals(0, headroomPill(status(), nowMs)?.paused)
    }

    // ------------------------------------------- the full answer, pill-shaped

    @Test
    fun `the full headroom answer folds into the pill's shape`() {
        val h = Headroom(
            mode = "red",
            worst = HeadroomWorst(
                slug = "jacob",
                window = "weekly_fable",
                percent = 92.0,
                label = "Current week (Fable)",
                resetsAt = inThreeHours,
            ),
            held = listOf(HeadroomHeld(agentId = "agent-aa", since = 1)),
        )
        val s = statusHeadroomOf(h)
        assertEquals("Fable 92% · resets 3h", headroomPill(s, nowMs)?.text)
        assertEquals(1, s?.paused)
        // Nothing to report and no subsystem at all are the same answer here.
        assertNull(statusHeadroomOf(null))
        assertNull(statusHeadroomOf(Headroom()))
    }

    @Test
    fun `a worst window with no label still names itself from its key`() {
        val s = statusHeadroomOf(Headroom(worst = HeadroomWorst(window = "session", percent = 80.0)))
        assertEquals("Session 80%", headroomPill(s, nowMs)?.text)
    }

    // ----------------------------------------------------- the limit notice

    @Test
    fun `a 429 is a limit notice and an ordinary answer is not`() {
        // The kind cannot separate them: Claude Code writes its own usage error
        // into the transcript as an assistant record, exactly like an answer.
        val limit = TranscriptEvent(
            seq = 4,
            kind = "assistant",
            text = "You've hit your session limit · resets 10:10pm (America/Los_Angeles)",
            apiError = 429,
        )
        assertTrue(isLimitNotice(limit))

        val spoke = TranscriptEvent(seq = 5, kind = "assistant", text = "Done — the deploy is green.")
        assertFalse(isLimitNotice(spoke), "an ordinary assistant record is a bubble, not a notice")

        // A genuine failure is not headroom and must not be dressed as waiting.
        val broke = TranscriptEvent(seq = 6, kind = "assistant", text = "overloaded", apiError = 529)
        assertFalse(isLimitNotice(broke))
    }

    @Test
    fun `the notice repeats the clock Claude printed and never formats one`() {
        val ev = TranscriptEvent(
            seq = 4,
            kind = "assistant",
            text = "You've hit your session limit · resets 10:10pm (America/Los_Angeles)",
            apiError = 429,
        )
        assertEquals("10:10pm", limitResetClock(ev.text))
        assertEquals("Usage limit hit · resets 10:10pm", limitNoticeTitle(ev))

        // The other observed shape says nothing about a clock, and a headline with
        // a dangling "resets" is worse than a short one.
        val weekly = TranscriptEvent(
            seq = 5,
            kind = "assistant",
            text = "You are out of usage credits for Fable 5.1",
            apiError = 429,
        )
        assertNull(limitResetClock(weekly.text))
        assertEquals("Usage limit hit", limitNoticeTitle(weekly))
    }

    private fun isoOf(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
