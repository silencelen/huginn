package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.AccountSwitch
import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.ModelChoice

/**
 * The owner-editable half of headroom: when to warn, when to move a session down
 * the ladder, whether to pick it back up, and what to say while doing it.
 *
 * ⚠ THE DAEMON IS THE VALIDATION AUTHORITY. `lib/headroom.js:validateSettings` is
 * what actually decides, answers a broken rule with a 400 naming it, and a
 * disagreement between that and [HeadroomForm] below is a bug in this file rather
 * than a second opinion. What the form does is stop an edit that cannot succeed
 * from being sent at all — a Save button that round-trips to a 400 the reader has
 * to decode is a worse version of the same answer, not a safer one.
 */
object HeadroomForm {

    /** The families the daemon's ladder may contain, in its own order. */
    val FAMILIES: List<String> = listOf("fable", "opus", "sonnet", "haiku")

    /** The slider range. Below 50 % none of these thresholds mean anything. */
    const val MIN_PCT: Int = 50
    const val MAX_PCT: Int = 100

    const val PHRASE_MAX: Int = 300
    const val HEADS_UP_MAX: Int = 600

    /** One broken rule, named by the field it belongs to. */
    data class Problem(val field: String, val message: String)

    /**
     * Every rule the daemon would refuse this settings object for.
     *
     * Order matters only in that the first is what a compact form shows.
     */
    fun problems(s: HeadroomSettings): List<Problem> {
        val out = ArrayList<Problem>()

        for ((name, v) in listOf(
            "headsUpPct" to s.headsUpPct,
            "ladderPct" to s.ladderPct,
            "ladderUpBelowPct" to s.ladderUpBelowPct,
            "stopPct" to s.stopPct,
            "stopFablePct" to s.stopFablePct,
            "clearBelowPct" to s.clearBelowPct,
        )) {
            if (v !in 1..100) out += Problem(name, "$name must be between 1 and 100")
        }

        // THE ORDERING RULES, and they are the whole point of the thresholds: a
        // heads-up that fires at or after the ladder move is a warning about
        // something that has already happened.
        if (s.headsUpPct >= s.ladderPct) {
            out += Problem("headsUpPct", "the heads-up has to come before the ladder move")
        }
        if (s.clearBelowPct >= s.stopPct) {
            out += Problem("clearBelowPct", "a window cannot clear at or above the level that stops spawns")
        }
        if (s.ladderUpBelowPct >= s.ladderPct) {
            out += Problem("ladderUpBelowPct", "going back up at or above the level that moved it down would loop")
        }

        val ladder = s.ladder.map { it.trim().lowercase() }
        when {
            ladder.isEmpty() -> out += Problem("ladder", "the ladder needs at least one model")
            ladder.size != ladder.toSet().size -> out += Problem("ladder", "a model can only be on the ladder once")
            ladder.any { it !in FAMILIES } ->
                out += Problem("ladder", "the ladder can only hold ${FAMILIES.joinToString(", ")}")
        }

        val phrase = s.resumePhrase.trim()
        when {
            phrase.isEmpty() -> out += Problem("resumePhrase", "a resume needs something to say")
            phrase.length > PHRASE_MAX -> out += Problem("resumePhrase", "at most $PHRASE_MAX characters")
            // A slash command is not a resume: typing one would run a command into
            // a session that was waiting to carry on working.
            phrase.startsWith("/") -> out += Problem("resumePhrase", "cannot start with a slash — that is a command, not a resume")
        }

        val headsUp = s.headsUpText
        when {
            headsUp.length > HEADS_UP_MAX -> out += Problem("headsUpText", "at most $HEADS_UP_MAX characters")
            headsUp.isNotEmpty() && !headsUp.contains("{pct}") ->
                out += Problem("headsUpText", "must contain {pct} — the number is the point of the message")
        }

        val sw = s.accountSwitch
        if (sw.threshold !in 1..100) out += Problem("accountSwitch", "the switch threshold must be between 1 and 100")
        if (sw.margin !in 0..100) out += Problem("accountSwitch", "the margin must be between 0 and 100")

        return out
    }

    fun valid(s: HeadroomSettings): Boolean = problems(s).isEmpty()
}

