package com.silencelen.huginn.desktop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.Composer
import com.silencelen.huginn.desktop.ComposerLayout

/**
 * The frame both composers are drawn in — the chat's and the session's.
 *
 * THE OWNER REPORTED THIS ONE: *"when in a session or chat view in huginn
 * desktop, and snapping the screen to the side so it's skinny, the chat box is
 * forced to be very tall, taking away a lot of space from the above session chat
 * or screen view. We should look at moving the items in the layout like the send
 * and interrupt buttons into symbols and/or stacked, as well as attach, to give
 * the textbox more horizontal space."*
 *
 * WHY IT HAPPENS, which is the part that decides the fix. A composer is one Row
 * of fixed-size controls around a single WEIGHTED field, so the field is the only
 * child that can shrink — it absorbs every pixel Attach, Interrupt and Send
 * refuse to give up. At 420dp of window that is ~230dp of labelled buttons
 * against a field of ~190, the placeholder wraps to five lines, and the band
 * reaches 22% of the window for an EMPTY box. The transcript, which is the reason
 * the window is open, pays for all of it.
 *
 * So under [ComposerLayout.COMPACT_BELOW_DP] the field takes the whole width and
 * the controls go beneath it as their own icons — the owner's "symbols", on one
 * 32dp line instead of 230dp of words. Nothing else changes: same controls, same
 * order, same keyboard model (Enter sends, Shift+Enter is a newline, Ctrl+Enter
 * still sends), same attachments.
 *
 * ONE composable for both views rather than two parallel edits, because the two
 * composers have already drifted once — the chat's had no `widthIn` cap at all
 * while the session's stopped at 900dp, so at 1440 one of them spanned the pane
 * and the other did not. A shape they share cannot drift.
 *
 * @param attach the clip button. Already an icon in both callers, so it is the
 *   one control that does not change between the two layouts.
 * @param actions Stop/Interrupt and Send, in that order. Called in a `RowScope`
 *   so a caller can still weight or space something unusual.
 * @param field the text field. Handed the modifier it must use — the width
 *   (weighted, or full) AND the height cap, because both are decisions about the
 *   FRAME rather than about the field, and leaving the cap at the call site is
 *   how it stayed a constant 160dp through four window shapes.
 */
@Composable
fun ComposerFrame(
    attach: @Composable () -> Unit,
    actions: @Composable RowScope.(ComposerLayout) -> Unit,
    field: @Composable (Modifier, ComposerLayout) -> Unit,
) {
    // ONE measurement for both questions. maxWidth decides the shape; maxHeight is
    // the pane the field may not eat more than its share of — and in a Column the
    // unweighted composer is measured against the whole remaining column, which is
    // exactly the height the transcript would otherwise have had.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = ComposerLayout.of(maxWidth.value)
        val fieldHeight = Modifier.heightIn(
            min = Composer.FIELD_MIN_DP.dp,
            max = Composer.fieldMaxHeight(maxHeight.value).dp,
        )
        when (layout) {
            ComposerLayout.FULL -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attach()
                // CAP BEFORE FILL, and before the weight for the same reason: the
                // weight hands down a fixed width, and a cap inside fixed
                // constraints can only coerce into them. The chat composer had no
                // cap at all and spanned a 1440 pane while the session's stopped
                // at 900 — two boxes for one job, disagreeing about the job.
                field(
                    Modifier.widthIn(max = Composer.FIELD_MAX_WIDTH_DP.dp)
                        .weight(1f)
                        .then(fieldHeight),
                    layout,
                )
                actions(layout)
            }

            ComposerLayout.COMPACT -> Column(Modifier.fillMaxWidth()) {
                field(Modifier.fillMaxWidth().then(fieldHeight), layout)
                // FlowRow rather than Row: three icons fit in anything this frame
                // can now be dragged to, but a fourth control added later must
                // wrap rather than push Send off the edge — which is the failure
                // this whole file exists to undo.
                ComposerControlRow {
                    attach()
                    actions(layout)
                }
            }
        }
    }
}

/**
 * What is riding out with this message: the page reference, the attached file.
 *
 * ONE wrapping line rather than a stack of full-width Rows holding one chip each.
 * Two chips sit side by side where there is room and stack where there is not,
 * which is the difference between a composer that costs 12dp of band for its
 * attachments and one that costs 24 — on the window shape where the band was
 * already the complaint.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposerChips(content: @Composable RowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}

/** The controls' own line, under a compact field. Trailing, like the buttons were. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerControlRow(content: @Composable RowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // FlowRowScope IS a RowScope, which is what lets one `actions` lambda
        // serve both layouts without either caller knowing which it is in.
        content()
    }
}

/**
 * One composer verb, in whichever form this width can afford.
 *
 * A word at desk widths, because a labelled button is unambiguous and there is
 * room for it. Its own icon under the breakpoint, with the word on hover — the
 * same trade the nav rail already makes, which is why [Tip] is here rather than a
 * tooltip of its own invention.
 *
 * @param prominent the primary verb (Send). Filled in both shapes: a composer
 *   with two identical-weight icons beside it gives no clue which one commits.
 * @param danger Interrupt, which stops a live agent mid-turn. Red in both shapes,
 *   because the colour is the warning and dropping it at a narrow width would
 *   make the destructive control the QUIET one.
 */
@Composable
fun RowScope.ComposerAction(
    layout: ComposerLayout,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    prominent: Boolean = false,
    danger: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    if (layout == ComposerLayout.FULL) {
        when {
            danger -> OutlinedButton(onClick = onClick, enabled = enabled) {
                Text(label, color = scheme.error)
            }
            prominent -> Button(onClick = onClick, enabled = enabled) { Text(label) }
            else -> TextButton(onClick = onClick, enabled = enabled) { Text(label) }
        }
        return
    }
    Tip(label) {
        if (prominent) {
            FilledIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.size(COMPACT_CONTROL),
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(COMPACT_GLYPH))
            }
        } else {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.size(COMPACT_CONTROL),
                colors = if (danger) {
                    IconButtonDefaults.iconButtonColors(contentColor = scheme.error)
                } else {
                    IconButtonDefaults.iconButtonColors()
                },
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(COMPACT_GLYPH))
            }
        }
    }
}

/**
 * The compact control's own square. 32dp rather than Material's 40: this is a
 * mouse target on a narrow window, and the whole point of the shape is that the
 * line under the field costs as little height as it can while staying hittable.
 */
private val COMPACT_CONTROL = 32.dp

/** The glyph inside it. Sized to the control, not to the rail's 20dp icons. */
private val COMPACT_GLYPH = 18.dp
