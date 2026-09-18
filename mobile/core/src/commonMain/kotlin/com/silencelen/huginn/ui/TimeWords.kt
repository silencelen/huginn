package com.silencelen.huginn.ui

/**
 * The local wall-clock rules a stamp is rendered under.
 *
 * A PARAMETER, not a read. Zone and locale are runtime facts `commonMain` cannot
 * see, and a formatter that reaches for them is a formatter that cannot be
 * asserted at a DST edge or in a 12-hour locale without moving the machine. The
 * one place the machine answers is
 * [com.silencelen.huginn.data.localTimeFormat], and every call site passes the
 * answer in.
 *
 * @param tzOffsetSec seconds east of UTC in force AT THE INSTANT BEING DRAWN —
 *   not "the current offset", which is an hour wrong for half the year's stamps.
 * @param hour24 whether this locale writes 14:05 or 2:05 pm. There is no setting
 *   behind it (decision 43): the system already knows, and asking twice is how
 *   two answers start disagreeing.
 */
data class TimeFormat(val tzOffsetSec: Int = 0, val hour24: Boolean = true)

/**
 * THE time vocabulary. One set of bands, one rule for a missing stamp, three
 * registers.
 *
 * ⚠ WHY THIS EXISTS. Five formatters had grown independently — `relTime()` in
 * the phone's `Common.kt` AND in the desktop's `Lists.kt`, `agoWords`/
 * `agoWordsMs` here in `Rounds.kt`, `humanDuration` and `timeTip` in the
 * desktop's `Tips.kt` — and they disagreed about their boundaries, their
 * wording, and what an absent timestamp means. Two clients drawing the same
 * `ts` from the same daemon could and did print different words for it. After
 * this, they cannot: nothing outside this file holds a band boundary or a time
 * string.
 *
 * ## The one rule that matters
 *
 * A null, zero or negative stamp renders the EMPTY STRING from every entry
 * point, and the caller draws nothing. `lib/transcript.js:439` maps an absent or
 * unparseable timestamp — and a literal epoch 0 — to null, so a formatter that
 * trusts its input prints "1 Jan 1970" on an ordinary transcript row. It is
 * asserted from both directions in `TimeWordsTest`, including one case whose
 * whole job is that no output ever contains "1970".
 *
 * ## The bands
 *
 * | since | [short] | [ago] | [stamp] |
 * |---|---|---|---|
 * | missing | "" | "" | "" |
 * | < 1 min (or up to [SKEW_GRACE_MS] ahead) | now | just now | just now |
 * | < 1 h | 14m | 14m ago | 14m ago |
 * | < 6 h | 3h | 3h ago | 3h ago |
 * | same civil day | 8h | 8h ago | 14:05 |
 * | the day before | 1d | yesterday | Yesterday 14:05 |
 * | < 7 civil days | 4d | 4 days ago | Tue 14:05 |
 * | same civil year | 40d | 40 days ago | 3 Sep 14:05 |
 * | earlier | 400d | 400 days ago | 3 Sep 2025 14:05 |
 *
 * [full] ignores the clock entirely: "Thursday 17 September 2026, 14:32".
 *
 * Six hours is where "7h ago" stops being easier to read than "08:12" — past it
 * the reader has to subtract either way, and only one of the two answers is a
 * fact about when the thing happened.
 *
 * The day bands are CIVIL, counted in [TimeFormat]'s zone, never elapsed hours:
 * 23:59 and 00:01 are a minute apart and different days, and a reader who sees
 * "yesterday" means the calendar.
 *
 * ## Registers, not vocabularies
 *
 * [short] is the same bands written for a three-character column on a list row;
 * [ago] is them written as a sentence; [stamp] is them written where an exact
 * time is worth having. They share every boundary, which is the whole point —
 * the three differ only in how much room they have.
 *
 * ## What this deliberately does NOT replace
 *
 * `Tips.humanDuration` formats an elapsed DURATION ("2h 10m", "3d 2h"), not a
 * wall-clock instant, and its two-unit precision is exactly what a "for how
 * long" tooltip needs. `WorkSummary.agoShort` is a duration too, and
 * `WorkSummary.sinceShort` measures against the SERVER's clock carried on
 * `AgentsInfo` rather than this machine's. None of the three is a timestamp;
 * folding them in here would be finishing a job that was not started.
 *
 * `Rounds.untilWords` is the mirror image — a countdown into the future, with a
 * deliberately coarser vocabulary because a schedule is not a stopwatch.
 *
 * Civil-date arithmetic is Hinnant's era algorithm. `PlanFormat` carries the
 * forward half ([com.silencelen.huginn.ui.PlanFormat]'s private `daysFromCivil`);
 * this file owns the inverse, `civilFromDays`, and the two are named as a pair in
 * each other's docs so neither grows a third copy. No `kotlinx-datetime`: it is
 * not a dependency of this module and one date function does not justify one.
 */
