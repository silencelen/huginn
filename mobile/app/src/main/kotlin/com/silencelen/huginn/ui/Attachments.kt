package com.silencelen.huginn.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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
 * Downscaled to [maxDim] on the long edge. A 200MP Samsung original is pixels
 * the model cannot use at that density anyway; 2048px keeps text on labels and
 * screens legible while cutting uploads to a few hundred KB.
 */
object Attachments {

    const val MIME = "image/jpeg"

    fun toJpeg(context: Context, uri: Uri, maxDim: Int = 2048, quality: Int = 85): ByteArray? {
        return runCatching {
            // Bounds pass. decodeStream RETURNS NULL HERE BY DESIGN — with
            // inJustDecodeBounds it fills `bounds` and decodes nothing. The first
            // release treated that null as "unreadable" and rejected every image
            // ever attached; the only failure signals in this pass are a stream
            // that will not open and dimensions that stayed unfilled.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (context.contentResolver.openInputStream(uri) ?: return null).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // The camera records orientation as EXIF rather than rotating pixels,
            // and a re-encode drops EXIF — so without applying it here, every
            // portrait photo arrives sideways and the model reads street signs
            // at 90°. Read before the pixel decode, from its own stream.
            val orientation = (context.contentResolver.openInputStream(uri) ?: return null).use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            }

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = (context.contentResolver.openInputStream(uri) ?: return null).use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            // Sampling only gets within a power of two; finish exactly.
            val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
            val sized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== decoded) decoded.recycle() }
            } else decoded

            val upright = applyOrientation(sized, orientation)

            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
            upright.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    /** Rotates/flips per the EXIF flag; returns the input unchanged when upright. */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * The line appended to the message so Claude knows there is something to look
     * at and how. Phrased as a bracketed system-ish note rather than prose in the
     * user's voice: the path is plumbing, not something the owner "said".
     */
    fun marker(path: String): String =
        "[Attached image at $path — view it with the Read tool.]"

    /** The same, for a non-image file; the name travels for context. */
    fun fileMarker(path: String, name: String?, readable: Boolean = true): String {
        val where = "$path${if (name.isNullOrBlank()) "" else " ($name)"}"
        // Telling Claude to Read a binary is exactly how the old upload refusal
        // justified itself: it comes back as mojibake and the answer is a shrug.
        // Naming the right tool instead is what makes accepting the file safe.
        return if (readable) "[Attached file at $where — view it with the Read tool.]"
        else "[Attached file at $where — a binary; inspect it with shell tools " +
            "(file, unzip, strings, sqlite3) rather than Read. Requires act mode.]"
    }

    /**
     * The same marker, made fit for human eyes. The bracketed path is plumbing
     * for Claude; a person reading their own message back should see that they
     * sent a photo, not where the daemon happened to store it.
     */
    private val MARKER_RE = Regex("""\[Attached image at [^\]]+ — view it with the Read tool\.\]""")
    private val FILE_RE = Regex("""\[Attached file at \S+( \(([^)]{1,80})\))? — [^\]]*\]""")

    fun displayText(text: String): String {
        if ('[' !in text) return text
        val cleaned = text
            .replace(MARKER_RE, "📷 Photo attached")
            .replace(FILE_RE) { m -> "📎 " + (m.groupValues[2].ifBlank { "File attached" }) }
            .trim()
        return cleaned.ifBlank { "📷 Photo attached" }
    }
}
