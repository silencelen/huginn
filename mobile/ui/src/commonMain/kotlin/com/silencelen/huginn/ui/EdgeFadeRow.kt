package com.silencelen.huginn.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A ROW THAT SCROLLS SIDEWAYS AND SAYS SO.
 *
 * ⚠⚠ EVERY CHIP ROW IN THIS PRODUCT ALREADY SCROLLED; NONE OF THEM ADMITTED IT
 * (P-27/D-17). The model-chip row cut "resumes on reset" at the phone's right
 * bezel, the post-turn suggestion chips cut the second chip mid-word, and the
 * Screen tab's key pad sliced `PgUp` in half on both shells. All three are
 * `Modifier.horizontalScroll`, all three work, and nothing on screen said to
 * drag them — on the desktop the gesture is shift+scroll, which is not a thing a
 * person tries on a row that simply looks clipped. A control cut by the window
 * frame reads as a rendering fault, and the reaction to a rendering fault is to
 * stop looking there.
 *
 * ⚠ THE FADE IS A MASK, NOT A TINT, and that is what makes it one implementation
 * for two shells and both themes. Painting a gradient of the background colour
 * over the edge requires knowing the background, which here is a half-alpha
 * `surfaceVariant` over `background` on the phone and the same over a root
 * Surface on the desktop — two different answers, neither of them available to
 * this composable. `BlendMode.DstIn` against an alpha ramp fades the CONTENT to
 * transparent instead, so whatever is behind the row shows through and the
 * result is correct on any ground. It needs an offscreen layer to have a
 * destination to blend into; without [CompositingStrategy.Offscreen] the mask
 * applies to the whole window and erases the screen.
 *
 * ⚠ AND THE FADE IS CONDITIONAL. A row whose chips all fit must not be drawn
 * with a soft edge — that is the fault this fixes, wearing a gradient. See
 * [edgeFades].
 */
object EdgeFade {

    /**
     * How wide the ramp is.
     *
     * Wide enough to read as deliberate and narrow enough that the chip under it
     * is still legible: the point is "there is more this way", not to hide the
     * next control. Matched to the chip spacing plus a chip's own end padding.
     */
    val WIDTH: Dp = 22.dp

    /** Which edges of a scrolling row have content past them. */
    data class Fades(val start: Boolean, val end: Boolean)

    /**
     * The decision, as a function so it can be asserted without a composition.
     *
     * ⚠ `maxValue` IS `Int.MAX_VALUE` UNTIL THE ROW HAS BEEN MEASURED, which is
     * what a [ScrollState] carries on its first frame. Comparing against it
     * naively draws an end fade on every row in the app for one frame — and on a
     * row that never scrolls, forever, because a row whose content fits is never
     * re-measured into a smaller max. That unmeasured state is "nothing is known
     * yet", so it fades nothing.
     */
    fun edgeFades(value: Int, maxValue: Int): Fades {
        if (maxValue == Int.MAX_VALUE || maxValue <= 0) return Fades(start = false, end = false)
        val at = value.coerceIn(0, maxValue)
        return Fades(start = at > 0, end = at < maxValue)
    }
}

/**
 * Fades this row's content at whichever edge has more content past it.
 *
 * ⚠ PUT IT BEFORE `horizontalScroll` IN THE CHAIN. Modifiers to the left of the
 * scroll see the VIEWPORT; to the right they see the scrolled content, which is
 * as wide as every chip put together and has no visible edge to fade.
 */
fun Modifier.horizontalEdgeFade(state: ScrollState, width: Dp = EdgeFade.WIDTH): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fades = EdgeFade.edgeFades(state.value, state.maxValue)
            val ramp = width.toPx().coerceAtMost(size.width / 2)
            if (ramp <= 0f) return@drawWithContent
            // The mask's colour is irrelevant; only its ALPHA is read by DstIn.
            if (fades.start) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.Black),
                        startX = 0f,
                        endX = ramp,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (fades.end) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startX = size.width - ramp,
                        endX = size.width,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/**
 * One row of chips or keys, scrolled sideways, with the affordance attached.
 *
 * Exists so the four rows that need this cannot each remember half of it: the
 * fade has to sit before the scroll, the state has to be the same one both read,
 * and the padding has to be INSIDE the scroll or the last chip ends flush
 * against the bezel — which is the appearance this whole file is about.
 */
@Composable
fun EdgeFadeRow(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalEdgeFade(state)
            .horizontalScroll(state)
            .padding(contentPadding),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}
