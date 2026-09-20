package com.silencelen.huginn.ui

/**
 * ⚠⚠ P-14. THE ROW SHOWED A PROMPT NOBODY TYPED.
 *
 * `rv_phone_2`'s row read `❯ run sleep 10 in the background then say doneB` —
 * text nobody had written. It is Claude Code's own dim inline SUGGESTION, offered
 * back from history, and the pane line carries SGR-2 with the cursor still at
 * column 2, i.e. the composer is empty:
 *
 *     '\x1b[39m❯\xa0\x1b[2mrun sleep 10 in the background then say doneB\x1b[0m'
 *     cursorX 2  cursorY 20
 *
 * The app drew it in normal weight, indistinguishable from a real queued prompt.
 *
 * ⚠ THE DAEMON CANNOT STRIP IT ON THIS PATH, which is why the filter is here.
 * `lib/typing.js` has the right tell — the dim attribute itself — and uses it for
 * `composerText`, which is captured with `tmux capture-pane -e`. The session
 * LIST's preview is a different capture: `huginn-appd.js` takes
 * `capture-pane -p` (no `-e`) and `previewLines` calls `stripAnsi` on every row,
 * so by the time a preview line exists the dimness is already gone. Adding `-e`
 * to that capture is a daemon change paid on every poll of every session; the
 * SHAPE is enough here.
 *
 * So the same structural tell `pane.js` uses — `PROMPT_MARK_RE`, `^\s*[❯>]\s*` —
 * decides. The daemon already drops a prompt line whose content is EMPTY; a
 * non-empty one is either this ghost or somebody's unsent draft, and neither is
 * "what the session is doing", which is the only question the preview exists to
 * answer. It is KEPT and marked rather than dropped, because a line that vanishes
 * takes its own explanation with it: the reader should see that the composer has
 * something in it without being told a message is pending.
 */
object PanePreview {

    /** One preview line, and whether it is the composer rather than output. */
    data class Row(val text: String, val hint: Boolean)

    /**
     * ⚠ THE GAP IS A NON-BREAKING SPACE. Claude Code writes the prompt as `❯` +
     * U+00A0, not `❯` + a space.
     *
     * Kotlin's `Char.isWhitespace()` happens to cover it — it is
     * `Character.isWhitespace(c) || Character.isSpaceChar(c)`, and NBSP is the
     * second — where Java's own `isWhitespace` alone answers FALSE. That is a
     * distinction one import away from mattering (a JS or Native target, or
     * anybody reaching for `java.lang.Character` here), and the whole prompt test
     * turns on it, so the character is named rather than trusted to a category.
     * `PanePreviewTest` pins the premise so a change in it is a failing test
     * rather than a preview that silently stops marking anything.
     */
    private const val NBSP: Char = ' '

    private fun Char.isGap(): Boolean = this == NBSP || isWhitespace()

    /**
     * The composer's own input line — `pane.js`'s `PROMPT_MARK_RE` exactly:
     * `^\s*[❯>]\s*`.
     */
    fun isComposerLine(line: String): Boolean {
        val t = line.trimStart { it.isGap() }
        if (t.isEmpty()) return false
        val mark = t[0]
        if (mark != '❯' && mark != '>') return false
        val rest = t.drop(1)
        // A mark with nothing after it is already dropped by the daemon; a mark
        // followed immediately by a word is a quote or a diff, not a prompt.
        return rest.isEmpty() || rest[0].isGap()
    }

    /** What a composer line SAYS, without the chevron and its gap. */
    fun composerText(line: String): String =
        line.trimStart { it.isGap() }.drop(1).trim { it.isGap() }

    /** The preview as rows, with the composer line marked as a hint. */
    fun rows(preview: List<String>): List<Row> =
        preview.map { Row(it, isComposerLine(it)) }
}
