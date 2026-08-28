package com.silencelen.huginn.desktop.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The desktop's spacing scale and type ramp, in one table.
 *
 * WHY A TABLE AND NOT LITERALS AT THE CALL SITES. The shell was first drawn by
 * copying the phone's numbers into a wider window, and a phone's numbers are sized
 * for a thumb: 16dp of vertical padding per list row, a 20dp line box for a title
 * that a mouse never has to hit. At 1400px that does not read as generous, it
 * reads as a phone screenshot someone stretched. Fixing that one literal at a time
 * is how a layout ends up with 6dp, 7dp and 9dp all meaning "a small gap".
 *
 * THE SCALE IS 2/4/8/12/16/24/32 and nothing else. Every off-grid value that was
 * in the shell (5, 6, 7, 9, 10) has a neighbour here; where one is missing the
 * answer is to move the design onto the grid, not to add a step.
 *
 * The shared composables in `:ui` are NOT re-measured here — they have their own
 * seam ([com.silencelen.huginn.ui.LocalTranscriptMetrics] and `LocalMonoStyle`),
 * because they are also the phone's, and a dp changed here must never land on the
 * owner's daily driver.
 */
object Space {
    /** Between two lines of the same row: the title and its snippet. */
    val hair = 2.dp

    /** Inside a dense row, and between a mark and the word it marks. */
    val tight = 4.dp

    /** The default gap. Between controls, around a label, inside a chip. */
    val unit = 8.dp

    /** A row's horizontal inset, and the gap between unrelated controls. */
    val wide = 12.dp

    /** A pane's inset: the distance from content to the edge of its column. */
    val gutter = 16.dp

    /** Between sections of a scrolling page. */
    val section = 24.dp
}

/**
 * Fixed sizes the frame is built from. These are not spacing — they are the
 * geometry of the window itself, and each one is a decision rather than a gap.
 */
object Frame {
    /** The nav rail. An icon column — the words live on hover — and no wider. */
    val railWidth = 52.dp

    // The list pane's width is not here: it is persisted state, so its bounds live
    // with the thing that stores them (`com.silencelen.huginn.desktop.Splitter`).
    // Two copies of MIN would drift, and the one that drifts is always the one the
    // clamp reads.

    /** The seam: a 1px line inside an 8px hit area, which is a mouse target. */
    val splitterHit = 8.dp

    // THE NOTCH — the one element in the frame that straddles rather than sits
    // beside. It is a drawer pull on the top of the seam: wider than the seam it
    // is attached to, so it overhangs by 3dp on each side and reads as a tab on a
    // line rather than as a button that happens to be near one. Off the spacing
    // grid deliberately, like every other number in this object: these are the
    // geometry of a control, not gaps between things.

    /** The pull itself: wide enough for a chevron, narrow enough to stay quiet. */
    val notchWidth = 14.dp
    val notchHeight = 28.dp

    /**
     * How far down the seam it sits. Not flush with the top: the frame has no
     * title bar of its own, so a tab pinned to y=0 reads as part of the window
     * chrome above it rather than as part of the seam it belongs to.
     */
    val notchInset = 12.dp

    /** Barely rounded. A pill would be a button; this is a notch. */
    val notchCorner = 3.dp

    /** The chevron inside it. Sized to the tab, not to the rail's 20dp icons. */
    val notchChevron = 14.dp

    /** The status line at the foot of the window. */
    val statusHeight = 26.dp

    /**
     * How wide a PARAGRAPH is allowed to get.
     *
     * The only number here that is about reading rather than about the window. A
     * pane is as wide as the window, and an empty state's sentence set across all
     * of it is one long line the eye has to track back across — the pane looks
     * emptier for having text in it. [NothingOpen] had a cap from the start; the
     * full-width panes (Rounds, Devices) did not, which is the whole difference
     * between those two screenshots.
     */
    val prose = 420.dp

    /** A state dot, in the row's own text flow. Never a bar. */
    val dot = 7.dp

    /** The same mark, smaller, where it sits beside 11sp text: rail and status line. */
    val markDot = 5.dp
}

/**
 * The three row states, as tints. No left accent bar anywhere — house rule, and
 * the reason is that an accent bar is the single most legible tell of a generated
 * interface.
 *
 * A DESKTOP NEEDS THREE where a phone needs one. On the phone a row is either the
 * one you tapped or it is not. Here "the one whose detail fills the pane", "one of
 * several a bulk verb would address" and "the one under the pointer" are three
 * different facts that can all be true of different rows at the same time, and
 * they have to be told apart at a glance without shouting.
 *
 * [here] is a wash of the app's one accent rather than another grey. Three greys a
 * few percent apart is what the first attempt used, and on the real palette the
 * selected row was invisible until a screenshot was contrast-boosted to find it.
 * The wash is also what lets the SAME mark mean "where you are" in the nav rail and
 * in the list, which is one vocabulary instead of two.
 */
object Tints {
    /** Where you are: the open row, the current view. */
    val here: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)

    /** In the selection a bulk verb would address. */
    val marked: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** Under the pointer, and nothing more. */
    val hover: androidx.compose.ui.graphics.Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
}

/**
 * Type, one step tighter than the phone's.
 *
 * A desktop list is read at arm's length but at a glance, and every list in the
 * business (Finder, Explorer, a file tree, a mail list) sits at 12–13px with a
 * ~1.3 line box. Material's `bodyMedium` is 14sp on a 20sp line, which is a
 * reading size — right for a paragraph, wrong for two hundred rows.
 *
 * 13sp rather than 12: at 12 the accented and descender-heavy session names in
 * this app started to smear at the software-rendered sizes the headless check
 * runs at, and 13/17 keeps the row under 44dp anyway.
 */
object DeskType {
    /** A list row's first line: the chat's title, the session's name. */
    val rowTitle: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp)

    /** A list row's second line, and every muted suffix on the first. */
    val rowMeta: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp)

    /** A compact control label: the list headers' "+ New" buttons, inline loads. */
    val rail: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium

    /** A pane header — "Chats", "Sessions". */
    val paneTitle: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp)

    /** The status line. Small, because it is read only when looked for. */
    val status: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
}
