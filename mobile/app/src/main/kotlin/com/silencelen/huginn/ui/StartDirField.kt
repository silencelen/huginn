package com.silencelen.huginn.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The new-session dialog's "Starting directory": a long path shows its END.
 *
 * ⚠ THE TAIL IS WHAT TELLS TWO DIRECTORIES APART, AND IT WAS THE PART HIDDEN.
 * The field showed `/root/projects/huginn-e2e/al` — one line, cursor at 0, the
 * Recent button taking the rest — and the Recent list cut every row at its end,
 * so two `/root/projects/apv2/assets/src/voice/…` rows read the same (Fold
 * cover screen, 2026-11-03). The field now wraps to two lines and a picked path
 * lands with the cursor at its end, so a longer one scrolls to its tail; a
 * Recent row is labelled by [StartDirRules.DirLabel] — the desktop's rule, one
 * copy in `:core` — measured into [RECENT_LABEL_WIDTH].
 *
 * The rules are `:core`'s StartDirRules (blank is the server default, one
 * absolute line); this is only how the phone draws them.
 */
object StartDirField {

    /**
     * The width a Recent row's label has: the house menu's row is at most
     * 280 dp, less Material's 12 dp either side, less a little slack.
     */
    val RECENT_LABEL_WIDTH: Dp = 248.dp

    /** A path picked from Recent, with the cursor at its END so the field shows the tail. */
    fun picked(path: String): TextFieldValue = TextFieldValue(path, selection = TextRange(path.length))

    /**
     * What was typed, kept on ONE line: the field wraps to show a long path, so
     * Enter (or a pasted line break) would otherwise land in it — and a
     * directory is one line. The cursor moves back over what was taken out.
     */
    fun oneLine(v: TextFieldValue): TextFieldValue {
        if (v.text.none { it == '\n' || it == '\r' }) return v
        fun strip(s: String) = s.filterNot { it == '\n' || it == '\r' }
        fun at(i: Int) = strip(v.text.substring(0, i.coerceIn(0, v.text.length))).length
        // min/max, never start/end: a TextRange is directed (HistoryRecallTest).
        // Right after the edit that put a line break in, the cursor is collapsed.
        return TextFieldValue(strip(v.text), TextRange(at(v.selection.min), at(v.selection.max)))
    }
}
