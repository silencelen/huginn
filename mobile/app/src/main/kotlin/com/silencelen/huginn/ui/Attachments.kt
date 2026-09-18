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

    // The marker text and its human-readable inverse live in :core
    // (AttachmentText): they must agree exactly with each other, and every client
    // that can attach a file needs both. Delegated rather than re-exported so the
    // call sites in this module did not have to move with them.
    fun marker(path: String): String = AttachmentText.marker(path)

    fun fileMarker(path: String, name: String?, readable: Boolean = true): String =
        AttachmentText.fileMarker(path, name, readable)
}

// ------------------------------------------------------- the clipboard path

/**
 * An image taken off the clipboard, already through [Attachments.toJpeg] and
 * ready for the ordinary upload path.
 */
class ClipboardImage(val name: String, val jpeg: ByteArray)

/**
 * Reading an image off the system clipboard.
 *
 * An interface with one method so the RULE around it ([pastePlan]) can be
 * asserted on a host with no Android framework — `ClipboardManager` is one of the
 * classes the unit-test android.jar throws from on first call, so the only
 * testable shape is one where the framework sits behind a seam.
 */
fun interface ImageClipboard {
    /** The first image on the clipboard, transcoded; null when there is none. */
    fun takeImage(): ClipboardImage?
}

/** The real one. Uses the same transcode as the picker, the camera and a share. */
class AndroidImageClipboard(private val context: Context) : ImageClipboard {
    override fun takeImage(): ClipboardImage? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return null
        val clip = cm.primaryClip ?: return null
        val cr = context.contentResolver
        for (i in 0 until clip.itemCount) {
            // A URI, because that is the only way an image reaches an Android
            // clipboard: ClipData carries text, intents and URIs — never a
            // Bitmap. A screenshot pasted from another app arrives as a
            // content:// URI its provider will open for us.
            val uri = runCatching { clip.getItemAt(i).uri }.getOrNull() ?: continue
            val type = runCatching { cr.getType(uri) }.getOrNull()
            if (type != null && !type.startsWith("image/")) continue
            val jpeg = Attachments.toJpeg(context, uri) ?: continue
            val base = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            return ClipboardImage(name = jpegName(base ?: "pasted"), jpeg = jpeg)
        }
        return null
    }

    /** The transcoded name says jpg, because the bytes are JPEG whatever came in. */
    private fun jpegName(raw: String): String {
        val stem = raw.substringAfterLast('/').substringBeforeLast('.').ifBlank { "pasted" }
        return "$stem.jpg"
    }
}

/** What a paste should do. */
sealed interface PasteOutcome {
    /** Go: these bytes, under this name. */
    data class Attach(val name: String, val jpeg: ByteArray) : PasteOutcome
    /** Stop, and say this — every refusal here is one a person can act on. */
    data class Refused(val why: String) : PasteOutcome
}

/**
 * The paste rule: what the clipboard offered, against how full the composer is.
 *
 * Pure, and separate from the reading, because the two failures it distinguishes
 * are the ones people report — "I copied a picture and nothing happened" is
 * either an empty clipboard or a full composer, and a paste that silently does
 * neither is indistinguishable from a broken button.
 */
fun pastePlan(clip: ClipboardImage?, pending: Int): PasteOutcome = when {
    clip == null -> PasteOutcome.Refused("No image on the clipboard")
    AttachBatch.room(pending) <= 0 ->
        PasteOutcome.Refused(AttachBatch.refusedNote(pending, 1) ?: "That is enough attachments")
    else -> PasteOutcome.Attach(clip.name, clip.jpeg)
}
