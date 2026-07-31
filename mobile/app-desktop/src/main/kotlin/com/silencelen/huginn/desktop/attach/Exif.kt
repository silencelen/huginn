package com.silencelen.huginn.desktop.attach

/**
 * The one EXIF tag that changes what the model sees: orientation.
 *
 * A camera records which way up it was held rather than rotating the pixels, and
 * ImageIO's JPEG reader does not apply that flag — unlike a browser's
 * `createImageBitmap`, which the Electron client got for free and this client
 * does not. Without this, every portrait photo dragged in from a phone arrives
 * sideways and Claude reads the text in it at 90°.
 *
 * Deliberately a 60-line tag reader, not an EXIF library: one tag, from one IFD,
 * out of the first APP1 segment. Anything it cannot parse returns [NORMAL], which
 * is what an image with no EXIF at all should do anyway.
 */
object Exif {

    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    private const val ORIENTATION_TAG = 0x0112

    /**
     * Reads the orientation flag out of JPEG [bytes], or [NORMAL] when there is
     * none, the file is not a JPEG, or anything about the structure disagrees with
     * itself. A malformed image must degrade to "upright", never throw: this runs
     * on whatever a drag-and-drop happened to carry.
     */
    fun orientation(bytes: ByteArray): Int = runCatching { parse(bytes) }.getOrDefault(NORMAL)

    private fun parse(b: ByteArray): Int {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return NORMAL
        var i = 2
        // Walk the segment chain looking for APP1. Stop at SOS (0xDA): image data
        // follows and is not segment-structured, so scanning past it would read
        // compressed pixels as lengths.
        while (i + 4 <= b.size) {
            if (u8(b, i) != 0xFF) return NORMAL
            val marker = u8(b, i + 1)
            if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) { i += 2; continue }
            if (marker == 0xDA || marker == 0xD9) return NORMAL
            val len = (u8(b, i + 2) shl 8) or u8(b, i + 3)
            if (len < 2 || i + 2 + len > b.size) return NORMAL
            if (marker == 0xE1) {
                val payload = i + 4
                // "Exif\0\0" — an APP1 can also be XMP, which is not this.
                if (payload + 6 <= b.size &&
                    u8(b, payload) == 0x45 && u8(b, payload + 1) == 0x78 &&
                    u8(b, payload + 2) == 0x69 && u8(b, payload + 3) == 0x66 &&
                    u8(b, payload + 4) == 0x00
                ) {
                    val found = readTiff(b, payload + 6)
                    if (found != null) return found
                }
            }
            i += 2 + len
        }
        return NORMAL
    }

    /** @param tiff offset of the TIFF header ("II"/"MM", 0x2A, IFD0 offset). */
    private fun readTiff(b: ByteArray, tiff: Int): Int? {
        if (tiff + 8 > b.size) return null
        val little = when {
            u8(b, tiff) == 0x49 && u8(b, tiff + 1) == 0x49 -> true
            u8(b, tiff) == 0x4D && u8(b, tiff + 1) == 0x4D -> false
            else -> return null
        }
        if (u16(b, tiff + 2, little) != 42) return null
        val ifd = tiff + u32(b, tiff + 4, little)
        if (ifd + 2 > b.size || ifd < tiff) return null
        val count = u16(b, ifd, little)
        for (e in 0 until count) {
            val entry = ifd + 2 + e * 12
            if (entry + 12 > b.size) return null
            if (u16(b, entry, little) != ORIENTATION_TAG) continue
            // Type 3 (SHORT): the value sits in the first two bytes of the
            // four-byte value field, at whichever end this file's byte order puts
            // it. Reading it as a 32-bit int gives 6 on one machine and 393216 on
            // the other, and the wrong one is silently "upright".
            val v = u16(b, entry + 8, little)
            // 1..8, and the upper bound is ROTATE_270 — NOT TRANSVERSE. Bounding it
            // at 7 (which reads as "the last one listed") silently rejected the one
            // value a landscape-left photo carries, and the fallback is "upright".
            return if (v in NORMAL..ROTATE_270) v else null
        }
        return null
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int, little: Boolean): Int =
        if (little) u8(b, i) or (u8(b, i + 1) shl 8) else (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int, little: Boolean): Int =
        if (little) u16(b, i, true) or (u16(b, i + 2, true) shl 16)
        else (u16(b, i, false) shl 16) or u16(b, i + 2, false)

    /** Whether applying [orientation] swaps the image's width and height. */
    fun swapsAxes(orientation: Int): Boolean =
        orientation == TRANSPOSE || orientation == ROTATE_90 ||
            orientation == TRANSVERSE || orientation == ROTATE_270
}
