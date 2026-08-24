package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Round

/**
 * How a Round's last run reads at a glance.
 *
 * A string off the wire is not a state: it can be misspelled, missing, or a
 * status this client has never heard of. Narrowing it here once means the two
 * shells cannot disagree about what an unrecognised status looks like, and an
 * older client meeting a newer daemon degrades to [UNKNOWN] instead of drawing
 * nothing.
 */
enum class RoundStatus { OK, ATTENTION, ACTION, UNKNOWN, NEVER_RUN }

fun roundStatusOf(status: String?): RoundStatus = when (status) {
    "ok" -> RoundStatus.OK
    "attention" -> RoundStatus.ATTENTION
    "action" -> RoundStatus.ACTION
    null -> RoundStatus.NEVER_RUN
    else -> RoundStatus.UNKNOWN
}

/**
 * Written from the reader's side, not the system's: a person wants to know
 * whether anything needs them, not which enum case the daemon chose.
 */
fun roundStatusLabel(s: RoundStatus): String = when (s) {
    RoundStatus.OK -> "All clear"
    RoundStatus.ATTENTION -> "Worth a look"
    RoundStatus.ACTION -> "Needs you"
    RoundStatus.UNKNOWN -> "Unclear"
    RoundStatus.NEVER_RUN -> "Not run yet"
}

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

/**
 * When a Round next goes out, in words.
 *
 * Deliberately coarse. A schedule is not a countdown — nobody needs "in 3h 42m"
 * for something weekly, and a precise figure invites the reader to check whether
 * it is right. Rounding to the largest useful unit says the true thing ("later
 * today", "in 4 days") without pretending to a precision the tick does not have.
 */
fun untilWords(nextRunAt: Long?, nowMs: Long): String {
    if (nextRunAt == null || nextRunAt <= 0L) return "not scheduled"
    val d = nextRunAt - nowMs
    return when {
        d <= 0L -> "due now"
        d < MIN -> "in under a minute"
        d < HOUR -> "in ${d / MIN}m"
        d < DAY -> "in ${d / HOUR}h"
        d < 2 * DAY -> "tomorrow"
        else -> "in ${d / DAY} days"
    }
}

/**
 * How long ago a run finished. Takes SECONDS, because that is what the daemon
 * stamps its records with — its schedule is in milliseconds and its timestamps
 * are not, and mixing the two silently produces "in 55 years".
 */
fun agoWords(atSec: Long?, nowMs: Long): String {
    if (atSec == null || atSec <= 0L) return ""
    val d = nowMs - atSec * 1000L
    return when {
        d < MIN -> "just now"
        d < HOUR -> "${d / MIN}m ago"
        d < DAY -> "${d / HOUR}h ago"
        d < 2 * DAY -> "yesterday"
        else -> "${d / DAY} days ago"
    }
}

/**
 * The one line under a Round's name.
 *
 * Cadence comes from the DAEMON (`round.cadence`), never from re-reading the
 * schedule here: rendering it in three places is how a phone and a desktop end
 * up disagreeing about what a schedule means, and the daemon is the only one
 * that knows the zone rules it actually fires by.
 */
fun roundSubtitle(round: Round, nowMs: Long): String {
    val cadence = round.cadence.ifBlank { "no cadence" }
    if (!round.enabled) return "$cadence · paused"
    if (round.running) return "$cadence · running now"
    return "$cadence · ${untilWords(round.nextRunAt, nowMs)}"
}

/**
 * What the Round last came back with, or an invitation to find out.
 *
 * A malformed report is called out rather than smoothed over: the run happened
 * and produced something, but its contract broke, and hiding that would let a
 * Round quietly stop reporting while still looking healthy.
 */
fun roundLastLine(round: Round): String {
    val last = round.lastRun ?: return "No runs yet"
    // Order matters: an unmet goal is the more important of the two, because a
    // headline can be perfectly cheerful while the job was not done.
    val prefix = when {
        last.goalMet == false -> "Did not finish: "
        last.malformed -> "Unreported: "
        else -> ""
    }
    return prefix + last.headline.ifBlank { "(no headline)" }
}

/**
 * Where a chat is running, for a reader — or null when it is this host.
 *
 * Null rather than "local" on purpose: the overwhelmingly common case needs no
 * label at all, and badging every ordinary chat with "huginn" would make the one
 * that IS somewhere else stop standing out. A badge that is always on says
 * nothing.
 */
fun chatHostLabel(host: String?, hostName: String?): String? {
    if (host.isNullOrBlank() || host == "local") return null
    return hostName?.takeIf { it.isNotBlank() } ?: "another machine"
}

/** The one line a sealed run shows instead of a composer. */
fun sealedNote(round: Boolean = true): String =
    if (round) "This round has finished. It is kept here for review."
    else "This conversation is closed."

