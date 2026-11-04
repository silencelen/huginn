package com.silencelen.huginn.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * THE BOTTOM OF A CONVERSATION — the pinned card (or the question bar), the
 * long-press verbs and the composer — MEASURED FROM THE BOTTOM UP.
 *
 * ⚠⚠ A COLUMN MEASURES ITS CHILDREN IN ORDER, AND THE COMPOSER WAS LAST. The
 * card was a `BoxWithConstraints` above the composer, capped at 0.7 of the
 * height it was handed — which was everything still left in the column,
 * composer included. So a tall card took its 70% first and the composer got
 * whatever remained: on the Fold's cover screen with the keyboard up, a
 * start_session card left it a sliver with its placeholder clipped, and a
 * multi-line draft was squeezed the same way with no keyboard at all.
 *
 * Here the composer is measured FIRST, with every pixel the column can give,
 * then the verbs bar, and only then the card, inside [CardRoom.cap] of what is
 * left above them. Placed top to bottom as before: card, bar, composer. The
 * transcript above (the column's weighted child) keeps the rest.
 *
 * The card slot is handed a height it may fill, not a share of one: read it
 * with `BoxWithConstraints` and pass `maxHeight` to the card as its cap.
 */
@Composable
fun ComposerStack(
    card: @Composable () -> Unit,
    bar: @Composable () -> Unit,
    composer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val floor = with(LocalDensity.current) { CardRoom.FLOOR.roundToPx() }
    Layout(contents = listOf(card, bar, composer), modifier = modifier) { slots, constraints ->
        val (cardSlot, barSlot, composerSlot) = slots
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        var cardP = emptyList<Placeable>()
        var barP = emptyList<Placeable>()
        var composerP = emptyList<Placeable>()
        val heights = CardRoom.bottomUp(
            room = constraints.maxHeight,
            floor = floor,
            composer = { max -> composerP = composerSlot.map { it.measure(loose.within(max)) }; composerP.sumOf { it.height } },
            bar = { max -> barP = barSlot.map { it.measure(loose.within(max)) }; barP.sumOf { it.height } },
            card = { max -> cardP = cardSlot.map { it.measure(loose.within(max)) }; cardP.sumOf { it.height } },
        )
        val placed = cardP + barP + composerP
        val width = (placed.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, heights.total.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            for (p in placed) {
                p.placeRelative(0, y)
                y += p.height
            }
        }
    }
}

private fun Constraints.within(max: Int): Constraints =
    if (max == Constraints.Infinity) this else copy(maxHeight = max.coerceAtMost(maxHeight))

/**
 * How much of a conversation's bottom the pinned card may take.
 *
 * ⚠ FROM THE ROOM LEFT ABOVE THE COMPOSER, never from the column. The card
 * scrolls inside its cap (owner, 2026-10-26: a tall question's lower options
 * and its own answer buttons stayed reachable that way), and the cap is what
 * decides whether anything below it fits. [SHARE] of the free room is the
 * card's, the rest stays the transcript's sliver; under [FLOOR] a sliver of
 * either is useless, so the card takes all of it.
 *
 * Pure, so the rule is asserted rather than eyeballed: the composer can never
 * be squeezed by the card ([bottomUp]), whatever the room and whatever is typed.
 */
object CardRoom {
    /** The card's share of the free room, the owner's 0.7 (2026-10-26). */
    const val SHARE: Float = 0.7f

    /** Below this much free room the card takes it all rather than 70% of nothing. */
    val FLOOR: Dp = 120.dp

    /** What [bottomUp] measured, top to bottom. */
    class Heights(val card: Int, val bar: Int, val composer: Int) {
        val total: Int get() = card + bar + composer
    }

    /**
     * The card's height cap, in px: [SHARE] of what [room] leaves over [below]
     * (the composer and the verbs bar, already measured), or all of that when
     * it is under [floor]. [room] unbounded ([Int.MAX_VALUE]) caps nothing.
     */
    fun cap(room: Int, below: Int, floor: Int, share: Float = SHARE): Int {
        if (room == Int.MAX_VALUE) return Int.MAX_VALUE
        val free = (room - below).coerceAtLeast(0)
        // Rounded, not truncated: 0.7f is 0.69999999, and 70% of 1640 px is 1148.
        return maxOf((free * share).roundToInt(), minOf(free, floor)).coerceAtMost(free)
    }

    /**
     * The measuring ORDER, as a function of three slots: each lambda measures
     * its slot within the height it is given and answers the height it took.
     * The composer first with the whole [room], the bar with what that left,
     * the card last with its [cap].
     */
    fun bottomUp(
        room: Int,
        floor: Int,
        composer: (Int) -> Int,
        bar: (Int) -> Int,
        card: (Int) -> Int,
    ): Heights {
        val c = composer(room)
        val b = bar(left(room, c))
        val k = card(cap(room, c + b, floor))
        return Heights(card = k, bar = b, composer = c)
    }

    private fun left(room: Int, used: Int): Int =
        if (room == Int.MAX_VALUE) Int.MAX_VALUE else (room - used).coerceAtLeast(0)
}

/**
 * The room a field's own button takes under it: a Material button (48 dp with
 * its touch target) and the gap above it.
 */
val FIELD_ACTION_ROOM: Dp = 56.dp

/**
 * Scrolling a focused field into view WITH THE BUTTON UNDER IT.
 *
 * ⚠ "Decline" opens a "Tell Claude why" field with "Decline, and tell Claude"
 * under it; "Other…" opens a field with Answer under it; Re-role, a field with
 * "Send the new task". The platform brings the FIELD into view and stops at its
 * bottom edge, so with the keyboard up the button that sends what was typed sat
 * just below the fold, and the next tap went somewhere else. This asks for
 * [below] more under the child when the viewport can hold it, and is the
 * platform's own rule otherwise.
 *
 * ⚠ It also decides what "already visible" means (Compose asks the same spec),
 * so a field must have at least [below] of content under it — every field it
 * is used around has its button there (66 dp in the cards: 6 + 48 + the card's
 * 12 dp padding). A field with nothing under it would read as not-quite-visible
 * and lose the keep-in-view when the keyboard opens.
 */
object FieldInView {
    /**
     * How far to scroll so the child at [offset] (from the viewport's leading
     * edge) of [size] is in a viewport of [container], with [below] under it.
     */
    fun scrollBy(offset: Float, size: Float, container: Float, below: Float): Float {
        val leading = offset
        val trailing = offset + size
        // Taller than the viewport: nothing can show it whole, so the platform's
        // rule (stay if it spans, else the nearer edge) and no extra room.
        if (size > container) return when {
            leading < 0f && trailing > container -> 0f
            leading >= 0f && trailing <= container -> 0f
            abs(leading) < abs(trailing - container) -> leading
            else -> trailing - container
        }
        // The extra room shrinks rather than push the child's own top out.
        val end = minOf(trailing + below, leading + container)
        return when {
            leading < 0f -> leading
            end > container -> end - container
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class RoomBelowSpec(private val below: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        FieldInView.scrollBy(offset, size, containerSize, below)
}

/**
 * Every scroll inside [content] brings a focused field into view with
 * [FIELD_ACTION_ROOM] under it — see [FieldInView]. The cards' own scroll and
 * the project page's read it through `LocalBringIntoViewSpec`, which is also
 * what keeps the focused field in view when the keyboard shrinks them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoomBelowFocused(content: @Composable () -> Unit) {
    val below = with(LocalDensity.current) { FIELD_ACTION_ROOM.toPx() }
    val spec = remember(below) { RoomBelowSpec(below) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
