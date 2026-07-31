package com.silencelen.huginn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Keeps a conversation pinned to its newest content, and reports when there is
 * something new below the fold.
 *
 * Following is a LATCH, not a per-arrival test — that distinction is the fix for
 * a real failure on the phone. Asking "are they at the bottom?" at the moment
 * content arrives is asking a question whose answer has already changed: by the
 * time the effect runs the new content is laid out, so a reader who WAS at the
 * tail measures as scrolled away and following silently stops. So the state is
 * explicit:
 *
 *  * **At the bottom means locked on.** Reaching the tail by any route — scroll,
 *    fling, the pill, the initial jump — arms following, and while armed every
 *    content change scrolls, no measurement consulted.
 *  * **Only a drag breaks the lock.** Programmatic scrolls emit no drag, so the
 *    follower can never mistake its own scrolling for the reader leaving.
 *  * **A tap that goes nowhere is not leaving.** Releasing still at the tail
 *    re-arms at once.
 *
 * This is the phone's `AutoScrollToNewest`, moved where both clients can render
 * from it; `:app`'s copy in `ui/Common.kt` is the one to delete when the phone is
 * next touched. The desktop needs it just as much — a chat that renders the real
 * transcript grows thinking blocks and tool cards mid-turn, and a view that
 * jumps to the tail on every one of those cannot be read while a run is live.
 *
 * @param key re-arms the initial jump when the conversation changes.
 * @return true when new content arrived while the reader had scrolled away.
 */
@Composable
fun FollowNewest(
    listState: LazyListState,
    itemCount: Int,
    revision: Any?,
    key: Any?,
): Boolean {
    var opened by remember(key) { mutableStateOf(false) }
    var following by remember(key) { mutableStateOf(true) }
    var unseen by remember(key) { mutableStateOf(false) }
    var lastCount by remember(key) { mutableStateOf(0) }

    LaunchedEffect(key, listState) {
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    if (listState.isAtTail()) { following = true; unseen = false }
                else -> Unit
            }
        }
    }

    LaunchedEffect(key, listState) {
        snapshotFlow { listState.isAtTail() }.collect { at ->
            if (at) { following = true; unseen = false }
        }
    }

    LaunchedEffect(key, itemCount, revision) {
        if (itemCount <= 0) return@LaunchedEffect
        if (!opened) {
            listState.scrollToNewest(itemCount, animate = false)
            opened = true
            lastCount = itemCount
            return@LaunchedEffect
        }
        val grew = itemCount > lastCount
        lastCount = itemCount
        if (following) {
            // Animate a genuinely new row; jump for a growing one, where an
            // animation restarted on every token would never finish.
            listState.scrollToNewest(itemCount, animate = grew)
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
 * than the viewport; the trailing [scrollBy] walks the remainder and is clamped
 * by the list, so it cannot overshoot.
 *
 * Public because the pill needs the SAME landing: a bare `animateScrollToItem`
 * there leaves the reader at the top of a long answer and — worse — `isAtTail`
 * stays false, so the latch never re-arms and the pill sticks on screen while the
 * conversation moves on beneath it.
 */
suspend fun LazyListState.scrollToNewest(itemCount: Int, animate: Boolean) {
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
fun NewestPill(onClick: () -> Unit) {
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
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