object TimeWords {

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** Past this, a clock time says more than "7h ago". */
    private const val RECENT = 6 * HOUR

    /**
     * How far ahead of this machine a stamp may be and still read as "just now".
     *
     * A daemon whose clock is a few minutes ahead of the phone's is ordinary —
     * two machines, two NTP states — and a message that arrives stamped 90
     * seconds into the future is not news. Past the grace it is a broken clock
     * somewhere, and [stamp] shows the raw clock time rather than a soothing
     * phrase, because "just now" about something dated next Tuesday is the
     * version of this that hides a real fault.
     */
    const val SKEW_GRACE_MS = 5 * MIN

    // ------------------------------------------------------------- registers

    /** The list-row register: "now" · "14m" · "3h" · "2d". Epoch SECONDS. */
    fun short(atSec: Long?, nowMs: Long): String = shortMs(atSec?.times(1000L), nowMs)

    /** [short] from a MILLISECOND stamp. */
    fun shortMs(atMs: Long?, nowMs: Long): String {
        if (atMs == null || atMs <= 0L) return ""
        val d = (nowMs - atMs).coerceAtLeast(0)
        return when {
            d < MIN -> "now"
            d < HOUR -> "${d / MIN}m"
            d < DAY -> "${d / HOUR}h"
            else -> "${d / DAY}d"
        }
    }

    /**
     * The sentence register: "just now" · "14m ago" · "3h ago" · "yesterday" ·
     * "4 days ago". Epoch SECONDS, because that is what the daemon stamps its
     * records with.
     */
    fun ago(atSec: Long?, nowMs: Long): String = agoMs(atSec?.times(1000L), nowMs)

    /**
     * The same sentence, from a MILLISECOND stamp.
     *
     * A second entry point rather than one function with a unit flag: the wire is
     * genuinely inconsistent — a Round's `lastRun.at` is in seconds while a
     * Device's `lastSeen` is in milliseconds — so both units are real, and the
     * mistake worth engineering against is picking the wrong one silently. Two
     * names that each say their unit make that a compile-time question instead of
     * a "55 years ago" question.
     */
    fun agoMs(atMs: Long?, nowMs: Long): String {
        if (atMs == null || atMs <= 0L) return ""
        val d = (nowMs - atMs).coerceAtLeast(0)
        return when {
            d < MIN -> "just now"
            d < HOUR -> "${d / MIN}m ago"
            d < DAY -> "${d / HOUR}h ago"
            d < 2 * DAY -> "yesterday"
            else -> "${d / DAY} days ago"
        }
    }

    /**
     * The reveal register: relative while that is the shorter answer, an exact
     * clock time once it is not. Epoch SECONDS. See the band table above.
     */
    fun stamp(atSec: Long?, nowMs: Long, fmt: TimeFormat): String =
        stampMs(atSec?.times(1000L), nowMs, fmt)

