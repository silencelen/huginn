package com.silencelen.huginn.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The phone's decoder. A bounds pass + inSampleSize first: the stored uploads
 * are ≤2048px JPEGs, which decode to ~16MB of ARGB — downsampling to the
 * thumbnail's real ceiling before decoding caps a cache entry at about a quarter
 * of that. Remember the trap recorded in Attachments.kt: in inJustDecodeBounds
 * mode decodeByteArray returns null BY DESIGN; success is the filled dimensions.
 */
class AndroidImageBytesDecoder(
    private val maxDim: Int = MAX_DIM,
) : ImageBytesDecoder {

    override fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()

    companion object {
        const val MAX_DIM: Int = 1280
    }
}
