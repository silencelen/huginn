package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import com.silencelen.huginn.ui.theme.stateWord
import com.silencelen.huginn.ui.theme.stateColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.silencelen.huginn.data.localTimeFormat
import kotlinx.coroutines.delay

/**
 * Session state marks. Deliberately a small filled dot plus a word rather than a
 * bar or a badge: the state is a property of the row, not a decoration on it.
 */
@Composable
fun StateDot(state: String?, modifier: Modifier = Modifier) {
    // One state language (ui/theme/StateColors.kt): needs-you is the lit accent,
    // never the error red; idle is the ladder's quiet step.
    val c = stateColor(state)
    Box(modifier.size(8.dp).clip(CircleShape).background(c))
}

/**
 * A slow breathing dot for something actively working. A spinner reads as "the
 * app is busy"; this reads as "the thing over there is busy", which is the truth.
 */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    // The shared stepped breath, not a second infinite tween of its own.
    // 2026-10-02: this copy redrew the Sessions tab at display rate (~100 draws
    // a second on a foldable) for as long as a session was working; see PulseDot.
    com.silencelen.huginn.ui.work.PulseDot(color, modifier)
}

fun stateLabel(state: String?): String = stateWord(state)

/**
 * "3m", "2h", "4d" — a phone has no room for timestamps nobody reads.
 *
 * ⚠ THE CLOCK READ, AND NOTHING ELSE. The words are [TimeWords.short]'s, shared
 * with the desktop's list rows and with every other time on both clients; this
 * function exists only because its three call sites are list rows that have no
 * `nowMs` in hand. Do not add a band here — the next reader will believe it.
 *
 * It samples the clock per call rather than per composition, which for a list
 * that recomposes on every daemon tick is the freshest of the cheap options; the
 * surfaces that must tick on their own (Rounds, the session map) take
 * [screenClock] instead.
 */
fun relTime(epochSec: Long): String = TimeWords.short(epochSec, System.currentTimeMillis())

/**
 * The phone's timestamp reveal — the first line of the long-press bar.
 *
 * [TimeWords.stamp] rather than the desktop's [TimeWords.full]: this is one
 * muted line squeezed above the composer, not a tooltip with room for a
 * sentence, and "Yesterday 21:40" answers the question in the space available.
 * Empty when the row carried no timestamp, which is what makes the bar draw no
 * line at all rather than an empty one.
 *
 * The zone is read at the STAMP's instant, not at now: a message sent in July and
 * read in December had July's clock time, and reading the current offset moves it
 * by an hour twice a year.
 */
fun rowTimeWords(atSec: Long?): String =
    TimeWords.stamp(atSec, System.currentTimeMillis(), localTimeFormat((atSec ?: 0L) * 1000L))

fun formatUptime(sec: Long): String {
    val d = sec / 86_400
    val h = (sec % 86_400) / 3600
    val m = (sec % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    // Title Case, labelLarge, no tracking: the house header (ui/system/ListHeader.kt).
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/**
 * A clock that ticks while the screen is on the front, and stops when it is not.
 *
 * ⚠ EVERY "in 4h" AND "3 minutes ago" ON THIS PHONE IS DRAWN FROM A `nowMs`, and
 * a `nowMs` sampled once per composition freezes the moment the screen settles.
 * The Rounds rows, the pages list and the session map all read one — and the
 * shell's was read straight from the clock with nothing to move it, so a phone
 * left open showed countdowns that never counted and "just now" an hour later.
 *
 * Thirty seconds because the finest unit any of them shows is a minute. Tied to
 * the LIFECYCLE rather than to composition: a backgrounded screen counting down
 * to itself is a wakeup every half minute for a number nobody is reading.
 */
@Composable
fun screenClock(periodMs: Long = 30_000L): Long {
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose { started = false }
    }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started, periodMs) {
        if (!started) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return nowMs
}

@Composable
fun EmptyState(title: String, hint: String) {
    // The house empty state (ui/system/EmptyState.kt): left-aligned, one line.
    com.silencelen.huginn.ui.system.EmptyState(title, hint)
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ------------------------------------------------------------- FAB clearance

/**
 * The height Material gives an [androidx.compose.material3.ExtendedFloatingActionButton].
 *
 * Not exposed as a token by the library, and it is the number every list on this
 * phone has to clear, so it is written down once with its source rather than
 * three times as a magic 88.
 */
val FAB_HEIGHT = 56.dp

/** The inset each FAB on this phone is placed with. */
val FAB_INSET = 16.dp

/**
 * What a scrolling list must add to the BOTTOM of its content so its last row can
 * be read and tapped.
 *
 * Chats and Sessions had this; Rounds had 24dp, which is less than the FAB it sits
 * under, so the newest round was permanently behind "New round" with no scroll
 * position that would move it. The fix is the same number in all three places and
 * the number derived from the thing it is clearing, so the next list to grow a FAB
 * cannot pick its own.
 *
 * A gap of [FAB_INSET] above the button as well, so the last row clears it rather
 * than touching it.
 */
val LIST_FAB_CLEARANCE = FAB_HEIGHT + FAB_INSET + FAB_INSET
