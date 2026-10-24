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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontWeight
import com.silencelen.huginn.data.AnswerValue
import com.silencelen.huginn.data.AskQuestionSpec
import com.silencelen.huginn.data.DegradedAsk
import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.PlanPending
import com.silencelen.huginn.data.PromptOption
import com.silencelen.huginn.data.QuestionAnswers
import com.silencelen.huginn.data.SessionQuestion
import com.silencelen.huginn.ui.theme.LocalMonoStyle
import kotlinx.serialization.json.JsonObject

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
                style = com.silencelen.huginn.ui.theme.LocalMonoStyle.current,
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

// ---- the request registry's card (decision 23 re-opened, 2026-10-23) ----------
//
// A SESSION DRAWS THIS AGAIN, compact, for a BOUND request: Claude Code 2.1.306
// answers a parked PermissionRequest whole through its own hook, so the card can
// finish every shape the registry binds — a two-question ask with a typed answer
// and a multi-select, a permission with "Always allow", a plan with feedback.
// [PromptGate.cardQuestion] picks the one request; [QuestionLinkBar] stays the
// surface for everything the registry does not hold. The 40dp lesson stands: the
// card is [promptCardMetrics] `compact` — cap before fill, options in a wrapping
// row, four lines of question with "more" — and nothing wider.

/** How many lines of a permission's detail (an Edit's old text) show before "more". */
const val DETAIL_FOLD_LINES: Int = 6

/** How tall a plan's text may draw before it scrolls inside the card. */
val PLAN_FOLD_HEIGHT: Dp = 220.dp

/**
 * ONE card for a registry question, by kind. [onAnswer] receives the wire body
 * ([QuestionAnswers] builds it; the shell adds the id and fingerprint through
 * `HuginnClient.answerQuestion`). [note] is the daemon's refusal, drawn under
 * the buttons; a `409` is ordinary and never retried.
 */
@Composable
fun QuestionCard(
    question: SessionQuestion,
    answering: Boolean = false,
    note: String? = null,
    compact: Boolean = true,
    onAnswer: (JsonObject) -> Unit,
    onOpenScreen: (() -> Unit)? = null,
) {
    when {
        question.isAsk -> AskQuestionCard(question, answering, note, compact, onAnswer)
        question.isPlan -> PlanQuestionCard(question, answering, note, compact, onAnswer, onOpenScreen)
        else -> PermissionQuestionCard(question, answering, note, compact, onAnswer)
    }
}

/**
 * An AskUserQuestion, one question at a time ([AskStepper]): a single choice
 * answers on tap (and submits when it was the last), a multi-select is a
 * checklist with its own button, a text or number question is a field, and
 * every choice question has an "Other…" field under its options. Options carry
 * their descriptions; a preview shows for the option last looked at.
 */
