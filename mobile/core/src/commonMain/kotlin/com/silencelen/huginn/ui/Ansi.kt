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
        // 0-15: the brand's ANSI-16, Moss & Heather
        // (assets/brand/explorations/color/color-04-moss-heather/terminal/moss-heather.ansi.json).
        // Four slots ARE app tokens, by rule (round 7, 2026-10-13): brightRed = error,
        // brightCyan = stateSuccess, brightYellow = stateAttention, brightMagenta =
        // stateInfo, so the terminal view and the app never show two reds. Every slot but black is
        // >= 3:1 on the app ground (#141615); black is the outlineVariant.
        addAll(
            listOf(
                Color(0xFF3A423E), Color(0xFFD65A62), Color(0xFF7CB98E), Color(0xFFD4B872),
                Color(0xFF6C9BD6), Color(0xFFA98BD6), Color(0xFF5EB5B0), Color(0xFFC8CDC9),
                Color(0xFF747D77), Color(0xFFEE757D), Color(0xFFC4CF7A), Color(0xFFE8D07E),
                Color(0xFF86B5E6), Color(0xFFB49CDB), Color(0xFF80CFC9), Color(0xFFE2E6E1),
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
