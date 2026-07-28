package com.silencelen.huginn

import androidx.compose.ui.graphics.Color
import com.silencelen.huginn.ui.TerminalGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ESC = '\u001B'
private const val BEL = '\u0007'

/** `<E>` is ESC, `<B>` is BEL, so no control byte lives in this source. */
private fun esc(s: String) = s.replace("<E>", ESC.toString()).replace("<B>", BEL.toString())

/**
 * The fixtures are verbatim `tmux capture-pane -e` output from live Claude Code
 * panes on huginn (2026-07-27), including the OSC 8 hyperlinks Claude Code wraps
 * around file paths and the box-drawing/`●`/`❯` furniture that broke v1's
 * per-line rendering.
 */
class TerminalGridTest {

    private val FG = Color(0xFFE8E2DA)
    private val BG = Color(0xFF12100F)

    private fun parse(line: String, cols: Int = 40) =
        TerminalGrid.parse(listOf(line), cols, FG, BG).rows[0]

    private fun textOf(row: List<com.silencelen.huginn.ui.TermCell>) =
        row.joinToString("") { it.text }.trimEnd()

    // ---- the property v1 got wrong -----------------------------------------

    @Test
    fun `a glyph wider than a cell does not shift the columns after it`() {
        // '●' is what Claude Code puts at the head of every assistant line. In v1
        // the font's advance for it decided where the rest of the line landed.
        val row = parse("●abc")
        assertEquals("●", row[0].text)
        assertEquals("a", row[1].text)
        assertEquals("b", row[2].text)
        assertEquals("c", row[3].text)
    }

    @Test
    fun `box drawing keeps its column so borders line up`() {
        val row = parse("─────x")
        assertEquals("the 6th cell must be x, not shifted", "x", row[5].text)
    }

    @Test
    fun `every row is padded to the same width so rows cannot drift`() {
        val g = TerminalGrid.parse(listOf("short", "a much longer line here"), 30, FG, BG)
        assertEquals(30, g.rows[0].size)
        assertEquals(30, g.rows[1].size)
    }

    @Test
    fun `a row longer than the pane is truncated, not wrapped`() {
        val row = parse("0123456789", cols = 5)
        assertEquals(5, row.size)
        assertEquals("01234", textOf(row))
    }

    // ---- character widths ---------------------------------------------------

    @Test
    fun `wide characters occupy two cells with a blank continuation`() {
        val row = parse("日x")
        assertEquals("日", row[0].text)
        assertTrue(row[0].wide)
        assertEquals("the second half of a wide glyph draws nothing", "", row[1].text)
        assertEquals("x sits in column 2, as the terminal placed it", "x", row[2].text)
    }

    @Test
    fun `emoji beyond the BMP are handled as one wide cell, not two halves`() {
        // Surrogate pairs must be read as a code point; otherwise a rocket
        // becomes two broken cells and everything after it shifts.
        val row = parse("🚀x")
        assertEquals("🚀", row[0].text)
        assertTrue(row[0].wide)
        assertEquals("x", row[2].text)
    }

    @Test
    fun `combining marks attach to the previous cell instead of taking a column`() {
        val row = parse("éx")   // e + combining acute
        assertEquals("é", row[0].text)
        assertEquals("x", row[1].text)
    }

    @Test
    fun `charWidth classifies the glyph classes the TUI actually uses`() {
        assertEquals(1, TerminalGrid.charWidth('a'.code))
        assertEquals(1, TerminalGrid.charWidth('●'.code))
        assertEquals(1, TerminalGrid.charWidth('─'.code))
        assertEquals(1, TerminalGrid.charWidth('❯'.code))
        assertEquals(2, TerminalGrid.charWidth('日'.code))
        assertEquals(2, TerminalGrid.charWidth(0x1F680))
        assertEquals(0, TerminalGrid.charWidth(0x0301))
        assertEquals(0, TerminalGrid.charWidth(0xFE0F))
    }

    // ---- SGR ----------------------------------------------------------------

    @Test
    fun `256 colour foreground and background both land on the cell`() {
        val row = parse(esc("<E>[38;5;167m<E>[48;5;52mX<E>[39m<E>[49mY"))
        assertNotNull(row[0].bg)
        assertEquals(Ansi256(167), row[0].fg)
        assertEquals("after the reset the default returns", FG, row[1].fg)
        assertNull(row[1].bg)
    }

    private fun Ansi256(i: Int) = com.silencelen.huginn.ui.Ansi.palette[i]

    @Test
    fun `truecolour sets an exact rgb foreground`() {
        val c = parse(esc("<E>[38;2;18;52;86mT"))[0].fg
        assertEquals(18, (c.red * 255f).toInt())
        assertEquals(52, (c.green * 255f).toInt())
        assertEquals(86, (c.blue * 255f).toInt())
    }

    @Test
    fun `bold and dim are set and cleared together by 22`() {
        val row = parse(esc("<E>[1mB<E>[22mN"))
        assertTrue(row[0].bold)
        assertFalse(row[1].bold)
    }

    @Test
    fun `reverse video swaps foreground and surface`() {
        val cell = parse(esc("<E>[7mR"))[0]
        assertEquals(BG, cell.fg)
        assertEquals(FG, cell.bg)
    }

    @Test
    fun `the 256 colour cube and greyscale ramp match the xterm table`() {
        val cube0 = parse(esc("<E>[38;5;16mX"))[0].fg
        assertEquals(0f, cube0.red, 0.001f)
        val cubeWhite = parse(esc("<E>[38;5;231mX"))[0].fg
        assertEquals(255, (cubeWhite.red * 255f).toInt())
        val grey = parse(esc("<E>[38;5;255mX"))[0].fg
        assertEquals(238, (grey.red * 255f).toInt())
    }

    // ---- escapes that must not become visible text --------------------------

    @Test
    fun `an OSC 8 hyperlink is consumed and only its label is shown`() {
        // Claude Code wraps file paths in OSC 8; v1 showed the whole URL inline.
        val row = parse(esc("<E>]8;id=x;file:///tmp/a.txt<B>a.txt<E>]8;;<B>"), cols = 20)
        assertEquals("a.txt", textOf(row))
    }

    @Test
    fun `an unterminated escape at the pane edge does not leak or throw`() {
        assertEquals("text", textOf(parse(esc("text<E>[38;5;"), cols = 20)))
    }

    @Test
    fun `a cursor-move escape is dropped rather than printed`() {
        assertEquals("ab", textOf(parse(esc("a<E>[2Kb"), cols = 20)))
    }

    @Test
    fun `plain text passes through untouched`() {
        val line = "  ▐▛███▜▌   Claude Code v2.1.220"
        assertEquals(line.trimEnd(), textOf(parse(line, cols = 60)))
    }

    @Test
    fun `a real captured pane line renders its visible text exactly`() {
        // Leading indent included: it is part of the captured line.
        val line = esc("     <E>[2m<E>[38;5;231m 76 <E>[0m<E>[38;5;231m   val last = 0<E>[39m")
        assertEquals("      76    val last = 0".trimEnd(), textOf(parse(line, cols = 60)))
    }
}
