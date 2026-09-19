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
    data class Code(val code: String, val lang: String?) : MdBlock
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
                    val lang = fence.groupValues[2].takeIf { it.isNotBlank() }
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && FENCE.matchEntire(lines[i].trim()) == null) {
                        body.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // closing fence (or end of input, which we accept)
                    out.add(MdBlock.Code(body.toString().trimEnd('\n'), lang))
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

    private val FENCE = Regex("^(`{3,}|~{3,})\\s*([A-Za-z0-9+#._-]*)\\s*$")
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
     * and `**MemPalace on muninn upgraded 3.7.0 → 3…`: the asterisks are the
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
     * Scanned in one pass so a marker inside a code span is left alone.
     *
     * A `[label](url)` becomes a REAL link annotation carrying the URL, and the
     * rendered text is the label alone — the appended `" (url)"` this used to
     * write is gone (decision 44). A bare `https://…` in prose is auto-linked in
     * place. Both are subject to [isLinkable]; a refused URL leaves the label as
     * ordinary text rather than hiding it.
     */
    fun inline(src: String, linkStyles: TextLinkStyles? = null): AnnotatedString = buildAnnotatedString {
        val styles = linkStyles ?: PLAIN_LINK_STYLES
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                            append(src.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(c); i++ }
                }
                c == '*' && src.startsWith("**", i) -> {
                    val end = src.indexOf("**", i + 2)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(src.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(c); i++ }
                }
                (c == '*' || c == '_') -> {
                    val end = src.indexOf(c, i + 1)
                    // A lone underscore inside a word (snake_case) is not emphasis.
                    val wordInternal = c == '_' && i > 0 && src[i - 1].isLetterOrDigit()
                    if (end > i + 1 && !wordInternal) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(src.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(c); i++ }
                }
                c == '~' && src.startsWith("~~", i) -> {
                    val end = src.indexOf("~~", i + 2)
                    if (end > i + 1) {
                        withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                            append(src.substring(i + 2, end))
                        }
                        i = end + 2
                    } else { append(c); i++ }
                }
                // `![alt](src)` inside a paragraph: left exactly as written. The
                // block form is handled by [parse]; this branch exists so the `[`
                // branch below cannot turn half an image into a link.
                c == '!' && i + 1 < src.length && src[i + 1] == '[' -> {
                    val span = imageSpan(src, i)
                    if (span > i) { append(src.substring(i, span)); i = span } else { append(c); i++ }
                }
                c == '[' -> {
                    val close = src.indexOf(']', i)
                    if (close > i && close + 1 < src.length && src[close + 1] == '(') {
                        val paren = destEnd(src, close + 1)
                        if (paren > close) {
                            val label = src.substring(i + 1, close)
                            val url = src.substring(close + 2, paren).trim()
                            // The label carries the meaning; the URL is the click
                            // target and the hover/long-press reveal, not prose.
                            if (isLinkable(url)) {
                                withLink(LinkAnnotation.Url(url, styles = styles)) { append(label) }
                            } else {
                                append(label)
                            }
                            i = paren + 1
                        } else { append(c); i++ }
                    } else { append(c); i++ }
                }
                // A bare URL in prose. The boundary rules are the whole trick:
                // a sentence's full stop is not part of the address, and a URL
                // written inside a parenthetical does not own the closing paren —
                // but a URL that carries its own balanced parens does.
                (c == 'h' || c == 'H') && startsUrl(src, i) -> {
                    val end = urlEnd(src, i)
                    val url = src.substring(i, end)
                    if (isLinkable(url)) {
                        withLink(LinkAnnotation.Url(url, styles = styles)) { append(url) }
                    } else {
                        append(url)
                    }
                    i = end
                }
                else -> { append(c); i++ }
            }
        }
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
