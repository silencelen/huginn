package com.silencelen.huginn

import com.silencelen.huginn.data.localTimeFormat
import com.silencelen.huginn.ui.TimeFormat
import com.silencelen.huginn.ui.TimeWords
import com.silencelen.huginn.ui.agoWords
import com.silencelen.huginn.ui.agoWordsMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one time vocabulary, asserted band by band.
 *
 * ⚠ WHY THIS FILE IS LONG. Five separate time formatters had grown across the
 * two shells and `:core` (`relTime` twice, `agoWords`/`agoWordsMs`,
 * `humanDuration`, `timeTip`), each with its own bands and its own idea of what
 * a missing stamp means. Collapsing them is only safe if every band boundary is
 * pinned here, because after this the shells have nothing of their own left to
 * disagree with.
 *
 * Every case uses a FIXED instant — 2026-09-17 14:32:00 UTC, a Thursday — and an
 * explicit [TimeFormat]. Nothing here reads the machine clock or the machine
 * zone, so a test that passes in London passes in Auckland and at both DST
 * edges.
 */
class TimeWordsTest {

    private val now = 1_789_655_520_000L // 2026-09-17 14:32:00 UTC, a Thursday
    private val utc = TimeFormat(tzOffsetSec = 0, hour24 = true)

    private fun secAgo(s: Long) = now / 1000 - s

    // ------------------------------------------------------- the missing stamp

    /**
     * ⚠ THE RULE THE WHOLE FEATURE EXISTS UNDER. `lib/transcript.js:439` maps an
     * absent or unparseable timestamp — and a literal epoch 0 — to null, so a
     * formatter that trusts its input renders "1 Jan 1970" on a real transcript
     * row. Every entry point returns the empty string instead, and the caller
     * draws nothing.
     */
    @Test
    fun aMissingStampRendersNothingFromEveryEntryPoint() {
        for (bad in listOf<Long?>(null, 0L, -1L, -86_400L)) {
            assertEquals("", TimeWords.short(bad, now), "short($bad)")
            assertEquals("", TimeWords.ago(bad, now), "ago($bad)")
            assertEquals("", TimeWords.agoMs(bad, now), "agoMs($bad)")
            assertEquals("", TimeWords.stamp(bad, now, utc), "stamp($bad)")
            assertEquals("", TimeWords.stampMs(bad, now, utc), "stampMs($bad)")
            assertEquals("", TimeWords.full(bad, utc), "full($bad)")
            assertEquals("", TimeWords.shortMs(bad, now), "shortMs($bad)")
        }
    }

    /** The same rule, stated the way a reader would notice it being broken. */
    @Test
    fun nothingEverRenders1970() {
        val everything = buildList {
            for (bad in listOf<Long?>(null, 0L, -1L, -86_400L, -1_000_000L)) {
                add(TimeWords.short(bad, now))
                add(TimeWords.shortMs(bad, now))
                add(TimeWords.ago(bad, now))
                add(TimeWords.agoMs(bad, now))
                add(TimeWords.stamp(bad, now, utc))
                add(TimeWords.stampMs(bad, now, utc))
                add(TimeWords.full(bad, utc))
                add(TimeWords.stamp(bad, now, TimeFormat(-8 * 3600, hour24 = false)))
            }
        }
        for (s in everything) assertFalse(s.contains("1970"), "rendered the epoch: \"$s\"")
        assertTrue(everything.all { it.isEmpty() }, everything.toString())
    }

    /**
     * The shipped [agoWordsMs] already held this rule; it is re-asserted through
     * the new names so that collapsing the two cannot quietly drop it.
     */
    @Test
    fun theShippedAgoWordsAgreeWithTheNewOnes() {
        assertEquals("", agoWordsMs(null, now))
        assertEquals("", agoWordsMs(0L, now))
        assertEquals("", agoWordsMs(-1L, now))
        assertEquals(TimeWords.agoMs(now - 3_600_000, now), agoWordsMs(now - 3_600_000, now))
        assertEquals(TimeWords.ago(secAgo(3600), now), agoWords(secAgo(3600), now))
    }

    // ------------------------------------------------------------ clock skew

    /**
     * A host a few minutes ahead of the phone is ordinary; a message stamped in
     * the future is not a message from the future. Inside the grace the words
     * say "just now"; beyond it the clock is shown rather than a negative
     * duration, because a stamp that far out is a broken clock somewhere and the
     * reader is better served by the raw figure than by a soothing phrase.
     */
    @Test
    fun aStampSlightlyInTheFutureReadsAsNow() {
        assertEquals("just now", TimeWords.stamp(secAgo(-60), now, utc), "1 min ahead")
        assertEquals("just now", TimeWords.stamp(secAgo(-299), now, utc), "just inside the grace")
        assertEquals("just now", TimeWords.ago(secAgo(-60), now))
        assertEquals("now", TimeWords.short(secAgo(-60), now))
    }

