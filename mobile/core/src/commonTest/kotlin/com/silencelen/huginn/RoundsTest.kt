package com.silencelen.huginn

import com.silencelen.huginn.data.Round
import com.silencelen.huginn.data.RoundItem
import com.silencelen.huginn.data.RoundRun
import com.silencelen.huginn.ui.RoundStatus
import com.silencelen.huginn.ui.agoWords
import com.silencelen.huginn.ui.canAcknowledge
import com.silencelen.huginn.ui.itemCountWords
import com.silencelen.huginn.ui.isAcknowledged
import com.silencelen.huginn.ui.roundLastLine
import com.silencelen.huginn.ui.roundStatusLabel
import com.silencelen.huginn.ui.roundStatusOf
import com.silencelen.huginn.ui.roundSubtitle
import com.silencelen.huginn.ui.untilWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `a capped run says both numbers`() {
        // The daemon keeps 20 items and records how many there really were.
        // Rendering the kept count as the authoritative one put "20 items"
        // directly under a headline saying 500 -- two contradicting numbers on
        // one screen, and the one an operator acts on was the wrong one.
        val run = RoundRun(
            status = "action",
            headline = "500 things need you",
            items = List(20) { RoundItem(title = "item $it") },
            itemsTotal = 500,
        )
        assertEquals("500 items, showing 20", itemCountWords(run))
    }

    @Test
    fun `an uncapped run says one number`() {
        val run = RoundRun(items = List(4) { RoundItem(title = "x") }, itemsTotal = 4)
        assertEquals("4 items", itemCountWords(run))
        assertEquals("1 item", itemCountWords(RoundRun(items = listOf(RoundItem(title = "x")), itemsTotal = 1)))
    }

    @Test
    fun `an older daemon that sends no total falls back to the list`() {
        // itemsTotal defaults to 0 off the wire. Trusting it blindly would make
        // every run from a daemon older than this field render as no items at
        // all -- a clean-looking row over a report that has findings in it.
        val run = RoundRun(items = List(3) { RoundItem(title = "x") })
        assertEquals("3 items", itemCountWords(run))
    }

    @Test
    fun `a clean run says nothing rather than zero`() {
        // An empty list is the NORMAL outcome of a healthy round, so a zero here
        // would be a number to read where there is nothing to say.
        assertNull(itemCountWords(RoundRun(status = "ok", headline = "all clear")))
        assertNull(itemCountWords(null))
    }

    @Test
    fun `a read report says so instead of repeating its verdict`() {
        // ⚠ THE GAP THIS FILLS: a report saying `action` is true the moment it is
        // written and stays true forever, because nothing could ever say
        // otherwise. The row held a red mark about findings already read and
        // worked through, and the only thing that would clear it was the next
        // run -- which for still-open findings said `action` again. A signal that
        // cannot be answered stops being a signal.
        assertEquals("Needs you", roundStatusLabel(RoundStatus.ACTION))
        assertEquals("read", roundStatusLabel(RoundStatus.ACTION, acknowledged = true))
        // The verdict is NOT rewritten anywhere -- it was true when written and
        // still is. What changes is that somebody has answered it.
        assertEquals("read", roundStatusLabel(RoundStatus.UNKNOWN, acknowledged = true))
    }

    @Test
    fun `an acknowledgement belongs to a run, so a new report arrives unread`() {
        // The design in one assertion. lastRun is replaced wholesale when a Round
        // fires, so this clears itself; held on the Round it would need code to
        // remember, and that code would eventually not run.
        val read = round(last = RoundRun(at = now / 1000, status = "action", headline = "h",
            acknowledgedAt = now / 1000))
        assertTrue(isAcknowledged(read.lastRun))
        assertFalse(canAcknowledge(read), "already marked - the control becomes Undo")

        val fresh = read.copy(lastRun = RoundRun(at = now / 1000, status = "action", headline = "next week"))
        assertFalse(isAcknowledged(fresh.lastRun), "a NEW report inherited the old acknowledgement")
        assertTrue(canAcknowledge(fresh))
    }

    @Test
    fun `a clean run is never offered the control`() {
        // Nothing to acknowledge on an all-clear, and a control on every row is
        // a control nobody reads -- the same rule as the host badge.
        assertFalse(canAcknowledge(round(last = RoundRun(at = now / 1000, status = "ok", headline = "clear"))))
        assertFalse(canAcknowledge(round(last = null)), "nothing has happened yet")
        assertFalse(canAcknowledge(null))
        assertTrue(canAcknowledge(round(last = RoundRun(at = now / 1000, status = "attention", headline = "h"))))
    }
}
