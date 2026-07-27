package com.silencelen.huginn.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Just enough markdown for what Claude actually writes in an answer, split into
 * blocks so a code fence can render as a real scrollable code card instead of
 * being flattened into prose (which is what v1 did, and it made any answer
 * containing a command unreadable on a phone).
 *
 * Deliberately not a full CommonMark implementation: no tables, no nested lists,
 * no reference links. Unsupported syntax degrades to its literal text rather than
 * disappearing, which is the safe failure for a reader.
 */
sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class Heading(val text: AnnotatedString, val level: Int) : MdBlock
    data class Bullet(val text: AnnotatedString, val ordinal: String?) : MdBlock
    data class Code(val code: String, val lang: String?) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data object Rule : MdBlock
}

object Markdown {

    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = src.replace("\r\n", "\n").split("\n")
        var i = 0
        val para = StringBuilder()

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

    /**
     * Inline spans: `code`, **bold**, *italic*, ~~strike~~ and [text](url).
     * Scanned in one pass so a marker inside a code span is left alone.
     */
    fun inline(src: String): AnnotatedString = buildAnnotatedString {
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
                c == '[' -> {
                    val close = src.indexOf(']', i)
                    if (close > i && close + 1 < src.length && src[close + 1] == '(') {
                        val paren = src.indexOf(')', close)
                        if (paren > close) {
                            val label = src.substring(i + 1, close)
                            val url = src.substring(close + 2, paren)
                            // The label carries the meaning on a phone; the URL is
                            // appended only when it adds information.
                            withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                                append(label)
                            }
                            if (url.isNotBlank() && url != label) {
                                append(" (")
                                append(url)
                                append(")")
                            }
                            i = paren + 1
                        } else { append(c); i++ }
                    } else { append(c); i++ }
                }
                else -> { append(c); i++ }
            }
        }
    }
}