    @Test
    fun aStampFarInTheFutureShowsTheClockRatherThanANegative() {
        // 2026-09-17 15:32 UTC — an hour ahead of "now", same civil day.
        val s = TimeWords.stamp(secAgo(-3600), now, utc)
        assertEquals("15:32", s)
        assertFalse(s.contains("-"), s)
        // Two days ahead is still not "in 2 days" — the ladder has no future
        // vocabulary, and a date is the honest answer.
        assertEquals("19 Sep 14:32", TimeWords.stamp(secAgo(-2 * 86_400), now, utc))
    }

    // ------------------------------------------------------- the stamp ladder

    @Test
    fun recentStampsAreRelative() {
        assertEquals("just now", TimeWords.stamp(secAgo(0), now, utc))
        assertEquals("just now", TimeWords.stamp(secAgo(59), now, utc))
        assertEquals("1m ago", TimeWords.stamp(secAgo(60), now, utc))
        assertEquals("14m ago", TimeWords.stamp(secAgo(14 * 60), now, utc))
        assertEquals("59m ago", TimeWords.stamp(secAgo(3599), now, utc))
        assertEquals("1h ago", TimeWords.stamp(secAgo(3600), now, utc))
        assertEquals("2h ago", TimeWords.stamp(secAgo(2 * 3600), now, utc))
        assertEquals("5h ago", TimeWords.stamp(secAgo(5 * 3600 + 59 * 60), now, utc))
    }

    /**
     * Past six hours, "7h ago" stops being easier to read than the clock — the
     * reader has to subtract either way, and only one of the two answers is a
     * fact about when the message was sent.
     */
    @Test
    fun earlierTodayIsAClockTime() {
        assertEquals("08:12", TimeWords.stamp(1_789_632_720, now, utc))
        // Exactly on the boundary: six hours back is 08:32 the same day.
        assertEquals("08:32", TimeWords.stamp(secAgo(6 * 3600), now, utc))
    }

    @Test
    fun yesterdayIsNamedAndDatedTogether() {
        assertEquals("Yesterday 21:40", TimeWords.stamp(1_789_594_800, now, utc))
    }

    @Test
    fun insideAWeekTheWeekdayCarriesIt() {
        // 2026-09-13 09:05 UTC is a Sunday, four days back.
        assertEquals("Sun 09:05", TimeWords.stamp(1_789_290_300, now, utc))
        // Six days back is the last day the weekday is unambiguous.
        assertEquals("Fri 14:32", TimeWords.stamp(secAgo(6 * 86_400), now, utc))
        // Seven is not: "Thu" would be today's name.
        assertEquals("10 Sep 14:32", TimeWords.stamp(secAgo(7 * 86_400), now, utc))
    }

    @Test
    fun sameYearDropsTheYearAndOtherYearsKeepIt() {
        assertEquals("3 Sep 14:05", TimeWords.stamp(1_788_444_300, now, utc))
        assertEquals("3 Sep 2025 14:05", TimeWords.stamp(1_756_908_300, now, utc))
    }

    /**
     * The year boundary is a CALENDAR question, not a 365-day one: 60 seconds
     * either side of new year is "yesterday", and eleven months back inside the
     * same year still drops the year.
     */
    @Test
    fun theYearBandIsCivilNotElapsed() {
        // 2026-01-01 14:00 UTC, far enough past midnight to be out of the
        // relative bands — the point is which CALENDAR band 23:59 lands in.
        val newYear = 1_767_276_000_000L
        assertEquals("Yesterday 23:59", TimeWords.stamp(1_767_225_540, newYear, utc))
        assertEquals("13 Jan 14:05", TimeWords.stamp(1_768_313_100, 1_770_683_400_000L, utc))
    }

    // ------------------------------------------------------------- zone + 12h

    @Test
    fun theOffsetMovesTheClockAndCanMoveTheDay() {
        // 22:40 UTC yesterday is 00:40 TODAY at +02:00 — the civil day, not the
        // elapsed hours, decides which band it lands in.
        val lateLastNight = 1_789_598_400L // 2026-09-16 22:40 UTC
        assertEquals("Yesterday 22:40", TimeWords.stamp(lateLastNight, now, utc))
        assertEquals("00:40", TimeWords.stamp(lateLastNight, now, TimeFormat(2 * 3600)))
        // And west of Greenwich the same instant is still the previous evening.
        assertEquals("Yesterday 15:40", TimeWords.stamp(lateLastNight, now, TimeFormat(-7 * 3600)))
    }

