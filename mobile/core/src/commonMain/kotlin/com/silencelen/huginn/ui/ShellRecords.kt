package com.silencelen.huginn.ui

/**
 * Claude Code's shell-mode (`!`) records, read back as what they are: a command
 * the person ran and what it printed.
 *
 * ⚠⚠ BOTH APPS DREW THEM AS RAW TAGS (2026-11-03). After a `!pwd` the
 * conversation showed two bubbles the owner appeared to have typed:
 * `<bash-input>pwd</bash-input>` and
 * `<bash-stdout>/root/projects/x</bash-stdout><bash-stderr></bash-stderr>`.
 * Claude Code writes a bash-mode command as TWO ordinary `user` records, and the
 * daemon passes them on as `user` events with that text, so nothing upstream of
 * the row could tell them from a message. They are recognised here, once, for
 * every surface that draws a transcript row.
 *
 * The shapes, read from the 2.1.316 binary and from 96 records written by
 * 2.1.280 – 2.1.316 on one host:
 *
 *  - The input is `<bash-input>CMD</bash-input>`, the command exactly as typed
 *    after the `!`, NOT escaped: a person who typed `! cmd` leaves the space
 *    (88 of the 96), the daemon's own `shell: true` send does not.
 *  - The output is `<bash-stdout>OUT</bash-stdout><bash-stderr>ERR</bash-stderr>`
 *    with `&`, `<` and `>` escaped and nothing else (785 `&gt;`, quotes literal).
 *    Claude Code's own reader ends stdout at the LAST `</bash-stdout>`, so output
 *    that prints the tag cannot end it early; so does this.
 *  - A command that could not start writes `<bash-stderr>…</bash-stderr>` alone.
 *  - Output too long to keep inline is wrapped in `<persisted-output>…`.
 *  - `(Bash completed with no output)` is the Bash tool's word for nothing
 *    (35 of the 96, still written by 2.1.316).
 *
 * Only a record that is the WHOLE text counts. A message that mentions the tag
 * between other words is somebody's message.
 */
object ShellRecords {

    sealed interface Record

    /** The command, with the `!` and the space a person typed after it removed. */
    data class Input(val command: String) : Record

    /** What the command printed, unescaped and with terminal noise removed. */
    data class Output(val stdout: String, val stderr: String) : Record {
        /** Nothing on either stream: the row says "no output" instead of drawing an empty block. */
        val empty: Boolean get() = stdout.isEmpty() && stderr.isEmpty()
    }

    /** The record [text] is, or null when it is not a shell-mode record. */
    fun parse(text: String?): Record? {
        val t = text?.trim() ?: return null
        if (!t.startsWith("<bash-")) return null
        if (t.startsWith(INPUT_OPEN) && t.endsWith(INPUT_CLOSE) && t.length >= INPUT_OPEN.length + INPUT_CLOSE.length) {
            return Input(t.substring(INPUT_OPEN.length, t.length - INPUT_CLOSE.length).trim())
        }
        if (t.startsWith(STDOUT_OPEN)) {
            val close = t.lastIndexOf(STDOUT_CLOSE)
            if (close < STDOUT_OPEN.length) return null
            val rest = t.substring(close + STDOUT_CLOSE.length).trim()
            val err = when {
                rest.isEmpty() -> ""
                rest.startsWith(STDERR_OPEN) && rest.endsWith(STDERR_CLOSE) &&
                    rest.length >= STDERR_OPEN.length + STDERR_CLOSE.length ->
                    rest.substring(STDERR_OPEN.length, rest.length - STDERR_CLOSE.length)
                else -> return null
            }
            val out = clean(unwrapPersisted(t.substring(STDOUT_OPEN.length, close)))
            return Output(if (out == NO_OUTPUT) "" else out, clean(err))
        }
        if (t.startsWith(STDERR_OPEN) && t.endsWith(STDERR_CLOSE) && t.length >= STDERR_OPEN.length + STDERR_CLOSE.length) {
            return Output("", clean(t.substring(STDERR_OPEN.length, t.length - STDERR_CLOSE.length)))
        }
        return null
    }

    /** What a long-press quotes: the command alone, or what was printed (stdout, then stderr). */
    fun plain(record: Record): String = when (record) {
        is Input -> record.command
        is Output -> listOf(record.stdout, record.stderr).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** A block of output as drawn closed: its first [maxLines] lines, and how many wait behind it. */
    data class Folded(val shown: String, val hidden: Int)

    /**
     * [text] folded to [maxLines]. A command's output can be a whole install log
     * — Claude Code keeps up to 30,000 characters of it inline — and a row that
     * long buries the conversation it sits in. Nothing hidden when it fits.
     */
    fun fold(text: String, maxLines: Int): Folded {
        if (maxLines <= 0 || text.isEmpty()) return Folded(text, 0)
        var at = 0
        repeat(maxLines) {
            val nl = text.indexOf('\n', at)
            if (nl < 0) return Folded(text, 0)
            at = nl + 1
        }
        if (at >= text.length) return Folded(text, 0)
        val hidden = text.substring(at).count { it == '\n' } + 1
        return Folded(text.substring(0, at - 1), hidden)
    }

    private const val INPUT_OPEN = "<bash-input>"
    private const val INPUT_CLOSE = "</bash-input>"
    private const val STDOUT_OPEN = "<bash-stdout>"
    private const val STDOUT_CLOSE = "</bash-stdout>"
    private const val STDERR_OPEN = "<bash-stderr>"
    private const val STDERR_CLOSE = "</bash-stderr>"
    private const val PERSISTED_OPEN = "<persisted-output>"
    private const val PERSISTED_CLOSE = "</persisted-output>"
    private const val NO_OUTPUT = "(Bash completed with no output)"

    /** Claude Code's own reading: the opener off the front, the LAST closer out. */
    private fun unwrapPersisted(s: String): String {
        val lead = s.trimStart('\n')
        if (!lead.startsWith(PERSISTED_OPEN)) return s
        val body = lead.substring(PERSISTED_OPEN.length)
        val close = body.lastIndexOf(PERSISTED_CLOSE)
        return if (close < 0) body else body.substring(0, close) + body.substring(close + PERSISTED_CLOSE.length)
    }

    private val ENTITY = Regex("&(amp|lt|gt);")
    private val CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

    /**
     * The three entities Claude Code writes, in ONE pass — `&amp;lt;` is the
     * output `&lt;`, not `<` — then what a terminal would have shown: CRLF to
     * LF, a carriage return keeping only what was drawn after it (a progress
     * bar's last frame), colour codes off. Leading blank lines and trailing
     * whitespace go; indentation stays.
     */
    private fun clean(s: String): String {
        val text = ENTITY.replace(s) {
            when (it.groupValues[1]) { "amp" -> "&"; "lt" -> "<"; else -> ">" }
        }
        val lines = CSI.replace(text, "").replace("\r\n", "\n").split('\n')
            .map { line -> line.substringAfterLast('\r') }
        return lines.joinToString("\n").trimStart('\n').trimEnd()
    }
}