/**
 * The settings form.
 *
 * @param models the host's `/v1/models` answer, for the default-model picker.
 *   Empty is fine — the field falls back to whatever is already set, because a
 *   list that has not landed must not silently clear a setting.
 * @param onSave handed the whole edited object; the caller sends the fields that
 *   changed as a PATCH so two open forms cannot overwrite each other.
 */
@Composable
fun HeadroomSettingsSection(
    settings: HeadroomSettings?,
    models: List<ModelChoice>,
    onSave: (HeadroomSettings) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    note: String? = null,
) {
    if (settings == null) {
        Text(
            "This host has no headroom settings — it is running a daemon older than 3.0.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    // Keyed on what arrived: a poll that re-delivers the same settings must not
    // discard an edit in progress, and a genuinely new object must replace it.
    var draft by remember(settings) { mutableStateOf(settings) }
    val problems = HeadroomForm.problems(draft)
    val dirty = draft != settings

    // CAP BEFORE FILL. `fillMaxWidth` hands DOWN fixed constraints, and a `widthIn`
    // inside fixed constraints can only coerce into them — so in the other order
    // the 760 is silently swallowed and this column spans whatever it is given.
    // Measured on the desktop at 1440: sliders 1105px wide for a 0-100 value, and
    // the intro paragraph set ~135 characters on one line. Both clients, one file.
    Column(modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("Thresholds")
        PctRow("Heads-up at", draft.headsUpPct) { draft = draft.copy(headsUpPct = it) }
        PctRow("Move down the ladder at", draft.ladderPct) { draft = draft.copy(ladderPct = it) }
        PctRow("Move back up below", draft.ladderUpBelowPct) { draft = draft.copy(ladderUpBelowPct = it) }
        PctRow("Hold new subagents at", draft.stopPct) { draft = draft.copy(stopPct = it) }
        PctRow("Hold Fable subagents at", draft.stopFablePct) { draft = draft.copy(stopFablePct = it) }
        PctRow("Treat a window as cleared below", draft.clearBelowPct) { draft = draft.copy(clearBelowPct = it) }
        Muted2("Holding a foreground Agent call freezes the turn that made it — the parent waits too.")

        Label("Ladder")
        Muted2("The order a live session is moved through when its window runs out.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val rungs = draft.ladder
            for (i in 0 until 3) {
                FamilyPicker(
                    value = rungs.getOrNull(i),
                    onPick = { picked ->
                        val next = rungs.toMutableList()
                        while (next.size <= i) next += ""
                        next[i] = picked
                        draft = draft.copy(ladder = next.filter { it.isNotEmpty() })
                    },
                )
            }
        }

        Label("Auto-resume")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = draft.autoResume, onCheckedChange = { draft = draft.copy(autoResume = it) })
            Spacer(Modifier.width(10.dp))
            Text(
                if (draft.autoResume) "Sessions pick themselves back up when the window resets"
                else "Sessions stay stopped until somebody types",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedTextField(
            value = draft.resumePhrase,
            onValueChange = { draft = draft.copy(resumePhrase = it) },
            label = { Text("What to type on resume") },
            singleLine = true,
            isError = problems.any { it.field == "resumePhrase" },
            modifier = Modifier.fillMaxWidth(),
        )

        Label("Default model")
        ModelPicker(
            value = draft.defaultModel,
            models = models,
            onPick = { draft = draft.copy(defaultModel = it) },
        )

        Label("Heads-up message")
        OutlinedTextField(
            value = draft.headsUpText,
            onValueChange = { draft = draft.copy(headsUpText = it) },
            label = { Text("Typed into the session before it is moved") },
            // Multiline: this is a paragraph asking for a handoff note, and a
            // single-line field hides all but the first clause of it.
            singleLine = false,
            minLines = 3,
            isError = problems.any { it.field == "headsUpText" },
            supportingText = { Text("{pct} becomes the percentage, {next} the model it is moving to, {ladderPct} the trigger.") },
            modifier = Modifier.fillMaxWidth(),
        )

        Label("Account switching")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = draft.accountSwitch.enabled,
                onCheckedChange = { draft = draft.copy(accountSwitch = draft.accountSwitch.copy(enabled = it)) },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Rotate to another saved login when this one runs out",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (draft.accountSwitch.enabled) {
            // Hoisted: a property of another module is not smart-cast through a
            // `copy` chain, and a local val is the fix rather than `!!`.
            val sw: AccountSwitch = draft.accountSwitch
            PctRow("Switch at", sw.threshold, min = 1) {
                draft = draft.copy(accountSwitch = sw.copy(threshold = it))
            }
            PctRow("Only to an account this much freer", sw.margin, min = 0) {
                draft = draft.copy(accountSwitch = sw.copy(margin = it))
            }
        }

        // The FIRST problem only. A list of six is a form shouting; the reader
        // fixes one, and the next one appears where they are already looking.
        problems.firstOrNull()?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        note?.let { Muted2(it) }

        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = dirty && problems.isEmpty() && !busy,
                onClick = { onSave(draft) },
            ) { Text(if (busy) "Saving…" else "Save") }
            if (dirty) {
                TextButton(enabled = !busy, onClick = { draft = settings }) { Text("Revert") }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Muted2(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A slider AND the number.
 *
 * Both, because they answer different questions: the slider says where this
 * threshold sits relative to the others at a glance, and the number is the only
 * way to set 92 rather than 91 on a track a few hundred pixels wide.
 */
@Composable
private fun PctRow(label: String, value: Int, min: Int = HeadroomForm.MIN_PCT, onChange: (Int) -> Unit) {
    // ⚠ THE SLIDER IS THE ONLY CHILD THAT CAN SHRINK, so it absorbs everything the
    // other two refuse to give up: a 230dp label and a 96dp number box out of
    // 340dp of pane left the track SIX PIXELS wide, which is a control that cannot
    // be dragged at all. The number box still worked, so the setting was reachable
    // — it simply looked broken, which is the worst of both.
    //
    // Under the width where all three fit, the label goes on its own line and the
    // slider gets the whole of the next one. Same three controls, same order, and
    // the slider never drops below a draggable track.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < PCT_ROW_STACK_BELOW

        @Composable
        fun RowScope.Track() {
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toInt().coerceIn(min, HeadroomForm.MAX_PCT)) },
                valueRange = min.toFloat()..HeadroomForm.MAX_PCT.toFloat(),
                modifier = Modifier.weight(1f),
            )
        }

        @Composable
        fun Number() {
            OutlinedTextField(
                value = value.toString(),
                // An empty field is a state the reader passes THROUGH while retyping a
                // number, so it must not be rejected into the old value on every
                // keystroke. Anything unparseable simply does not move the setting.
                onValueChange = { raw -> raw.trim().toIntOrNull()?.let { onChange(it.coerceIn(1, 100)) } },
                singleLine = true,
                suffix = { Text("%") },
                modifier = Modifier.width(PCT_NUMBER),
            )
        }

        if (stacked) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Track()
                    Spacer(Modifier.width(PCT_GAP))
                    Number()
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(PCT_LABEL))
                Track()
                Spacer(Modifier.width(PCT_GAP))
                Number()
            }
        }
    }
}