    /** [stamp] from a MILLISECOND stamp. */
    fun stampMs(atMs: Long?, nowMs: Long, fmt: TimeFormat): String {
        if (atMs == null || atMs <= 0L) return ""
        val d = nowMs - atMs
        if (d >= 0L) {
            if (d < MIN) return "just now"
            if (d < HOUR) return "${d / MIN}m ago"
            if (d < RECENT) return "${d / HOUR}h ago"
        } else if (-d <= SKEW_GRACE_MS) {
            return "just now"
        }
        // Everything past the relative bands — and every stamp far enough ahead
        // of this clock to be a fault — is answered with the calendar.
        val at = civil(atMs, fmt)
        val here = civil(nowMs, fmt)
        val delta = here.days - at.days
        val time = clock(at, fmt)
        return when {
            delta == 0L -> time
            delta == 1L -> "Yesterday $time"
            delta in 2L..6L -> "${DAY_SHORT[at.dow]} $time"
            at.year == here.year -> "${at.day} ${MONTH_SHORT[at.month - 1]} $time"
            else -> "${at.day} ${MONTH_SHORT[at.month - 1]} ${at.year} $time"
        }
    }

    /**
     * The whole answer, with no relative part at all: "Thursday 17 September
     * 2026, 14:32".
     *
     * Takes no clock, so it cannot go stale between being composed and being
     * read — which is what a hover reveal is for.
     */
    fun full(atSec: Long?, fmt: TimeFormat): String {
        if (atSec == null || atSec <= 0L) return ""
        val at = civil(atSec * 1000L, fmt)
        return "${DAY_LONG[at.dow]} ${at.day} ${MONTH_LONG[at.month - 1]} ${at.year}, ${clock(at, fmt)}"
    }

    // ------------------------------------------------------------- internals

    private class Civil(
        /** Whole days since 1970-01-01 IN THE LOCAL ZONE — the civil day number. */
        val days: Long,
        val year: Int,
        val month: Int,
        val day: Int,
        /** 0 = Sunday. 1970-01-01 was a Thursday, which is where the +4 comes from. */
        val dow: Int,
        val secOfDay: Int,
    )

    /**
     * Instant → local civil date. Hinnant's era arithmetic, the inverse of
     * `PlanFormat.daysFromCivil`: shift the year to start in March so the leap
     * day lands last and the month lengths become a linear sequence, then count
     * whole 400-year eras, whose day count is fixed. No loops, no table, no
     * library.
     *
     * `floorDiv`/`mod` rather than `/` and `%` throughout: both are negative for
     * instants before 1970 and for any zone west of Greenwich near the epoch, and
     * truncating division would put those on the wrong day.
     */
    private fun civil(atMs: Long, fmt: TimeFormat): Civil {
        val local = atMs.floorDiv(1000L) + fmt.tzOffsetSec
        val days = local.floorDiv(86_400L)
        val sod = local.mod(86_400L).toInt()
        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L                                      // [0, 146096]
        val yoe = (doe - doe / 1460L + doe / 36_524L - doe / 146_096L) / 365L // [0, 399]
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)              // [0, 365]
        val mp = (5L * doy + 2L) / 153L                                   // [0, 11]
        val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt()                // [1, 31]
        val m = (if (mp < 10L) mp + 3L else mp - 9L).toInt()              // [1, 12]
        val year = (if (m <= 2) y + 1L else y).toInt()
        return Civil(days, year, m, d, ((days + 4L).mod(7L)).toInt(), sod)
    }

    private fun clock(at: Civil, fmt: TimeFormat): String {
        val h = at.secOfDay / 3600
        val m = (at.secOfDay % 3600) / 60
        val mm = if (m < 10) "0$m" else "$m"
        if (fmt.hour24) return "${if (h < 10) "0$h" else "$h"}:$mm"
        // Midnight and noon are the two a 12-hour clock gets wrong: both are 12,
        // and they are the ones a reader notices.
        val h12 = ((h + 11) % 12) + 1
        return "$h12:$mm ${if (h < 12) "am" else "pm"}"
    }

    private val MONTH_SHORT = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    private val MONTH_LONG = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val DAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val DAY_LONG = listOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
    )
}
