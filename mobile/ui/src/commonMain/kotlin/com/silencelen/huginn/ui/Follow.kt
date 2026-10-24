package com.silencelen.huginn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.ui.theme.LocalHuginnColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a conversation is pinned to its newest content — the decision alone,
 * with no Compose in it.
 *
 * Lifted out of [FollowNewest] after the FOURTH bug in this latch, for the same
 * reason [LocalEcho], [TranscriptGroups] and `WatchCycle.finishedSince` were
 * lifted out of their views: while the rule lived inside a composable it could
 * not be tested at all, so every one of those bugs was found by the owner, in a
 * live conversation, one at a time.
 *
 * Each rule below was a failure first:
 *
 *  * **Reaching the tail locks on.** Any route there — a fling, the pill, the
 *    initial jump — arms following.
 *  * **Content arriving measures NOTHING.** Asking "are they at the bottom?" as
 *    content lands asks a question whose answer has already changed: the new rows
 *    are laid out by then, so a reader who WAS at the tail measures as scrolled
 *    away and following silently stops. That is why [arrived] has no geometry in
 *    its signature to be tempted by.
 *  * **Only the reader's own input unlatches.** A programmatic scroll is not an
 *    input, so the follower can never mistake its own scrolling for the reader
 *    leaving.
 *  * **An input that goes nowhere is not leaving.** Ending still at the tail
 *    re-arms at once — a tap that does not move, or a wheel tick at the bottom.
 *  * **Following holds through a re-measure, not only through new content.**
 *    See [relaid].
 */
object Follow {

    /**
     * @param following locked on: every content change scrolls to the newest.
     * @param unseen something new arrived while the reader was away — the pill.
     * @param atTail the list as last measured: is the end of the content on
     *   screen? Recorded by [settled] on every change, so [away] can be read
     *   without asking the list again.
     */
    data class State(val following: Boolean = true, val unseen: Boolean = false, val atTail: Boolean = true) {
        /**
         * Scrolled off the tail: the reader left AND the bottom is out of view —
         * the "Back to bottom" pill. Both halves on purpose. The latch alone
         * flips off at the first wheel tick, before the list has moved, so a
         * reader wheeling down AT the bottom would see the pill flash on every
         * tick; the geometry alone goes off-tail for a frame whenever rows land
         * below a following reader, who is about to be scrolled to them.
         */
        val away: Boolean get() = !following && !atTail
    }

    /**
     * The reader took the list: a finger on it, or a wheel/trackpad tick.
     *
     * Unconditional, and judged afterwards by [settled] rather than here, because
     * where an input LEAVES the reader is not knowable at the moment it arrives —
     * the list has not moved yet.
     */
    fun tookControl(s: State): State = s.copy(following = false)

    /**
     * The scroll they started has stopped. Still at the tail means they never
     * really left, so following re-arms and the pill goes with it.
     */
    fun settled(s: State, atTail: Boolean): State =
        if (atTail) State(following = true, unseen = false, atTail = true) else s.copy(atTail = false)

    /**
     * Content changed: while following this stays following and the caller
     * scrolls; while not, it only records that there is something new below.
     *
     * @param grew a genuinely new ROW, as against text growing into the one that
     *   is already at the bottom — a stream of tokens is not "new messages".
     * @param prepended the rows that arrived landed at the TOP: an earlier page
     *   of history the reader asked for. That is neither news below the fold nor
     *   a reason to move: the state is left exactly as it was, and
     *   [scrollsOnArrival] says no. ⚠ BUG 6 (2026-10-23): "Load earlier
     *   messages" raised the count, the count read as new content, and a reader
     *   at the top got a "New messages" pill for pages above them.
     *
     *   Following is NOT ended by a prepend. The only reader who is following
     *   when history lands is one whose whole transcript fitted the screen, and
     *   the list keeps them at its end (it fills the viewport from the bottom
     *   when the content overflows) — so they are still at the tail, and the
     *   next real message should still come to them rather than raise a pill.
     */
    fun arrived(s: State, grew: Boolean, prepended: Boolean = false): State = when {
        prepended -> s
        !s.following && grew -> s.copy(unseen = true)
        else -> s
    }

