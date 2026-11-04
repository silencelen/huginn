package com.silencelen.huginn.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Just enough markdown for what Claude actually writes in an answer, split into
 * blocks so a code fence can render as a real scrollable code card instead of
 * being flattened into prose (which is what v1 did, and it made any answer
 * containing a command unreadable on a phone).
 *
 * Deliberately not a full CommonMark implementation: no nested lists, no
 * reference links. Unsupported syntax degrades to its literal text rather than
 * disappearing, which is the safe failure for a reader.
 *
 * GFM PIPE TABLES ARE IN (3.5.1). "No tables" was a deliberate line here until
 * the walk on the owner's Fold put the two tabs side by side: the Screen tab drew
 * an answer's table as a box table — that is Claude Code's own terminal doing it —
 * while the Conversation tab drew forty lines of raw `| # | Item |` pipes. The
 * same answer, and the app's own rendering was the worse of the two.
 */
sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class Heading(val text: AnnotatedString, val level: Int) : MdBlock
    data class Bullet(val text: AnnotatedString, val ordinal: String?) : MdBlock
    /**
     * [lang] is the first word after the fence; [info] is the rest of the line,
     * when there is one — a lead's proposal opens "```huginn-project <tag>" and
     * the tag is what the daemon keys the block on.
     */
    data class Code(val code: String, val lang: String?, val info: String? = null) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    /**
     * `![alt](src)` standing alone on a line. `src` is whatever was written —
     * a host path, an uploads path, or a URL — because this parser has no way to
     * resolve one and the renderer is the thing that knows which of those it can
     * fetch. A src it cannot draw falls back to [alt] as ordinary prose.
     */
    data class Image(val src: String, val alt: String) : MdBlock
    /**
     * A GFM pipe table. [rows] is RECTANGULAR — a short row is padded with empty
     * cells by the parser, because a grid cannot draw a hole and the columns of
     * every row below a ragged one would otherwise stop lining up.
     *
     * [header] says whether `rows[0]` is a heading rather than data; it is true
     * only when the source carried a `|---|---|` delimiter under it. The
     * delimiter row itself is never a row.
     */
    data class Table(val rows: List<List<AnnotatedString>>, val header: Boolean) : MdBlock
    data object Rule : MdBlock
}

object Markdown {

    /**
     * @param linkStyles how a link is painted. A parameter rather than a constant
     * because the colour is the theme's — `:core` has no theme — and null keeps
     * the old signature working for every caller that renders text with no
     * colours at all ([plainInline] and the chats-list snippets).
     */
    fun parse(src: String, linkStyles: TextLinkStyles? = null): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = src.replace("\r\n", "\n").split("\n")
        var i = 0
        val para = StringBuilder()

        fun inline(s: String) = inline(s, linkStyles)

