package com.silencelen.huginn.ui

/**
 * How a Claude-written prompt is SHOWN on an approval card — never how it is
 * sent. The `start_session` request's prompt (appd 3.17) is written by Claude,
 * which may itself be prompt-injected, and the owner's tap on Start is the only
 * thing between it and a full session. So the card must show every character
 * the daemon will type, and nothing may be able to hide one.
 *
 * Four things can hide text in a monospace block: a run of whitespace that
 * pushes the rest past the edge, a run of blank lines that pushes it below a
 * capped block's fold, a control or bidi character that moves, erases or
 * reverses what follows, and sheer length. [visible] handles the first three in
 * a DISPLAY COPY, [counts] the fourth. The prompt the daemon sends is untouched:
 * the answer to the question carries no prompt at all, and the card's job is
 * only to show the one the daemon already holds.
 *
 * Plain text comes through [visible] unchanged, newlines included — a single
 * blank line between paragraphs too — so a normal prompt reads as written.
 *
 * ⚠ EVERY SPACE-LIKE CHARACTER IS A GAP, AND BLANK LINES ARE COUNTED OUT
 * (security scan 2026-11-02, third scan, F1). The first copy collapsed only
 * ASCII spaces and tabs; 2,000 no-break spaces, or sixty newlines, pushed the
 * real instruction below a 180dp fold with no scrollbar, and the person read
 * "Summarize README.md" and tapped Start. So [isGap] covers everything
 * `Character.isWhitespace` or the SPACE_SEPARATOR category calls blank plus the
 * blanks those miss (the no-break spaces, the Braille blank, the ideographic
 * space), and a run of two or more blank lines is one line that says how many.
 */
object PromptDisplay {
    /** Stands in for a run of three or more space-like characters ([isGap]). */
    const val GAP: String = "⋯"

    /** `⋯ 50 blank lines ⋯` — the one line that stands in for a run of two or more blank lines. */
    fun blankLinesMark(n: Int): String = "$GAP $n blank lines $GAP"

    /** Why Start or Approve is still disabled: the card's text has not been scrolled to its end. */
    const val SCROLL_FIRST: String = "Scroll to the end of the prompt first."

    /**
     * The display copy: hidden characters made visible, space runs collapsed to
     * [GAP], a run of two or more blank lines to one [blankLinesMark] line.
     * Single newlines and a single blank line are kept as they are.
     */
    fun visible(prompt: String): String {
        if (prompt.isEmpty()) return prompt
        // A trailing newline ends the last line; it is not a line of its own.
        val terminated = prompt.endsWith('\n')
        val lines = (if (terminated) prompt.dropLast(1) else prompt).split('\n')
        val out = StringBuilder(prompt.length)
        var i = 0
        while (i < lines.size) {
            if (i > 0) out.append('\n')
            if (lines[i].all { isGap(it) }) {
                var j = i + 1
                while (j < lines.size && lines[j].all { isGap(it) }) j++
                // One blank line is a paragraph break and stays one empty line;
                // two or more are the mark, which says how many.
                if (j - i > 1) out.append(blankLinesMark(j - i))
                i = j
            } else {
                visibleLine(lines[i], out)
                i++
            }
        }
        if (terminated) out.append('\n')
        return out.toString()
    }

