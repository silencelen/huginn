package com.silencelen.huginn.desktop.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.localTimeFormat
import com.silencelen.huginn.ui.RowTimeTooltip
import com.silencelen.huginn.ui.TimeWords

/**
 * Hover text, and the one desktop affordance with no phone equivalent at all: a
 * pointer can ask a question of a thing without doing anything to it.
 *
 * That is what makes it the right home for STATE. This app draws state as 7px
 * dots and one-letter suffixes on purpose (house rule: subtle in-vernacular marks,
 * never loud badges), and the standing objection to a subtle mark is that nobody
 * can learn what it means. On a phone the answer is a legend, which costs a row of
 * chrome forever. On a desktop the answer is here: the mark stays small and the
 * pointer carries the sentence.
 *
 * EVERYTHING A TIP SAYS COMES OFF THE ROW. The formatters below take only fields
 * the daemon already sent and the current clock; none of them guesses, and a field
 * that is absent produces a shorter sentence rather than a plausible one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // A blank tip must not become an empty popup that flickers under the cursor.
    if (text.isBlank()) {
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        tooltip = { TipCard(text) },
        modifier = modifier,
        // 400ms: long enough that sweeping the pointer across a list does not
        // trail popups, short enough to feel like an answer rather than a wait.
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(12.dp, 16.dp)),
        content = content,
    )
}

@Composable
private fun TipCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 8.dp,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
    ) {
        Text(
            text,
            style = DeskType.rowMeta,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Space.wide, vertical = Space.unit),
        )
    }
}

// --------------------------------------------------------------- formatters
//
// Pure, and tested. A tooltip is the one surface where a wrong sentence is worse
// than no sentence — it is read as an explanation of a mark the reader could not
// otherwise decode, so it gets asserted like a parser.

/**
 * Seconds as a person says them: "just now", "4m", "2h 10m", "3d".
 *
 * ⚠ THIS IS A DURATION, AND IT STAYS. [TimeWords] absorbed every wall-clock
 * formatter on both clients, and this one looks like one from the outside — but
 * it measures an ELAPSED SPAN ("Working · for 2h 10m"), not an instant, and its
 * two-unit precision is exactly what makes "waiting on you · for 3d 2h" worth
 * reading. A timestamp never wants that shape and a span always does. Folding it
 * in would be finishing a job that was not started.
 *
 * Its one remaining caller is [sessionStateTip], which asks how long a state has
 * HELD. Everything here that asks WHEN instead goes through [TimeWords].
 */
fun humanDuration(sec: Long): String = when {
    sec < 45 -> "just now"
    sec < 3600 -> "${sec / 60}m"
    sec < 86_400 -> {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        if (m == 0L) "${h}h" else "${h}h ${m}m"
    }
    else -> {
        val d = sec / 86_400
        val h = (sec % 86_400) / 3600
        if (h == 0L) "${d}d" else "${d}d ${h}h"
    }
}

/**
 * The session dot. Which state, and how long it has been in it — the second half
 * is the part a dot cannot carry and the part that decides whether "waiting on
 * you" is a glance or an interruption.
 *
 * @param stateSince epoch SECONDS, as the daemon reports it, or null/0 when it has
 *   recorded no transition. A missing timestamp shortens the sentence; it never
 *   invents a duration.
 */
fun sessionStateTip(state: String?, stateSince: Long?, nowSec: Long): String {
    val what = when (state) {
        "running" -> "Working"
        "attention" -> "Waiting on you"
        "idle" -> "Idle"
        null, "" -> return "No state recorded for this session yet"
        else -> state.replaceFirstChar { it.uppercase() }
    }
    val since = stateSince ?: 0
    if (since <= 0) return what
    val elapsed = (nowSec - since).coerceAtLeast(0)
    return "$what · for ${humanDuration(elapsed)}"
}

/**
 * The chat dot and its queued suffix. Both marks on one row answer the same
 * question — "is anything happening here" — so they get one sentence.
 */
fun chatStateTip(running: Boolean, pending: Int, turns: Int, updatedAt: Long, nowSec: Long): String {
    val head = when {
        running && pending > 0 ->
            "Running now · $pending message${plural(pending)} queued behind this turn"
        running -> "Running now"
        pending > 0 -> "$pending message${plural(pending)} queued"
        else -> "Idle"
    }
    val parts = mutableListOf(head)
    if (turns > 0) parts += "$turns turn${plural(turns)}"
    // WHEN, not for how long — so the shared vocabulary, the same words the row
    // itself and the phone's chats list draw.
    TimeWords.ago(updatedAt, nowSec * 1000L).takeIf { it.isNotBlank() }
        ?.let { parts += "last activity $it" }
    return parts.joinToString(" · ")
}

