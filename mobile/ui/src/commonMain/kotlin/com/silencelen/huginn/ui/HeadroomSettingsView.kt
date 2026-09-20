package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.AccountSwitch
import com.silencelen.huginn.settings.KeepAwakeCopy
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

    const val MIN_PCT: Int = 1
    const val MAX_PCT: Int = 100

    /**
     * Thresholds that mean "this window is nearly gone": the heads-up, the ladder
     * move, and the two spawn gates. Below 50 % none of them mean anything — a
     * heads-up at 12 % is a session being warned about nothing.
     */
    val HIGH_RANGE: IntRange = 50..MAX_PCT

    /**
     * The two "…below" fields, which are the OTHER end of the same scale: a
     * window counts as cleared, or a session is worth picking back up, when
     * little enough of it is spent. They must be able to sit UNDER the high ones
     * — the daemon refuses `clearBelowPct >= stopPct` and `ladderUpBelowPct >=
     * ladderPct` — so 50 as a floor put the useful half of their range off the
     * slider entirely. 99 rather than 100 for the same reason: 100 can never
     * satisfy either ordering rule.
     */
    val BELOW_RANGE: IntRange = 1..(MAX_PCT - 1)

    /** Account switching has its own two, and the margin is a difference, not a level. */
    val SWITCH_AT_RANGE: IntRange = 1..MAX_PCT
    val SWITCH_MARGIN_RANGE: IntRange = 0..MAX_PCT

    /**
     * The range ONE field's slider and its number box share.
     *
     * ⚠ ONE RANGE PER FIELD, read by both controls. They used to disagree: the
     * slider was a flat 50..100 for everything and the box clamped to 1..100, so
     * a typed 20 in "Treat a window as cleared below" was silently snapped back
     * to 50 by the next touch of the slider beside it — the box accepted a value
     * the control next to it could not hold, which is a form that argues with
     * itself. Named for the daemon's own field names, the same strings
     * [Problem.field] carries, so the two cannot drift apart.
     */
    fun range(field: String): IntRange = when (field) {
        "ladderUpBelowPct", "clearBelowPct" -> BELOW_RANGE
        "accountSwitch.threshold" -> SWITCH_AT_RANGE
        "accountSwitch.margin" -> SWITCH_MARGIN_RANGE
        else -> HIGH_RANGE
    }

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

        if (s.keepAwakeModel.isBlank()) {
            out += Problem("keepAwakeModel", "pick a model for the keep-awake request")
        }
        val quiet = s.keepAwakeQuietHours
        if (quiet != null && quiet.isNotBlank() && parseQuietHours(quiet) == null) {
            out += Problem("keepAwakeQuietHours", "quiet hours read as two clock times, like 01:00-07:00")
        }

        return out
    }

    /**
     * `"01:00-07:00"` → the two minute counts, or null.
     *
     * ⚠ THE DAEMON'S RULE, RE-EXPRESSED. `lib/keepawake.js:parseQuietHours` is
     * the authority and answers a 400 naming it; this exists so a range that
     * cannot be saved is refused under the reader's finger rather than after a
     * round trip. Same three refusals, deliberately: not two clock times, an
     * hour or minute out of range, and a span that starts and ends on the same
     * minute — which is either nothing or everything, and no reader agrees on
     * which. An en dash is accepted because that is what a phone keyboard makes.
     */
    fun parseQuietHours(spec: String?): Pair<Int, Int>? {
        val raw = spec?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val m = QUIET.matchEntire(raw) ?: return null
        val (fromH, fromM, toH, toM) = m.destructured
        val from = (fromH.toIntOrNull() ?: return null) * 60 + (fromM.toIntOrNull() ?: return null)
        val to = (toH.toIntOrNull() ?: return null) * 60 + (toM.toIntOrNull() ?: return null)
        if (from > 23 * 60 + 59 || to > 23 * 60 + 59) return null
        if (fromM.toInt() > 59 || toM.toInt() > 59) return null
        if (from == to) return null
        return from to to
    }

    private val QUIET = Regex("""^(\d{1,2}):(\d{2})\s*[-–—]\s*(\d{1,2}):(\d{2})$""")

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
        PctRow("Heads-up at", draft.headsUpPct, HeadroomForm.range("headsUpPct")) { draft = draft.copy(headsUpPct = it) }
        PctRow("Move down the ladder at", draft.ladderPct, HeadroomForm.range("ladderPct")) { draft = draft.copy(ladderPct = it) }
        PctRow("Move back up below", draft.ladderUpBelowPct, HeadroomForm.range("ladderUpBelowPct")) { draft = draft.copy(ladderUpBelowPct = it) }
        PctRow("Hold new subagents at", draft.stopPct, HeadroomForm.range("stopPct")) { draft = draft.copy(stopPct = it) }
        PctRow("Hold Fable subagents at", draft.stopFablePct, HeadroomForm.range("stopFablePct")) { draft = draft.copy(stopFablePct = it) }
        PctRow("Treat a window as cleared below", draft.clearBelowPct, HeadroomForm.range("clearBelowPct")) { draft = draft.copy(clearBelowPct = it) }
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
            // MULTI-LINE, like the heads-up message below it. This is a sentence
            // typed into a live session, up to PHRASE_MAX characters; on one line
            // the owner's own phrase showed as "Your usage limit has reset.
            // Continue the task" with the rest of it off the right edge, so the
            // field could not be read, let alone checked before saving.
            singleLine = false,
            minLines = 2,
            maxLines = 4,
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
            PctRow("Switch at", sw.threshold, HeadroomForm.range("accountSwitch.threshold")) {
                draft = draft.copy(accountSwitch = sw.copy(threshold = it))
            }
            PctRow("Only to an account this much freer", sw.margin, HeadroomForm.range("accountSwitch.margin")) {
                draft = draft.copy(accountSwitch = sw.copy(margin = it))
            }
        }

        Label("Keep a window rotating")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = draft.keepAwake, onCheckedChange = { draft = draft.copy(keepAwake = it) })
            Spacer(Modifier.width(10.dp))
            // ⚠ THE COPY SAYS WHAT IT COSTS, in both states. This is the only
            // control in the product that spends the owner's quota with nobody
            // asking for it, and a toggle labelled "keep a window rotating" tells
            // a reader what it does without telling them what it is for or what
            // it takes. The OFF wording says what is given up rather than
            // nothing, so the two states read as a choice instead of as a feature
            // and its absence.
            // ⚠ ONE SENTENCE, FROM `KeepAwakeCopy` (P-20). This page and a
            // settings search for "keep" described the same toggle two
            // contradictory ways — "Nothing is spent" here against "Spends a
            // fraction of a cent" there — because the OFF copy was being read as
            // the feature's description. The cost is stated in the ON state and
            // the OFF state now talks about the switch.
            Text(
                if (draft.keepAwake) KeepAwakeCopy.WHAT else KeepAwakeCopy.OFF,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (draft.keepAwake) {
            ModelPicker(
                value = draft.keepAwakeModel,
                models = models,
                onPick = { draft = draft.copy(keepAwakeModel = it) },
            )
            Muted2(
                "The 5-hour window is account-wide, so the cheapest model opens the same window " +
                    "every other model then shares.",
            )
            OutlinedTextField(
                // `?: ""` rather than a nullable field: an empty box IS "no quiet
                // hours", and the daemon reads "" and null identically so that
                // clearing the field and never setting it mean the same thing.
                value = draft.keepAwakeQuietHours ?: "",
                onValueChange = { draft = draft.copy(keepAwakeQuietHours = it.ifBlank { null }) },
                label = { Text("Quiet hours (optional)") },
                singleLine = true,
                isError = problems.any { it.field == "keepAwakeQuietHours" },
                supportingText = { Text("Local time, as 01:00-07:00. A span crossing midnight is fine. Empty means never quiet.") },
                modifier = Modifier.fillMaxWidth(),
            )
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
// SliderDefaults.Track's stop-indicator parameter is still experimental; the
// annotation is the price of turning the dot off. See the note at the call.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun PctRow(
    label: String,
    value: Int,
    /** [HeadroomForm.range] for this field — the SAME object both controls use. */
    range: IntRange,
    onChange: (Int) -> Unit,
) {
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
            // THE THEME'S OWN MUTED SURFACE for the unspent part of the track.
            // Material's default inactive track is secondaryContainer, which in
            // this palette is a violet that appears nowhere else in the product —
            // six sliders' worth of a colour the app does not use.
            val colors = SliderDefaults.colors(
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toInt().coerceIn(range)) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                colors = colors,
                // ⚠ NO STOP INDICATOR. Material 3 draws a small dot near the end
                // of the inactive track, which on a threshold slider reads as a
                // second, stuck thumb — six of them down the Usage page, each one
                // inviting somebody to try to drag it. It means "the end of the
                // range", which is what the end of the track already means and
                // what the number in the box beside it says outright.
                track = { state -> SliderDefaults.Track(sliderState = state, colors = colors, drawStopIndicator = null) },
                modifier = Modifier.weight(1f),
            )
        }

        @Composable
        fun Number() {
            // ⚠ THE BOX HOLDS ITS OWN TEXT. Fully controlled from `value`, every
            // intermediate parse was clamped into the field's range and written
            // straight back, so on a 50..100 row nothing between 51 and 99 could
            // be typed at all: the first digit clamped to 50, the second landed
            // mid-string and clamped to 100, and the box could never be emptied.
            // The first keystroke therefore SET the setting — one character into
            // "Hold new subagents at" disabled the hold gate.
            var text by remember(value) { mutableStateOf(value.toString()) }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val edit = pctFieldEdit(raw, range)
                    text = edit.text
                    edit.commit?.let(onChange)
                },
                singleLine = true,
                suffix = { Text("%") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

/**
 * What one keystroke in a percentage box does: the text it leaves on screen, and
 * the value to commit (null for a state the reader is merely passing THROUGH).
 *
 * Pure so the arithmetic is assertable — the defect it replaces was invisible in
 * a screenshot and only reachable a keystroke at a time.
 *
 * ⚠ ONLY AN IN-RANGE PARSE COMMITS. Clamping a prefix is what made 51..99
 * untypable on the four 50..100 rows; an empty box, a lone "9" on such a row, or
 * anything unparseable simply does not move the setting.
 */
internal data class PctEdit(val text: String, val commit: Int?)

internal fun pctFieldEdit(raw: String, range: IntRange): PctEdit {
    val digits = raw.filter { it.isDigit() }.take(3)
    val n = digits.toIntOrNull()
    return PctEdit(digits, if (n != null && n in range) n else null)
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

/**
 * A picker that looks like one.
 *
 * The ladder rungs and the default model were bare [TextButton]s: primary-tinted
 * words with nothing around them and no caret, sitting in a form full of other
 * primary-tinted words (every section heading is one). On the walk the owner's
 * ladder read as the sentence "fable opus sonnet" and the default model as a
 * label — three controls and a heading, drawn identically. A container tint and a
 * caret are the whole fix: this is the vernacular every other dropdown in the
 * product already uses.
 */
@Composable
private fun PickerButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            // ⚠ THE THEME'S OWN MUTED SURFACE, the same one the sliders' inactive
            // track is pinned to and for the same reason: `Theme.kt` never
            // defines `secondaryContainer`, so it falls back to Material's
            // baseline violet — a colour that appears nowhere else in a warm
            // rune-gold palette, and there are six of these pills down the Usage
            // page. The LABEL stays at full `onSurface`: it is the picker's
            // value, not a caption, and it is the thing being read.
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(label, maxLines = 1)
        Icon(
            Icons.Filled.ArrowDropDown,
            // The tint and the caret say the same thing; a reader who cannot
            // separate them still has one of the two.
            contentDescription = "choose",
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun FamilyPicker(value: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        PickerButton(value?.ifEmpty { "—" } ?: "—") { open = true }
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
        PickerButton(
            options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "host default" },
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}
