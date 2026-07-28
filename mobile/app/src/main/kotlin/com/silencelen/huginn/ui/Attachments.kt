package com.silencelen.huginn.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Turns whatever the picker or the share sheet handed over into JPEG bytes that
 * Claude's Read tool can actually open.
 *
 * Transcoding is not optional politeness. This phone's camera shoots HEIC by
 * default, Read cannot open HEIC, and the failure without this step is the worst
 * kind: the upload succeeds, the chat runs, and the answer is a shrug about an
 * unreadable file. Decoding and re-encoding here means the daemon only ever sees
 * formats that work.
 *
 * Downscaled to [maxDim] on the long edge first. A 200MP Samsung original is
 * ~40MB of pixels the model cannot use at that density anyway; 2048px keeps text
 * on labels and screens legible while cutting uploads to a few hundred KB.
 */
object Attachments {

    const val MIME = "image/jpeg"

    fun toJpeg(context: Context, uri: Uri, maxDim: Int = 2048, quality: Int = 85): ByteArray? {
        return runCatching {
            // Bounds first, so a huge original is sampled down during decode
            // rather than materialised at full size and then shrunk.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // Sampling only gets within a power of two; finish exactly.
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val sized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== bitmap) bitmap.recycle() }
            } else bitmap

            val out = ByteArrayOutputStream()
            sized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            sized.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    /**
     * The line appended to the message so Claude knows there is something to look
     * at and how. Phrased as a bracketed system-ish note rather than prose in the
     * user's voice: the path is plumbing, not something the owner "said".
     */
    fun marker(path: String): String =
        "[Attached image at $path — view it with the Read tool.]"
}
