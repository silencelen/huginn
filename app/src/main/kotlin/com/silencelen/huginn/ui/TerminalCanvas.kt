package com.silencelen.huginn.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Cell metrics for a monospace face at a given text size, measured from the font
 * rather than assumed. v1 guessed an advance of 0.6 em, which is wrong for the
 * device mono face and made the column count (and therefore the requested pane
 * width) subtly wrong.
 */
class CellMetrics(textSizePx: Float) {
    val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = textSizePx
    }
    private val boldPaint: Paint = Paint(paint).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    val cellWidth: Float = paint.measureText("M")
    private val fm = paint.fontMetrics
    val cellHeight: Float = (fm.descent - fm.ascent) * 1.06f
    val baseline: Float = -fm.ascent

    fun paintFor(bold: Boolean, italic: Boolean): Paint = when {
        bold && italic -> Paint(boldPaint).apply { textSkewX = -0.25f }
        bold -> boldPaint
        italic -> Paint(paint).apply { textSkewX = -0.25f }
        else -> paint
    }
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
    metrics: CellMetrics,
    cursor: Pair<Int, Int>?,      // col, row
    cursorColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val w = remember(grid.cols, metrics) { with(density) { (grid.cols * metrics.cellWidth).toDp() } }
    val h = remember(grid.height, metrics) { with(density) { (grid.height * metrics.cellHeight).toDp() } }

    Canvas(modifier.size(w, h)) {
        drawGrid(grid, metrics, cursor, cursorColor, density)
    }
}

private fun DrawScope.drawGrid(
    grid: TermGrid,
    m: CellMetrics,
    cursor: Pair<Int, Int>?,
    cursorColor: Color,
    density: Density,
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

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
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
                    val p = m.paintFor(cell.bold, cell.italic).apply { color = cell.fg.toArgb() }
                    native.drawText(sb.toString(), col * m.cellWidth, baseY, p)
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
                    val p = m.paintFor(cell.bold, cell.italic).apply { color = cell.fg.toArgb() }
                    val advance = p.measureText(cell.text)
                    val boxW = span * m.cellWidth
                    val x = col * m.cellWidth + ((boxW - advance) / 2f).coerceAtLeast(0f)
                    native.drawText(cell.text, x, baseY, p)
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
    }

    if (cursor != null) {
        val (cx, cy) = cursor
        if (cy in 0 until grid.height && cx in 0 until grid.cols) {
            // Hollow box: it marks the position without hiding the character
            // underneath, which matters when the cursor sits on real text.
            val t = (m.cellWidth * 0.12f).coerceAtLeast(1f)
            val x = cx * m.cellWidth
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
