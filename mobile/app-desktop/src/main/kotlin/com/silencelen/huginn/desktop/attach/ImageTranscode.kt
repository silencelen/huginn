package com.silencelen.huginn.desktop.attach

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Whatever was pasted, dropped or picked, as a JPEG the daemon and Claude can
 * both open — capped at [ImageScale.LONG_EDGE], quality [ImageScale.QUALITY].
 *
 * Skia is not used here even though the app renders with it: `ImageIO` is already
 * on the classpath (it is in `java.desktop`, which the jlink runtime image
 * carries for AWT's sake), it decodes every format ImageIO has a reader for, and
 * it writes JPEG with an explicit quality parameter, which is precisely the knob
 * this needs. Skia's encoder would be a second image stack for no gain.
 */
object ImageTranscode {

    const val MIME: String = "image/jpeg"

    /** Whether ImageIO believes it can decode this at all. Cheap: reads the header only. */
    fun canDecode(file: File): Boolean = runCatching {
        file.inputStream().buffered().use { s ->
            ImageIO.createImageInputStream(s)?.use { ImageIO.getImageReaders(it).hasNext() } ?: false
        }
    }.getOrDefault(false)

    /**
     * Reads [file], applies its EXIF orientation, downscales and re-encodes.
     * Null when there is no reader for the format — HEIC is the one that matters,
     * and a null here is what turns into an honest "could not read that image"
     * chip rather than an upload of bytes Claude cannot open.
     */
    fun fromFile(file: File): ByteArray? = runCatching {
        val bytes = file.readBytes()
        fromBytes(bytes)
    }.getOrNull()

    /** The same, for bytes already in hand — a clipboard image, say. */
    fun fromBytes(bytes: ByteArray): ByteArray? = runCatching {
        val decoded = ImageIO.read(bytes.inputStream()) ?: return null
        encode(decoded, Exif.orientation(bytes))
    }.getOrNull()

    /** For an image the clipboard handed over already decoded, with no file behind it. */
    fun fromImage(image: BufferedImage): ByteArray? = runCatching { encode(image, Exif.NORMAL) }.getOrNull()

    private fun encode(source: BufferedImage, orientation: Int): ByteArray {
        val upright = applyOrientation(source, orientation)
        val target = ImageScale.fit(upright.width, upright.height)
        val scaled = scaleTo(upright, target.width, target.height)
        // ALPHA MUST NOT REACH THE WRITER. Both paths above can hand back the source
        // untouched — an already-small clipboard PNG needs no rotation and no scale —
        // and the source is very often ARGB, which the JPEG plugin rejects from deep
        // inside with a message about component counts. One redraw makes the
        // guarantee unconditional rather than a property of which branch ran.
        val flat = if (scaled.type == BufferedImage.TYPE_INT_RGB) scaled
        else draw(scaled, scaled.width, scaled.height)
        return toJpegBytes(flat)
    }

    /**
     * Progressive halving, then one final draw at the exact size.
     *
     * A single bilinear draw from 4000px to 2048px samples four source pixels per
     * destination pixel and throws the rest away, which turns small text — the
     * whole reason a screenshot is worth attaching — into grey mush. Halving keeps
     * every source pixel contributing. This is the standard recipe and the reason
     * `getScaledInstance(SCALE_SMOOTH)` is not used instead: that one is correct
     * and roughly an order of magnitude slower.
     */
    private fun scaleTo(src: BufferedImage, width: Int, height: Int): BufferedImage {
        var current = src
        var w = src.width
        var h = src.height
        while (w / 2 >= width && h / 2 >= height && w / 2 >= 1 && h / 2 >= 1) {
            w /= 2
            h /= 2
            current = draw(current, w, h)
        }
        return if (w == width && h == height) current else draw(current, width, height)
    }

    private fun draw(src: BufferedImage, w: Int, h: Int): BufferedImage {
        // TYPE_INT_RGB, always: the JPEG writer cannot encode an alpha channel, and
        // handing it an ARGB raster throws deep inside the plugin with a message
        // about component counts. Flattening HERE also decides what transparency
        // becomes, rather than leaving it to whatever the encoder defaults to.
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            // WHITE, not black. A transparent PNG is nearly always dark ink meant to
            // sit on a light page — a cropped diagram, an exported logo, a screenshot
            // with rounded corners. Compositing those onto black is how an attachment
            // arrives as a black rectangle and the answer is a shrug.
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** Rotates/flips per the EXIF flag; returns the input untouched when upright. */
    private fun applyOrientation(src: BufferedImage, orientation: Int): BufferedImage {
        if (orientation == Exif.NORMAL) return src
        val w = src.width
        val h = src.height
        val swap = Exif.swapsAxes(orientation)
        val outW = if (swap) h else w
        val outH = if (swap) w else h
        val t = AffineTransform()
        when (orientation) {
            Exif.FLIP_HORIZONTAL -> { t.scale(-1.0, 1.0); t.translate(-w.toDouble(), 0.0) }
            Exif.ROTATE_180 -> { t.translate(w.toDouble(), h.toDouble()); t.rotate(Math.PI) }
            Exif.FLIP_VERTICAL -> { t.scale(1.0, -1.0); t.translate(0.0, -h.toDouble()) }
            Exif.TRANSPOSE -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0) }
            Exif.ROTATE_90 -> { t.translate(h.toDouble(), 0.0); t.rotate(Math.PI / 2) }
            Exif.TRANSVERSE -> { t.translate(h.toDouble(), -w.toDouble()); t.rotate(Math.PI / 2); t.scale(-1.0, 1.0) }
            Exif.ROTATE_270 -> { t.translate(0.0, w.toDouble()); t.rotate(-Math.PI / 2) }
            else -> return src
        }
        val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, outW, outH)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, t, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun toJpegBytes(image: BufferedImage): ByteArray {
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        require(writers.hasNext()) { "no JPEG writer in this runtime image" }
        val writer = writers.next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val param = writer.defaultWriteParam
                if (param.canWriteCompressed()) {
                    param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    param.compressionQuality = ImageScale.QUALITY
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }
}
