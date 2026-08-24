package com.silencelen.huginn

import com.silencelen.huginn.data.Round
import com.silencelen.huginn.data.RoundSchedule
import com.silencelen.huginn.ui.RoundDraft
import com.silencelen.huginn.ui.cadencePreview
import com.silencelen.huginn.ui.isClockTime
import com.silencelen.huginn.ui.problem
import com.silencelen.huginn.ui.toDraft
import com.silencelen.huginn.ui.toSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The form behind a scheduled job.
 *
 * These rules MIRROR the daemon's `validateSchedule`; they do not replace it. The
 * value of testing them is that a person is told what is wrong while they are
 * still looking at the field — a save that comes back 400 and discards the typing
 * is the failure this is here to prevent.
 */
class RoundDraftTest {

    private val ok = RoundDraft(title = "Health check", prompt = "Read the alerts.")

    @Test
    fun aUsableDraftHasNoComplaint() {
        assertNull(ok.problem())
    }

    @Test
    fun theFirstMissingThingIsTheOnlyThingSaid() {
        // Not a list of six complaints about a half-filled form: the NEXT thing to
        // fix is the useful answer, and the order matches the way the form reads.
        assertEquals("Give it a name.", ok.copy(title = "", prompt = "").problem())
        assertEquals("Say what it should do.", ok.copy(prompt = "  ").problem())
    }

    @Test
    fun aTimeMustLookLikeATime() {
        assertTrue(isClockTime("00:00"))
        assertTrue(isClockTime("23:59"))
        assertTrue(isClockTime(" 19:00 "), "a trimmed field is still a time")
        assertFalse(isClockTime("9:00"), "the daemon wants two digits and so do we")
        assertFalse(isClockTime("24:00"))
        assertFalse(isClockTime("19:60"))
        assertFalse(isClockTime("19-00"))
        assertFalse(isClockTime(""))
        assertNotNull(ok.copy(at = "7pm").problem())
    }

    @Test
    fun aWeeklyRoundNeedsADayAndAMonthlyOneNeedsADate() {
        assertNotNull(ok.copy(kind = "weekly", days = emptySet()).problem())
        assertNotNull(ok.copy(kind = "monthly", dates = emptySet()).problem())
        assertNull(ok.copy(kind = "monthly", dates = setOf(1, 15)).problem())
        // Daily asks for neither, so neither being empty may block it.
        assertNull(ok.copy(kind = "daily", days = emptySet(), dates = emptySet()).problem())
    }

    @Test
    fun anIntervalIsBounded() {
        assertNotNull(ok.copy(kind = "interval", everyMinutes = "").problem())
        assertNotNull(ok.copy(kind = "interval", everyMinutes = "4").problem())
        assertNull(ok.copy(kind = "interval", everyMinutes = "5").problem())
        assertNull(ok.copy(kind = "interval", everyMinutes = "10080").problem())
        assertNotNull(ok.copy(kind = "interval", everyMinutes = "10081").problem())
        // And a broken clock time cannot block it: an interval has no wall clock.
        assertNull(ok.copy(kind = "interval", everyMinutes = "60", at = "nonsense").problem())
    }

    @Test
    fun aScheduleCarriesOnlyWhatItsKindMeans() {
        val weekly = ok.copy(kind = "weekly", days = setOf(3, 0), at = "19:00").toSchedule("America/Los_Angeles")
        assertEquals("weekly", weekly.kind)
        assertEquals(listOf(0, 3), weekly.days, "sorted, because the daemon sorts and a diff should not")
        assertEquals("America/Los_Angeles", weekly.tz)

        val interval = ok.copy(kind = "interval", everyMinutes = "90").toSchedule("America/Los_Angeles")
        assertEquals(90, interval.everyMinutes)
        // No zone and no time: an interval counts minutes, so there is no wall
        // clock to place and nothing for a zone to get wrong.
        assertNull(interval.tz)
        assertNull(interval.at)
    }

    @Test
    fun noZoneIsSentWhenTheClientHasNoneToGive() {
        // The shared editor is multiplatform and has no calendar. Null here means
        // "the host's zone", which is where the Round actually fires.
        assertNull(ok.copy(kind = "daily").toSchedule(null).tz)
    }

    @Test
    fun anExistingRoundOpensOnItsOwnValues() {
        val r = Round(
            id = "r1", title = "T", prompt = "P", goal = "G", mode = "act",
            notifyWhen = "always", host = "dev-1",
            schedule = RoundSchedule(kind = "weekly", at = "19:00", days = listOf(0)),
        )
        val d = r.toDraft()
        assertEquals("T", d.title); assertEquals("G", d.goal)
        assertEquals("act", d.mode); assertEquals("always", d.notifyWhen)
        assertEquals("dev-1", d.host)
        assertEquals(setOf(0), d.days)
        assertEquals("19:00", d.at)
    }

    @Test
    fun aMalformedStoredTimeDoesNotOpenTheFormInAnError() {
        // Whatever is on the server, the person opening the form did not type it,
        // and greeting them with their own supposed mistake is a lie.
        val r = Round(id = "r", title = "T", prompt = "P", schedule = RoundSchedule(kind = "daily", at = "7pm"))
        assertTrue(isClockTime(r.toDraft().at))
        assertNull(r.toDraft().copy(title = "T", prompt = "P").problem())
    }

    @Test
    fun thePreviewSaysWhatTheSchedulesMean() {
        assertEquals("Sun at 7:00 PM", ok.copy(kind = "weekly", days = setOf(0), at = "19:00").cadencePreview())
        assertEquals(
            "Every day at 7:00 AM",
            ok.copy(kind = "weekly", days = (0..6).toSet(), at = "07:00").cadencePreview(),
        )
        assertEquals("Every day at 12:00 AM", ok.copy(kind = "daily", at = "00:00").cadencePreview())
        assertEquals("Every day at 12:00 PM", ok.copy(kind = "daily", at = "12:00").cadencePreview())
        assertEquals("The 1st, 15th at 9:00 AM", ok.copy(kind = "monthly", dates = setOf(15, 1), at = "09:00").cadencePreview())
        assertEquals("Every 2 hours", ok.copy(kind = "interval", everyMinutes = "120").cadencePreview())
        assertEquals("Every hour", ok.copy(kind = "interval", everyMinutes = "60").cadencePreview())
        assertEquals("Every day", ok.copy(kind = "interval", everyMinutes = "1440").cadencePreview())
        assertEquals("Every 45 minutes", ok.copy(kind = "interval", everyMinutes = "45").cadencePreview())
    }

    @Test
    fun thePreviewNeverPretendsAnEmptyFormIsASchedule() {
        assertEquals("Pick a day", ok.copy(kind = "weekly", days = emptySet()).cadencePreview())
        assertEquals("Pick a date", ok.copy(kind = "monthly", dates = emptySet()).cadencePreview())
        assertEquals("Every … minutes", ok.copy(kind = "interval", everyMinutes = "").cadencePreview())
    }
}
