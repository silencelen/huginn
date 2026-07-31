package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Keeps a conversation pinned to its newest content, and reports when there is
 * something new below the fold.
 *
 * Following is a LATCH, not a per-arrival test — that distinction is the fix for
 * a real failure. The old version asked "are they at the bottom?" at the moment
 * content arrived, but by the time that effect ran the new content was already
 * laid out, so a reader who WAS at the bottom now measured as scrolled-up and
 * following silently stopped; the "new messages" pill appeared where a scroll
 * should have happened. Asking the question later cannot fix a question whose
 * answer changes the moment it becomes interesting, so the state is explicit:
 *
 *  * **At the bottom means locked on.** Reaching the tail by any route — scroll,
 *    fling, the pill, the initial jump — arms following, and while armed every
 *    content change scrolls, no measurement consulted.
 *  * **Only a finger breaks the lock.** A [DragInteraction.Start] is the one
 *    signal that means the reader chose to leave. Programmatic scrolls never emit
 *    drags, so the follower can never mistake its own scrolling for the reader
 *    leaving — which is exactly the mistake the geometry test made.
 *  * **A tap that goes nowhere does not count as leaving.** Touch down and
 *    release still at the tail re-arms immediately.
 *
 * Everything else stands from before: open on the newest message (nothing is laid
 * out on a cold open, so an at-bottom rule can never fire), key the revision on
 * content rather than item count (the retained window is capped, so count freezes
 * on long sessions), and scroll to the END of the content, not the top of the
 * last item (a streaming answer taller than the screen otherwise shows its
 * beginning forever).
 *
 * @param key re-arms the initial jump when the conversation changes.
 * @return true when new content arrived while the reader had scrolled away.
 */
@Composable
fun AutoScrollToNewest(
    listState: LazyListState,
    itemCount: Int,
    revision: Any?,
    key: Any?,
): Boolean {
    var opened by remember(key) { mutableStateOf(false) }
    var following by remember(key) { mutableStateOf(true) }
    var unseen by remember(key) { mutableStateOf(false) }
    var lastCount by remember(key) { mutableStateOf(0) }

    // The reader's finger, and nothing else, breaks the latch.
    LaunchedEffect(key, listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> following = false
                // Released without leaving the tail: they did not go anywhere, so
                // there is nothing to stay unlatched about. A fling still in motion
                // is not at the tail yet; the landing is caught below.
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    if (listState.isAtTail()) { following = true; unseen = false }
                else -> Unit
            }
        }
    }

    // Reaching the bottom by any means re-arms the latch.
    LaunchedEffect(key, listState) {
        snapshotFlow { listState.isAtTail() }.collect { at ->
            if (at) { following = true; unseen = false }
        }
    }

    LaunchedEffect(key, itemCount, revision) {
        if (itemCount <= 0) return@LaunchedEffect
        if (!opened) {
            listState.jumpToTail(itemCount, animate = false)
            opened = true
            lastCount = itemCount
            return@LaunchedEffect
        }
        val grew = itemCount > lastCount
        lastCount = itemCount
        if (following) {
            // Animate a genuinely new message; jump for a growing one, where an
            // animation restarted on every token would never finish.
            listState.jumpToTail(itemCount, animate = grew)
        } else if (grew) {
            unseen = true
        }
    }

    return unseen
}

/**
 * True when the end of the content is on screen. Slack absorbs content padding
 * and sub-pixel rounding, so "one pixel short" still counts as the bottom.
 */
private fun LazyListState.isAtTail(slackPx: Int = 48): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    if (last.index < info.totalItemsCount - 1) return false
    return last.offset + last.size <= info.viewportEndOffset + slackPx
}

/**
 * Scrolls to the very end of the content. `scrollToItem(last)` alone lands on the
 * TOP of the last item, which hides the newest text whenever that item is taller
 * than the screen; the trailing [scrollBy] walks the remainder and is clamped by
 * the list, so it cannot overshoot.
 *
 * Public because the "new messages" pill needs the SAME landing. Using a bare
 * animateScrollToItem there left the reader at the top of a long answer, and — worse
 * — `isAtTail` stayed false, so the follow latch never re-armed and the pill stuck
 * on screen while the conversation moved on beneath it.
 */
suspend fun LazyListState.jumpToTail(itemCount: Int, animate: Boolean) {
    if (itemCount <= 0) return
    val last = itemCount - 1
    if (animate) animateScrollToItem(last) else scrollToItem(last)
    if (!isAtTail()) scrollBy(1_000_000f)
}

/**
 * Shown when new content arrived while the reader was scrolled up. Without it,
 * deliberately scrolling back to read something older looks like the app has
 * stopped following — the reader cannot tell "nothing new" from "not following".
 */
@Composable
fun JumpToNewest(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "New messages",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Session state marks. Deliberately a small filled dot plus a word rather than a
 * bar or a badge: the state is a property of the row, not a decoration on it.
 */
@Composable
fun StateDot(state: String?, modifier: Modifier = Modifier) {
    val c = when (state) {
        "running" -> MaterialTheme.colorScheme.primary
        "attention" -> MaterialTheme.colorScheme.error
        "idle" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Box(modifier.size(8.dp).clip(CircleShape).background(c))
}

/**
 * A slow breathing dot for something actively working. A spinner reads as "the
 * app is busy"; this reads as "the thing over there is busy", which is the truth.
 */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val a by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(800),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = a)))
}

fun stateLabel(state: String?): String = when (state) {
    "running" -> "working"
    "attention" -> "needs you"
    "idle" -> "waiting"
    else -> "no claude"
}

/** "3m", "2h", "4d" — a phone has no room for timestamps nobody reads. */
fun relTime(epochSec: Long): String {
    if (epochSec <= 0) return ""
    val secs = (System.currentTimeMillis() / 1000 - epochSec).coerceAtLeast(0)
    return when {
        secs < 60 -> "now"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        else -> "${secs / 86_400}d"
    }
}

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
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun EmptyState(title: String, hint: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