    /**
     * Whether the caller scrolls to the newest after [arrived]: while following,
     * and never for a prepend — the rows that landed are above the reader, and
     * the bottom of the list is not where they asked to go.
     */
    fun scrollsOnArrival(s: State, prepended: Boolean): Boolean = s.following && !prepended

    /**
     * The list as measured, with where it is scrolled to taken out: the
     * viewport, and how tall each row on screen came out, by its key.
     */
    data class Shape(val width: Int, val height: Int, val rows: Map<Any, Int>)

    /**
     * The list was measured again: whether to put the reader back on the newest.
     *
     * ⚠ THE BUG (owner, 2026-10-23): "when a user opens a conversation they chat
     * correctly starts from the bottom (newest at bottom), but then the status
     * and chat suggestion bars load in causing the user to have to scroll a bit
     * down to lock it in to the bottom." The jump to the newest ran when content
     * changed and at no other time. A strip that arrives under the list a moment
     * later (the work strip, the suggestions, a composer growing a line) takes
     * its height out of the viewport, the list keeps its top where it was, and
     * the newest rows end up underneath — while nothing has scrolled and the
     * latch still says following.
     *
     * So a re-measure scrolls too, while [State.following] and only when there
     * is further to go. And only for a change NO SCROLL CAN MAKE: a scroll
     * changes which rows are on screen, never the viewport and never how tall a
     * row is, so a scroll in progress is never itself mistaken for a re-measure.
     *
     * A row that changes height counts only while [settling] — content arrived,
     * or the conversation opened, a moment ago, so this is that content still
     * laying out (an image decoding, a block measured late). Any later change
     * in a row is somebody opening or closing a card, and putting them at the
     * bottom of what they just opened is not following, it is losing their
     * place. The viewport counts at any time: the bars come when they come.
     */
    fun relaid(s: State, before: Shape, after: Shape, canScrollFurther: Boolean, settling: Boolean): Boolean {
        if (!s.following || !canScrollFurther) return false
        if (before.width != after.width || before.height != after.height) return true
        return settling && before.rows.any { (key, height) -> after.rows[key].let { it != null && it != height } }
    }
}

/**
 * Keeps a conversation pinned to its newest content, and reports when there is
 * something new below the fold. The rules are [Follow]; this is the wiring.
 *
 * BOTH CLIENTS FOLLOW WITH THIS ONE. It began as the phone's `AutoScrollToNewest`,
 * moved here for the desktop, and the phone kept its own copy until 2026-10-23 —
 * when that copy missed the fix for bars arriving under the list (see
 * [Follow.relaid]) and was deleted. A chat that renders the real transcript
 * grows thinking blocks and tool cards mid-turn, and a view that jumps to the
 * tail on every one of those cannot be read while a run is live, on either.
 *
 * @param key re-arms the initial jump when the conversation changes.
 * @param scrolls the reader's own scroll input, counted by [onScrollInput] on the
 *   list. A caller that leaves this out gets a latch that only a FINGER can
 *   break, which is correct on the phone and silently wrong anywhere there is a
 *   mouse: a wheel emits no [DragInteraction], so the reader scrolls up to read
 *   something, the next token yanks them back to the tail, and it never stops.
 * @param scrollbar the interaction source of a scrollbar over the list, when
 *   there is one. Dragging its thumb moves the list without a single event the
 *   list sees, so without this a reader who scrolled up by the thumb was still
 *   "following" — and the next token, or a strip arriving under the list, put
 *   them back at the tail. Its drags count exactly as the list's own do.
 * @param prepends changes when rows were added at the TOP — the page's
 *   `windowStart`, which only moves when an earlier page lands. A growth that
 *   arrives with a change here is history, not news: see [Follow.arrived]'s
 *   `prepended`. Leave it null for a list that never pages backwards.
 * @return the latch's state: [Follow.State.away] and [Follow.State.unseen] are
 *   what the pill reads.
 */
