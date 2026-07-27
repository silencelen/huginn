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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * The rules, and why each one exists:
 *
 *  * **Open on the newest message.** Without an explicit first jump, a
 *    follow-if-at-bottom rule can never fire on a cold open: nothing is laid out
 *    yet, so the reader looks scrolled-up and stays parked at the oldest message.
 *  * **Follow on any content change, not on item count.** The retained window is
 *    capped, so once a long session reaches the cap the count stops changing
 *    forever — keying on it silently killed following for exactly the sessions
 *    that need it. [tailRevision] changes whenever visible content changes,
 *    including a streaming message growing without a new item appearing.
 *  * **Decide "at the bottom" from geometry, not from an index.** A last item
 *    taller than the viewport is "the last index" while the reader sits at its
 *    top, and yanking them to the end mid-read is worse than not following.
 *  * **Scroll to the end of the content**, not to the top of the last item, or
 *    following a long streaming answer shows its beginning forever.
 *
 * @param key re-arms the initial jump when the conversation changes.
 * @return true when new content arrived while scrolled away from the bottom.
 */
@Composable
fun AutoScrollToNewest(
    listState: LazyListState,
    itemCount: Int,
    revision: Any?,
    key: Any?,
): Boolean {
    var opened by remember(key) { mutableStateOf(false) }
    var unseen by remember(key) { mutableStateOf(false) }
    var lastCount by remember(key) { mutableStateOf(0) }

    val atBottom by remember(listState) { derivedStateOf { listState.isAtTail() } }

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
        // Read before the new content is laid out, so this is "were they at the
        // bottom when it arrived", which is the question that matters.
        if (atBottom) {
            // Animate a genuinely new message; jump for a growing one, where an
            // animation restarted on every token would never finish.
            listState.jumpToTail(itemCount, animate = grew)
        } else if (grew) {
            unseen = true
        }
    }

    // Catching up clears the marker.
    LaunchedEffect(atBottom) { if (atBottom) unseen = false }

    return unseen
}

/** Everything that should make a follower move, including growth of the last item. */
fun tailRevision(vararg parts: Any?): Any = parts.toList()

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
 */
private suspend fun LazyListState.jumpToTail(itemCount: Int, animate: Boolean) {
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
