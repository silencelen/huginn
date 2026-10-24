package com.silencelen.huginn.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

/**
 * The desktop's decoder: skia directly — the layer this module already reaches
 * for the terminal blit ([SkiaCellPainter]), so no new dependency. Skia decodes
 * jpeg/png/webp/gif where javax.imageio would need plugins, and decoding is CPU
 * raster: indifferent to whether the window is on a real GPU or the software
 * renderer the audit harness runs under.
 *
 * ⚠ THE HEADER IS READ BEFORE A SINGLE PIXEL IS. Decoding allocates width ×
 * height × 4 bytes for whatever size the file DECLARES, and these bytes are not
 * only the owner's own photos: an answer that names an image path draws it,
 * fetched and decoded with nobody clicking anything. A few hundred bytes of PNG
 * or WebP can declare 16,383 × 16,383 — a gigabyte as RGBA, per thumbnail, four
 * thumbnails to a message — in native memory, where the JVM's collector feels
 * no pressure to free it, and again every time the transcript is opened. So
 * skia's [Codec] reads the dimensions alone first, and anything past
 * [maxPixels] is refused like any other file that will not decode: the
 * placeholder, carrying the path. The phone's decoder has no such hole; it
 * samples down to the thumbnail before it allocates.
 *
 * @param maxPixels the largest width × height decoded; [MAX_PIXELS] unless a
 *   test needs a smaller one.
 */
class SkiaImageBytesDecoder(
    private val maxPixels: Long = MAX_PIXELS,
) : ImageBytesDecoder {

    override fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val pixels = declaredPixels(bytes) ?: return null
        if (pixels !in 1..maxPixels) return null
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

    companion object {
        /**
         * 50 megapixels (50 × 2²⁰), 200 MiB as RGBA: room for what an answer
         * really shows, and a hard stop for what it should not.
         *
         * An 8K screenshot is 33 MP, a full-page capture of a long web page at
         * 2× (2560 × 16,000) is 41 MP, and a phone's full-resolution photo from
         * a 48 or 50 MP sensor is 48–50.3 MP (8192 × 6144) — which is why this is
         * 50 × 2²⁰ and not fifty million. A 64 MP or larger full-resolution
         * photo draws the placeholder instead: the one kind of real picture
         * given up.
         * The apps' own uploads never come near it: each scales a picture to
         * 2048 px on the long edge before sending it.
         */
        const val MAX_PIXELS: Long = 50L * 1024 * 1024

        /**
         * width × height as the file's header declares it, or null when skia has
         * no codec for the bytes. Reads the header and nothing more: no pixel
         * buffer exists until the decode that this guards.
         */
        internal fun declaredPixels(bytes: ByteArray): Long? {
            if (bytes.isEmpty()) return null
            return Data.makeFromBytes(bytes).use { data ->
                runCatching { Codec.makeFromData(data) }.getOrNull()?.use { codec ->
                    codec.width.toLong() * codec.height.toLong()
                }
            }
        }
    }
}