@Composable
fun FollowNewest(
    listState: LazyListState,
    itemCount: Int,
    revision: Any?,
    key: Any?,
    scrolls: State<Int> = remember { mutableStateOf(0) },
    scrollbar: InteractionSource? = null,
    prepends: Any? = null,
): Follow.State {
    var opened by remember(key) { mutableStateOf(false) }
    var state by remember(key) { mutableStateOf(Follow.State()) }
    var lastCount by remember(key) { mutableStateOf(0) }
    var lastPrepends by remember(key) { mutableStateOf(prepends) }
    // Content arrived (or the conversation opened) within SETTLE_MS: a row that
    // changes height now is that content still laying out. See Follow.relaid.
    var settling by remember(key) { mutableStateOf(true) }
    val count by rememberUpdatedState(itemCount)

    LaunchedEffect(key, listState, scrollbar) {
        listOfNotNull(listState.interactionSource, scrollbar).map { it.interactions }.merge().collect { i ->
            when (i) {
                is DragInteraction.Start -> state = Follow.tookControl(state)
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    state = Follow.settled(state, listState.isAtTail())
                else -> Unit
            }
        }
    }

    // The wheel's drag-start and drag-stop, neither of which the wheel has: it
    // emits no DragInteraction at all, so the latch above is armed-only on a
    // mouse and a live conversation cannot be read — scroll up and the next token
    // puts you back at the tail, every time.
    LaunchedEffect(key, listState, scrolls) {
        snapshotFlow { scrolls.value }.collectLatest { n ->
            if (n == 0) return@collectLatest
            state = Follow.tookControl(state)
            // Where that tick left them is only knowable once it has been
            // applied, and the wheel's scroll is both deferred by a frame and
            // animated — measuring on the event itself would measure the position
            // they are in the middle of leaving and re-arm immediately. Waiting
            // for the scroll to stop is the wheel's DragInteraction.Stop.
            //
            // The start window is for the tick that moves nothing at all, a
            // wheel-down at the very bottom: no scroll ever begins, and without a
            // cap the settle would never run and a reader sitting AT the tail
            // would be left unlatched, watching a conversation stop following.
            withTimeoutOrNull(SCROLL_START_MS) {
                snapshotFlow { listState.isScrollInProgress }.first { it }
            }
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            state = Follow.settled(state, listState.isAtTail())
        }
    }

    LaunchedEffect(key, listState) {
        snapshotFlow { listState.isAtTail() }.collect { at ->
            state = Follow.settled(state, at)
        }
    }

    // ⚠ THE BARS THAT ARRIVE AFTER THE JUMP (owner, 2026-10-23). Every measure,
    // compared with the one before, through Follow.relaid — which is where the
    // reasons live. `collect`, not `collectLatest`: the scroll below re-measures
    // the list, and a collector that cancelled itself on its own re-measure would
    // stop halfway down.
    LaunchedEffect(key, listState) {
        var before: Follow.Shape? = null
        snapshotFlow { listState.layoutInfo.shape() }.collect { after ->
            val prev = before
            before = after
            if (prev == null || !opened) return@collect
            if (!Follow.relaid(state, prev, after, listState.canScrollForward, settling)) return@collect
            // ⚠ OUT OF THE LAYOUT PASS FIRST. A LazyColumn sends its snapshot
            // notifications while it places its children, so this can resume
            // inside the very pass that measured `after` — and a scroll from there
            // is a re-entrant measure, which Compose refuses with an exception.
            // The next frame is outside it; whatever moved meanwhile is re-asked.
            withFrameNanos { }
            if (!state.following || !listState.canScrollForward) return@collect
            try {
                listState.scrollToNewest(count, animate = false)
            } catch (e: CancellationException) {
                // A scroll that started meanwhile (the reader's, or the arrival
                // effect's) took the list: that is not this collector ending.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    // `prepends` is a key too, so a change there is consumed by THIS run and can
    // never be left over to misread the next real arrival as history.
    LaunchedEffect(key, itemCount, revision, prepends) {
        val prepended = prepends != lastPrepends
        lastPrepends = prepends
        if (itemCount <= 0) return@LaunchedEffect
        settling = true
        try {
            if (!opened) {
                listState.scrollToNewest(itemCount, animate = false)
                opened = true
                lastCount = itemCount
            } else {
                val grew = itemCount > lastCount
                lastCount = itemCount
                state = Follow.arrived(state, grew, prepended)
                if (Follow.scrollsOnArrival(state, prepended)) {
                    // Animate a genuinely new row; jump for a growing one, where an
                    // animation restarted on every token would never finish.
                    listState.scrollToNewest(itemCount, animate = grew)
                }
            }
        } catch (e: CancellationException) {
            // Another scroll took the list (the reader's, or the re-measure
            // collector's); this arrival still has its settling time to run out.
            currentCoroutineContext().ensureActive()
        }
        // Restarted by the next arrival, which is what keeps a live answer
        // settling for as long as it is still arriving.
        delay(SETTLE_MS)
        settling = false
    }

    return state
}

/** How long a wheel tick is given to actually start scrolling before it counts as one that moved nothing. */
private const val SCROLL_START_MS: Long = 120

/**
 * How long after content arrives a row changing height is still that content
 * laying out (an image decoding, a block measured late) rather than the reader
 * opening or closing a card. See [Follow.relaid].
 */
private const val SETTLE_MS: Long = 1_500

/** This measure's [Follow.Shape]: the viewport, and each row on screen by key. */
private fun LazyListLayoutInfo.shape(): Follow.Shape =
    Follow.Shape(viewportSize.width, viewportSize.height, visibleItemsInfo.associate { it.key to it.size })

/**
 * Counts real scroll input — a wheel detent, a trackpad glide — on the list it
 * decorates, for [FollowNewest]'s `scrolls`.
 *
 * A POINTER event on purpose: it exists only because a device sent one, so no
 * amount of programmatic scrolling can produce one. Any signal derived from the
 * list's own position could not make that promise — content arriving below the
 * fold moves the position too, and mistaking that for the reader leaving is this
 * file's oldest bug.
 */
@Composable
fun Modifier.onScrollInput(onScroll: () -> Unit): Modifier {
    // The gesture loop below is started once and captures whatever it is given;
    // without this it would keep calling the FIRST composition's lambda.
    val scrolled by rememberUpdatedState(onScroll)
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Initial, so the report happens whether or not the list goes on
                // to consume the scroll.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll) scrolled()
            }
        }
    }
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
 * ⚠ THE REMAINDER IS ASKED OF THE LIST, NOT OF [isAtTail]. Its 48 px of slack is
 * right for judging whether a READER is at the bottom and wrong for landing
 * there: a last row up to 48 px taller than the viewport counted as "at the
 * tail" and kept its last lines below the fold.
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
    if (canScrollForward) scrollBy(1_000_000f)
}