    @Test
    fun a12HourLocaleGetsAmAndPm() {
        val twelve = TimeFormat(tzOffsetSec = 0, hour24 = false)
        assertEquals("8:12 am", TimeWords.stamp(1_789_632_720, now, twelve))
        assertEquals("Yesterday 9:40 pm", TimeWords.stamp(1_789_594_800, now, twelve))
        // Midnight and noon are the two a 12-hour clock gets wrong.
        assertEquals("12:00 am", TimeWords.stamp(1_789_603_200, now, twelve)) // 2026-09-17 00:00
        assertEquals("Yesterday 12:00 pm", TimeWords.stamp(1_789_560_000, now, twelve))
    }

    // ----------------------------------------------------------------- full

    @Test
    fun fullIsTheWholeAnswerAndNeedsNoClock() {
        assertEquals("Thursday 17 September 2026, 14:32", TimeWords.full(now / 1000, utc))
        assertEquals("Wednesday 3 September 2025, 14:05", TimeWords.full(1_756_908_300, utc))
        assertEquals("Thursday 17 September 2026, 4:32 pm", TimeWords.full(now / 1000, TimeFormat(2 * 3600, hour24 = false)))
    }

    // ---------------------------------------------------------- the registers

    /**
     * [TimeWords.short] is the same bands in a narrower register — a list row's
     * time column is three characters wide and cannot carry "ago". It is a
     * REGISTER, not a second vocabulary: the boundaries below are the same ones
     * the sentences use.
     */
    @Test
    fun shortIsTheTightRegisterOfTheSameBands() {
        assertEquals("now", TimeWords.short(secAgo(0), now))
        assertEquals("now", TimeWords.short(secAgo(59), now))
        assertEquals("1m", TimeWords.short(secAgo(60), now))
        assertEquals("59m", TimeWords.short(secAgo(3599), now))
        assertEquals("1h", TimeWords.short(secAgo(3600), now))
        assertEquals("23h", TimeWords.short(secAgo(86_399), now))
        assertEquals("1d", TimeWords.short(secAgo(86_400), now))
        assertEquals("9d", TimeWords.short(secAgo(9 * 86_400), now))
    }

    @Test
    fun agoIsTheSentenceRegisterOfTheSameBands() {
        assertEquals("just now", TimeWords.ago(secAgo(5), now))
        assertEquals("14m ago", TimeWords.ago(secAgo(14 * 60), now))
        assertEquals("1h ago", TimeWords.ago(secAgo(3600), now))
        assertEquals("yesterday", TimeWords.ago(secAgo(30 * 3600), now))
        assertEquals("3 days ago", TimeWords.ago(secAgo(3 * 86_400), now))
    }

    /**
     * Seconds and milliseconds are BOTH real on this wire — a Round's `lastRun.at`
     * is seconds, a Device's `lastSeen` is milliseconds — so both units get a
     * named entry point and picking the wrong one is a compile-time question
     * rather than a "55 years ago" question.
     */
    @Test
    fun theSecondsAndMillisecondEntryPointsAgree() {
        assertEquals(TimeWords.ago(secAgo(7200), now), TimeWords.agoMs(now - 7_200_000, now))
        assertEquals(TimeWords.short(secAgo(7200), now), TimeWords.shortMs(now - 7_200_000, now))
        assertEquals(
            TimeWords.stamp(1_789_594_800, now, utc),
            TimeWords.stampMs(1_789_594_800_000, now, utc),
        )
    }

    // --------------------------------------------------------- the local zone

    /**
     * The one platform read: the local UTC offset and whether the locale writes a
     * 24-hour clock. Asserted only for PLAUSIBILITY — the value is whatever the
     * machine running the test is set to, and pinning it would make this suite
     * fail on a plane.
     */
    @Test
    fun theLocalFormatIsPlausible() {
        val f = localTimeFormat(now)
        assertTrue(f.tzOffsetSec in -18 * 3600..18 * 3600, "offset ${f.tzOffsetSec}")
        assertEquals(0, f.tzOffsetSec % 60, "zone offsets are whole minutes")
        // And it must not render 1970 for a missing stamp either.
        assertEquals("", TimeWords.stamp(null, now, f))
    }
}
