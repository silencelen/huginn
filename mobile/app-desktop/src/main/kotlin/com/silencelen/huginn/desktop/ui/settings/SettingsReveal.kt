package com.silencelen.huginn.desktop.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * TAKING THE READER TO THE ROW THEY SEARCHED FOR.
 *
 * ⚠ D-23. THE MARK EXISTED AND NOBODY EVER SAW IT. Settings search already
 * decides which row matched, already opens the right drawer, and already tells
 * that drawer which row to mark — `SettingsScaffoldRules.open` returns the pair
 * precisely so the two cannot disagree. What nothing did was MOVE. Searching
 * *awake* found "Keep a window rotating → Usage & headroom" and opened Usage &
 * headroom scrolled to the top, with the matched row two full screens down: the
 * reader sees a page they did not ask for and has to find by hand the thing the
 * app had already located for them.
 *
 * ⚠ IT IS A MEASUREMENT, NOT AN INDEX. A settings page is a `Column` in a
 * `verticalScroll` whose rows are different heights, several of which are whole
 * forms — so "the fourth row" is not a scroll position, and any arithmetic on
 * row counts would be wrong the first time a row grew a second summary line. The
 * marked row reports where it actually landed and the pane scrolls by the
 * difference. Two facts make that possible and both are already on screen: the
 * pane's own top ([anchorPane]) and the row's ([target]), each in root
 * coordinates, so neither has to know anything about the other's layout.
 *
 * ⚠ ONCE PER ARRIVAL. `onGloballyPositioned` fires on every layout pass — a
 * hover, a poll landing a new summary, the reader's own scroll — and a scroll
 * that re-fires is a pane that fights the wheel. [done] is that latch, and the
 * instance is `remember`ed on the mark, so a second search hit is a second
 * arrival and moves again.
 *
 * THE MARK ITSELF IS NOT TOUCHED. The tint and the dot beside the title are the
 * shared row's, drawn from `highlighted`, and they stay until the reader opens
 * another drawer — which is the same vernacular the nav rail and the open list
 * row use for "where you are". This file only makes them visible.
 */
class SettingsReveal internal constructor(
    /** The row a search hit is arriving at, or null when this is an ordinary open. */
    private val mark: String?,
    /** Where the pane is scrolled to now, and how far it can go. */
    private val here: () -> Int,
    private val extent: () -> Int,
    /** Take the pane there. A launch into the composition's scope, in the real thing. */
    private val goTo: (Int) -> Unit,
) {

    private var paneTop: Float? = null
    private var done = false

    /** Where the scrolling pane starts, in root coordinates. */
    fun anchorPane(topInRoot: Float) {
        paneTop = topInRoot
    }

    /** Whether anything on screen should be reporting its position at all. */
    fun claims(id: String): Boolean = mark != null && mark == id

    /** The same question for a section that draws rows this shell cannot reach. */
    fun claimsAny(predicate: (String) -> Boolean): Boolean = mark != null && predicate(mark)

    /**
     * The marked row has been laid out at [topInRoot] — go there.
     *
     * @return whether this call moved the pane. False for the second and every
     *   later layout pass of one arrival, and false before the pane has said
     *   where its own top is.
     */
    fun target(topInRoot: Float): Boolean {
        val top = paneTop ?: return false
        if (done) return false
        done = true
        goTo(scrollTarget(here(), top, topInRoot, extent()))
        return true
    }

    companion object {

        /** Breathing room above the row that was found. One settings row's height. */
        const val MARGIN_DP: Float = 44f

        /**
         * Where the pane has to be scrolled to for a row at [rowTop] to be near
         * the top of it — all four numbers in the units they arrive in.
         *
         * Pure, because it is the whole of the decision and the only part that
         * can be wrong arithmetically: the row and the pane report root
         * coordinates, which move as the pane scrolls, so the offset is a
         * DIFFERENCE added to where the pane already is. Clamped, because a
         * match near the end of a short page cannot be scrolled to the top and
         * must not leave the pane blank trying.
         */
        fun scrollTarget(current: Int, paneTop: Float, rowTop: Float, max: Int): Int =
            (current + (rowTop - paneTop) - MARGIN_DP)
                .roundToInt()
                .coerceIn(0, max.coerceAtLeast(0))
    }
}

/**
 * The one reveal for this pane, forgotten and rebuilt on every arrival.
 *
 * Keyed on the mark so the [SettingsReveal.done] latch belongs to ONE arrival:
 * searching twice for two rows in the same drawer is two journeys, and a latch
 * shared between them would strand the second.
 */
@Composable
fun rememberSettingsReveal(mark: String?, scroll: ScrollState): SettingsReveal {
    val scope = rememberCoroutineScope()
    return remember(mark, scroll) {
        SettingsReveal(
            mark = mark,
            here = { scroll.value },
            extent = { scroll.maxValue },
            // ANIMATED, not snapped: a pane that teleports leaves the reader with
            // no idea whether they are above or below where they were, and the
            // row they were sent to is then just a row.
            goTo = { scope.launch { scroll.animateScrollTo(it) } },
        )
    }
}

/**
 * Null outside Settings, which is why every reader goes through the helpers
 * below rather than through `.current!!` — the same shape as the transcript
 * metrics: a shell that provides nothing gets the no-op answer.
 */
val LocalSettingsReveal = staticCompositionLocalOf<SettingsReveal?> { null }

/** A row that search may arrive at. `Modifier` unchanged when it is not this one. */
@Composable
internal fun revealMark(id: String): Modifier {
    val reveal = LocalSettingsReveal.current ?: return Modifier
    if (!reveal.claims(id)) return Modifier
    return Modifier.onGloballyPositioned { reveal.target(it.positionInRoot().y) }
}

/**
 * A whole SECTION that may hold the row, for the forms this shell hosts but does
 * not draw.
 *
 * ⚠ THE SHARED SECTIONS TAKE NO MARK. `HeadroomSettingsSection` owns seventeen
 * catalog rows and its signature has nowhere to put "and mark this one", so the
 * best this shell can do for a hit inside it is to put the section on screen.
 * That is still the difference between "the page you asked for" and "the page
 * you asked for, scrolled past the two screens you did not" — and it is honest
 * about being section-accurate rather than row-accurate.
 */
@Composable
internal fun revealSection(claims: (String) -> Boolean): Modifier {
    val reveal = LocalSettingsReveal.current ?: return Modifier
    if (!reveal.claimsAny(claims)) return Modifier
    return Modifier.onGloballyPositioned { reveal.target(it.positionInRoot().y) }
}
