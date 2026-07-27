package com.silencelen.huginn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Renders the SGR escape sequences `tmux capture-pane -e` emits into styled text.
 *
 * Scope is deliberately the subset tmux actually produces for a Claude Code pane:
 * bold/dim/italic/underline/reverse, the 8 basic colours and their bright forms,
 * 256-colour (`38;5;n` / `48;5;n`) and truecolour (`38;2;r;g;b`). Anything else
 * (cursor moves, OSC titles) is dropped rather than printed, because printing a
 * raw escape is worse than losing its effect.
 */
object Ansi {

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    private val palette: List<Color> = buildList {
        // 0-15: standard + bright, tuned to read on the app's near-black surface
        // rather than matching a specific terminal's defaults exactly.
        addAll(
            listOf(
                Color(0xFF3B3733), Color(0xFFD1544F), Color(0xFF69A95B), Color(0xFFC7A24A),
                Color(0xFF5B8FD6), Color(0xFFA974C4), Color(0xFF4FA5A8), Color(0xFFCFC8BF),
                Color(0xFF6B645D), Color(0xFFE8736D), Color(0xFF8CCB7B), Color(0xFFE3C169),
                Color(0xFF7DAFEA), Color(0xFFC495DC), Color(0xFF6FC4C7), Color(0xFFF2ECE4),
            )
        )
        // 16-231: the 6x6x6 cube
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        for (r in 0..5) for (g in 0..5) for (b in 0..5) {
            add(Color(steps[r] / 255f, steps[g] / 255f, steps[b] / 255f))
        }
        // 232-255: greyscale ramp
        for (i in 0..23) {
            val v = (8 + i * 10) / 255f
            add(Color(v, v, v))
        }
    }

    private data class State(
        var fg: Color? = null,
        var bg: Color? = null,
        var bold: Boolean = false,
        var dim: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var reverse: Boolean = false,
    ) {
        fun reset() { fg = null; bg = null; bold = false; dim = false; italic = false; underline = false; reverse = false }
    }

    /**
     * @param defaultFg colour for text with no explicit foreground
     * @param defaultBg surface colour, needed to render `reverse` (swap) faithfully
     */
    fun render(line: String, defaultFg: Color, defaultBg: Color): AnnotatedString {
        val st = State()
        return buildAnnotatedString {
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == ESC) {
                    // CSI ... final-byte, or a sequence we skip wholesale.
                    if (i + 1 < line.length && line[i + 1] == '[') {
                        var j = i + 2
                        while (j < line.length && line[j] !in '@'..'~') j++
                        if (j < line.length) {
                            if (line[j] == 'm') applySgr(line.substring(i + 2, j), st)
                            i = j + 1
                        } else i = line.length
                    } else {
                        // OSC (ESC ]) runs to BEL or ST; other 2-byte escapes are skipped.
                        if (i + 1 < line.length && line[i + 1] == ']') {
                            var j = i + 2
                            while (j < line.length && line[j] != BEL && line[j] != ESC) j++
                            i = if (j < line.length && line[j] == BEL) j + 1 else j
                        } else i += 2
                    }
                    continue
                }
                val fg0 = st.fg ?: defaultFg
                val bg0 = st.bg
                val fg = if (st.reverse) (bg0 ?: defaultBg) else fg0
                val bg = if (st.reverse) fg0 else bg0
                withStyle(
                    SpanStyle(
                        color = if (st.dim && !st.reverse) fg.copy(alpha = 0.6f) else fg,
                        background = bg ?: Color.Unspecified,
                        fontWeight = if (st.bold) FontWeight.Bold else null,
                        fontStyle = if (st.italic) FontStyle.Italic else null,
                        textDecoration = if (st.underline) TextDecoration.Underline else null,
                    )
                ) { append(c) }
                i++
            }
        }
    }

    private fun applySgr(params: String, st: State) {
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
                in 30..37 -> st.fg = palette[code - 30]
                39 -> st.fg = null
                in 40..47 -> st.bg = palette[code - 40]
                49 -> st.bg = null
                in 90..97 -> st.fg = palette[code - 90 + 8]
                in 100..107 -> st.bg = palette[code - 100 + 8]
                38, 48 -> {
                    val isFg = code == 38
                    when (codes.getOrNull(k + 1)) {
                        5 -> {
                            val idx = codes.getOrNull(k + 2) ?: 0
                            val col = palette.getOrNull(idx)
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

    /** Plain text with every escape stripped (for previews and snippets). */
    fun strip(line: String): String = buildString {
        var i = 0
        while (i < line.length) {
            if (line[i] == ESC) {
                if (i + 1 < line.length && line[i + 1] == '[') {
                    var j = i + 2
                    while (j < line.length && line[j] !in '@'..'~') j++
                    i = if (j < line.length) j + 1 else line.length
                } else i += 2
            } else { append(line[i]); i++ }
        }
    }
}
