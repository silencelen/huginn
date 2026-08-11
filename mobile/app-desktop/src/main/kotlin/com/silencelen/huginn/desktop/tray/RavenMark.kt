package com.silencelen.huginn.desktop.tray

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPainter
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * The Huginn raven, drawn rather than shipped — same reasoning as the tray dots
 * this replaces: a generated mark cannot go missing from a jar, a `.deb` or a
 * jpackage app-image, and an invisible tray on a close-to-tray app is an app the
 * owner cannot get back.
 *
 * The geometry is the brand mark from `assets/brand/raven.svg` (108-unit
 * viewBox), hand-carried into Java2D. If the canonical path changes, change
 * this with it — generate.sh's header lists every hand-carried copy.
 */
object RavenMark {

    /** Ink and tile from the brand palette (assets/brand/). */
    val INK: Color = Color(0x16, 0x13, 0x10)
    val BONE: Color = Color(0xE8, 0xE2, 0xDA)

    // The mark's bounding box inside the 108-unit brand viewBox. Scaling works
    // from these so the raven fills whatever box a caller gives it, rather than
    // inheriting the SVG canvas's empty margins.
    private const val MIN_X = 10.0
    private const val MIN_Y = 17.0
    private const val WIDTH = 84.6
    private const val HEIGHT = 52.0

    /**
     * One subpath, clockwise from the bill tip; the eye is an evenodd hole so
     * whatever sits behind the mark shows through it. `withEye = false` for
     * sizes where a 2-unit hole is sub-pixel noise (the tray).
     */
    private fun markPath(withEye: Boolean): Path2D.Double {
        val p = Path2D.Double(Path2D.WIND_EVEN_ODD)
        p.moveTo(94.0, 32.0)
        p.curveTo(87.0, 27.5, 80.0, 25.8, 74.0, 25.5)
        p.curveTo(72.5, 19.5, 64.0, 17.0, 58.5, 19.3)
        p.curveTo(54.0, 21.3, 51.0, 24.0, 48.0, 27.0)
        p.curveTo(41.0, 32.5, 35.0, 37.5, 30.0, 42.0)
        p.lineTo(10.0, 60.0)
        p.lineTo(16.0, 69.0)
        p.lineTo(37.0, 54.0)
        p.curveTo(40.0, 58.5, 45.0, 61.5, 51.0, 62.5)
        p.curveTo(58.0, 63.3, 64.0, 60.0, 67.0, 55.5)
        p.curveTo(70.3, 50.3, 71.8, 45.2, 70.0, 41.0)
        p.lineTo(67.8, 40.2)
        p.lineTo(71.3, 37.8)
        p.lineTo(68.8, 36.2)
        p.lineTo(72.0, 34.8)
        p.curveTo(79.0, 34.6, 86.0, 34.2, 93.0, 33.6)
        p.curveTo(94.6, 33.0, 94.6, 32.5, 94.0, 32.0)
        p.closePath()
        if (withEye) p.append(Ellipse2D.Double(62.5, 23.5, 4.0, 4.0), false)
        return p
    }

    /**
     * Draw the raven filling a [w]×[h] box at ([x],[y]), preserving aspect,
     * centred on the box's free axis.
     */
    fun draw(g: Graphics2D, x: Double, y: Double, w: Double, h: Double, color: Color, withEye: Boolean = false) {
        val scale = minOf(w / WIDTH, h / HEIGHT)
        val tx = x + (w - WIDTH * scale) / 2 - MIN_X * scale
        val ty = y + (h - HEIGHT * scale) / 2 - MIN_Y * scale
        val old = g.transform
        g.translate(tx, ty)
        g.scale(scale, scale)
        g.color = color
        g.fill(markPath(withEye))
        g.transform = old
    }

    private var cachedWindowIcon: Painter? = null

    /**
     * The window/taskbar icon: ink raven on a rounded bone tile, matching the
     * installed icons (huginn.ico / the .deb's png) pixel-for-pixel in spirit.
     * 256px because the platform derives every smaller size by downscaling this
     * one at runtime, and downscaling a clean vector render stays clean.
     */
    fun windowIcon(): Painter = cachedWindowIcon ?: run {
        val size = 256
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = BONE
            // The corner radius Windows' own tiles use, near enough: ~12%.
            g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), size * 0.24, size * 0.24))
            val inset = size * 0.10
            draw(g, inset, inset, size - inset * 2, size - inset * 2, INK, withEye = true)
        } finally {
            g.dispose()
        }
        img.toPainter().also { cachedWindowIcon = it }
    }
}
