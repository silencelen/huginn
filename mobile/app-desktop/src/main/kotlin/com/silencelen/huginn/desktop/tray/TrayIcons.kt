package com.silencelen.huginn.desktop.tray

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * The tray icon, drawn rather than shipped.
 *
 * Three coloured dots is not an asset pipeline's worth of work, and a generated
 * icon cannot go missing from a jar, a `.deb` or a jpackage app-image — which is
 * the actual failure mode being avoided: a tray with no icon is invisible, and
 * an invisible tray on a close-to-tray app is an app the owner cannot get back.
 *
 * The palette matches the Electron client's tray, so the two say the same thing
 * with the same colours while they are both installed.
 */
object TrayIcons {

    private val COLORS: Map<TrayState, Color> = mapOf(
        TrayState.IDLE to Color(123, 135, 148),
        TrayState.WORKING to Color(122, 162, 247),
        TrayState.ATTENTION to Color(224, 175, 104),
    )

    /**
     * Rendered at 64px rather than 16: Compose scales the painter to whatever the
     * platform's tray asks for, and a 16px source scaled up on a HiDPI tray is a
     * blurred smear. Downscaling a clean circle is free.
     */
    private const val SIZE = 64

    private val cache = HashMap<TrayState, Painter>()

    fun painter(state: TrayState): Painter = synchronized(cache) {
        cache.getOrPut(state) { draw(state).toPainter() }
    }

    private fun draw(state: TrayState): BufferedImage {
        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = COLORS[state] ?: COLORS.getValue(TrayState.IDLE)
            val inset = SIZE * 0.12
            val d = SIZE - inset * 2
            g.fillOval(inset.toInt(), inset.toInt(), d.toInt(), d.toInt())
            if (state == TrayState.ATTENTION) {
                // A hole punched out of the dot rather than a second colour: at
                // 16px a two-tone mark is mud, and the ring still reads as
                // "different" on a monochrome or inverted tray theme.
                g.composite = java.awt.AlphaComposite.Clear
                val hole = d * 0.34
                val off = (SIZE - hole) / 2
                g.fillOval(off.toInt(), off.toInt(), hole.toInt(), hole.toInt())
            }
        } finally {
            g.dispose()
        }
        return img
    }
}
