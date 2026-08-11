package com.silencelen.huginn.desktop.tray

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

/**
 * The tray icon: the brand raven ([RavenMark]), tinted by state. Drawn rather
 * than shipped — a generated icon cannot go missing from a jar, a `.deb` or a
 * jpackage app-image, which is the actual failure mode being avoided: a tray
 * with no icon is invisible, and an invisible tray on a close-to-tray app is an
 * app the owner cannot get back.
 *
 * The palette matches the Electron client's tray, so the two say the same thing
 * with the same colours while they are both installed. Colour alone is not the
 * only signal: ATTENTION also carries a badge dot, so it still reads as
 * "different" on a monochrome or inverted tray theme.
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
     * blurred smear. Downscaling a clean render is free.
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
            val color = COLORS[state] ?: COLORS.getValue(TrayState.IDLE)
            if (state == TrayState.ATTENTION) {
                // Raven shifted down-left, badge dot in the cleared top-right
                // corner. The badge is the monochrome-safe cue; the gap between
                // the two is what keeps it legible at 16px.
                RavenMark.draw(g, 0.0, 22.0, 50.0, 42.0, color)
                g.color = color
                g.fill(Ellipse2D.Double(44.0, 2.0, 18.0, 18.0))
            } else {
                RavenMark.draw(g, 2.0, 2.0, 60.0, 60.0, color)
            }
        } finally {
            g.dispose()
        }
        return img
    }
}
