package com.silencelen.huginn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.DegradedAsk
import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.PromptOption

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
    onAnswer: (Int) -> Unit,
    onAnswerMulti: (List<Int>) -> Unit,
) {
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

    CardShell {
        QuestionHeader(
            question = prompt.question.ifBlank { "Claude is asking" },
            questionIndex = prompt.questionIndex,
            questionCount = prompt.questionCount,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    onAnswer: (Int) -> Unit,
    onOpenScreen: (() -> Unit)? = null,
) {
    CardShell {
        QuestionHeader(
            question = ask.question.ifBlank { "Claude is asking" },
            questionIndex = ask.questionIndex,
            questionCount = ask.questionCount,
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
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ask.options.forEach { option ->
                AnswerButton(
                    option = option,
                    highlighted = false,
                    enabled = !answering,
                    onClick = { onAnswer(option.number) },
                )
            }
        }
        NoteLine(note)
    }
}

@Composable
private fun CardShell(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun QuestionHeader(question: String, questionIndex: Int?, questionCount: Int?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            question,
            style = MaterialTheme.typography.bodyMedium,
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
}

@Composable
private fun AnswerButton(
    option: PromptOption,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = "${option.number}.  ${option.label}"
    val mod = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    Column(mod) {
        if (highlighted) {
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
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
