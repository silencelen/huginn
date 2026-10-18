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

    private val PASTED = Regex(
        """<pasted_content id="([^"\n]{1,32})">\n?([\s\S]*?)\n?</pasted_content id="\1">\n?""",
    )
    private val AGENT = Regex("""<agent-message\b[^>]*>\n?([\s\S]*?)\n?</agent-message>""")
    private val CROSS = Regex("""<cross-session-message\b([^>]*)>\n?([\s\S]*?)\n?</cross-session-message>""")
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
            PASTED.findAll(text).forEach { add(it.range to pastedSegment(it.groupValues[2])) }
            AGENT.findAll(text).forEach { add(it.range to messageSegment(null, it.groupValues[1])) }
            CROSS.findAll(text).forEach {
                val from = FROM_NAME.find(it.groupValues[1])?.groupValues?.get(1)
                // A subagent's report relayed between sessions nests an
                // <agent-message> inside; the outer block is the one card.
                val inner = AGENT.find(it.groupValues[2])?.groupValues?.get(1) ?: it.groupValues[2]
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
