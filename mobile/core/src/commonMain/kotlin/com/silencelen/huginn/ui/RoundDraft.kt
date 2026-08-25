package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Round
import com.silencelen.huginn.data.RoundSchedule

/**
 * A Round being written, before it is one.
 *
 * Pure, and in `:core` rather than beside the form, so "is this schedule valid"
 * is answerable in a test instead of by tapping Save and reading a 400. The rules
 * here MIRROR the daemon's `validateSchedule` — they do not replace it. The
 * daemon is still the thing that decides; this exists so a person is told what is
 * wrong while they are still looking at the field, rather than after a round trip
 * that discards their typing.
 *
 * Where the two disagree the daemon wins, and that is a bug in this file.
 */
data class RoundDraft(
    val title: String = "",
    val prompt: String = "",
    /**
     * Optional on purpose. A Round that reports on something has no finish line —
     * but with a goal the run is asked whether it got there, and an honest no is
     * reported instead of smoothed into a cheerful headline.
     */
    val goal: String = "",
    /** daily | weekly | monthly | interval */
    val kind: String = "weekly",
    /** "HH:MM", 24-hour. Ignored for `interval`. */
    val at: String = "09:00",
    /** 0 = Sunday. `weekly` only. */
    val days: Set<Int> = setOf(1),
    /** 1-31. `monthly` only. */
    val dates: Set<Int> = setOf(1),
    /** Text, not a number, because it is bound to a field somebody is mid-typing. */
    val everyMinutes: String = "60",
    /**
     * The zone this Round's clock is in, carried through the editor untouched.
     *
     * ⚠ THIS FIELD IS THE FIX FOR A REAL BUG. Without it the draft had no zone,
     * so [toSchedule] substituted the EDITING DEVICE's zone — and opening a Round
     * set for 07:30 Europe/London on a phone in Los Angeles and saving it without
     * touching anything moved the job eight hours. Every surface would have gone
     * on showing a correct-looking "7:30", because the daemon renders the cadence
     * in the zone it was given.
     *
     * Null only for a Round being WRITTEN, where the device's zone is the right
     * default and the only one available.
     */
    val tz: String? = null,
    val mode: String = "ask",
    /** always | attention | never */
    val notifyWhen: String = "attention",
    /** A device id, or "local". */
    val host: String = "local",
) {
    val isInterval: Boolean get() = kind == "interval"
}

const val ROUND_PROMPT_MAX = 20_000
const val ROUND_GOAL_MAX = 500
const val ROUND_TITLE_MAX = 80
const val ROUND_INTERVAL_MIN = 5
const val ROUND_INTERVAL_MAX = 7 * 24 * 60

/** Sunday-first, matching the daemon's 0 = Sunday. */
val ROUND_DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * What is wrong with this draft, in words a person can act on — or null.
 *
 * One problem at a time, in the order the form reads. A list of six complaints
 * about a half-filled form is noise; the next thing to fix is the useful answer.
 */
fun RoundDraft.problem(): String? {
    if (title.isBlank()) return "Give it a name."
    if (title.length > ROUND_TITLE_MAX) return "That name is longer than $ROUND_TITLE_MAX characters."
    if (prompt.isBlank()) return "Say what it should do."
    if (prompt.length > ROUND_PROMPT_MAX) return "That is longer than this can carry."
    if (goal.length > ROUND_GOAL_MAX) return "Keep the goal under $ROUND_GOAL_MAX characters."

    if (isInterval) {
        val n = everyMinutes.trim().toIntOrNull()
            ?: return "How many minutes between runs?"
        if (n < ROUND_INTERVAL_MIN) return "Every $ROUND_INTERVAL_MIN minutes is as often as this goes."
        if (n > ROUND_INTERVAL_MAX) return "More than a week apart — pick a day and a time instead."
        return null
    }

    if (!isClockTime(at)) return "Time needs to look like 09:00 or 19:30."
    if (kind == "weekly" && days.isEmpty()) return "Pick at least one day."
    if (kind == "monthly") {
        if (dates.isEmpty()) return "Pick at least one date."
        if (dates.any { it < 1 || it > 31 }) return "Dates run from 1 to 31."
    }
    return null
}

