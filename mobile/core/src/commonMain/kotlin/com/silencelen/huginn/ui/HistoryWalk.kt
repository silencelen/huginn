package com.silencelen.huginn.ui

/**
 * The Up/Down sent-history recall state machine, extracted from the composable
 * so its rules can be unit-tested (the LocalEcho / VoiceLoop precedent: engine
 * callbacks and key events race; a pure reducer cannot).
 *
 * Semantics (matching shell/CLI muscle memory):
 *  - Up ENTERS history only when the caret is on the first line with a collapsed
 *    selection ([canEnter]) — so Up inside a multiline draft moves the caret,
 *    not the history.
 *  - Once IN history, Up/Down walk entries regardless of caret position: a
 *    recalled multiline entry parks the caret at its end, and gating in-mode
 *    navigation on the caret would strand the walk there.
 *  - Up clamps at the oldest entry (no wrap — a held key must not teleport back
 *    to newest).
 *  - Down past the newest EXITS, restoring the stashed in-progress draft.
 *  - Escape exits restoring the stash. The composable consumes Escape only
 *    while a walk is active, so it cannot shadow any other Escape use.
 *  - Any edit that diverges from the recalled text exits the walk keeping the
 *    edit (the caller compares against [text] and calls [exitKeepingEdit]).
 */
object HistoryWalk {

    /** An active walk. [index] points into [entries]; [stash] is the pre-walk draft. */
    data class Cursor(
        val entries: List<String>,
        val index: Int,
        val stash: String,
    )

    /**
     * May Up begin a walk from this composer state? Only with a collapsed
     * selection sitting on the FIRST line (no newline before the caret) — which
     * includes the empty composer.
     */
    fun canEnter(text: String, selStart: Int, selEnd: Int): Boolean {
        if (selStart != selEnd) return false
        val caret = selStart.coerceIn(0, text.length)
        return '\n' !in text.substring(0, caret)
    }

    /** Begin a walk at the newest entry, stashing the in-progress draft. Null when there is nothing to recall. */
    fun enter(entries: List<String>, draft: String): Cursor? {
        if (entries.isEmpty()) return null
        return Cursor(entries, entries.size - 1, draft)
    }

    /** Older. Clamps at the oldest. */
    fun up(c: Cursor): Cursor = if (c.index <= 0) c else c.copy(index = c.index - 1)

    /** Newer. Past the newest returns null — the walk is over, restore [Cursor.stash]. */
    fun down(c: Cursor): Cursor? =
        if (c.index >= c.entries.size - 1) null else c.copy(index = c.index + 1)

    /** The entry the walk is showing. */
    fun text(c: Cursor): String = c.entries[c.index]
}
