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
 * viewBox): the raven upright, twelve straight edges on the brand's 24x16
 * lattice, the eye an evenodd hole, one hairline fold along the folded wing
 * spliced into the outline. Hand-carried into Java2D; if the canonical path
 * changes, change this with it — generate.sh's header lists every hand-carried
 * copy, and assets/brand/BRAND.md is the rule book.
 */
object RavenMark {

    /** Ink and tile from the brand palette (Moss & Heather, assets/brand/BRAND.md). */
    val INK: Color = Color(0x14, 0x16, 0x15)
    val BONE: Color = Color(0xE2, 0xE6, 0xE1)

    // The mark's bounding box inside the 108-unit brand viewBox (path frame).
    // Scaling works from these so the raven fills whatever box a caller gives
    // it, rather than inheriting the SVG canvas's empty margins.
    private const val MIN_X = 22.5
    private const val MIN_Y = 17.8
    private const val WIDTH = 54.0
    private const val HEIGHT = 51.0

    /**
     * One ring, clockwise from the bill tip, with the fold slit spliced in at
     * the nape; the eye is an evenodd hole so whatever sits behind the mark
     * shows through it. `withFold = false` gives the twelve-vertex solid the
     * brand uses at 32 px and below (the 2-unit slit antialiases to a smear).
     */
    private fun markPath(withEye: Boolean, withFold: Boolean): Path2D.Double {
        val p = Path2D.Double(Path2D.WIND_EVEN_ODD)
        p.moveTo(76.5, 28.0)      // bill tip
        p.lineTo(67.5, 24.6)      // culmen
        p.lineTo(54.0, 17.8)      // forehead
        p.lineTo(45.0, 17.8)      // crown
        p.lineTo(36.0, 21.2)      // back of head
        if (withFold) {
            p.lineTo(32.18, 26.972)
            p.lineTo(41.616, 51.925)
            p.lineTo(32.334, 65.952)
            p.lineTo(30.666, 64.848)
            p.lineTo(39.384, 51.675)
            p.lineTo(31.106, 29.786)
        } else {
            p.lineTo(31.5, 28.0)  // nape
        }
        p.lineTo(22.5, 68.8)      // tail tip
        p.lineTo(40.5, 68.8)      // tail bottom
        p.lineTo(54.0, 62.0)      // under-tail notch
        p.lineTo(58.5, 62.0)      // belly
        p.lineTo(63.0, 48.4)      // chest
        p.lineTo(63.0, 34.8)      // throat
        p.closePath()
        if (withEye) p.append(Ellipse2D.Double(48.55, 23.1, 6.4, 6.4), false)
        return p
    }

    /**
     * Draw the raven filling a [w]×[h] box at ([x],[y]), preserving aspect,
     * centred on the box's free axis. The fold is drawn when the box is 64 px
     * or taller, per the brand's size rule.
     */
    fun draw(g: Graphics2D, x: Double, y: Double, w: Double, h: Double, color: Color, withEye: Boolean = true) {
        val scale = minOf(w / WIDTH, h / HEIGHT)
        val tx = x + (w - WIDTH * scale) / 2 - MIN_X * scale
        val ty = y + (h - HEIGHT * scale) / 2 - MIN_Y * scale
        val old = g.transform
        g.translate(tx, ty)
        g.scale(scale, scale)
        g.color = color
        g.fill(markPath(withEye, withFold = HEIGHT * scale >= 64.0))
        g.transform = old
    }

    /**
     * The brand's small form: the raven cut out of a rounded tile
     * (`source/round3/fold-system/glyph-c.svg`, 64-unit box, corner
     * radius 14, the bird at 0.84 with its fold, the eye a 4x4 cell on whole
     * pixels so it is exactly one pixel at 16). One evenodd shape: fill it in
     * the state colour and the raven is the ground showing through.
     */
    fun tilePath(): Path2D.Double {
        val p = Path2D.Double(Path2D.WIND_EVEN_ODD)
        p.append(RoundRectangle2D.Double(0.0, 0.0, 64.0, 64.0, 28.0, 28.0), false)
        val bird = doubleArrayOf(
            54.79, 19.428, 47.23, 16.572, 35.89, 10.86, 28.33, 10.86, 20.77, 13.716, 17.79, 18.219,
            25.863, 39.567, 17.971, 51.493, 16.009, 50.195, 23.237, 39.273, 16.527, 21.528,
            9.43, 53.7, 24.55, 53.7, 35.89, 47.988, 39.67, 47.988, 43.45, 36.564, 43.45, 25.14,
        )
        p.moveTo(bird[0], bird[1])
        var i = 2
        while (i < bird.size) { p.lineTo(bird[i], bird[i + 1]); i += 2 }
        p.closePath()
        p.moveTo(32.0, 16.0); p.lineTo(36.0, 16.0); p.lineTo(36.0, 20.0); p.lineTo(32.0, 20.0); p.closePath()
        return p
    }

    /** Draw the tile filling a [size]-px square at ([x],[y]) in [color]. */
    fun drawTile(g: Graphics2D, x: Double, y: Double, size: Double, color: Color) {
        val old = g.transform
        g.translate(x, y)
        g.scale(size / 64.0, size / 64.0)
        g.color = color
        g.fill(tilePath())
        g.transform = old
    }

    private var cachedWindowIcon: Painter? = null

    /**
     * The window/taskbar icon: ink raven on a rounded bone tile, matching the
     * installed icons (huginn.ico / the .deb's png) in spirit: the bird at 1.4
     * about its box centre, a 15 % side inset. 256px because the platform
     * derives every smaller size by downscaling this one at runtime, and
     * downscaling a clean vector render stays clean.
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
            val inset = size * 0.15
            draw(g, inset, inset, size - inset * 2, size - inset * 2, INK, withEye = true)
        } finally {
            g.dispose()
        }
        img.toPainter().also { cachedWindowIcon = it }
    }
}