/**
 * Background work: shells the session left running and subagents it fanned out.
 * Empty when there is none, so the caller can skip the tip rather than hover a
 * mark that says "0".
 */
fun bgWorkTip(bgShells: Int, bgAgents: Int, bgTask: String?): String {
    val parts = mutableListOf<String>()
    if (bgShells > 0) parts += "$bgShells background shell${plural(bgShells)} still running"
    if (bgAgents > 0) parts += "$bgAgents subagent${plural(bgAgents)} working"
    if (parts.isEmpty()) return ""
    val task = bgTask?.trim()?.takeIf { it.isNotEmpty() }
    return parts.joinToString(" · ") + (task?.let { "\nLongest: $it" } ?: "")
}

/**
 * The one liveness mark in the frame, and the only one whose meaning is about
 * something OTHER than what is on screen: whether alerts from this daemon reach
 * this machine, or fall through to the household's Telegram.
 */
fun connectionTip(
    connected: Boolean,
    route: String,
    notifyEnabled: Boolean,
    routeName: String = "",
): String {
    val address = route.removePrefix("https://").removePrefix("http://").trimEnd('/')
    // THE OWNER'S NAME FOR THE PATH FIRST. "Yggdrasil" is the fact that explains
    // a dead stream; the address is the detail under it. An unnamed route — or
    // an install that has not pinned one yet — falls back to the address alone
    // rather than to an empty phrase.
    val where = if (routeName.isBlank()) address else "$routeName · $address"
    return when {
        !connected ->
            "Watch stream detached from $where.\n" +
                "Nothing is being delivered to this window; alerts fall back to Telegram."
        !notifyEnabled ->
            "Watch stream attached to $where.\n" +
                "Notifications are switched off in Settings, so alerts go to Telegram instead."
        else ->
            "Watch stream attached to $where.\n" +
                "Notifications arrive here while you are at this window; Telegram covers the rest."
    }
}

/**
 * A relative timestamp, spelled out. "3d" on the row, the sentence on hover —
 * the same bands in two registers, which is precisely what [TimeWords] is for.
 * Empty for a missing stamp, so [Tip] draws no popup rather than an empty one.
 */
fun timeTip(label: String, epochSec: Long, nowSec: Long): String {
    val words = TimeWords.ago(epochSec, nowSec * 1000L)
    return if (words.isBlank()) "" else "$label $words"
}

/**
 * The transcript's hover reveal: the exact date and time a message was written,
 * or nothing at all.
 *
 * EXACT RATHER THAN RELATIVE, and the two clients differ here on purpose. A
 * reader who hovers a message is asking the one question the row cannot already
 * answer — "2h ago" is what they could see without hovering. A tooltip has room
 * for the whole sentence, and an absolute one cannot go stale between being
 * composed and being read. The phone's long-press bar is one tight line above
 * the composer, so it gets [TimeWords.stamp] instead.
 *
 * The zone is read AT THE STAMP'S OWN INSTANT: a message sent in July, read in
 * December, had July's clock time.
 */
fun rowTimeReveal(atSec: Long?): String =
    TimeWords.full(atSec, localTimeFormat((atSec ?: 0L) * 1000L))

/**
 * The desktop's answer to [RowTimeTooltip]: hover a message, get its time.
 *
 * Installed once, at the window root in `Main.kt`, so every surface that draws
 * transcript rows — chat, session, the subagent card's innards — reveals times
 * the same way. `Tip` already declines to draw for blank text, which is how a
 * row with no stamp costs nothing at all.
 */
val DesktopRowTime: RowTimeTooltip = object : RowTimeTooltip {
    @Composable
    override fun Wrap(atSec: Long?, content: @Composable () -> Unit) {
        Tip(rowTimeReveal(atSec)) { content() }
    }
}

/** What the counts in the nav rail mean, spelled out rather than badged. */
fun railCountTip(what: String, total: Int, marked: Int, markedWord: String): String {
    if (total == 0) return "No $what"
    val head = "$total $what"
    return if (marked > 0) "$head · $marked $markedWord" else head
}

private fun plural(n: Int) = if (n == 1) "" else "s"
