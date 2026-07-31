package com.silencelen.huginn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity

/**
 * Cell metrics and the glyph blit — the only two things about drawing a terminal
 * that a platform can still surprise you with.
 *
 * This is an INTERFACE handed to [TerminalCanvas], not an `expect`/`actual`, and
 * the difference matters: `expect`/`actual` would fix one implementation per
 * platform at link time, where a parameter lets a test, a preview or a future
 * export-to-image path substitute its own. Everything above it — walking the
 * grid, coalescing runs, backgrounds, underlines, the cursor, the optimistic echo
 * — is shared, which is most of what there is.
 *
 * Metrics are MEASURED from the face, never assumed. v1 guessed an advance of
 * 0.6 em, which is wrong for the device mono face and made the column count — and
 * therefore the pane width this client asks tmux for — subtly wrong.
 */
interface CellPainter {
    /** Advance of one cell: the mono face's own advance for `M`. */
    val cellWidth: Float

    /** Line box, ascent to descent with a little leading. */
    val cellHeight: Float

    /** Distance from the top of the line box down to the baseline. */
    val baseline: Float

    /**
     * How wide [text] actually draws. Only asked about the glyphs that are NOT
     * plain ASCII — box drawing, `●`, emoji — because those are exactly the ones
     * whose advance disagrees with the cell and have to be centred instead.
     */
    fun advanceOf(text: String, bold: Boolean, italic: Boolean): Float

    /**
     * Blits [text] with its left edge at [x] and its baseline at [baseline].
     *
     * Takes the [DrawScope] as an ordinary parameter rather than as a receiver:
     * a member extension would resolve through two implicit receivers at once,
     * which is exactly the kind of cleverness that makes a painter hard to
     * substitute.
     */
    fun drawRun(
        scope: DrawScope,
        text: String,
        x: Float,
        baseline: Float,
        color: Color,
        bold: Boolean,
        italic: Boolean,
    )
}

/**
 * Draws a [TermGrid] as a true character grid: every cell is placed at
 * `col * cellWidth`, so a glyph whose font advance differs from the cell width
 * (box drawing, `●`, emoji) cannot push the rest of the row sideways.
 *
 * Runs of plain ASCII sharing a style are drawn as one string — correct because
 * a monospace face advances those uniformly — and anything else is drawn per
 * cell. That keeps a full 130x50 screen down to a few hundred draw calls instead
 * of six thousand.
 */
@Composable
fun TerminalCanvas(
    grid: TermGrid,
    painter: CellPainter,
    cursor: Pair<Int, Int>?,      // col, row
    cursorColor: Color,
    /** Optimistically typed text drawn at the cursor, clipped to the row. */
    echo: String = "",
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val w = remember(grid.cols, painter) { with(density) { (grid.cols * painter.cellWidth).toDp() } }
    val h = remember(grid.height, painter) { with(density) { (grid.height * painter.cellHeight).toDp() } }

    Canvas(modifier.size(w, h)) {
        drawGrid(grid, painter, cursor, cursorColor, echo)
    }
}

/**
 * Internal so the grid walk can be tested against a recording [CellPainter] —
 * run coalescing, wide-glyph centring, cursor placement and echo clipping are
 * rules with edges, and every one of them was a real drift bug before it was a
 * rule. Rendering them into pixels and eyeballing the result is not a test.
 */
