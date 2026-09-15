package com.silencelen.huginn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.silencelen.huginn.data.DegradedAsk
import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.PlanPending
import com.silencelen.huginn.data.PromptOption

// ⚠ WHO DRAWS THESE CARDS, AS OF 2026-09-15 (owner decision 23).
//
// A SESSION NO LONGER DOES. Its Conversation and Overview show [QuestionLinkBar]
// — one line, 40dp — and the reader answers in the pane, which is the only
// surface that handles every prompt type. The cards cost 290-330dp for a
// five-option question and handled some of them.
//
// A CHAT STILL WOULD, in the [promptCardMetrics] `compact` shape: it has no pane,
// so there is nowhere to steer anybody and the card has to be answerable where it
// stands. The daemon does not yet serve a chat-side question (there is no pane to
// scrape and no `/answer` route for a headless run), so that call site does not
// exist yet — [PromptPlacement.of] is where it will be decided when it does, and
// the shape is asserted in `QuestionSurfaceTest` so it cannot rot in the meantime.
//
// Do not delete these on a "no callers" sweep. They are the answerable surface,
// the measurements are the owner's, and re-deriving them is how the width-cap
// ORDER bug (`fillMaxWidth().widthIn()`) comes back.

/**
 * The three measurements the COMPACT card differs by.
 *
 * A parameter object with a pure constructor rather than three `if (compact)`s
 * buried in the layout, because these are the whole of the owner's complaint and
 * they have to be assertable: a card whose cap was silently defeated renders
 * perfectly and is exactly as wrong as before.
 *
 * @param maxWidth the reading measure. [Dp.Unspecified] means uncapped — the old
 *   behaviour, kept for the phone's 360dp column where a cap does nothing.
 * @param questionMaxLines how much of the question shows before the "more"
 *   expander. [Int.MAX_VALUE] means all of it.
 * @param wrapOptions option buttons sized to their LABELS in a wrapping row,
 *   rather than one full-width button per option. At 1440 a full-width button
 *   was ~1030px of chrome holding two words, and five of them cost 290-330dp of
 *   vertical space (measured, design audit 2026-09-15).
 */
data class PromptCardMetrics(
    val maxWidth: Dp,
    val questionMaxLines: Int,
    val wrapOptions: Boolean,
)

/** How many lines of question a compact card shows before "more". */
const val COMPACT_QUESTION_LINES: Int = 4

/** The compact card's reading measure. */
val COMPACT_CARD_MAX_WIDTH: Dp = 560.dp

/**
 * The card's shape, decided without a Compose runtime.
 *
 * `compact` is [PromptPlacement.INLINE_COMPACT]'s shape — what a headless CHAT
 * draws, where there is no pane to steer to and the question has to be answerable
 * where it stands. A session no longer draws this card on its Conversation or
 * Overview at all (it shows [QuestionLinkBar] instead), so the uncapped shape
 * survives only for a caller that genuinely wants the old full-bleed card.
 */
fun promptCardMetrics(compact: Boolean): PromptCardMetrics =
    if (compact) {
        PromptCardMetrics(
            maxWidth = COMPACT_CARD_MAX_WIDTH,
            questionMaxLines = COMPACT_QUESTION_LINES,
            wrapOptions = true,
        )
    } else {
        PromptCardMetrics(
            maxWidth = Dp.Unspecified,
            questionMaxLines = Int.MAX_VALUE,
            wrapOptions = false,
        )
    }

/**
 * THE question card, one implementation for both shells — the phone and desktop
 * copies had already drifted cosmetically, and the fused prompt's new payload
 * (descriptions, question count, TUI-extra rows) would have had to land twice.
 *
 * Answering stays per-shell: the callbacks reach a controller on the desktop and
 * a viewmodel on the phone. The card renders what the host serves and holds only
 * the multi-select's local choice set.
 *
 * The host refuses an answer whose pane has moved on (409, its own sentence) —
 * an ORDINARY outcome for a card drawn from a polled screen; [note] shows it.
 */