@Composable
private fun AskQuestionCard(
    question: SessionQuestion,
    answering: Boolean,
    note: String?,
    compact: Boolean,
    onAnswer: (JsonObject) -> Unit,
) {
    val metrics = promptCardMetrics(compact)
    // Keyed on the request's identity: a new question starts a new walk, a
    // repaint of the same one keeps where the thumb was.
    var stepper by remember(question.id, question.fingerprint) { mutableStateOf(AskStepper.of(question)) }
    var otherOpen by remember(question.id, stepper.index) { mutableStateOf(false) }
    var typed by remember(question.id, stepper.index) { mutableStateOf("") }
    var previewOf by remember(question.id, stepper.index) { mutableStateOf<Int?>(null) }
    val spec = stepper.current
    // Submit when the walk is complete: the hook answers the whole call once.
    fun advance(next: AskStepper) {
        stepper = next
        if (next.complete && next.isLast) next.body(question)?.let(onAnswer)
    }
    CardShell(metrics) {
        if (spec == null) {
            Text("Claude is asking a question", style = MaterialTheme.typography.bodyMedium)
            NoteLine(note)
            return@CardShell
        }
        spec.header?.takeIf { it.isNotBlank() }?.let { HeaderChip(it, question.agentType) }
        QuestionHeader(
            question = spec.question.ifBlank { "Claude is asking" },
            questionIndex = stepper.index,
            questionCount = stepper.count,
            maxLines = metrics.questionMaxLines,
        )
        spec.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        when {
            spec.isText -> {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it },
                    placeholder = { Text(spec.placeholder ?: "Type your answer") },
                    enabled = !answering, singleLine = false, minLines = 1, maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                StepButtons(stepper, answering, enabled = typed.isNotBlank(),
                    label = if (stepper.isLast) "Answer" else "Next",
                    onBack = { stepper = stepper.back() },
                    onNext = { advance(stepper.answer(AnswerValue.Text(typed.trim()))) })
            }
            spec.isNumber -> {
                val hint = listOfNotNull(spec.min?.let { "min ${trimNumber(it)}" }, spec.max?.let { "max ${trimNumber(it)}" }, spec.unit).joinToString(" · ")
                OutlinedTextField(
                    value = typed.ifEmpty { spec.default?.let(::trimNumber) ?: "" }, onValueChange = { typed = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    placeholder = { Text(hint.ifBlank { "A number" }) }, supportingText = if (hint.isBlank()) null else ({ Text(hint) }),
                    enabled = !answering, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                val value = typed.ifEmpty { spec.default?.let(::trimNumber) ?: "" }
                StepButtons(stepper, answering, enabled = value.toDoubleOrNull() != null,
                    label = if (stepper.isLast) "Answer" else "Next",
                    onBack = { stepper = stepper.back() },
                    onNext = { advance(stepper.answer(AnswerValue.Text(value))) })
            }
            spec.multiSelect -> {
                val chosen = stepper.currentChosen
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    spec.options.forEachIndexed { i, o ->
                        CheckRow(
                            option = PromptOption(number = i + 1, label = o.label, description = o.description),
                            checked = o.label in chosen, enabled = !answering,
                            onToggle = { stepper = stepper.toggle(o.label); previewOf = i },
                        )
                    }
                }
                PreviewBlock(spec, previewOf)
                StepButtons(stepper, answering, enabled = true,
                    label = (if (stepper.isLast) "Answer" else "Next") + if (chosen.isEmpty()) " with none selected" else " with ${chosen.size} selected",
                    onBack = { stepper = stepper.back() },
                    onNext = { advance(stepper.confirmMany()) })
            }
            else -> {
                val wrap = metrics.wrapOptions
                val picked = (stepper.currentAnswer as? AnswerValue.One)?.label
                OptionLayout(wrap) {
                    spec.options.forEachIndexed { i, o ->
                        AnswerButton(
                            option = PromptOption(number = i + 1, label = o.label, description = o.description),
                            highlighted = o.label == picked, enabled = !answering, wrap = wrap,
                            onClick = { previewOf = i; advance(stepper.answer(AnswerValue.One(o.label))) },
                        )
                    }
                }
                PreviewBlock(spec, previewOf)
                // "Other…": the free text the TUI offers as "Type something."; the
                // daemon takes any string, so it is just another answer.
                if (!otherOpen) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { otherOpen = true }, enabled = !answering,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                            Text("Other…", style = MaterialTheme.typography.labelMedium)
                        }
                        if (!stepper.isFirst) {
                            TextButton(onClick = { stepper = stepper.back() }, enabled = !answering,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                Text("Back", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it }, placeholder = { Text("Something else…") },
                        enabled = !answering, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    StepButtons(stepper, answering, enabled = typed.isNotBlank(),
                        label = if (stepper.isLast) "Answer" else "Next",
                        onBack = { stepper = stepper.back() },
                        onNext = { advance(stepper.answer(AnswerValue.Text(typed.trim()))) })
                }
            }
        }
        if (stepper.count > 1 && stepper.answers.isNotEmpty()) {
            // What was already answered, so "2 of 2" is never answered blind.
            Text(
                stepper.specs.take(stepper.index).mapNotNull { s -> stepper.answers[s.question]?.let { "${s.header ?: s.question}: ${QuestionAnswers.summary(it)}" } }
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
        }
        NoteLine(note)
    }
}

/** The ask dialog's own chip ("Colour"), and which agent asked when a subagent did. */
@Composable
private fun HeaderChip(header: String, agentType: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Text(header, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        agentType?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(8.dp))
            Text("asked by $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Back (when there is an earlier question) and the one forward button. */
@Composable
private fun StepButtons(stepper: AskStepper, answering: Boolean, enabled: Boolean, label: String, onBack: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!stepper.isFirst) {
            TextButton(onClick = onBack, enabled = !answering) { Text("Back") }
            Spacer(Modifier.width(6.dp))
        }
        Button(onClick = onNext, enabled = enabled && !answering, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
    }
}

/**
 * The preview of the option last looked at: text as it is, in the mono style;
 * HTML (the TUI's `previewFormat: html`) as a folded code block, never rendered.
 */
@Composable
private fun PreviewBlock(spec: AskQuestionSpec, index: Int?) {
    val preview = index?.let { spec.options.getOrNull(it)?.preview }?.takeIf { it.isNotBlank() } ?: return
    val html = preview.trimStart().startsWith("<")
    var open by remember(preview) { mutableStateOf(!html) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (html) "HTML preview" else "Preview", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (html) {
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text(if (open) "hide" else "show source", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (open) MonoBlock(preview)
    }
}

/** A folded monospace block: capped height, scrolls both ways, never wraps a diff. */
@Composable
private fun MonoBlock(text: String, maxHeight: Dp = PLAN_FOLD_HEIGHT) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)) {
            Text(text, style = LocalMonoStyle.current, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun trimNumber(d: Double): String = if (d == kotlin.math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

/**
 * A tool permission: what the tool wants to do (the command, the file, the URL
 * — [SessionQuestion.headline]), the rest of its input folded under "more", and
 * Yes / Always allow (only when the CLI offered suggestions) / No. A No opens a
 * line for "tell Claude what to change"; sent empty it is the TUI's own No.
 */
@Composable
private fun PermissionQuestionCard(
    question: SessionQuestion,
    answering: Boolean,
    note: String?,
    compact: Boolean,
    onAnswer: (JsonObject) -> Unit,
) {
    val metrics = promptCardMetrics(compact)
    var more by remember(question.id) { mutableStateOf(false) }
    var noOpen by remember(question.id) { mutableStateOf(false) }
    var words by remember(question.id) { mutableStateOf("") }
    val lines = question.permissionLines()
    CardShell(metrics) {
        HeaderChip(question.toolName.let { t -> question.mcpServer?.let { "$it · ${t.removePrefix("mcp__${it}__")}" } ?: t }, question.agentType)
        val detail = question.permissionDetail()
        if (detail != null) {
            MonoBlock(detail, maxHeight = 120.dp)
        } else {
            Text("Claude wants to use ${question.toolName}", style = MaterialTheme.typography.bodyMedium)
        }
        if (lines.isNotEmpty()) {
            if (more) {
                Spacer(Modifier.height(4.dp))
                MonoBlock(lines.joinToString("\n"))
            }
            TextButton(onClick = { more = !more }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text(if (more) "less" else "more (${lines.size} more field${if (lines.size == 1) "" else "s"})",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        OptionLayout(wrap = true) {
            Button(onClick = { onAnswer(QuestionAnswers.permissionBody(question, allow = true)) }, enabled = !answering) { Text("Yes") }
            if (!question.suggestions.isNullOrEmpty()) {
                OutlinedButton(onClick = { onAnswer(QuestionAnswers.permissionBody(question, allow = true, always = true)) }, enabled = !answering) {
                    Text("Always allow")
                }
            }
            OutlinedButton(onClick = { noOpen = !noOpen }, enabled = !answering) { Text("No") }
        }
        if (noOpen) {
            OutlinedTextField(
                value = words, onValueChange = { words = it },
                placeholder = { Text("Tell Claude what to do instead (optional)") },
                enabled = !answering, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(
                onClick = { onAnswer(QuestionAnswers.permissionBody(question, allow = false, message = words)) },
                enabled = !answering, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text(if (words.isBlank()) "No" else "No, and tell Claude") }
        }
        NoteLine(note)
    }
}

/**
 * A plan approval: the plan itself, folded, then "Yes" (approval by the dialog's
 * own keys, keeping the mode) and "No, tell Claude what to change" with the
 * words (through the hook when one waits, else typed into the dialog's row).
 */
@Composable
private fun PlanQuestionCard(
    question: SessionQuestion,
    answering: Boolean,
    note: String?,
    compact: Boolean,
    onAnswer: (JsonObject) -> Unit,
    onOpenScreen: (() -> Unit)?,
) {
    val metrics = promptCardMetrics(compact)
    var planOpen by remember(question.id) { mutableStateOf(false) }
    var noOpen by remember(question.id) { mutableStateOf(false) }
    var words by remember(question.id) { mutableStateOf("") }
    CardShell(metrics) {
        Text("Plan ready — approve to continue", style = MaterialTheme.typography.bodyMedium)
        question.planText()?.let { plan ->
            TextButton(onClick = { planOpen = !planOpen }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text(if (planOpen) "hide the plan" else "read the plan", style = MaterialTheme.typography.labelSmall)
            }
            if (planOpen) MonoBlock(plan)
        }
        question.planFilePath()?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        OptionLayout(wrap = true) {
            Button(onClick = { QuestionAnswers.planBody(approve = true)?.let(onAnswer) }, enabled = !answering) { Text("Yes") }
            OutlinedButton(onClick = { noOpen = !noOpen }, enabled = !answering) { Text("No, tell Claude what to change") }
            onOpenScreen?.let { TextButton(onClick = it) { Text("Screen tab") } }
        }
        if (noOpen) {
            OutlinedTextField(
                value = words, onValueChange = { words = it },
                placeholder = { Text("What should change?") },
                enabled = !answering, minLines = 1, maxLines = 5, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(
                onClick = { QuestionAnswers.planBody(approve = false, message = words)?.let(onAnswer) },
                enabled = !answering && words.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("Send feedback") }
        }
        NoteLine(note)
    }
}