internal fun DrawScope.drawGrid(
    grid: TermGrid,
    m: CellPainter,
    cursor: Pair<Int, Int>?,
    cursorColor: Color,
    echo: String = "",
) {
    // Background runs first, so a coloured span cannot paint over the glyph of
    // the cell to its left.
    grid.rows.forEachIndexed { rowIdx, row ->
        val y = rowIdx * m.cellHeight
        var col = 0
        while (col < row.size) {
            val bg = row[col].bg
            if (bg == null) { col++; continue }
            var end = col
            while (end + 1 < row.size && row[end + 1].bg == bg) end++
            drawRect(
                color = bg,
                topLeft = Offset(col * m.cellWidth, y),
                size = Size((end - col + 1) * m.cellWidth, m.cellHeight),
            )
            col = end + 1
        }
    }

    grid.rows.forEachIndexed { rowIdx, row ->
        val baseY = rowIdx * m.cellHeight + m.baseline
        var col = 0
        while (col < row.size) {
            val cell = row[col]
            if (cell.text.isEmpty() || cell.text == " ") { col++; continue }

            if (isPlainAscii(cell.text)) {
                // Extend the run while style matches and the chars stay ASCII.
                val sb = StringBuilder(cell.text)
                var end = col
                while (end + 1 < row.size) {
                    val n = row[end + 1]
                    if (n.text.isEmpty()) break
                    if (!isPlainAscii(n.text) || !sameStyle(cell, n)) break
                    sb.append(n.text)
                    end++
                }
                m.drawRun(this, sb.toString(), col * m.cellWidth, baseY, cell.fg, cell.bold, cell.italic)
                if (cell.underline) {
                    drawRect(
                        color = cell.fg,
                        topLeft = Offset(col * m.cellWidth, baseY + m.cellHeight * 0.08f),
                        size = Size((end - col + 1) * m.cellWidth, m.cellHeight * 0.05f),
                    )
                }
                col = end + 1
            } else {
                // One glyph, centred in its own cell (two cells when wide) so
                // an over-wide emoji overlaps nothing.
                val span = if (cell.wide) 2 else 1
                val advance = m.advanceOf(cell.text, cell.bold, cell.italic)
                val boxW = span * m.cellWidth
                val x = col * m.cellWidth + ((boxW - advance) / 2f).coerceAtLeast(0f)
                m.drawRun(this, cell.text, x, baseY, cell.fg, cell.bold, cell.italic)
                if (cell.underline) {
                    drawRect(
                        color = cell.fg,
                        topLeft = Offset(col * m.cellWidth, baseY + m.cellHeight * 0.08f),
                        size = Size(boxW, m.cellHeight * 0.05f),
                    )
                }
                col += span
            }
        }
    }

    if (cursor != null) {
        val (cx, cy) = cursor

        // Optimistic echo: characters typed but not yet confirmed by the pane,
        // drawn from the cursor cell and CLIPPED at the row's end — the echo
        // never invents a wrap, because predicting the composer's wrapping is
        // exactly where ghost characters come from. Slightly translucent, so a
        // reader can tell promised text from confirmed text if they look.
        var drawnEcho = 0
        if (echo.isNotEmpty() && cy in 0 until grid.height) {
            val echoColor = cursorColor.copy(alpha = 0.85f)
            val y = cy * m.cellHeight + m.baseline
            for (ch in echo) {
                val col = cx + drawnEcho
                if (col >= grid.cols) break
                m.drawRun(this, ch.toString(), col * m.cellWidth, y, echoColor, bold = false, italic = false)
                drawnEcho++
            }
        }

        // The cursor sits AFTER the echo: that is where the next character goes,
        // which is what a cursor is for.
        val ecx = (cx + drawnEcho).coerceAtMost(grid.cols - 1)
        if (cy in 0 until grid.height && ecx in 0 until grid.cols) {
            // Hollow box: it marks the position without hiding the character
            // underneath, which matters when the cursor sits on real text.
            val t = (m.cellWidth * 0.12f).coerceAtLeast(1f)
            val x = ecx * m.cellWidth
            val y = cy * m.cellHeight
            drawRect(cursorColor, Offset(x, y), Size(m.cellWidth, t))
            drawRect(cursorColor, Offset(x, y + m.cellHeight - t), Size(m.cellWidth, t))
            drawRect(cursorColor, Offset(x, y), Size(t, m.cellHeight))
            drawRect(cursorColor, Offset(x + m.cellWidth - t, y), Size(t, m.cellHeight))
        }
    }
}

private fun isPlainAscii(s: String): Boolean {
    if (s.length != 1) return false
    val c = s[0]
    return c.code in 0x20..0x7E
}

private fun sameStyle(a: TermCell, b: TermCell): Boolean =
    a.fg == b.fg && a.bold == b.bold && a.italic == b.italic && a.underline == b.underline
