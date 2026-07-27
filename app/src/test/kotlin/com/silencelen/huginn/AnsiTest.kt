package com.silencelen.huginn

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.silencelen.huginn.ui.Ansi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ESC = '\u001B'
private const val BEL = '\u0007'

/** `<E>` stands in for ESC and `<B>` for BEL so no control byte lives in this source. */
private fun esc(s: String) = s.replace("<E>", ESC.toString()).replace("<B>", BEL.toString())

/**
 * The fixtures here are verbatim `tmux capture-pane -e` output from a live Claude
 * Code pane on huginn (a diff view, which exercises 256-colour fg AND bg in the
 * same line), plus the pane header. If tmux ever changes what it emits, these are
 * the lines that should be re-captured.
 */
class AnsiTest {

    private val FG = Color(0xFFE8E2DA)
    private val BG = Color(0xFF12100F)

    @Test
    fun `strips sgr sequences leaving exactly the visible text`() {
        val line = esc("     <E>[2m<E>[38;5;231m 76 <E>[0m<E>[38;5;231m   val last = 0<E>[39m")
        assertEquals("      76    val last = 0", Ansi.strip(line))
    }

    @Test
    fun `renders visible text without leaking escape bytes`() {
        val line = esc("<E>[38;5;167m<E>[48;5;52m 77 -<E>[38;5;231m removed line<E>[39m<E>[49m")
        val out = Ansi.render(line, FG, BG)
        assertEquals(" 77 - removed line", out.text)
        assertTrue("no ESC may survive into rendered text", !out.text.contains(ESC))
    }

    @Test
    fun `applies 256 colour foreground and background from the same line`() {
        // 38;5;167 is a cube colour; 48;5;52 a dark red background. Both must land
        // on the first visible character.
        val line = esc("<E>[38;5;167m<E>[48;5;52mX<E>[39m<E>[49mY")
        val out = Ansi.render(line, FG, BG)
        val first = out.spanStyles.first { it.start == 0 }
        assertNotNull(first.item.color)
        assertNotNull(first.item.background)
        // After the resets, Y falls back to the default fg and no background.
        val yStyle = out.spanStyles.first { it.start == 1 }
        assertEquals(FG, yStyle.item.color)
        assertEquals(Color.Unspecified, yStyle.item.background)
    }

    @Test
    fun `bold and dim resolve independently and reset together on 22`() {
        val line = esc("<E>[1mB<E>[22mN")
        val out = Ansi.render(line, FG, BG)
        assertEquals(FontWeight.Bold, out.spanStyles.first { it.start == 0 }.item.fontWeight)
        assertNull(out.spanStyles.first { it.start == 1 }.item.fontWeight)
    }

    @Test
    fun `reverse video swaps foreground and surface`() {
        val line = esc("<E>[7mR")
        val out = Ansi.render(line, FG, BG)
        val s = out.spanStyles.first { it.start == 0 }.item
        assertEquals(BG, s.color)
        assertEquals(FG, s.background)
    }

    @Test
    fun `truecolour sets an exact rgb foreground`() {
        val line = esc("<E>[38;2;18;52;86mT")
        val out = Ansi.render(line, FG, BG)
        val c = out.spanStyles.first { it.start == 0 }.item.color!!
        assertEquals(18, (c.red * 255f).toInt())
        assertEquals(52, (c.green * 255f).toInt())
        assertEquals(86, (c.blue * 255f).toInt())
    }

    @Test
    fun `unterminated escape at end of line does not throw or leak`() {
        // capture-pane can hand us a line cut mid-sequence at the pane edge.
        val line = esc("text<E>[38;5;")
        val out = Ansi.render(line, FG, BG)
        assertEquals("text", out.text)
        assertEquals("text", Ansi.strip(line))
    }

    @Test
    fun `osc title sequence is consumed not printed`() {
        val line = esc("a<E>]2;some titleb")
        assertEquals("ab", Ansi.render(line, FG, BG).text)
    }

    @Test
    fun `plain text with no escapes is returned unchanged`() {
        val line = "  ▐▛███▜▌   Claude Code v2.1.220"
        assertEquals(line, Ansi.render(line, FG, BG).text)
        assertEquals(line, Ansi.strip(line))
    }

    @Test
    fun `256 colour index maps to the documented cube and greyscale values`() {
        // index 16 is the cube origin (pure black); 231 is the cube's white;
        // 255 is the brightest greyscale step. Verified against xterm's table.
        val out16 = Ansi.render(esc("<E>[38;5;16mX"), FG, BG).spanStyles.first().item.color!!
        assertEquals(0f, out16.red, 0.001f)
        val out231 = Ansi.render(esc("<E>[38;5;231mX"), FG, BG).spanStyles.first().item.color!!
        assertEquals(255, (out231.red * 255f).toInt())
        val out255 = Ansi.render(esc("<E>[38;5;255mX"), FG, BG).spanStyles.first().item.color!!
        assertEquals(238, (out255.red * 255f).toInt())
    }
}
