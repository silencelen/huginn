package com.silencelen.huginn.ui

import androidx.compose.runtime.MutableState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The composer glue for Up/Down sent-message recall. The decision logic is
 * [HistoryWalk] (pure, tested); this is the thin part that reads a key event and
 * moves a [TextFieldValue] — shared so the chat and session composers behave
 * identically.
 *
 * Call from `onPreviewKeyEvent` on a KeyDown with NO modifiers (a modified arrow
 * is list navigation or selection, not history). Returns true when it consumed
 * the key.
 *
 * [suppressed] is the Screen-tab-live-keyboard case: there every keystroke
 * belongs to the pane, so recall must not swallow the arrow.
 */
fun handleHistoryKey(
    key: Key,
    field: TextFieldValue,
    recall: MutableState<HistoryWalk.Cursor?>,
    history: List<String>,
    suppressed: Boolean,
    setField: (TextFieldValue) -> Unit,
    onDraft: (String) -> Unit,
): Boolean {
    if (suppressed) return false

    fun adopt(text: String) {
        setField(TextFieldValue(text, TextRange(text.length)))
        onDraft(text)
    }

    return when (key) {
        Key.DirectionUp -> {
            val cur = recall.value
            if (cur == null) {
                if (!HistoryWalk.canEnter(field.text, field.selection.start, field.selection.end)) return false
                val started = HistoryWalk.enter(history, field.text) ?: return false
                recall.value = started
                adopt(HistoryWalk.text(started))
                true
            } else {
                val next = HistoryWalk.up(cur)
                recall.value = next
                adopt(HistoryWalk.text(next))
                true
            }
        }
        Key.DirectionDown -> {
            val cur = recall.value ?: return false
            val next = HistoryWalk.down(cur)
            if (next == null) {
                recall.value = null
                adopt(cur.stash)       // past the newest: the in-progress draft is back
            } else {
                recall.value = next
                adopt(HistoryWalk.text(next))
            }
            true
        }
        Key.Escape -> {
            val cur = recall.value ?: return false   // only consumed while walking
            recall.value = null
            adopt(cur.stash)
            true
        }
        else -> false
    }
}

/**
 * Call from `onValueChange`: any edit that no longer matches the recalled entry
 * ends the walk, keeping the edit (the stash is abandoned — the edit is the new
 * draft). A no-op when not walking.
 */
fun exitRecallIfDiverged(recall: MutableState<HistoryWalk.Cursor?>, newText: String) {
    val cur = recall.value ?: return
    if (newText != HistoryWalk.text(cur)) recall.value = null
}
