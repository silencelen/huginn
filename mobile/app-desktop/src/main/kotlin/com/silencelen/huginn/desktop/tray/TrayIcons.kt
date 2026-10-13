package com.silencelen.huginn.desktop.tray

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage

/**
 * The tray icon: the brand's small form ([RavenMark.drawTile], the raven cut out
 * of a rounded tile), tinted by state. Drawn rather than shipped — a generated
 * icon cannot go missing from a jar, a `.deb` or a jpackage app-image, which is
 * the actual failure mode being avoided: a tray with no icon is invisible, and an
 * invisible tray on a close-to-tray app is an app the owner cannot get back.
 *
 * The three tints are the brand's state ladder (assets/brand/BRAND.md section 2):
 * idle, working and needs-you are one family at three lightness steps, 12 L*
 * apart or more, so they stay distinct on a monochrome or inverted tray theme.
 * Colour is still not the only signal: ATTENTION also carries the badge dot in
 * the error red, the cue that reads at 16 px.
 */
object TrayIcons {

    private val COLORS: Map<TrayState, Color> = mapOf(
        TrayState.IDLE to Color(0x84, 0x8C, 0x87),       // stateIdle
        TrayState.WORKING to Color(0x7C, 0xB9, 0x8E),    // stateWorking, the moss accent
        TrayState.ATTENTION to Color(0xE4, 0xE2, 0xA8),  // stateAttention, the moss lit up
    )
    private val BADGE = Color(0xEE, 0x75, 0x7D)          // error

    /**
     * Rendered at 64px rather than 16: Compose scales the painter to whatever the
     * platform's tray asks for, and a 16px source scaled up on a HiDPI tray is a
     * blurred smear. Downscaling a clean render is free. 64 is also the tile's
     * own unit box, so the eye lands on whole pixels.
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
            RavenMark.drawTile(g, 0.0, 0.0, SIZE.toDouble(), color)
            if (state == TrayState.ATTENTION) {
                // The badge dot, r 9 at (53,11) of 64, over the tile's top-right
                // corner: the monochrome-safe cue, and the shape the attention
                // pulse pops in.
                g.color = BADGE
                g.fill(Ellipse2D.Double(44.0, 2.0, 18.0, 18.0))
            }
        } finally {
            g.dispose()
        }
        return img
    }
}
