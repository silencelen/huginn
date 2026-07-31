package com.silencelen.huginn.desktop.attach

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How big an attached image should be by the time it leaves this machine.
 *
 * Pure, and separated from the pixels on purpose: this is the one part of the
 * intake path with an arithmetic answer that can be asserted without a decoder,
 * a display or a file. Everything around it (ImageIO, alpha flattening, EXIF)
 * is plumbing.
 *
 * The cap is about MODEL UTILITY, not bandwidth. A 12MP screenshot is pixels
 * Claude cannot use — it costs tokens for detail that is thrown away before the
 * model sees it — while 2048px on the long edge keeps text on labels, terminals
 * and UI screenshots legible. The same number the phone uses, and the same one
 * the Electron client's canvas path used, so all three clients hand the daemon
 * comparable images.
 */
object ImageScale {

    /** Long-edge cap in pixels. */
    const val LONG_EDGE: Int = 2048

    /** JPEG quality. 0.85 is where re-encode artifacts stop being visible on text. */
    const val QUALITY: Float = 0.85f

    data class Size(val width: Int, val height: Int)

    /**
     * The size [width]x[height] should be re-encoded at.
     *
     * NEVER upscales — a 300px avatar handed a 2048 cap comes back 300px. Growing
     * a small image would cost real bytes for invented detail, which is the exact
     * opposite of what the cap is for.
     *
     * Both edges are kept at least 1: a 4000x1 panorama scaled by the long edge
     * rounds its short edge to zero, and a zero-height BufferedImage throws from
     * the constructor rather than anywhere near here.
     */
    fun fit(width: Int, height: Int, longEdge: Int = LONG_EDGE): Size {
        val w = max(1, width)
        val h = max(1, height)
        val cap = max(1, longEdge)
        val longest = max(w, h)
        if (longest <= cap) return Size(w, h)
        val scale = cap.toDouble() / longest.toDouble()
        return Size(
            width = max(1, (w * scale).roundToInt()),
            height = max(1, (h * scale).roundToInt()),
        )
    }

    /**
     * The name the transcoded file should carry. The bytes are JPEG whatever
     * arrived, so a `.png` name on them is a small lie that shows up later in the
     * chip, in the daemon's stored name and in Claude's own description of what it
     * was handed.
     */
    fun jpegName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
        return base.replace(Regex("""\.[A-Za-z0-9]{1,5}$"""), "") + ".jpg"
    }
}
