package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Suggested next messages, as chips above the composer.
 *
 * A chip FILLS THE COMPOSER; it does not send. That is the entire contract and
 * it is what makes suggestions safe to offer at all: a wrong guess costs a
 * keystroke to fix rather than a message nobody meant to send. Whether they
 * belong on screen at this instant is [Suggest.visible]'s decision, not this
 * composable's — it draws what it is given.
 *
 * One row, scrolled horizontally rather than wrapped: wrapping lets a set of
 * long suggestions grow to three lines and push the composer off a phone, and
 * the whole surface is optional.
 *
 * ⚠ AND NO CHIP IS WIDER THAN THE ROW. This is the half that was missing, and it
 * is why the phone's second suggestion started 102 px from the right edge of a
 * 1080 px screen and was otherwise invisible — the walk's D13, still there three
 * versions later. Inside a horizontal scroll the incoming maxWidth is INFINITE,
 * so `maxLines = 1` with an ellipsis never fires: a chip simply grows to the
 * length of its sentence and pushes the next one off the world. Capped against
 * the row, the long one ellipsises and the next one PEEKS, which is the only
 * thing that says a row scrolls at all.
 *
 * Ellipsising a chip costs nothing: tapping it fills the composer with the whole
 * suggestion, not with what was drawn.
 */
@Composable
fun SuggestionChips(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cap = suggestionChipMaxWidth(maxWidth)
        // ⚠ AND THE ROW SAYS IT SCROLLS (P-27/D-17). The cap above makes the next
        // chip PEEK, which was the only hint there was — and a peeking chip that
        // ends at the bezel still reads as a clipped one. See [EdgeFade].
        EdgeFadeRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { text ->
                SuggestionChip(
                    onClick = { onPick(text) },
                    modifier = Modifier.widthIn(max = cap),
                    label = {
                        Text(
                            text,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The fraction of the row one chip may occupy. Enough of the next chip has to
 * show to read as a chip rather than as a rendering accident.
 */
const val SUGGESTION_CHIP_FRACTION: Float = 0.78f

/** How wide one suggestion chip may be drawn in a row of [rowWidth]. */
fun suggestionChipMaxWidth(rowWidth: Dp): Dp = rowWidth * SUGGESTION_CHIP_FRACTION