@Composable
fun PromptCard(
    prompt: PanePrompt,
    answering: Boolean = false,
    note: String? = null,
    /** The chat's shape: capped, wrapped, and four lines of question. */
    compact: Boolean = false,
    onAnswer: (Int) -> Unit,
    onAnswerMulti: (List<Int>) -> Unit,
) {
    val metrics = promptCardMetrics(compact)
    // Local checkbox state, seeded from what the dialog already shows (it may be
    // half-answered in tmux) and RECONCILED against later pane frames by delta —
    // an external toggle arrives, a local pick survives (PromptChoices' rule; the
    // old remember-once seeding silently reverted tmux-side toggles on Answer).
    val initialBaseline = remember(prompt.question, prompt.options.size) {
        prompt.options.filter { it.checked == true }.map { it.number }.toSet()
    }
    var baseline by remember(prompt.question, prompt.options.size) { mutableStateOf(initialBaseline) }
    var chosen by remember(prompt.question, prompt.options.size) { mutableStateOf(initialBaseline) }
    val paneNow = prompt.options.filter { it.checked == true }.map { it.number }.toSet()
    if (paneNow != baseline) {
        chosen = PromptChoices.mergeBaseline(baseline, paneNow, chosen)
        baseline = paneNow
    }

    CardShell(metrics) {
        QuestionHeader(
            question = prompt.question.ifBlank { "Claude is asking" },
            questionIndex = prompt.questionIndex,
            questionCount = prompt.questionCount,
            maxLines = metrics.questionMaxLines,
        )
        Spacer(Modifier.height(8.dp))
        // A multi-select is a checklist and stays a column whatever the metrics
        // say: checkboxes wrapped into a flow row read as unrelated chips, and
        // the whole point of the shape is that they are one list with one Answer.
        val wrap = metrics.wrapOptions && !prompt.multiSelect
        OptionLayout(wrap) {
            prompt.options.forEach { option ->
                val checkable = prompt.multiSelect && option.checked != null
                if (checkable) {
                    CheckRow(
                        option = option,
                        checked = chosen.contains(option.number),
                        enabled = !answering,
                        onToggle = {
                            chosen = if (chosen.contains(option.number)) chosen - option.number
                            else chosen + option.number
                        },
                    )
                } else {
                    AnswerButton(
                        option = option,
                        highlighted = option.selected && !prompt.multiSelect,
                        enabled = !answering,
                        wrap = wrap,
                        onClick = { onAnswer(option.number) },
                    )
                }
            }
        }
        if (prompt.multiSelect) {
            Button(
                onClick = { onAnswerMulti(chosen.toList().sorted()) },
                enabled = !answering,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(
                    if (chosen.isEmpty()) "Answer with none selected"
                    else "Answer with ${chosen.size} selected",
                )
            }
        }
        NoteLine(note)
    }
}

/**
 * The hook knows a question is waiting but the pane scrape cannot read the
 * dialog (an exotic shape). The buttons still work: the host re-checks the live
 * pane at answer time and refuses with its own sentence when it still cannot see
 * the run — at which point the shell's callback steers to the Screen tab.
 *
 * A MULTI-PART question ([DegradedAsk.multiPart]) is the exception: it is
 * answered through the TUI's tab strip, which a single button tap cannot drive
 * (the tap over-answers and the host 409s). So we render it read-only and point
 * the owner at the Screen tab — [onOpenScreen] jumps there directly when the
 * shell can, otherwise the message alone tells them where to go.
 */