/** The row's fixed halves: the setting's name, and the number you can type into. */
private val PCT_LABEL = 230.dp
private val PCT_NUMBER = 96.dp
private val PCT_GAP = 10.dp

/** The narrowest slider that is still a slider rather than a decoration. */
private val MIN_TRACK = 160.dp

/**
 * Where a label, a slider and a number box stop fitting on one line.
 *
 * WRITTEN AS ITS PARTS rather than as a number, because that is what it has to
 * keep agreeing with: change the label column or the number box and this follows
 * on its own. Below it the label takes its own line and the slider gets a real
 * track back — ~310dp at 420dp of window, rather than six pixels.
 */
private val PCT_ROW_STACK_BELOW = PCT_LABEL + PCT_NUMBER + PCT_GAP + MIN_TRACK

@Composable
private fun FamilyPicker(value: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }) { Text(value?.ifEmpty { "—" } ?: "—") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            HeadroomForm.FAMILIES.forEach { f ->
                DropdownMenuItem(text = { Text(f) }, onClick = { open = false; onPick(f) })
            }
        }
    }
}

@Composable
private fun ModelPicker(value: String, models: List<ModelChoice>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // SESSION site: this model is typed at a live pane and a local row could never
    // serve one, which is the same rule the session's own picker follows.
    val options = ModelLabels.options(models, ModelLabels.PickerSite.SESSION)
    Column {
        TextButton(onClick = { open = true }) {
            Text(options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "host default" })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}
