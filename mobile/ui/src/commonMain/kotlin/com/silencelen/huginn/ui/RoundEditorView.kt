package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.PolishResult
import com.silencelen.huginn.device.DevicePolicy

/**
 * Writing a Round.
 *
 * Until this existed a Round could only be made with curl, which meant the
 * feature was real and unreachable — and the one thing a scheduled job must be
 * is easy to look at and change, because it runs when nobody is watching.
 *
 * Ordered as the sentence a person is actually composing: what it is, what it
 * should do, how you will know it finished, when, where, and who to tell. The
 * schedule is in the middle rather than first because "every Sunday" is not a
 * decision anybody makes before knowing what the thing does.
 *
 * Shared by both clients. A form that differed between them would be two chances
 * to write a schedule wrong.
 */
// FlowRow is still marked experimental. Used anyway, and deliberately: the
// alternative is a fixed Row that clips the seventh day chip on a narrow phone,
// which is a wrong schedule rather than a wrong layout.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoundEditor(
    draft: RoundDraft,
    onDraft: (RoundDraft) -> Unit,
    devices: List<Device>,
    saving: Boolean,
    error: String?,
    editing: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    /**
     * Asks the host to rewrite one field — "prompt" or "goal" — and hands the answer
     * back. Null hides the affordance entirely, which is what a shell that has not
     * wired it should look like: a Polish button that does nothing is worse than no
     * Polish button.
     */
    onPolish: ((draft: RoundDraft, field: String, done: (PolishResult) -> Unit) -> Unit)? = null,
) {
    val problem = draft.problem()

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = draft.title,
            onValueChange = { onDraft(draft.copy(title = it.take(ROUND_TITLE_MAX))) },
            label = { Text("Name") },
            placeholder = { Text("Telegram health check") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Each field and its Polish affordance are ONE group, spaced tighter than
        // the form's 14dp so the button reads as belonging to the field above it
        // rather than as a control floating between two of them.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = draft.prompt,
                onValueChange = { onDraft(draft.copy(prompt = it)) },
                label = { Text("What should it do?") },
                placeholder = { Text("Read the last week of Telegram alerts and find anything that needs a decision.") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Polish(
                field = "prompt",
                draft = draft,
                enabled = !saving,
                onPolish = onPolish,
                // Through the field's own onValueChange, so accepting a proposal
                // behaves exactly like typing it: the same cap, and the same
                // clearing of a stale server error.
                onAccept = { onDraft(draft.copy(prompt = it)) },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = draft.goal,
                onValueChange = { onDraft(draft.copy(goal = it.take(ROUND_GOAL_MAX))) },
                label = { Text("How will you know it finished? (optional)") },
                // The supporting text carries the whole reason this field exists: with
                // a goal the run is ASKED whether it got there, and an honest no is
                // reported instead of smoothed into a cheerful headline.
                supportingText = {
                    Text(
                        "A completion test. With one, each run answers whether it got there — " +
                            "and a run that did not is flagged even if it sounds pleased with itself.",
                    )
                },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Polish(
                field = "goal",
                draft = draft,
                enabled = !saving,
                onPolish = onPolish,
                onAccept = { onDraft(draft.copy(goal = it.take(ROUND_GOAL_MAX))) },
            )
        }

        SectionLabel("WHEN")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kind("Daily", "daily", draft, onDraft)
            Kind("Weekly", "weekly", draft, onDraft)
            Kind("Monthly", "monthly", draft, onDraft)
            Kind("Every…", "interval", draft, onDraft)
        }

        if (draft.isInterval) {
            OutlinedTextField(
                value = draft.everyMinutes,
                onValueChange = { v -> onDraft(draft.copy(everyMinutes = v.filter { it.isDigit() }.take(5))) },
                label = { Text("Minutes between runs") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(220.dp),
            )
        } else {
            if (draft.kind == "weekly") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ROUND_DAY_NAMES.forEachIndexed { i, name ->
                        FilterChip(
                            selected = i in draft.days,
                            onClick = {
                                val next = if (i in draft.days) draft.days - i else draft.days + i
                                onDraft(draft.copy(days = next))
                            },
                            label = { Text(name) },
                        )
                    }
                }
            }
            if (draft.kind == "monthly") {
                OutlinedTextField(
                    value = draft.dates.sorted().joinToString(", "),
                    onValueChange = { v ->
                        val parsed = v.split(',', ' ')
                            .mapNotNull { it.trim().toIntOrNull() }
                            .filter { it in 1..31 }
                            .toSet()
                        onDraft(draft.copy(dates = parsed))
                    },
                    label = { Text("Day of the month") },
                    placeholder = { Text("1, 15") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(220.dp),
                )
            }
            OutlinedTextField(
                value = draft.at,
                onValueChange = { onDraft(draft.copy(at = it.take(5))) },
                label = { Text("Time (24-hour)") },
                placeholder = { Text("19:00") },
                singleLine = true,
                modifier = Modifier.width(220.dp),
            )
        }

        // A local echo, said quietly. Once the Round exists the daemon renders
        // this line and fires by it; this is here so a form that is wrong is
        // wrong VISIBLY, rather than after a save that discards the typing.
        Text(
            draft.cadencePreview(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("WHAT IT MAY DO")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.mode == "ask",
                onClick = { onDraft(draft.copy(mode = "ask")) },
                label = { Text("Look and report") },
            )
            FilterChip(
                selected = draft.mode == "act",
                onClick = { onDraft(draft.copy(mode = "act")) },
                label = { Text("Change things") },
            )
        }
        if (draft.mode == "act") {
            // Said at the moment it is chosen, not buried in a doc. Wanting
            // something on a schedule does not imply consent to an unattended run
            // holding a shell at 3am, and those are different risks.
            Text(
                "This runs unattended. Nobody will be watching it, and it will not stop to ask.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ⚠ ALSO WHEN THERE ARE NO DEVICES, if this Round is pinned to one. Hiding
        // the section on an empty list meant a Round whose machine had been
        // unenrolled had no chip to move it back to Huginn — and since the daemon
        // refused every edit for the same reason, it was permanently uneditable
        // while Pause/Resume kept working, so the row looked perfectly alive.
        val pinnedElsewhere = draft.host != "local"
        if (devices.isNotEmpty() || pinnedElsewhere) {
            SectionLabel("WHERE IT RUNS")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.host == "local",
                    onClick = { onDraft(draft.copy(host = "local")) },
                    label = { Text("Huginn") },
                )
                // Serving rows are ABSENT, not disabled: a Round can never run
                // on the generate credential, the machine's claude enrolment is
                // already its own chip, and a permanently dead chip named for a
                // "-llm" credential nobody sees anywhere else is noise wearing
                // a control's clothes.
                devices.filter { it.scope != "generate" }.forEach { d ->
                    FilterChip(
                        selected = draft.host == d.id,
                        onClick = { onDraft(draft.copy(host = d.id)) },
                        label = { Text(d.name) },
                        // Its ENROLLED scope, not what it will do this second: a
                        // Round for next Sunday must not be un-pickable because the
                        // laptop happens to be locked on a Tuesday. The policy
                        // answers, not a hand-rolled comparison.
                        enabled = DevicePolicy.allows(DevicePolicy.parse(d.scope), draft.mode),
                    )
                }
                // The machine it names is not in the list. Shown, selected, and
                // said plainly — otherwise the form would silently look as though
                // the Round runs here.
                if (pinnedElsewhere && devices.none { it.id == draft.host }) {
                    FilterChip(
                        selected = true,
                        onClick = { },
                        label = { Text("a machine that is gone") },
                    )
                }
            }
            if (pinnedElsewhere && devices.none { it.id == draft.host }) {
                Text(
                    "This round is pinned to a machine huginn no longer knows, so it cannot run. " +
                        "Pick Huginn to bring it back here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionLabel("TELL ME")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Notify("Every time", "always", draft, onDraft)
            Notify("When it needs me", "attention", draft, onDraft)
            Notify("Never", "never", draft, onDraft)
        }

        // The daemon's word wins over the local guess: if it refused, that text is
        // the real reason and the local one is at best a near miss.
        (error ?: problem)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSave, enabled = problem == null && !saving) {
                Text(if (saving) "Saving…" else if (editing) "Save" else "Create")
            }
            TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
            if (onDelete != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDelete, enabled = !saving) { Text("Delete") }
                }
            }
        }
    }
}

