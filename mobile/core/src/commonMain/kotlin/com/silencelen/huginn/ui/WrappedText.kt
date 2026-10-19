package com.silencelen.huginn.ui

/**
 * The blocks inside a message that arrive wrapped in markup and are drawn
 * folded: a long paste, and another session's message that reached the
 * transcript as raw text.
 *
 * Claude Code stores a long paste into its prompt as
 * `<pasted_content id="d842">\n…\n</pasted_content id="d842">\n`, and the
 * bubble drew the tags as the owner's own words. The id is four hex digits and
 * repeats across messages; the closer names its opener's, which is what keeps a
 * paste that quotes the tag from closing early. The daemon re-kinds peer
 * messages as notes, so a raw `<agent-message>` or `<cross-session-message>`
 * here means a transcript or daemon that did not, and it is folded too.
 *
 * Pure, so the split is tested without a renderer.
 *
 * ⚠⚠ SCANNED, NOT MATCHED WITH A REGEX, AND THE DIFFERENCE IS THE POINT. The
 * paste was `<pasted_content id="(…)">…[\s\S]*?…</pasted_content id="\1">`: for
 * every opener whose closer never comes, the lazy body ran to the end of the
 * message before the engine moved on one character, so a message of many
 * openers and no matching closer took time in the square of its length — on
 * the render path. The agent and cross-session patterns did the same with any
 * opener after the last closer. Each kind is now one pass: every closer is
 * found once, and an opener looks its closer up instead of searching for it.
 * What is matched is exactly what those patterns matched (WrappedTextTest holds
 * the scan to them case by case and on generated messages).
 */
object WrappedText {

    /** One run of a message: words the person typed, or a block drawn folded. */
    data class Segment(
        val text: String,
        /** Null for typed text; otherwise the card's label. */
        val label: String? = null,
    ) {
        val folded: Boolean get() = label != null
    }

    private const val PASTED_OPEN = "<pasted_content id=\""
    private const val PASTED_CLOSE = "</pasted_content id=\""
    private const val AGENT_OPEN = "<agent-message"
    private const val AGENT_CLOSE = "</agent-message>"
    private const val CROSS_OPEN = "<cross-session-message"
    private const val CROSS_CLOSE = "</cross-session-message>"

    /** A paste id: one to this many characters, none of them `"` or a newline. */
    private const val ID_MAX = 32

