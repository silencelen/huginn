package com.silencelen.huginn.ui

/**
 * How a Claude-written prompt is SHOWN on an approval card — never how it is
 * sent. The `start_session` request's prompt (appd 3.17) is written by Claude,
 * which may itself be prompt-injected, and the owner's tap on Start is the only
 * thing between it and a full session. So the card must show every character
 * the daemon will type, and nothing may be able to hide one.
 *
 * Three things can hide text in a monospace block: a run of whitespace that
 * pushes the rest past the edge, a control or bidi character that moves,
 * erases or reverses what follows, and sheer length. [visible] handles the
 * first two in a DISPLAY COPY, [counts] the third. The prompt the daemon sends
 * is untouched: the answer to the question carries no prompt at all, and the
 * card's job is only to show the one the daemon already holds.
 *
 * Plain text comes through [visible] unchanged, newlines included, so a normal
 * prompt reads as written.
 */
object PromptDisplay {
    /** Stands in for a run of three or more spaces and tabs. */
    const val GAP: String = "⋯"

    /** The display copy: hidden characters made visible, whitespace runs collapsed. */
    fun visible(prompt: String): String {
        val out = StringBuilder(prompt.length)
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 3) out.append(GAP) else out.append(run)
            run.setLength(0)
        }
        for (ch in prompt) {
            if (ch == ' ' || ch == '\t') { run.append(ch); continue }
            flush()
            if (isHidden(ch)) out.append(mark(ch)) else out.append(ch)
        }
        flush()
        return out.toString()
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

    /** Size of the prompt as SENT: lines and characters (code points), of the original, not the display copy. */
    data class Counts(val lines: Int, val characters: Int)

    fun counts(prompt: String): Counts {
        if (prompt.isEmpty()) return Counts(0, 0)
        var lines = 1
        var chars = 0
        for (ch in prompt) {
            if (ch == '\n') lines++
            if (!ch.isLowSurrogate()) chars++
        }
        // A trailing newline ends the last line rather than starting an empty one.
        if (prompt.endsWith('\n')) lines--
        return Counts(lines, chars)
    }

    /** `3 lines · 212 characters` — drawn beside Start. */
    fun countsLine(prompt: String): String {
        val c = counts(prompt)
        val l = if (c.lines == 1) "1 line" else "${c.lines} lines"
        val n = if (c.characters == 1) "1 character" else "${c.characters} characters"
        return "$l · $n"
    }
}