        fun flushPara() {
            if (para.isNotBlank()) out.add(MdBlock.Paragraph(inline(para.toString().trim())))
            para.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line.trim())
            when {
                fence != null -> {
                    flushPara()
                    val open = fence.groupValues[1]
                    val lang = fence.groupValues[2].takeIf { it.isNotBlank() }
                    val info = fence.groupValues[3].trim().takeIf { it.isNotEmpty() }
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !closes(lines[i].trim(), open)) {
                        body.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // closing fence (or end of input, which we accept)
                    out.add(MdBlock.Code(body.toString().trimEnd('\n'), lang, info))
                    continue
                }
                line.isBlank() -> { flushPara(); i++ }
                RULE.matches(line.trim()) -> { flushPara(); out.add(MdBlock.Rule); i++ }
                // An image is a BLOCK only when it is the whole line. One in the
                // middle of a sentence has nowhere to draw — a paragraph is a
                // single text flow — so [inline] leaves that one as it was
                // written, which is the same degrade-to-literal rule as the rest
                // of this parser.
                IMAGE_LINE.matches(line.trim()) -> {
                    flushPara()
                    val m = IMAGE_LINE.find(line.trim())!!
                    out.add(MdBlock.Image(m.groupValues[2].trim(), m.groupValues[1].trim()))
                    i++
                }
                // Before HEADING and BULLET, neither of which a `|` line can
                // match, and after the fence so a table inside a code block stays
                // code. A run that turns out not to be a table falls back into the
                // paragraph as the literal text it was written as.
                isTableRow(line) -> {
                    val raw = mutableListOf<String>()
                    while (i < lines.size && isTableRow(lines[i])) { raw.add(lines[i]); i++ }
                    val table = tableBlock(raw) { inline(it) }
                    if (table != null) { flushPara(); out.add(table) }
                    else raw.forEach { para.append(it).append('\n') }
                }
                HEADING.matches(line) -> {
                    flushPara()
                    val m = HEADING.find(line)!!
                    out.add(MdBlock.Heading(inline(m.groupValues[2].trim()), m.groupValues[1].length))
                    i++
                }
                QUOTE.matches(line) -> {
                    flushPara()
                    out.add(MdBlock.Quote(inline(QUOTE.find(line)!!.groupValues[1].trim())))
                    i++
                }
                BULLET.matches(line) -> {
                    flushPara()
                    val m = BULLET.find(line)!!
                    // Continuation lines of the same item are indented; fold them in
                    // so a wrapped bullet stays one bullet.
                    val text = StringBuilder(m.groupValues[3])
                    i++
                    while (i < lines.size && CONT.matches(lines[i]) && BULLET.find(lines[i]) == null) {
                        text.append(' ').append(lines[i].trim()); i++
                    }
                    val marker = m.groupValues[2]
                    out.add(MdBlock.Bullet(inline(text.toString().trim()), if (marker.firstOrNull()?.isDigit() == true) marker else null))
                }
                else -> { para.append(line).append('\n'); i++ }
            }
        }
        flushPara()
        return out
    }

    /**
     * An opening fence: the bar, a language word, and whatever else the line
     * says. ⚠ THE REST OF THE LINE IS ALLOWED. This grammar knew one word, so
     * "```huginn-project 34f88e7484" — a lead's proposal, tagged the way the
     * daemon requires — was not a fence at all and the whole block landed in
     * the transcript as a paragraph of JSON, which the owner read as a call the
     * lead had made and that had failed.
     */
    private val FENCE = Regex("^(`{3,}|~{3,})\\s*([A-Za-z0-9+#._-]*)(?:\\s+(\\S.*?))?\\s*$")
    /** A closing fence carries no words (CommonMark): the same bar, at least as long. */
    private val CLOSE = Regex("^(`{3,}|~{3,})\\s*$")
    private fun closes(line: String, open: String): Boolean {
        val bar = CLOSE.matchEntire(line)?.groupValues?.get(1) ?: return false
        return bar[0] == open[0] && bar.length >= open.length
    }
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s{0,3})([-*+]|\\d{1,2}[.)])\\s+(.*)$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val CONT = Regex("^\\s{2,}\\S.*$")
    private val IMAGE_LINE = Regex("^!\\[([^\\]]*)\\]\\(([^()]+)\\)$")

    // ------------------------------------------------------------ pipe tables

    /** A delimiter cell: `---`, `:--`, `--:` or `:-:`. */
    private val TABLE_DELIM = Regex("^:?-+:?$")

    /**
     * Whether [line] could be a row of a pipe table.
     *
     * The bar must OPEN the line, and there must be a second one: "pipe it | into
     * grep" is a sentence, and a parser that turned it into a one-cell grid would
     * be a worse bug than the one this feature fixes. A `\|` does not count —
     * it is a pipe a cell contains, not a cell boundary.
     */
    private fun isTableRow(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && unescapedPipes(t) >= 2
    }

    private fun unescapedPipes(t: String): Int {
        var n = 0
        var i = 0
        while (i < t.length) {
            if (t[i] == '\\' && i + 1 < t.length) { i += 2; continue }
            if (t[i] == '|') n++
            i++
        }
        return n
    }

    /** One row's cells, trimmed, with each `\|` unescaped to the pipe it stands for. */
    private fun tableCells(line: String): List<String> {
        val t = line.trim()
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var i = if (t.startsWith("|")) 1 else 0
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' && i + 1 < t.length && t[i + 1] == '|' -> { cur.append('|'); i += 2 }
                c == '|' -> { cells.add(cur.toString().trim()); cur.setLength(0); i++ }
                else -> { cur.append(c); i++ }
            }
        }
        // A row written without its closing bar still ends in a cell.
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) cells.add(tail)
        return cells
    }

    private fun isDelimiterRow(cells: List<String>): Boolean =
        cells.isNotEmpty() && cells.all { TABLE_DELIM.matches(it) }

    /**
     * A run of bar-delimited lines as a table, or null when it is only prose.
     *
     * TWO lines, or one line and a delimiter. A single `| like this |` on its own
     * is a sentence somebody wrapped in bars, and drawing it as a one-row grid is
     * the false positive that would make this feature unwelcome.
     */
    private fun tableBlock(raw: List<String>, inline: (String) -> AnnotatedString): MdBlock.Table? {
        val rows = raw.map { tableCells(it) }
        val headed = rows.size >= 2 && isDelimiterRow(rows[1])
        if (!headed && rows.size < 2) return null
        val body = if (headed) listOf(rows[0]) + rows.drop(2) else rows
        if (body.isEmpty()) return null
        val width = body.maxOf { it.size }
        return MdBlock.Table(
            rows = body.map { r -> List(width) { c -> inline(r.getOrElse(c) { "" }) } },
            header = headed,
        )
    }

    /**
     * Every table in [src] replaced by its first row, cells joined by " · ".
     *
     * For [plainInline] alone. A chats row is one line in one style: the pipes and
     * the `|---|` rule are instructions to a renderer that is not running there,
     * and the first row is the label the rest of the table hangs off.
     */
    private fun flattenTables(src: String): String {
        if (!src.contains('|')) return src
        val lines = src.split("\n")
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            if (!isTableRow(lines[i])) { out.add(lines[i]); i++; continue }
            val raw = mutableListOf<String>()
            while (i < lines.size && isTableRow(lines[i])) { raw.add(lines[i]); i++ }
            val rows = raw.map { tableCells(it) }
            val headed = rows.size >= 2 && isDelimiterRow(rows[1])
            if (headed || rows.size >= 2) out.add(rows[0].joinToString(" · "))
            else out.addAll(raw)
        }
        return out.joinToString("\n")
    }

    /**
     * The schemes a label is allowed to become a clickable link for.
     *
     * ⚠ THE SECURITY LINE OF THIS FILE, and the reason it is an allowlist rather
     * than a denylist. This text comes from a model, and both shells hand a link
     * click to the operating system. `huginn://` is the desktop's own scheme —
     * fingerprint-gated precisely because it is reachable from outside — and a
     * clickable one in an answer would walk straight through that gate. `file:`
     * reads the disk, `javascript:` is self-explanatory, and `mailto:` opens a
     * composer nobody asked for. Everything that is not http(s) renders as the
     * label it was written with and is simply not a link.
     */
    private val LINK_SCHEMES = setOf("http", "https")

    /** Whether [url] may become a link span. See [LINK_SCHEMES]. */
    fun isLinkable(url: String): Boolean {
        val t = url.trim()
        val colon = t.indexOf(':')
        if (colon <= 0) return false
        val scheme = t.substring(0, colon).lowercase()
        if (scheme !in LINK_SCHEMES) return false
        return t.length > colon + 3 && t.regionMatches(colon, "://", 0, 3)
    }

    /** What a link looks like when the caller named no colours: underlined, as it always was. */
    private val PLAIN_LINK_STYLES = TextLinkStyles(
        style = SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
    )

    /** `<` `>` `"` and a backtick end a URL; so does any whitespace. */
    private const val URL_STOP = "<>\"`"

    /** Trailing characters that belong to the sentence, not to the URL. */
    private const val URL_TRAILING = ".,;:!?'"

    /**
     * The same inline markdown as [inline], with the markers REMOVED and nothing
     * styled — for the places that render a one-line preview as plain text.
     *
     * A chats row showed `**Creative is back online at 15:05.** Both players…`
     * and `**MemPalace on memhost upgraded 3.7.0 → 3…`: the asterisks are the
     * FIRST characters of the snippet, so the one line a reader scans a list by
     * opened with punctuation that meant nothing there. A one-line row cannot
     * carry a bold span — it is drawn with a single style — so the only honest
     * options are to show the markers or to take them off, and the markers are
     * an instruction to a renderer that is not running.
     *
     * Reuses [inline] rather than growing a second scanner: two parsers of the
     * same syntax disagree eventually, and the one a reader would notice is the
     * one that leaves a stray `**` behind. Whatever [inline] treats as a marker
     * is what this drops, by construction.
     *
     * Block syntax is NOT touched — a leading `#` or `-` is one character and
     * reads as the punctuation it is, while a lost `**` pair reads as an error.
     * Newlines become spaces, because the caller wanted one line.
     *
     * THE ONE EXCEPTION IS A TABLE, which is not punctuation but a whole grid:
     * left alone it filled the row with `| # | Item | What I need | |---|---|---|`.
     * It flattens to its first row, cells joined by " · " — see [flattenTables].
     */
    fun plainInline(src: String): String =
        inline(flattenTables(src.replace("\r\n", "\n"))).text.replace('\n', ' ').trim()

    /**
     * Inline spans: `code`, **bold**, *italic*, ~~strike~~ and [text](url).
     *
     * A `[label](url)` becomes a REAL link annotation carrying the URL, and the
     * rendered text is the label alone — the appended `" (url)"` this used to
     * write is gone (decision 44). A bare `https://…` in prose is auto-linked in
     * place. Both are subject to [isLinkable]; a refused URL leaves the label as
     * ordinary text rather than hiding it.
     *
     * ⚠⚠ EMPHASIS IS COMMONMARK'S DELIMITER RUNS, NOT "FIND THE NEXT MARKER"
     * (2026-11-03). The old one-pass scanner had two bugs both apps showed:
     *
     *  - `mcp__mempalace__mempalace_kg_timeline` in the /context table rendered
     *    as "mcp_*mempalace*mempalacekg_timeline". Only an opener was checked for
     *    a letter before it, so the second `_` of every `__` opened emphasis.
     *  - `**\`hi.png\`**` rendered bold WITH its backticks: a strong span's text
     *    was appended raw, so nothing nested inside one was parsed.
     *
     * So: code spans, links and autolinks are cut out first, as atoms nothing can
     * reach inside; every run of `*`, `_` or `~~` is then judged by what is on
     * either side of it (left- and right-flanking), a `_` run between two
     * alphanumerics can neither open nor close, and runs are paired innermost
     * first, the nearest opener for each closer — which is what lets a code span,
     * a link or another emphasis sit inside a strong span. An unmatched run is
     * shown as written.
     */
    fun inline(src: String, linkStyles: TextLinkStyles? = null): AnnotatedString = buildAnnotatedString {
        appendInline(src, linkStyles ?: PLAIN_LINK_STYLES, links = true)
    }

    /** One piece of a line, before emphasis is paired. */
    private sealed interface Tok
    private class Plain(val text: String) : Tok
    private class Code(val text: String) : Tok

    /** `[label](url)`: [url] null when the scheme is refused — the label is then plain words. */
    private class LinkTok(val label: String, val url: String?) : Tok
    private class AutoLink(val url: String) : Tok

    /**
     * A run of `*`, `_` or `~~`. [left] is what pairing has not used yet; what
     * it used as a CLOSER came off its left side and what it used as an OPENER
     * off its right, so what is left is drawn between the two.
     */
    private class Delim(val ch: Char, val len: Int, val canOpen: Boolean, val canClose: Boolean) : Tok {
        var left = len
        var closeAt = 0
        var openAt = 0
    }

    private class Match(val opener: Delim, val closer: Delim, val use: Int)

    private fun AnnotatedString.Builder.appendInline(src: String, styles: TextLinkStyles, links: Boolean) {
        val toks = tokenize(src, links)
        val matches = pairDelimiters(toks)
        for (t in toks) when (t) {
            is Plain -> append(t.text)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(t.text) }
            // A link's words are inline markdown too — `[**docs**](…)` is a bold
            // link — but never a second link: `links = false` inside one.
            is LinkTok -> if (t.url != null) {
                withLink(LinkAnnotation.Url(t.url, styles = styles)) { appendInline(t.label, styles, links = false) }
            } else {
                appendInline(t.label, styles, links = false)
            }
            is AutoLink -> if (isLinkable(t.url)) {
                withLink(LinkAnnotation.Url(t.url, styles = styles)) { append(t.url) }
            } else {
                append(t.url)
            }
            is Delim -> {
                t.closeAt = length
                repeat(t.left) { append(t.ch) }
                t.openAt = length
            }
        }
        for (m in matches) {
            val style = when {
                m.opener.ch == '~' -> SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                m.use == 2 -> SpanStyle(fontWeight = FontWeight.Bold)
                else -> SpanStyle(fontStyle = FontStyle.Italic)
            }
            if (m.closer.closeAt > m.opener.openAt) addStyle(style, m.opener.openAt, m.closer.closeAt)
        }
    }

    /**
     * The line as atoms and delimiter runs. Linear: each character is looked at
     * once, and a code span's search for its closing run gives up for good at
     * the first run length it has failed to find (a line of unmatched backticks
     * must not search the rest of the line from every one of them).
     */
    private fun tokenize(src: String, links: Boolean): List<Tok> {
        val out = ArrayList<Tok>()
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { out.add(Plain(text.toString())); text.setLength(0) } }
        val noCloserFrom = HashMap<Int, Int>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '`' -> {
                    val n = runLength(src, i)
                    val end = codeSpanEnd(src, i + n, n, noCloserFrom)
                    if (end < 0) { text.append(src.substring(i, i + n)); i += n; continue }
                    flush()
                    out.add(Code(codeContent(src.substring(i + n, end))))
                    i = end + n
                }
                c == '*' || c == '_' || c == '~' -> {
                    val n = runLength(src, i)
                    // ~~ only: a single `~` is "about" (`~5 min`) far more often
                    // than it is strikethrough, and longer runs are fences' business.
                    if (c == '~' && n != 2) { text.append(src.substring(i, i + n)); i += n; continue }
                    val prev = if (i == 0) null else src[i - 1]
                    val next = if (i + n >= src.length) null else src[i + n]
                    val leftFlanking = next != null && !next.isWhitespace() &&
                        (!isPunct(next) || prev == null || prev.isWhitespace() || isPunct(prev))
                    val rightFlanking = prev != null && !prev.isWhitespace() &&
                        (!isPunct(prev) || next == null || next.isWhitespace() || isPunct(next))
                    // The underscore rule: `_` between two alphanumerics (snake_case,
                    // mcp__server__tool) is a character, not a marker.
                    val canOpen = if (c == '_') leftFlanking && (!rightFlanking || (prev != null && isPunct(prev))) else leftFlanking
                    val canClose = if (c == '_') rightFlanking && (!leftFlanking || (next != null && isPunct(next))) else rightFlanking
                    flush()
                    out.add(Delim(c, n, canOpen, canClose))
                    i += n
                }
                // `![alt](src)` inside a paragraph: left exactly as written. The
                // block form is handled by [parse]; this branch exists so the `[`
                // branch below cannot turn half an image into a link.
                c == '!' && i + 1 < src.length && src[i + 1] == '[' -> {
                    val span = imageSpan(src, i)
                    if (span > i) { text.append(src.substring(i, span)); i = span } else { text.append(c); i++ }
                }
                links && c == '[' -> {
                    val close = src.indexOf(']', i)
                    val paren = if (close > i && close + 1 < src.length && src[close + 1] == '(') destEnd(src, close + 1) else -1
                    if (paren > close) {
                        val url = src.substring(close + 2, paren).trim()
                        flush()
                        // The label carries the meaning; the URL is the click
                        // target and the hover/long-press reveal, not prose.
                        out.add(LinkTok(src.substring(i + 1, close), url.takeIf { isLinkable(it) }))
                        i = paren + 1
                    } else { text.append(c); i++ }
                }
                // A bare URL in prose. The boundary rules are the whole trick:
                // a sentence's full stop is not part of the address, and a URL
                // written inside a parenthetical does not own the closing paren —
                // but a URL that carries its own balanced parens does.
                links && (c == 'h' || c == 'H') && startsUrl(src, i) -> {
                    val end = urlEnd(src, i)
                    flush()
                    out.add(AutoLink(src.substring(i, end)))
                    i = end
                }
                else -> { text.append(c); i++ }
            }
        }
        flush()
        return out
    }

    private fun runLength(src: String, at: Int): Int {
        var j = at
        while (j < src.length && src[j] == src[at]) j++
        return j - at
    }

    /** Where the code span opened by a run of [n] backticks ends: the next run of exactly [n], or -1. */
    private fun codeSpanEnd(src: String, from: Int, n: Int, noCloserFrom: HashMap<Int, Int>): Int {
        if (from >= (noCloserFrom[n] ?: Int.MAX_VALUE)) return -1
        var j = src.indexOf('`', from)
        while (j >= 0) {
            val m = runLength(src, j)
            if (m == n) return j
            j = src.indexOf('`', j + m)
        }
        noCloserFrom[n] = minOf(from, noCloserFrom[n] ?: Int.MAX_VALUE)
        return -1
    }

    /** CommonMark: line breaks read as spaces, and one space each side comes off when both are there. */
    private fun codeContent(raw: String): String {
        val s = raw.replace('\n', ' ')
        return if (s.length >= 2 && s.first() == ' ' && s.last() == ' ' && s.isNotBlank()) s.substring(1, s.length - 1) else s
    }

    /** Unicode punctuation and symbols — CommonMark's "punctuation character". */
    private fun isPunct(c: Char): Boolean = when (c.category) {
        CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION, CharCategory.MATH_SYMBOL, CharCategory.CURRENCY_SYMBOL,
        CharCategory.MODIFIER_SYMBOL, CharCategory.OTHER_SYMBOL -> true
        else -> false
    }

    /**
     * CommonMark's "process emphasis": each closer, left to right, takes the
     * NEAREST opener of its kind; strong when both sides have two to give;
     * whatever lay between them can no longer pair with anything outside.
     *
     * Linear on the render path, which re-parses a streaming answer on every
     * delta: the delimiter stack is linked by index, so taking a run out of it
     * costs nothing, and `openersBottom` remembers, per kind of closer, how far
     * down a search has already failed — a line of a thousand unmatched `*`
     * does not search the line a thousand times.
     */
    private fun pairDelimiters(toks: List<Tok>): List<Match> {
        val d = toks.filterIsInstance<Delim>()
        if (d.size < 2) return emptyList()
        val end = d.size
        val prev = IntArray(end) { it - 1 }
        val next = IntArray(end) { it + 1 }
        fun unlink(k: Int) {
            val p = prev[k]
            val n = next[k]
            if (p >= 0) next[p] = n
            if (n < end) prev[n] = p
        }
        val matches = ArrayList<Match>()
        val openersBottom = HashMap<Triple<Char, Boolean, Int>, Int>()
        var c = 0
        while (c < end) {
            val closer = d[c]
            if (!closer.canClose || closer.left == 0) { c = next[c]; continue }
            val key = Triple(closer.ch, closer.canOpen, closer.len % 3)
            val floor = openersBottom[key] ?: -1
            var o = prev[c]
            var found = -1
            while (o > floor) {
                val op = d[o]
                if (op.ch == closer.ch && op.canOpen && op.left > 0 && !ruleOfThree(op, closer)) { found = o; break }
                o = prev[o]
            }
            if (found < 0) {
                openersBottom[key] = prev[c]
                val after = next[c]
                // A closer that cannot open is spent; one that can stays, for a later closer.
                if (!closer.canOpen) unlink(c)
                c = after
                continue
            }
            val opener = d[found]
            val use = if (opener.left >= 2 && closer.left >= 2) 2 else 1
            opener.left -= use
            closer.left -= use
            matches.add(Match(opener, closer, use))
            var k = next[found]
            while (k != c) { val n = next[k]; unlink(k); k = n }
            if (opener.left == 0) unlink(found)
            if (closer.left == 0) { val after = next[c]; unlink(c); c = after }
        }
        return matches
    }

    /**
     * CommonMark's "rule of 3": when either run could both open and close, the
     * two lengths may not sum to a multiple of 3 unless both are — which is what
     * keeps `*foo**bar*` one emphasis rather than three broken ones. `~~` only
     * ever pairs with `~~`.
     */
    private fun ruleOfThree(o: Delim, c: Delim): Boolean {
        if (o.ch == '~') return o.left < 2 || c.left < 2
        if (!(o.canClose || c.canOpen)) return false
        return (o.len + c.len) % 3 == 0 && !(o.len % 3 == 0 && c.len % 3 == 0)
    }

    /** End index (exclusive) of a complete `![alt](src)` at [at], or [at] if there is not one. */
    private fun imageSpan(src: String, at: Int): Int {
        val close = src.indexOf(']', at + 1)
        if (close < at + 2) return at
        if (close + 1 >= src.length || src[close + 1] != '(') return at
        val paren = destEnd(src, close + 1)
        return if (paren > close) paren + 1 else at
    }

    /**
     * Index of the `)` closing the destination that opens at [from], or -1.
     *
     * Counts depth rather than taking the first `)`: `[x](javascript:alert(1))`
     * otherwise ends one character early, and the leftover `)` lands in the
     * rendered prose — which is exactly how a refused URL still managed to look
     * like a rendering bug.
     */
    private fun destEnd(src: String, from: Int): Int {
        var depth = 0
        var k = from
        while (k < src.length) {
            when (src[k]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return k }
                '\n' -> return -1
            }
            k++
        }
        return -1
    }

    private fun startsUrl(src: String, at: Int): Boolean {
        val isUrl = src.startsWith("http://", at, ignoreCase = true) ||
            src.startsWith("https://", at, ignoreCase = true)
        if (!isUrl) return false
        // Not mid-word: `foohttps://x` is not an address, it is a typo.
        val prev = if (at == 0) null else src[at - 1]
        return prev == null || !(prev.isLetterOrDigit() || prev == '/' || prev == '@')
    }

    private fun urlEnd(src: String, at: Int): Int {
        var j = at
        while (j < src.length && !src[j].isWhitespace() && src[j] !in URL_STOP) j++
        while (j > at) {
            val ch = src[j - 1]
            if (ch in URL_TRAILING) { j--; continue }
            if (ch == ')' || ch == ']') {
                val open = if (ch == ')') '(' else '['
                val seg = src.substring(at, j)
                if (seg.count { it == ch } > seg.count { it == open }) { j--; continue }
            }
            break
        }
        return j
    }
}
