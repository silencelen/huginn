package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.QuickActions

/**
 * The wording Explain, Execute and Ask in a new chat put in the composer.
 *
 * MOVED HERE FROM THE DESKTOP, LOGIC UNCHANGED. It was desktop-only
 * (`SettingsView.kt:1092-1153`) even though the wording is HOST-owned and the
 * phone consumes it — `w2-surface.md:583` promised the phone editor and it was
 * never built, so the phone could use these templates and not change them. One
 * editor, both shells, one set of words.
 *
 * ⚠ THE TEMPLATES ARE THE DAEMON'S AND SO ARE THE REFUSALS. `{selection}`
 * exactly once, never in the quote lead-in, 400 characters each — all enforced
 * on the host, which answers a broken one with its own sentence. This surfaces
 * that sentence rather than growing a second copy of the rules that would
 * eventually disagree with it.
 *
 * ⚠ HIDDEN ENTIRELY against a daemon with no templates: the caller checks
 * `SettingsProbe.quickActions` (`status.quickActions != null`) and does not draw
 * this at all. Four boxes whose Save can only 404 is worse than no editor.
 *
 * @param actions the host's current templates, carrying the `rev` a save is
 *   guarded by. The fields are KEYED on that rev, so a save — this window's or
 *   another client's — refills the boxes rather than leaving this one editing a
 *   copy the host has already moved past.
 * @param busy a save is in flight.
 * @param note the host's own words about the last save, or null.
 * @param onSave handed the edited templates, `rev` included. The caller does the
 *   PATCH and owns the outcome sentence.
 */
/**
 * THE ONE BLURB. Both shells draw the editor and both used to introduce it, so
 * the phone printed a sentence about quick actions and then the editor printed
 * another one directly under it — and the editor's said "when you right-click
 * selected text", which is not a gesture this phone has. Held here as a constant
 * so the duplicate cannot come back as a paraphrase: a shell that wants to say
 * something about quick actions says THIS.
 *
 * ⚠ PLATFORM-NEUTRAL. `:ui` is shared: the desktop right-clicks, the phone
 * long-presses, and neither gesture may be named here. [QuickActionsBlurbTest]
 * greps this string for the words.
 */
const val QUICK_ACTIONS_BLURB: String =
    "What Explain, Execute and Ask in a new chat put in the composer when you select text and " +
        "pick an action. {selection} is the text you selected. Nothing is ever sent — it is " +
        "staged for you to edit."

/** Gesture words a shared blurb may not use. One of them was in it. */
val PLATFORM_GESTURE_WORDS: List<String> = listOf("right-click", "right click", "long-press", "long press", "tap")

@Composable
fun QuickActionsEditor(
    actions: QuickActions,
    busy: Boolean,
    note: String?,
    onSave: (QuickActions) -> Unit,
    modifier: Modifier = Modifier,
) {
    var explain by remember(actions.rev) { mutableStateOf(actions.explain) }
    var execute by remember(actions.rev) { mutableStateOf(actions.execute) }
    var askInNewChat by remember(actions.rev) { mutableStateOf(actions.askInNewChat) }
    var quote by remember(actions.rev) { mutableStateOf(actions.quote) }

    // Cap before fill: the other order hands this column fixed constraints and
    // the cap can only coerce into them.
    Column(
        modifier.widthIn(max = SETTINGS_READING_WIDTH).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EditorNote(QUICK_ACTIONS_BLURB, maxLines = 4)

        Field("Explain", explain) { explain = it }
        Field("Execute", execute) { execute = it }
        Field("Ask in new chat", askInNewChat) { askInNewChat = it }
        Field("Quote lead-in (optional, no {selection})", quote) { quote = it }
        // The frame itself is not editable and is not shown as a field: "> " in
        // front of every line is a markdown fact this client owns, not a phrase.
        EditorNote(
            "Quote always frames the selection as a > block; the lead-in sits above it.",
            Modifier.padding(top = 4.dp),
            // THREE, because EditorNote's default is one and this sentence does
            // not fit on one line of a phone: it ellipsised at "Quote always
            // frames…", which turns the explanation of the only non-obvious field
            // in this editor into three words and a dot-dot-dot.
            maxLines = 3,
        )

        Button(
            onClick = {
                onSave(
                    actions.copy(
                        explain = explain,
                        execute = execute,
                        askInNewChat = askInNewChat,
                        quote = quote,
                    ),
                )
            },
            enabled = !busy,
            modifier = Modifier.padding(top = 10.dp),
        ) { Text(if (busy) "Saving…" else "Save quick actions") }

        note?.let { EditorNote(it, Modifier.padding(top = 6.dp), maxLines = 2) }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        maxLines = 4,
        // CAP BEFORE FILL, then fill. Without the fill the field is only as wide
        // as its content asks for, so "Quote lead-in (optional, no {selection})"
        // — the longest label of the four — overflowed the box it labels on a
        // phone. The cap is what keeps it a readable measure on a desktop.
        modifier = Modifier.padding(top = 10.dp).widthIn(min = 280.dp, max = 560.dp).fillMaxWidth(),
    )
}

/**
 * The muted meta line these editors are built out of. `:ui` has no equivalent of
 * the desktop's `Muted`, and importing one from a shell would point this module
 * at its own consumer.
 */
@Composable
internal fun EditorNote(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