@Composable
fun DegradedAskCard(
    ask: DegradedAsk,
    answering: Boolean = false,
    note: String? = null,
    /** The chat's shape: capped, wrapped, and four lines of question. */
    compact: Boolean = false,
    onAnswer: (Int) -> Unit,
    onOpenScreen: (() -> Unit)? = null,
) {
    val metrics = promptCardMetrics(compact)
    CardShell(metrics) {
        QuestionHeader(
            question = ask.question.ifBlank { "Claude is asking" },
            questionIndex = ask.questionIndex,
            questionCount = ask.questionCount,
            maxLines = metrics.questionMaxLines,
        )
        if (ask.multiPart) {
            Text(
                "This question has more than one part. Answer it on the Screen tab — " +
                    "the parts are stepped through there.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            // Read-only: the first part's choices, shown for context, NOT tappable
            // (a tap can't answer a tab-strip dialog from here).
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                ask.options.forEach { option ->
                    Text(
                        "${option.number}.  ${option.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            onOpenScreen?.let {
                Button(
                    onClick = it,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Answer on the Screen tab") }
            }
            NoteLine(note)
            return@CardShell
        }
        Text(
            "The dialog is on screen but not readable from here — answers are verified against the live screen.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(8.dp))
        OptionLayout(metrics.wrapOptions) {
            ask.options.forEach { option ->
                AnswerButton(
                    option = option,
                    highlighted = false,
                    enabled = !answering,
                    wrap = metrics.wrapOptions,
                    onClick = { onAnswer(option.number) },
                )
            }
        }
        NoteLine(note)
    }
}

/**
 * An `ExitPlanMode` approval is waiting, and this shows the PLAN.
 *
 * The daemon ships `screen.planPending` (the plan text, up to 8000 chars) on every
 * poll, but no client rendered it — so in the common case the owner approved from
 * the pane-detected 1/2/3 buttons without ever seeing the plan as a card, and in
 * the pane-unreadable case they saw nothing at all (the "plan approvals were
 * invisible for weeks" symptom this field exists to defend against).
 *
 * There is no fingerprinted answer channel for a plan the way there is for a
 * question, so this card does not itself tap-answer. When a readable prompt is
 * present ([hasButtons]) its approve/reject buttons render just below and this card
 * is pure context. When the pane could not be read there are no buttons anywhere,
 * so the card steers to the Screen tab, where the numbered dialog can be answered.
 */
@Composable
fun PlanApprovalCard(
    plan: PlanPending,
    hasButtons: Boolean,
    compact: Boolean = false,
    onOpenScreen: (() -> Unit)? = null,
) {
    CardShell(promptCardMetrics(compact)) {
        Text(
            "Plan ready — approve to continue",
            style = MaterialTheme.typography.bodyMedium,
        )
        plan.plan?.takeIf { it.isNotBlank() }?.let { body ->
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        if (!hasButtons) {
            Spacer(Modifier.height(8.dp))
            Text(
                "The approval dialog is on the Screen tab — approve or keep planning there.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onOpenScreen?.let {
                Button(
                    onClick = it,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Open the Screen tab") }
            }
        }
    }
}

@Composable
private fun CardShell(metrics: PromptCardMetrics, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // ⚠ ORDER IS LOAD-BEARING and the wrong way round fails SILENTLY.
        // `fillMaxWidth` hands DOWN fixed constraints and a `widthIn` inside fixed
        // constraints can only coerce into them, so `fillMaxWidth().widthIn(max=…)`
        // ignores the cap entirely — the bug already found at
        // HeadroomSettingsView.kt:159 and SettingsView.kt:417. Cap first, fill
        // second; same rule as the user bubble's (TranscriptView.kt:207).
        modifier = Modifier
            .then(if (metrics.maxWidth == Dp.Unspecified) Modifier else Modifier.widthIn(max = metrics.maxWidth))
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

/**
 * The options, as a column of full-width buttons or as a row that wraps.
 *
 * One composable rather than an `if` at three call sites: the choice is the
 * compact card's whole shape and it has to be made the same way everywhere, or a
 * degraded ask ends up wrapping while the prompt beside it does not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionLayout(wrap: Boolean, content: @Composable () -> Unit) {
    if (wrap) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun QuestionHeader(
    question: String,
    questionIndex: Int?,
    questionCount: Int?,
    maxLines: Int = Int.MAX_VALUE,
) {
    // Collapsed by default when there is a budget, and the expander is only
    // OFFERED once the text was actually cut — a "more" under a two-line question
    // is a control that does nothing, and the reader learns to ignore the real one.
    var expanded by remember(question) { mutableStateOf(false) }
    var truncated by remember(question) { mutableStateOf(false) }
    val lines = if (expanded) Int.MAX_VALUE else maxLines
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            question,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
            modifier = Modifier.weight(1f),
        )
        // "1 of 2" when the host knows this dialog carries sibling questions —
        // answering this one advances the TUI to the next.
        if (questionCount != null && questionCount > 1) {
            Text(
                "${(questionIndex ?: 0) + 1} of $questionCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    if (truncated || expanded) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        ) {
            Text(
                if (expanded) "less" else "more",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun AnswerButton(
    option: PromptOption,
    highlighted: Boolean,
    enabled: Boolean,
    /** Sized to the LABEL, for a wrapping row; otherwise full width. */
    wrap: Boolean = false,
    onClick: () -> Unit,
) {
    val label = "${option.number}.  ${option.label}"
    val mod = if (wrap) Modifier else Modifier.fillMaxWidth().padding(vertical = 2.dp)
    val button = if (wrap) Modifier else Modifier.fillMaxWidth()
    Column(mod) {
        if (highlighted) {
            Button(onClick = onClick, enabled = enabled, modifier = button) { Text(label, maxLines = 2) }
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled, modifier = button) { Text(label, maxLines = 2) }
        }
        // The option's explanation, present when the host fused the exact hook
        // input. The TUI-added rows (Type something / Chat about this) have none.
        option.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 1.dp),
            )
        }
    }
}

@Composable
private fun CheckRow(
    option: PromptOption,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(6.dp))
            Text("${option.number}.  ${option.label}", style = MaterialTheme.typography.bodyMedium)
        }
        option.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
}

@Composable
private fun NoteLine(note: String?) {
    note?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