/**
 * The way back to the newest, floating over the bottom of the list — one pill
 * for both apps, chats and sessions.
 *
 * It reads "Back to bottom" while the reader is [away] (scrolled off the tail)
 * and "New messages" once something arrived below them ([unseen]), and shows
 * nothing while they are following. Without the first the reader cannot tell
 * "nothing new" from "not following"; without the second a deliberate scroll
 * up to re-read looked like the app had stopped.
 *
 * FLOATING, in a [BoxScope] over the list, not a row in the column under it: a
 * row took its height out of the viewport when it appeared, which moved the
 * very rows the reader was looking at. Lands with [scrollToNewest] — the same
 * landing the follower uses, or the latch never re-arms and the pill sticks.
 *
 * A routine pill, not the one filled button on the screen (that is Send):
 * tier 4 with a hairline, the house arrow, label type.
 */
@Composable
fun BoxScope.BackToBottomPill(
    away: Boolean,
    unseen: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = away || unseen,
        modifier = modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        // BRAND.md section 6: arrivals cubic-bezier(0.2, 0, 0, 1), departures
        // ease-in, both inside the 150–400 ms band, no overshoot.
        enter = fadeIn(tween(PILL_IN_MS, easing = ARRIVE)) + slideInVertically(tween(PILL_IN_MS, easing = ARRIVE)) { it / 2 },
        exit = fadeOut(tween(PILL_OUT_MS, easing = DEPART)) + slideOutVertically(tween(PILL_OUT_MS, easing = DEPART)) { it / 2 },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = CircleShape,
            border = BorderStroke(1.dp, LocalHuginnColors.current.outlineHairline),
            modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).testTag(BACK_TO_BOTTOM_TAG),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (unseen) NEW_MESSAGES else BACK_TO_BOTTOM,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** The pill's testTag and its two readings, for the shells' tests. */
const val BACK_TO_BOTTOM_TAG: String = "backToBottom"
const val BACK_TO_BOTTOM: String = "Back to bottom"
const val NEW_MESSAGES: String = "New messages"

private const val PILL_IN_MS = 200
private const val PILL_OUT_MS = 150
private val ARRIVE = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val DEPART = CubicBezierEasing(0.4f, 0f, 1f, 1f)
