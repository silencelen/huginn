package com.silencelen.huginn

import com.silencelen.huginn.data.Round
import com.silencelen.huginn.data.RoundItem
import com.silencelen.huginn.data.RoundRun
import com.silencelen.huginn.ui.RoundStatus
import com.silencelen.huginn.ui.agoWords
import com.silencelen.huginn.ui.roundLastLine
import com.silencelen.huginn.ui.roundStatusLabel
import com.silencelen.huginn.ui.roundStatusOf
import com.silencelen.huginn.ui.roundSubtitle
import com.silencelen.huginn.ui.untilWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wording both clients show for a Round. Shared so a phone and a desktop
 * cannot describe the same schedule differently — the divergence the :core
 * extraction exists to prevent.
 */
class RoundsTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun round(
        enabled: Boolean = true,
        running: Boolean = false,
        next: Long? = now + 4 * hour,
        last: RoundRun? = null,
    ) = Round(
        id = "r1",
        title = "Telegram health check",
        cadence = "Sundays at 7:00 PM",
        enabled = enabled,
        running = running,
        nextRunAt = next,
        lastRun = last,
    )

    @Test
    fun theNextRunIsRoundedToTheLargestUsefulUnit() {
        assertEquals("not scheduled", untilWords(null, now))
        assertEquals("not scheduled", untilWords(0, now))
        assertEquals("due now", untilWords(now - 5000, now))
        assertEquals("in under a minute", untilWords(now + 30_000, now))
        assertEquals("in 30m", untilWords(now + 30 * 60_000, now))
        assertEquals("in 5h", untilWords(now + 5 * hour, now))
        assertEquals("tomorrow", untilWords(now + 30 * hour, now))
        assertEquals("in 4 days", untilWords(now + 4 * 24 * hour, now))
    }

    @Test
    fun agoWordsTakeSecondsBecauseThatIsWhatTheRecordCarries() {
        // The daemon schedules in milliseconds and stamps records in seconds. Fed
        // the wrong one this reads "19696 days ago", which is the kind of bug that
        // ships because nobody scrolls to a Round that has not run yet.
        assertEquals("1h ago", agoWords(now / 1000 - 3600, now))
        assertEquals("just now", agoWords(now / 1000 - 5, now))
        assertEquals("yesterday", agoWords(now / 1000 - 30 * 3600, now))
        assertEquals("3 days ago", agoWords(now / 1000 - 3 * 24 * 3600, now))
        assertEquals("", agoWords(null, now))
    }

    @Test
    fun anUnrecognisedStatusDegradesInsteadOfVanishing() {
        assertEquals(RoundStatus.OK, roundStatusOf("ok"))
        assertEquals(RoundStatus.ACTION, roundStatusOf("action"))
        assertEquals(RoundStatus.NEVER_RUN, roundStatusOf(null))
        assertEquals(RoundStatus.UNKNOWN, roundStatusOf("something-new"),
            "an older client meeting a newer daemon must still draw a row")
        assertTrue(roundStatusLabel(RoundStatus.ACTION).isNotBlank())
    }

    @Test
    fun theSubtitleSaysWhatTheRoundIsDoingRightNow() {
        assertEquals("Sundays at 7:00 PM · in 4h", roundSubtitle(round(), now))
        assertEquals("Sundays at 7:00 PM · paused", roundSubtitle(round(enabled = false), now))
        assertEquals("Sundays at 7:00 PM · running now", roundSubtitle(round(running = true), now))
    }

    @Test
    fun aPausedRoundReadsAsPausedEvenWithAStaleSlot() {
        // Disabling deliberately leaves nextRunAt where it was, so the subtitle
        // must not fall through to "due now" for something switched off.
        assertEquals("Sundays at 7:00 PM · paused",
            roundSubtitle(round(enabled = false, next = now - 99 * hour), now))
    }

    @Test
    fun theLastLineIsTheReportOrAnInvitation() {
        assertEquals("No runs yet", roundLastLine(round()))
        val ok = round(last = RoundRun(at = now / 1000, status = "ok", headline = "All quiet"))
        assertEquals("All quiet", roundLastLine(ok))
    }

    @Test
    fun aBrokenReportIsCalledOutRatherThanSmoothedOver() {
        // A Round whose contract broke must not look like a clean week.
        val bad = round(last = RoundRun(at = now / 1000, status = "unknown",
            headline = "I had a look and things seem fine", malformed = true))
        assertTrue(roundLastLine(bad).startsWith("Unreported: "), roundLastLine(bad))
    }

    @Test
    fun itemsSurviveTheRoundTrip() {
        val r = round(last = RoundRun(at = now / 1000, status = "action", headline = "h",
            items = listOf(RoundItem(title = "t", detail = "d", suggest = "do it"))))
        assertEquals("do it", r.lastRun?.items?.first()?.suggest)
    }
}
