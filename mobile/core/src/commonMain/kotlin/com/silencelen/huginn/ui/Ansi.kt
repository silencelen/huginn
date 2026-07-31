package com.silencelen.huginn.ui

import androidx.compose.ui.graphics.Color

/**
 * The SGR colour palette, resolved by [TerminalGrid].
 *
 * v1 also had an ANSI-to-AnnotatedString renderer here; the cell grid replaced
 * it (see TerminalGrid for why), so only the palette remains.
 */
object Ansi {

    val palette: List<Color> = buildList {
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
}