/**
 * "Polish", and what comes back from it.
 *
 * A Round's prompt is written once and then runs unattended for months, so the gap
 * between a vague prompt and a good one is not a worse session — it is months of
 * reports nobody can act on. But the person writing it cannot see the frame their
 * words land in: the daemon prepends the goal, appends a report contract, and hands
 * the run only the tools the mode allows. This asks a model to fit the field to
 * that frame.
 *
 * ⚠ IT PROPOSES, IT DOES NOT APPLY. The answer appears BESIDE the field, not in it,
 * until somebody presses Use this. A control that rewrote the box under your cursor
 * would have to be undone, and there is no undo in a text field you did not type.
 *
 * The state is a plain [remember]: a proposal nobody has accepted is worth exactly
 * as much as the keystrokes around it, so it survives recomposition and dies with
 * the screen. Held per field, because polishing the goal must not discard a prompt
 * proposal still sitting on screen unread.
 */
@Composable
private fun Polish(
    field: String,
    draft: RoundDraft,
    enabled: Boolean,
    onPolish: ((RoundDraft, String, (PolishResult) -> Unit) -> Unit)?,
    onAccept: (String) -> Unit,
) {
    if (onPolish == null) return
    var inFlight by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One line, said once, and no dialog: a model being busy is not an event in
        // somebody's afternoon.
        if (failed) {
            Text(
                "Polish is unavailable right now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                inFlight = true; failed = false; proposal = null; note = null
                onPolish(draft, field) { r ->
                    inFlight = false
                    // A blank polished field counts as a failure however it was
                    // dressed: offering somebody an empty box to accept is worse
                    // than saying the thing did not work.
                    if (r.polished.isNullOrBlank()) {
                        failed = true
                    } else {
                        proposal = r.polished
                        note = r.note
                    }
                }
            },
            // Nothing to improve from, and the daemon refuses it anyway — better as
            // a control that is visibly not ready than as one that fails when pressed.
            enabled = enabled && !inFlight && (draft.prompt.isNotBlank() || draft.goal.isNotBlank()),
        ) {
            Text(if (inFlight) "Polishing…" else "Polish")
        }
    }

    proposal?.let { text ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Selectable: somebody may want half of it, and a proposal you can
                // only take whole is a proposal you argue with rather than edit.
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onAccept(text); proposal = null; note = null }) {
                        Text("Use this")
                    }
                    TextButton(onClick = { proposal = null; note = null }) { Text("Discard") }
                }
            }
        }
    }
}

@Composable
private fun Kind(label: String, kind: String, draft: RoundDraft, onDraft: (RoundDraft) -> Unit) {
    FilterChip(
        selected = draft.kind == kind,
        onClick = { onDraft(draft.copy(kind = kind)) },
        label = { Text(label) },
    )
}

@Composable
private fun Notify(label: String, value: String, draft: RoundDraft, onDraft: (RoundDraft) -> Unit) {
    FilterChip(
        selected = draft.notifyWhen == value,
        onClick = { onDraft(draft.copy(notifyWhen = value)) },
        label = { Text(label) },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
