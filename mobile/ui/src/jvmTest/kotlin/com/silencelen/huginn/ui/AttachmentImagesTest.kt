package com.silencelen.huginn.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class AttachmentImagesTest {

    /** A real encoded PNG of the given size, so the skia decoder has something valid to chew. */
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL))
        bmp.erase(0xFF3366CC.toInt())
        return Image.makeFromBitmap(bmp).encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @Test
    fun `the skia decoder decodes a real PNG to the right dimensions`() {
        val bmp = SkiaImageBytesDecoder().decode(pngBytes(12, 7))
        assertNotNull(bmp)
        assertEquals(12, bmp.width)
        assertEquals(7, bmp.height)
    }

    @Test
    fun `garbage bytes decode to null, never a throw`() {
        assertNull(SkiaImageBytesDecoder().decode(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(SkiaImageBytesDecoder().decode(ByteArray(0)))
    }

    @Test
    fun `the loader fetches once across two loads of the same path (cache hit)`() = runBlocking {
        var fetches = 0
        val png = pngBytes(4, 4)
        val loader = AttachmentImageLoader(
            fetch = { fetches++; png },
            decoder = SkiaImageBytesDecoder(),
        )
        val a = loader.load("/uploads/up-1-ab.png")
        val b = loader.load("/uploads/up-1-ab.png")
        assertNotNull(a)
        assertTrue(a === b, "the second load returns the cached bitmap")
        assertEquals(1, fetches)
    }

    @Test
    fun `a fetch failure is cached as a miss, not retried every recomposition`() = runBlocking {
        var fetches = 0
        val loader = AttachmentImageLoader(
            fetch = { fetches++; throw RuntimeException("404") },
            decoder = SkiaImageBytesDecoder(),
        )
        assertNull(loader.load("/uploads/gone.png"))
        assertNull(loader.load("/uploads/gone.png"))
        assertEquals(1, fetches, "the miss is remembered")
    }

    @Test
    fun `two concurrent loads of the same path share one fetch`() = runBlocking {
        var fetches = 0
        val png = pngBytes(4, 4)
        val loader = AttachmentImageLoader(
            fetch = { fetches++; png },
            decoder = SkiaImageBytesDecoder(),
        )
        val one = async { loader.load("/uploads/same.png") }
        val two = async { loader.load("/uploads/same.png") }
        assertNotNull(one.await())
        assertNotNull(two.await())
        assertEquals(1, fetches)
    }

    @Test
    fun `a path with no basename does not fetch`() = runBlocking {
        var fetches = 0
        val loader = AttachmentImageLoader({ fetches++; ByteArray(0) }, SkiaImageBytesDecoder())
        assertNull(loader.load("/uploads/"))
        assertEquals(0, fetches)
    }
}
