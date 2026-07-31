package com.silencelen.huginn.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grid walk, against a painter that records instead of drawing.
 *
 * Every rule asserted here was a bug first. Runs coalesce because six thousand
 * draw calls a frame is not a frame rate; a glyph is placed at `col * cellWidth`
 * rather than after the previous glyph's advance because otherwise Claude Code's
 * box borders shear; the echo is clipped at the row's end because predicting the
 * composer's wrap is where ghost characters come from.
 *
 * NOTE the argument order — kotlin.test is `assertEquals(expected, actual, msg)`,
 * JUnit is `assertEquals(msg, expected, actual)`. Three strings compile either
 * way and assert something different.
 */
class TerminalCanvasTest {

    private class Recorder : CellPainter {
        data class Run(val text: String, val x: Float, val bold: Boolean, val italic: Boolean)

        val runs = mutableListOf<Run>()
        override val cellWidth = 10f
        override val cellHeight = 20f
        override val baseline = 15f

        /** Deliberately WIDER than a cell — the case centring exists for. */
        override fun advanceOf(text: String, bold: Boolean, italic: Boolean) = 14f

        override fun drawRun(
            scope: DrawScope,
            text: String,
            x: Float,
            baseline: Float,
            color: Color,
            bold: Boolean,
            italic: Boolean,
        ) {
            runs += Run(text, x, bold, italic)
        }
    }

    private fun cells(text: String, bold: Boolean = false): List<TermCell> =
        text.map { TermCell(it.toString(), Color.White, null, bold = bold) }

    private fun paint(
        rows: List<List<TermCell>>,
        cols: Int,
        cursor: Pair<Int, Int>? = null,
        echo: String = "",
    ): Recorder {
        val painter = Recorder()
        val bitmap = ImageBitmap(200, 100)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(200f, 100f),
        ) {
            drawGrid(TermGrid(rows, cols), painter, cursor, Color.Yellow, echo)
        }
        return painter
    }

    @Test
    fun coalescesAPlainAsciiRunIntoOneDrawCall() {
        val r = paint(listOf(cells("hello")), cols = 5)
        assertEquals(1, r.runs.size, "one styled ASCII run is one draw call")
        assertEquals("hello", r.runs[0].text)
        assertEquals(0f, r.runs[0].x)
    }

    @Test
    fun breaksARunWhenTheStyleChanges() {
        val row = cells("ab") + cells("CD", bold = true) + cells("ef")
        val r = paint(listOf(row), cols = 6)
        assertEquals(listOf("ab", "CD", "ef"), r.runs.map { it.text })
        // Every run still starts on its own column boundary, not after the
        // previous run's advance.
        assertEquals(listOf(0f, 20f, 40f), r.runs.map { it.x })
    }

    @Test
    fun anInteriorSpaceStaysInsideTheRunAndALeadingOneIsSkipped() {
        // A space is plain ASCII, so it does not break a run — drawing "ab cd" as
        // one string is both correct and one call instead of two. Blanks are only
        // skipped when they would START a run, which is what keeps a mostly-empty
        // 130x50 screen cheap.
        val leading = listOf(TermCell(" ", Color.White, null)) + cells("ab") +
            TermCell(" ", Color.White, null) + cells("cd")
        val r = paint(listOf(leading), cols = 6)
        assertEquals(listOf("ab cd"), r.runs.map { it.text })
        assertEquals(10f, r.runs[0].x, "the run starts at the first non-blank column, not at zero")
    }

    @Test
    fun centresAnOverWideGlyphInItsOwnCellsInsteadOfPushingTheRow() {
        // `●` is not plain ASCII, so it is drawn alone; its 14pt advance in a
        // 10pt cell is centred by -2pt rather than shifting what follows.
        val row = listOf(TermCell("●", Color.White, null)) + cells("ok")
        val r = paint(listOf(row), cols = 3)
        assertEquals(listOf("●", "ok"), r.runs.map { it.text })
        assertEquals(0f, r.runs[0].x, "an advance wider than the cell clamps at the cell's left edge")
        assertEquals(10f, r.runs[1].x, "the text after it still starts at column 1")
    }

    @Test
    fun aWideGlyphSpansTwoCellsAndTheNextTextClearsBoth() {
        val row = listOf(
            TermCell("漢", Color.White, null, wide = true),
            TermCell("", Color.White, null),
        ) + cells("x")
        val r = paint(listOf(row), cols = 3)
        assertEquals(listOf("漢", "x"), r.runs.map { it.text })
        // Box is 20pt, advance 14pt: centred by 3pt.
        assertEquals(3f, r.runs[0].x)
        assertEquals(20f, r.runs[1].x)
    }

    @Test
    fun echoDrawsFromTheCursorAndIsClippedAtTheRowEnd() {
        val r = paint(listOf(cells("....")), cols = 4, cursor = 2 to 0, echo = "abcd")
        val echoRuns = r.runs.filter { it.text.length == 1 && it.text[0] in 'a'..'d' }
        assertEquals(
            listOf("a", "b"), echoRuns.map { it.text },
            "two columns left of a four-column row: the echo never invents a wrap",
        )
        assertEquals(listOf(20f, 30f), echoRuns.map { it.x })
    }

    @Test
    fun paintsSomethingWithTheRealSkiaPainter() {
        // The JVM painter for real: skia resolves a mono face, the metrics come
        // out of the font rather than a guess, and pixels actually change. A
        // painter that compiles and draws nothing looks identical to a working
        // one in a screenshot of an empty pane.
        val painter = SkiaCellPainter(14f)
        assertTrue(painter.cellWidth > 0f, "a measured cell width, not zero")
        assertTrue(painter.cellHeight > painter.cellWidth, "a line box is taller than one cell is wide")
        assertEquals(
            painter.advanceOf("M", bold = false, italic = false),
            painter.advanceOf("i", bold = false, italic = false),
            "the resolved face must be MONOSPACE: M and i advance identically",
        )

        val bitmap = ImageBitmap(80, 40)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(80f, 40f)) {
            painter.drawRun(this, "HELLO", 0f, 20f, Color.White, bold = false, italic = false)
        }
        val pixels = bitmap.toPixelMap()
        var lit = 0
        for (y in 0 until 40) for (x in 0 until 80) if (pixels[x, y].alpha > 0f) lit++
        assertTrue(lit > 0, "the skia painter put ink on the canvas")
    }
}