/** "HH:MM", 24-hour, and nothing else. Same shape the daemon insists on. */
fun isClockTime(v: String): Boolean {
    val s = v.trim()
    if (s.length != 5 || s[2] != ':') return false
    val h = s.substring(0, 2).toIntOrNull() ?: return false
    val m = s.substring(3, 5).toIntOrNull() ?: return false
    return h in 0..23 && m in 0..59
}

/**
 * @param tz this client's IANA zone, or null to let the daemon use the host's.
 *   Null is the normal case for the shared UI, which has no calendar to ask.
 */
fun RoundDraft.toSchedule(deviceTz: String? = null): RoundSchedule {
    // The Round's OWN zone wins. `deviceTz` is a default for a Round being
    // written, never an override for one being edited — see [RoundDraft.tz].
    val zone = tz?.takeIf { it.isNotBlank() } ?: deviceTz
    return when (kind) {
        "interval" -> RoundSchedule(kind = "interval", everyMinutes = everyMinutes.trim().toIntOrNull())
        "weekly" -> RoundSchedule(kind = "weekly", at = at.trim(), tz = zone, days = days.sorted())
        "monthly" -> RoundSchedule(kind = "monthly", at = at.trim(), tz = zone, dates = dates.sorted())
        else -> RoundSchedule(kind = "daily", at = at.trim(), tz = zone)
    }
}

/** An existing Round, opened for editing. */
fun Round.toDraft(): RoundDraft = RoundDraft(
    title = title,
    prompt = prompt,
    goal = goal,
    kind = schedule.kind,
    // Kept only when it is a real clock time: a draft seeded with a malformed
    // value would put the form straight into an error the person did not cause.
    at = schedule.at?.takeIf { isClockTime(it) } ?: "09:00",
    days = schedule.days.toSet().ifEmpty { setOf(1) },
    dates = schedule.dates.toSet().ifEmpty { setOf(1) },
    everyMinutes = (schedule.everyMinutes ?: 60).toString(),
    tz = schedule.tz,
    mode = mode,
    notifyWhen = notifyWhen,
    host = host,
)

/**
 * How this draft's cadence reads, for the line under the picker.
 *
 * ⚠ A LOCAL ECHO, not the truth. The daemon renders `Round.cadence` and fires by
 * it; once the Round exists, that is what every surface shows. This exists only
 * because a form with no feedback until Save is a form people get wrong twice.
 */
fun RoundDraft.cadencePreview(): String = cadenceWords() + zoneSuffix()

/**
 * The zone, said out loud whenever the Round has one.
 *
 * A time with no clock named beside it is the thing that let the zone bug hide:
 * "7:30" reads as correct in any zone, so nobody could see it move.
 */
private fun RoundDraft.zoneSuffix(): String =
    if (isInterval) "" else tz?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""

private fun RoundDraft.cadenceWords(): String {
    if (isInterval) {
        val n = everyMinutes.trim().toIntOrNull() ?: return "Every … minutes"
        return when {
            n % 1440 == 0 && n / 1440 == 1 -> "Every day"
            n % 1440 == 0 -> "Every ${n / 1440} days"
            n % 60 == 0 && n / 60 == 1 -> "Every hour"
            n % 60 == 0 -> "Every ${n / 60} hours"
            else -> "Every $n minutes"
        }
    }
    val time = clockWords(at)
    return when (kind) {
        "weekly" -> {
            val names = days.sorted().map { ROUND_DAY_NAMES.getOrElse(it) { "?" } }
            when {
                names.isEmpty() -> "Pick a day"
                names.size == 7 -> "Every day at $time"
                else -> "${names.joinToString(", ")} at $time"
            }
        }
        "monthly" -> {
            val ds = dates.sorted()
            if (ds.isEmpty()) "Pick a date" else "The ${ds.joinToString(", ") { ordinal(it) }} at $time"
        }
        else -> "Every day at $time"
    }
}

/** 24-hour in the field, 12-hour in the sentence — the daemon words it the same way. */
private fun clockWords(at: String): String {
    if (!isClockTime(at)) return "…"
    val h = at.substring(0, 2).toInt()
    val m = at.substring(3, 5)
    val suffix = if (h < 12) "AM" else "PM"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$h12:$m $suffix"
}

private fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