    private val FROM_NAME = Regex("""from-name="([^"]*)"""")
    private val HANDBACK_FRAME = Regex("""^\s*\[Subagent hand-back][^\n]*\n""")

    /** True when [segments] would fold something; cheap enough for a render path. */
    fun hasWrapped(text: String): Boolean =
        ("<pasted_content id=\"" in text && "</pasted_content id=\"" in text) ||
            ("<agent-message" in text && "</agent-message>" in text) ||
            ("<cross-session-message" in text && "</cross-session-message>" in text)

    /**
     * The message split into typed runs and folded blocks, in order. Typed runs
     * are trimmed and blank ones dropped; a message with nothing wrapped is one
     * typed segment.
     */
    fun segments(text: String): List<Segment> {
        if (!hasWrapped(text)) return listOf(Segment(text))
        val blocks = buildList {
            pastedBlocks(text).forEach { add(it.range to pastedSegment(it.body)) }
            taggedBlocks(text, AGENT_OPEN, AGENT_CLOSE).forEach { add(it.range to messageSegment(null, it.body)) }
            taggedBlocks(text, CROSS_OPEN, CROSS_CLOSE).forEach {
                val from = FROM_NAME.find(it.attrs)?.groupValues?.get(1)
                // A subagent's report relayed between sessions nests an
                // <agent-message> inside; the outer block is the one card.
                val inner = taggedBlocks(it.body, AGENT_OPEN, AGENT_CLOSE).firstOrNull()?.body ?: it.body
                add(it.range to messageSegment(from, inner))
            }
        }.sortedBy { it.first.first }
        val out = mutableListOf<Segment>()
        var at = 0
        for ((range, seg) in blocks) {
            // A block inside an earlier one (an agent message nested in a
            // cross-session one, a pasted tag inside a paste) is part of it.
            if (range.first < at) continue
            typed(text.substring(at, range.first))?.let(out::add)
            out += seg
            at = range.last + 1
        }
        typed(text.substring(at))?.let(out::add)
        return out
    }

    /** The message with every wrapper removed and its words kept: what a copy takes. */
    fun plain(text: String): String {
        if (!hasWrapped(text)) return text
        return segments(text).joinToString("\n\n") { it.text }
    }

    /** "Pasted text · 181 lines". */
    fun pastedLabel(body: String): String {
        val lines = body.trimEnd().lines().size
        return if (lines == 1) "Pasted text · 1 line" else "Pasted text · $lines lines"
    }

    /** The first line worth reading, for a closed card's preview. */
    fun preview(body: String, max: Int = 110): String =
        body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(max).orEmpty()

    /**
     * One wrapped block: where it sits in the message ([range], closer and any
     * trailing newline the pattern took included), its [body] with the newline
     * after the opener and before the closer left out, and the opener's [attrs].
     */
    internal class Block(val range: IntRange, val body: String, val attrs: String = "")

    /**
     * Every `<pasted_content id="X">…</pasted_content id="X">` block, in order —
     * what `<pasted_content id="([^"\n]{1,32})">\n?([\s\S]*?)\n?</pasted_content id="\1">\n?`
     * matched, in one pass.
     *
     * The body ends at the first closer naming the SAME id after it, and a
     * closer is found by looking its id up: every closer in the message is
     * indexed once, by id, before any opener is read. Openers that fail cost a
     * lookup, never a search, and a closer earlier than an opener's body is
     * dropped for good because each later opener's body starts later still.
     */
    internal fun pastedBlocks(text: String): List<Block> {
        if (PASTED_OPEN !in text) return emptyList()
        val closers = HashMap<String, ArrayDeque<Int>>()
        var c = text.indexOf(PASTED_CLOSE)
        while (c >= 0) {
            idAt(text, c + PASTED_CLOSE.length)?.let { closers.getOrPut(it) { ArrayDeque() }.addLast(c) }
            c = text.indexOf(PASTED_CLOSE, c + 1)
        }
        val out = mutableListOf<Block>()
        var from = 0
        while (true) {
            val open = text.indexOf(PASTED_OPEN, from)
            if (open < 0) break
            val idStart = open + PASTED_OPEN.length
            val id = idAt(text, idStart)
            if (id == null) { from = open + 1; continue }
            var bodyStart = idStart + id.length + 2
            if (bodyStart < text.length && text[bodyStart] == '\n') bodyStart++
            val queue = closers[id]
            while (queue != null && queue.isNotEmpty() && queue.first() < bodyStart) queue.removeFirst()
            val close = queue?.firstOrNull()
            if (close == null) { from = open + 1; continue }
            val bodyEnd = if (close - 1 >= bodyStart && text[close - 1] == '\n') close - 1 else close
            var end = close + PASTED_CLOSE.length + id.length + 2
            if (end < text.length && text[end] == '\n') end++
            out += Block(open until end, text.substring(bodyStart, bodyEnd))
            from = end
        }
        return out
    }

    /** The paste id at [at] — 1..[ID_MAX] characters, no `"` or newline, then `">` — or null. */
    private fun idAt(text: String, at: Int): String? {
        var j = at
        while (j < text.length && j - at <= ID_MAX && text[j] != '"' && text[j] != '\n') j++
        val len = j - at
        if (len < 1 || len > ID_MAX || !text.startsWith("\">", j)) return null
        return text.substring(at, j)
    }

    /**
     * Every `<name …>…</name>` block, in order — what
     * `<name\b([^>]*)>\n?([\s\S]*?)\n?</name>` matched, in one pass.
     *
     * The closer is found once and reused until an opener's body starts past
     * it, so the text after the last closer is never searched twice; and when
     * there is no closer (or no `>`) after one opener there is none after any
     * later one either, so the scan stops there.
     */
    internal fun taggedBlocks(text: String, open: String, close: String): List<Block> {
        val out = mutableListOf<Block>()
        var from = 0
        var closeAt = -2 // not looked up yet
        while (true) {
            val o = text.indexOf(open, from)
            if (o < 0) break
            val nameEnd = o + open.length
            // `\b`: the name ends here only if no word character follows.
            if (nameEnd < text.length && (text[nameEnd].isLetterOrDigit() || text[nameEnd] == '_')) {
                from = o + 1
                continue
            }
            val gt = text.indexOf('>', nameEnd)
            if (gt < 0) break
            var bodyStart = gt + 1
            if (bodyStart < text.length && text[bodyStart] == '\n') bodyStart++
            if (closeAt == -2 || closeAt < bodyStart) closeAt = text.indexOf(close, bodyStart)
            if (closeAt < 0) break
            val bodyEnd = if (closeAt - 1 >= bodyStart && text[closeAt - 1] == '\n') closeAt - 1 else closeAt
            val end = closeAt + close.length
            out += Block(o until end, text.substring(bodyStart, bodyEnd), text.substring(nameEnd, gt))
            from = end
        }
        return out
    }

    private fun typed(s: String): Segment? = s.trim().takeIf { it.isNotEmpty() }?.let { Segment(it) }

    private fun pastedSegment(body: String): Segment = Segment(body.trimEnd('\n'), pastedLabel(body))

    private fun messageSegment(from: String?, raw: String): Segment {
        // A hand-back's frame line is for the model; the report is indented two
        // spaces under it, which would turn a four-space list into a code block.
        val frame = HANDBACK_FRAME.find(raw)
        val body = if (frame == null) raw.trim()
        else raw.substring(frame.range.last + 1).lines().joinToString("\n") { it.removePrefix("  ") }.trim()
        val who = from?.trim()?.takeIf { it.isNotEmpty() } ?: "a subagent"
        return Segment(body, "Message from $who")
    }
}
