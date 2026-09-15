package com.silencelen.huginn.desktop.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The bar that says how much more there is — and lets a mouse go and get it.
 *
 * THERE WAS NOT ONE IN THE WHOLE DESKTOP APP. Settings, Status, Devices, Rounds,
 * both list panes and both transcripts were bare `verticalScroll`/`LazyColumn`,
 * which on a phone is right and on a desk is a pane with no position, no extent,
 * no drag — and, on the two panes where content routinely runs off the bottom, no
 * signal that there is anything below at all. Compose Desktop ships the control;
 * this is one call site per pane rather than eleven hand-placed ones.
 *
 * It does NOT change scroll behaviour. The adapter reads the same state the
 * content is already scrolled by, so the wheel, the keyboard, the follow-the-tail
 * effects and every `animateScrollToItem` in the app keep working exactly as they
 * did — the bar is a second way to drive the same number, never a second number.
 */
@Composable
fun BoxScope.PaneScrollbar(state: ScrollState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        style = deskScrollbar(),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
}

/** The same bar, over a lazy list. */
@Composable
fun BoxScope.PaneScrollbar(state: LazyListState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        style = deskScrollbar(),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
}

/**
 * ⚠ THE DEFAULT SCROLLBAR IS INVISIBLE IN THIS APP. Compose Desktop's default
 * thumb is `Color.Black` at 50% — right on a light desktop, and on this dark
 * theme a slightly-darker smudge on a dark ground. First screenshot of the fix
 * showed the bar present and unreadable, which is a control nobody will find.
 *
 * So the thumb is drawn from the THEME instead: the same `onSurfaceVariant` every
 * other quiet mark in the frame uses, faint at rest and twice as strong under the
 * pointer. 8dp rather than Material's 12: this rides the inside edge of panes
 * that already have their own gutter.
 */
@Composable
private fun deskScrollbar(): ScrollbarStyle {
    val scheme = MaterialTheme.colorScheme
    return ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300,
        unhoverColor = scheme.onSurfaceVariant.copy(alpha = 0.30f),
        hoverColor = scheme.onSurfaceVariant.copy(alpha = 0.60f),
    )
}

/**
 * A full-width pane that is actually READ: capped to a measure, centred, scrolled,
 * and with a bar saying how far down it is.
 *
 * `Frame.prose` has existed since the frame did and was applied to EMPTY STATES
 * ONLY — so the one place the app set a reading measure was the place with the
 * least to read. Status, Devices, Rounds and Settings ran to the pane edge:
 * >90 characters a line from about 1000px of window onward, and the Headroom
 * intro set ~135 characters on one line at 1440. The phone had already solved
 * this (`MainActivity` centres Rounds/Status/Settings/Devices in `widthIn(max =
 * 840.dp)`); this is that constant, arrived at from the same direction.
 *
 * ⚠ CAP BEFORE FILL. The cap is on the OUTSIDE of the `fillMaxWidth`, because
 * `fillMaxWidth` hands down fixed constraints and a `widthIn` inside fixed
 * constraints can only coerce into them. The other order compiles, reads as a
 * capped column, and spans the window — twice in this tree already, which is why
 * `CapBeforeFillTest` now greps for it.
 *
 * @param padding the pane's own inset. Each pane has a different one and always
 *   did; it is passed rather than unified because Settings' 24dp gutter and the
 *   Devices cards' 12dp are two different decisions about two different contents.
 */
@Composable
fun ReadingPane(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Space.section),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = Frame.reading).fillMaxWidth().padding(padding),
                content = content,
            )
        }
        PaneScrollbar(scroll)
    }
}