    /** One line of the display copy: gap runs of three or more collapsed, hidden characters marked. */
    private fun visibleLine(line: String, out: StringBuilder) {
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 3) out.append(GAP) else out.append(run)
            run.setLength(0)
        }
        for (ch in line) {
            if (isGap(ch)) { run.append(ch); continue }
            flush()
            if (isHidden(ch)) out.append(mark(ch)) else out.append(ch)
        }
        flush()
    }

    /**
     * A character that draws as blank and lays text out — what a run of can push
     * the rest of a prompt past an edge or below a fold: the space and the tab,
     * everything else `Character.isWhitespace` calls white (except the newline,
     * which is the line structure itself, and the controls, which [isHidden]
     * spells out), every SPACE_SEPARATOR (the no-break spaces U+00A0 and U+202F,
     * the figure space U+2007 and the en/em/thin spaces beside them, the
     * ideographic space U+3000), and the Braille blank U+2800, a symbol by
     * category that draws as nothing.
     */
    fun isGap(ch: Char): Boolean {
        if (ch == '\n' || isHidden(ch)) return false
        val c = ch.code
        return ch == ' ' || ch == '\t' ||
            ch.isWhitespace() ||
            ch.category == CharCategory.SPACE_SEPARATOR ||
            c == 0x00A0 || c == 0x2007 || c == 0x202F || c == 0x2800 || c == 0x3000
    }

    /**
     * A character that draws nothing of its own and can move, erase or reverse
     * what is around it — or simply hide in front of one that matters: the C0
     * controls (except newline and tab, which lay text out), DEL, every Unicode
     * FORMAT character (the zero-width and bidi marks, the bidi embeddings,
     * overrides and isolates, the soft hyphen, the word joiner U+2060 and the
     * invisible operators beside it, the deprecated format controls U+206A–206F,
     * the byte-order mark, the interlinear annotation marks), and the blank
     * fillers that are letters by category but draw as nothing (the Hangul
     * fillers, the combining grapheme joiner).
     *
     * ⚠ The FORMAT category, not a hand list (security scan 2026-11-02, F2): a
     * word joiner in front of a `!` was invisible here while the daemon strips it
     * before the paste, so the card showed a prompt that did not start with `!`
     * and the pasted bytes did. The daemon's own list (typing.js
     * ALWAYS_REMOVED_RE) is the set this must cover; the category is a superset.
     */
    fun isHidden(ch: Char): Boolean {
        val c = ch.code
        return (c < 0x20 && ch != '\n' && ch != '\t') ||
            c == 0x7F ||
            (c in 0x80..0x9F) ||
            ch.category == CharCategory.FORMAT ||
            c == 0x034F || c == 0x115F || c == 0x1160 || c == 0x3164 || c == 0xFFA0 ||
            (c in 0xFFF0..0xFFF8)
    }

    /** `⟨U+202E⟩` — the code point, spelled, where the character was. */
    fun mark(ch: Char): String = "⟨U+" + ch.code.toString(16).uppercase().padStart(4, '0') + "⟩"

    /**
     * Size of the prompt as SENT: lines, characters (code points) and how many of
     * the lines are blank ([isGap] only, or empty) — of the original, not the
     * display copy. The line rule is [visible]'s: a trailing newline ends the
     * last line rather than starting an empty one, so the lines said here are the
     * lines the display copy accounts for, blank ones through [blankLinesMark].
     */
    data class Counts(val lines: Int, val characters: Int, val blank: Int = 0)

    fun counts(prompt: String): Counts {
        if (prompt.isEmpty()) return Counts(0, 0, 0)
        var chars = 0
        for (ch in prompt) if (!ch.isLowSurrogate()) chars++
        val body = if (prompt.endsWith('\n')) prompt.dropLast(1) else prompt
        val lines = body.split('\n')
        return Counts(lines.size, chars, lines.count { l -> l.all { isGap(it) } })
    }

    /** The lines of the prompt as sent, counted as [counts] does. */
    fun lineCount(prompt: String): Int = counts(prompt).lines

    /** `3 lines · 212 characters`, or `52 lines, 50 blank · 212 characters` — drawn beside Start. */
    fun countsLine(prompt: String): String {
        val c = counts(prompt)
        val l = if (c.lines == 1) "1 line" else "${c.lines} lines"
        val b = if (c.blank > 0) ", ${c.blank} blank" else ""
        val n = if (c.characters == 1) "1 character" else "${c.characters} characters"
        return "$l$b · $n"
    }
}
