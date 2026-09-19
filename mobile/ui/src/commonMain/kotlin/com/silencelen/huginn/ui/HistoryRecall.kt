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
                // ⚠ min/max, NOT start/end — the same rule `newlineIn` states for
                // the Shift+Enter splice, and this was the other site. A
                // [TextRange] is DIRECTED: Shift+Left, Shift+Home, Shift+Up and a
                // right-to-left drag all produce `start > end`, and Compose's
                // legacy TextFieldValue path hands that straight to
                // onPreviewKeyEvent unnormalised. Reading the raw pair means
                // every caret arithmetic downstream is one edit away from running
                // backwards; ordering it here is what stops that being possible.
                if (!HistoryWalk.canEnter(field.text, field.selection.min, field.selection.max)) return false
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
