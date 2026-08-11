package com.silencelen.huginn.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/**
 * The desktop's decoder: skia directly — the layer this module already reaches
 * for the terminal blit ([SkiaCellPainter]), so no new dependency. Skia decodes
 * jpeg/png/webp/gif where javax.imageio would need plugins, and decoding is CPU
 * raster: indifferent to whether the window is on a real GPU or the software
 * renderer the audit harness runs under.
 */
class SkiaImageBytesDecoder : ImageBytesDecoder {
    override fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
