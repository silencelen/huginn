package com.silencelen.huginn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What the one-line question bar says, or null when it must not draw at all.
 *
 * Decided without a Compose runtime for the same reason every other view rule in
 * this module is: the composable cannot be asserted without a window, and the
 * mistakes are never in the pixels — they are in a bar that appears with nothing
 * pending, or one that appears on the very face the reader was steered to.
 */
data class QuestionLinkFace(
    val label: String,
    /** The question itself, shortened. Null when there is no room or no text. */
    val detail: String?,
)

/** The bar's wording. ONE sentence, and the arrow is part of it. */
const val QUESTION_LINK_LABEL: String = "Claude is asking a question · Answer on Screen →"

/**
 * Below this the bar shows its own sentence and nothing else.
 *
 * The label is already 48 characters; a gist squeezed in beside it on a 360dp
 * phone would be three words and an ellipsis, which is worse than no preview —
 * it looks like the question, and it is not.
 */
private val GIST_FROM = 520.dp

/**
 * The bar's content.
 *
 * @param question the pending question's text, or null when NOTHING is pending.
 *   Blank is a different answer from null and both are legitimate: a degraded ask
 *   is pending with text the pane scrape could not read, and it still needs a bar.
 * @param placement where this surface was told to put a question. Anything but
 *   [PromptPlacement.LINK_TO_SCREEN] draws nothing here — the chat's compact card
 *   and the Screen face's deliberate silence are both somebody else's job.
 */
fun questionLinkFace(
    question: String?,
    placement: PromptPlacement,
    wide: Boolean,
): QuestionLinkFace? {
    if (placement != PromptPlacement.LINK_TO_SCREEN) return null
    if (question == null) return null
    return QuestionLinkFace(
        label = QUESTION_LINK_LABEL,
        detail = if (wide) PromptGate.gist(question) else null,
    )
}

/**
 * The question, as a line rather than as a card.
 *
 * WHAT THIS REPLACED, and why, because it is a deliberate loss of function:
 * the Conversation and Overview faces used to draw the full answerable card. A
 * five-option question cost 290-330dp there (uncapped `CardShell`, full-width
 * `AnswerButton`s, plus the "Type something" button), which at a 768x1024 window
 * left about 250dp of transcript — and only some prompt types could be answered
 * from it at all. The pane answers every type, so the owner's decision 23 traded
 * the buttons for a 40dp steer to the place that always works.
 *
 * TAPPING ANYWHERE ON IT goes to the Screen tab. A bar whose only hit target is
 * the word "Answer" is a bar people tap and nothing happens on.
 *
 * Nothing is drawn when [questionLinkFace] has nothing to say, so a call site may
 * place it unconditionally.
 */
@Composable
fun QuestionLinkBar(
    question: String?,
    onOpenScreen: () -> Unit,
    modifier: Modifier = Modifier,
    placement: PromptPlacement = PromptPlacement.LINK_TO_SCREEN,
) {
    // The preview is a property of how wide the bar happens to be, not of which
    // client is drawing it — a desktop window narrowed to a phone's width should
    // get the phone's answer. Measured here rather than passed in, so no call site
    // can hold a stale breakpoint.
    BoxWithConstraints(modifier) {
        val face = questionLinkFace(question, placement, wide = maxWidth >= GIST_FROM) ?: return@BoxWithConstraints
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenScreen),
        ) {
            Row(
                // CAPPED, not fixed: 40dp is the budget decision 23 bought, and a
                // question whose gist wanted two lines would spend it silently.
                // One line, ellipsised, or it is not a link bar.
                Modifier.fillMaxWidth().heightIn(max = 40.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The same attention dot the session rows and the rail use. A third
                // vocabulary for "needs you" is how a legend becomes necessary.
                Spacer(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    face.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                face.detail?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The dot beside a tab's label, drawn when [PromptGate.screenTabDot] says so.
 *
 * Here rather than in each shell's tab strip because both strips need it and the
 * two of them are already the place the clients drift: the phone's is a Material
 * `TabRow` and the desktop's is a hand-built `Row`, and the only thing they must
 * agree on is that the mark means "the answer is in here".
 */
@Composable
fun TabAttentionDot(show: Boolean, modifier: Modifier = Modifier) {
    if (!show) return
    Spacer(
        modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
    )
}
