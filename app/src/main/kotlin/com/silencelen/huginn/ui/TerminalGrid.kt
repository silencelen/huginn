package com.silencelen.huginn.ui

import androidx.compose.ui.graphics.Color

/**
 * A parsed terminal screen: rows of fixed-width cells.
 *
 * v1 drew each captured line as one `Text`, which let the font decide advances.
 * Claude Code's TUI is full of glyphs whose font advance is not one monospace
 * cell (box drawing, `●`, `❯`, `⏵⏵`, emoji), so columns drifted and the layout
 * sheared — box borders never lined up. Laying the line out into cells first,
 * using the SAME width rule tmux used when it composed the pane (wcwidth), makes
 * the app's grid agree with the server's grid by construction.
 */
data class TermCell(
    val text: String,          // "" for the trailing half of a wide glyph
    val fg: Color,
    val bg: Color?,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val wide: Boolean = false, // occupies this cell plus the next
) {
    val isBlank: Boolean get() = text.isEmpty() || text == " "
}

data class TermGrid(val rows: List<List<TermCell>>, val cols: Int) {
    val height: Int get() = rows.size
}

object TerminalGrid {

    /**
     * Display width of a code point, matching the wcwidth rule a terminal uses.
     * Only the classes that actually occur matter here: zero-width combining
     * marks and joiners, and the wide East Asian / emoji blocks.
     */
    fun charWidth(cp: Int): Int = when {
        cp == 0 -> 0
        cp < 32 -> 0
        // Combining marks, joiners, variation selectors: they attach to the
        // previous cell rather than taking one of their own.
        cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF ||
            cp in 0x20D0..0x20F0 || cp in 0xFE00..0xFE0F || cp in 0xFE20..0xFE2F ||
            cp in 0x200B..0x200F || cp in 0x2060..0x2064 -> 0
        // Wide (East Asian W/F) and emoji-presentation blocks.
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF ||
            cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF ||
            cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE6F ||
            cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 ||
            cp in 0x1F300..0x1F64F || cp in 0x1F680..0x1F6FF ||
            cp in 0x1F900..0x1F9FF || cp in 0x1FA70..0x1FAFF ||
            cp in 0x20000..0x3FFFD -> 2
        else -> 1
    }

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    private data class Style(
        var fg: Color? = null,
        var bg: Color? = null,
        var bold: Boolean = false,
        var dim: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var reverse: Boolean = false,
    ) {
        fun reset() {
            fg = null; bg = null; bold = false; dim = false
            italic = false; underline = false; reverse = false
        }
    }

    /**
     * Parses `capture-pane -e` lines into a grid.
     *
     * @param cols pad/truncate every row to this width so the grid is rectangular
     *   and a row that ends early cannot make the next row's cells shift left.
     */
    fun parse(lines: List<String>, cols: Int, defaultFg: Color, defaultBg: Color): TermGrid {
        val rows = lines.map { parseLine(it, cols, defaultFg, defaultBg) }
        return TermGrid(rows, cols)
    }

    private fun parseLine(line: String, cols: Int, defaultFg: Color, defaultBg: Color): List<TermCell> {
        val st = Style()
        val out = ArrayList<TermCell>(cols)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == ESC) {
                i = skipEscape(line, i, st)
                continue
            }
            val cp = line.codePointAt(i)
            val charCount = Character.charCount(cp)
            val s = line.substring(i, i + charCount)
            i += charCount
            val w = charWidth(cp)
            if (w == 0) {
                // Attach to the previous cell so an accent or a variation
                // selector renders with its base instead of eating a column.
                if (out.isNotEmpty()) {
                    val last = out.removeAt(out.size - 1)
                    out.add(last.copy(text = last.text + s))
                }
                continue
            }
            val fg0 = st.fg ?: defaultFg
            val bg0 = st.bg
            val fg = if (st.reverse) (bg0 ?: defaultBg) else fg0
            val bg = if (st.reverse) fg0 else bg0
            val cell = TermCell(
                text = s,
                fg = if (st.dim && !st.reverse) fg.copy(alpha = 0.6f) else fg,
                bg = bg,
                bold = st.bold, dim = st.dim, italic = st.italic, underline = st.underline,
                wide = w == 2,
            )
            out.add(cell)
            // The trailing half of a wide glyph is a real cell that draws nothing,
            // so column arithmetic stays honest.
            if (w == 2) out.add(cell.copy(text = "", wide = false))
        }
        // Rectangular rows: pad short lines, drop overflow.
        val blank = TermCell(" ", defaultFg, null)
        while (out.size < cols) out.add(blank)
        return if (out.size > cols) out.subList(0, cols).toList() else out
    }

    /** @return the index just past the escape sequence starting at [start]. */
    private fun skipEscape(line: String, start: Int, st: Style): Int {
        if (start + 1 >= line.length) return line.length
        return when (line[start + 1]) {
            '[' -> {
                var j = start + 2
                while (j < line.length && line[j] !in '@'..'~') j++
                if (j < line.length) {
                    if (line[j] == 'm') applySgr(line.substring(start + 2, j), st)
                    j + 1
                } else line.length
            }
            // OSC: title changes and, notably, OSC 8 hyperlinks, which Claude Code
            // emits around file paths. Consumed whole — the visible label follows
            // as ordinary text.
            ']' -> {
                var j = start + 2
                while (j < line.length && line[j] != BEL && line[j] != ESC) j++
                if (j < line.length && line[j] == BEL) j + 1 else j
            }
            else -> start + 2
        }
    }

    private fun applySgr(params: String, st: Style) {
        if (params.isEmpty()) { st.reset(); return }
        val codes = params.split(';').map { it.toIntOrNull() ?: 0 }
        var k = 0
        while (k < codes.size) {
            when (val code = codes[k]) {
                0 -> st.reset()
                1 -> st.bold = true
                2 -> st.dim = true
                3 -> st.italic = true
                4 -> st.underline = true
                7 -> st.reverse = true
                22 -> { st.bold = false; st.dim = false }
                23 -> st.italic = false
                24 -> st.underline = false
                27 -> st.reverse = false
                in 30..37 -> st.fg = Ansi.palette[code - 30]
                39 -> st.fg = null
                in 40..47 -> st.bg = Ansi.palette[code - 40]
                49 -> st.bg = null
                in 90..97 -> st.fg = Ansi.palette[code - 90 + 8]
                in 100..107 -> st.bg = Ansi.palette[code - 100 + 8]
                38, 48 -> {
                    val isFg = code == 38
                    when (codes.getOrNull(k + 1)) {
                        5 -> {
                            val col = Ansi.palette.getOrNull(codes.getOrNull(k + 2) ?: 0)
                            if (isFg) st.fg = col else st.bg = col
                            k += 2
                        }
                        2 -> {
                            val r = codes.getOrNull(k + 2) ?: 0
                            val g = codes.getOrNull(k + 3) ?: 0
                            val b = codes.getOrNull(k + 4) ?: 0
                            val col = Color(r / 255f, g / 255f, b / 255f)
                            if (isFg) st.fg = col else st.bg = col
                            k += 4
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
            k++
        }
    }
}
