package com.silencelen.huginn.ui

import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.HuginnClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ------------------------------------------------- what a header declares

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(name); update(data) }
        return ByteBuffer.allocate(12 + data.size)
            .putInt(data.size).put(name).put(data).putInt(crc.value.toInt()).array()
    }

    /** A PNG header for [w] × [h] grey at [bits] per pixel, and the signature before it. */
    private fun pngHead(w: Int, h: Int, bits: Int = 8): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(bits.toByte()).put(0).put(0).put(0).put(0).array())

    private fun deflated(rowBytes: Int, rows: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { z ->
            val row = ByteArray(rowBytes)
            repeat(rows) { z.write(row) }
        }
        return out.toByteArray()
    }

    /**
     * A complete, valid, black PNG of [w] × [h] at one bit per pixel. Every row
     * is the same zero bytes, which deflate folds away: a few kilobytes on the
     * wire, whatever it says it is.
     */
    private fun bilevelPng(w: Int, h: Int): ByteArray =
        pngHead(w, h, bits = 1) + chunk("IDAT", deflated(1 + (w + 7) / 8, h)) + chunk("IEND", ByteArray(0))

    /**
     * ⚠⚠ THE DEFECT, AS A FILE. An answer that names an image path has it fetched
     * and decoded with nobody clicking anything, and the decode allocated width ×
     * height × 4 bytes for whatever the header said. This one is about 13 KB
     * and says 10,500 × 10,500: 110 MP, 441 MB of pixels the old decoder
     * produced without a murmur. Past the budget it is refused before anything
     * is allocated, the same null as bytes that do not decode.
     */
    @Test
    fun `a small file declaring more pixels than the budget is refused, not decoded`() {
        val bomb = bilevelPng(10_500, 10_500)
        assertTrue(bomb.size < 16 * 1024, "the file is ${bomb.size} bytes")
        assertNull(SkiaImageBytesDecoder().decode(bomb))
    }

    @Test
    fun `the dimensions come from the header alone`() {
        // No image data worth the name: a header for the 16,383 × 16,383 a few
        // hundred bytes of WebP or PNG can declare (a gigabyte as RGBA) and one
        // row of pixels. The size is read without decoding any of it.
        val head = pngHead(16_383, 16_383) + chunk("IDAT", deflated(1 + 16_383, 1)) + chunk("IEND", ByteArray(0))
        assertTrue(head.size < 1024, "the file is ${head.size} bytes")
        assertEquals(16_383L * 16_383L, SkiaImageBytesDecoder.declaredPixels(head))
        assertEquals(56_250_000L, SkiaImageBytesDecoder.declaredPixels(bilevelPng(7500, 7500)))
        assertNull(SkiaImageBytesDecoder.declaredPixels(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(110_250_000L, SkiaImageBytesDecoder.declaredPixels(bilevelPng(10_500, 10_500)))
        assertTrue(110_250_000L > SkiaImageBytesDecoder.MAX_PIXELS, "the bomb above must be over the budget")
        assertTrue(11_648L * 8_736L <= SkiaImageBytesDecoder.MAX_PIXELS, "a 100 MP camera's photo must fit")
    }

    @Test
    fun `the budget is width times height, inclusive`() {
        val decoder = SkiaImageBytesDecoder(maxPixels = 84)
        assertNotNull(decoder.decode(pngBytes(12, 7)), "84 pixels is within a budget of 84")
        assertNotNull(decoder.decode(pngBytes(7, 12)), "and so is the same image on its side")
        assertNull(decoder.decode(pngBytes(12, 8)), "96 is not")
        assertNull(decoder.decode(pngBytes(85, 1)), "nor is one row of 85")
    }

    private fun awtEncoded(format: String, w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(img, format, out), "this JDK has no $format writer")
        return out.toByteArray()
    }

    /** An icon file holding one PNG, as sites serve favicons. */
    private fun icoOf(png: ByteArray, w: Int, h: Int): ByteArray =
        ByteBuffer.allocate(22 + png.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(1).putShort(1)
            .put(w.toByte()).put(h.toByte()).put(0).put(0).putShort(1).putShort(32).putInt(png.size).putInt(22)
            .put(png).array()

    /**
     * The header read must not refuse a format the old decoder drew: every
     * format a transcript or an app row shows — photos, screenshots, plots,
     * favicons — still decodes, at its own size.
     */
    @Test
    fun `every format the transcript draws still decodes under the guard`() {
        val skia = Image.makeFromBitmap(Bitmap().apply {
            allocPixels(ImageInfo(10, 6, ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL))
            erase(0xFF3366CC.toInt())
        })
        val files = mapOf(
            "png" to pngBytes(10, 6),
            "jpeg" to skia.encodeToData(EncodedImageFormat.JPEG)!!.bytes,
            "webp" to skia.encodeToData(EncodedImageFormat.WEBP)!!.bytes,
            "gif" to awtEncoded("gif", 10, 6),
            "bmp" to awtEncoded("bmp", 10, 6),
            "ico" to icoOf(pngBytes(10, 6), 10, 6),
        )
        for ((format, bytes) in files) {
            val bmp = SkiaImageBytesDecoder().decode(bytes)
            assertNotNull(bmp, "$format no longer decodes")
            assertEquals(10 to 6, bmp.width to bmp.height, format)
        }
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
            fetch = { fetches++; throw HuginnClient.HuginnException(404, "gone") },
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

    // ------------------------------------------ loadPath: assistant image paths

    /**
     * ⚠ THE WHOLE REASON `loadPath` IS NOT `load`. `load()` keys its cache on the
     * BASENAME, because an uploads path is `/uploads/<server-minted-name>` and
     * the name is unique by construction. An assistant's path is not: every
     * session writes `screenshot.png` into its own directory, and a basename key
     * would serve the first one to every other one — the wrong picture, cached,
     * with nothing in the logs.
     */
    @Test
    fun `two different directories sharing a basename are two different images`() = runBlocking {
        val seen = mutableListOf<String>()
        val loader = AttachmentImageLoader(
            fetch = { error("not the uploads route") },
            decoder = SkiaImageBytesDecoder(),
            fetchPath = { path, _ -> seen += path; pngBytes(if (path.startsWith("/a/")) 4 else 8, 4) },
        )
        val a = loader.loadPath("/a/screenshot.png")
        val b = loader.loadPath("/b/screenshot.png")
        assertEquals(listOf("/a/screenshot.png", "/b/screenshot.png"), seen)
        assertEquals(4, a?.width)
        assertEquals(8, b?.width, "the second directory got its own image, not the first one's")
    }

    @Test
    fun `loadPath still caches, dedupes and remembers a miss, keyed on the full path`() = runBlocking {
        var fetches = 0
        val png = pngBytes(4, 4)
        val loader = AttachmentImageLoader(
            fetch = { error("not the uploads route") },
            decoder = SkiaImageBytesDecoder(),
            fetchPath = { _, _ -> fetches++; png },
        )
        val one = async { loader.loadPath("/tmp/a.png") }
        val two = async { loader.loadPath("/tmp/a.png") }
        assertNotNull(one.await())
        assertNotNull(two.await())
        assertNotNull(loader.loadPath("/tmp/a.png"))
        assertEquals(1, fetches, "one fetch across a dedupe and a later cache hit")

        var misses = 0
        val failing = AttachmentImageLoader(
            fetch = { error("not the uploads route") },
            decoder = SkiaImageBytesDecoder(),
            fetchPath = { _, _ -> misses++; throw HuginnClient.HuginnException(403, "refused") },
        )
        assertNull(failing.loadPath("/etc/nope.png"))
        assertNull(failing.loadPath("/etc/nope.png"))
        assertEquals(1, misses, "a 403 is permanent; asking again on every recomposition is not")
    }

    /**
     * The graceful story against a daemon with no `/v1/files/image` — which is
     * every daemon before appd 3.2.0. No fetcher, no fetch, no thumbnail, and
     * the placeholder renders instead.
     */
    @Test
    fun `without a path fetcher loadPath is a quiet miss, not a crash`() = runBlocking {
        var fetches = 0
        val loader = AttachmentImageLoader({ fetches++; ByteArray(0) }, SkiaImageBytesDecoder())
        assertNull(loader.loadPath("/tmp/a.png"))
        assertEquals(0, fetches, "and it does not fall back to the uploads route")
    }

    @Test
    fun `the uploads path keeps its exact basename behaviour`() = runBlocking {
        // loadPath must not have changed load(): chat-history thumbnails are the
        // one caller that IS keyed correctly on a basename.
        var fetches = 0
        val png = pngBytes(4, 4)
        val loader = AttachmentImageLoader(
            fetch = { fetches++; png },
            decoder = SkiaImageBytesDecoder(),
            fetchPath = { _, _ -> error("not the files route") },
        )
        assertNotNull(loader.load("/uploads/up-1-ab.png"))
        assertNotNull(loader.load("/somewhere/else/up-1-ab.png"))
        assertEquals(1, fetches, "same upload name, one fetch — the behaviour this always had")
    }

    // ------------------------------------------------------ the full-size viewer

    /**
     * Tapping a thumbnail opens the picture at full size. The state is a plain
     * holder rather than a `remember` inside the composable so the rule — what
     * opens it, what closes it, and what must NOT open it — is asserted here
     * instead of by looking at a screenshot.
     */
    @Test
    fun `tapping a thumbnail opens the viewer on that image, and dismiss closes it`() = runBlocking {
        val bmp = SkiaImageBytesDecoder().decode(pngBytes(6, 6))
        val state = ImageViewerState()
        assertFalse(state.isOpen, "nothing is open until something is tapped")

        state.open("/tmp/shot.png", bmp)
        assertTrue(state.isOpen)
        assertEquals("/tmp/shot.png", state.path)
        assertTrue(state.bitmap === bmp)

        state.close()
        assertFalse(state.isOpen)
        assertNull(state.bitmap, "and it lets go of the bitmap rather than pinning it in RAM")
    }

    @Test
    fun `a thumbnail that never decoded cannot open an empty viewer`() {
        val state = ImageViewerState()
        state.open("/tmp/broken.png", null)
        assertFalse(state.isOpen, "there is nothing to show full size")
    }

    // ------------------------------------------------------------ app icons

    /**
     * ⚠ AN APP ICON IS KEYED ON ID **AND** `iconAt` — the stamp on the BYTES, not
     * the row's `version`. Rows recycle, so the cache is what makes this one
     * request rather than one per scroll; the key is what decides which question
     * that cached answer is an answer to.
     */
    @Test
    fun `an app icon is cached per id and refetched when the bytes change`() = runBlocking {
        val asked = mutableListOf<String>()
        val png = pngBytes(16, 16)
        val loader = AttachmentImageLoader(
            fetch = { error("not the uploads route") },
            decoder = SkiaImageBytesDecoder(),
            fetchIcon = { id -> asked += id; png },
        )
        val a = loader.loadIcon("atlas", iconAt = 1_789_000_000)
        val b = loader.loadIcon("atlas", iconAt = 1_789_000_000)
        assertNotNull(a)
        assertTrue(a === b, "the second draw of the same row returns the cached bitmap")
        assertEquals(listOf("atlas"), asked)

        assertNotNull(loader.loadIcon("atlas", iconAt = 1_789_003_600))
        assertEquals(listOf("atlas", "atlas"), asked, "new bytes are a new picture")

        assertNotNull(loader.loadIcon("jotter", iconAt = 1_789_000_000))
        assertEquals(listOf("atlas", "atlas", "jotter"), asked, "and another row is another key")
    }

    /**
     * ⚠⚠ THE DEFECT, STATED AS THE ROW ITSELF WOULD PRODUCE IT. The daemon
     * re-fetches a row's favicon ON PROBE — hourly, with nobody touching the row
     * — so `App.version` does not move when the picture does. Keyed on `version`
     * the app served the OLD icon for the life of the process: cached, silent,
     * and unfixable short of a restart. The refetched row is otherwise identical.
     */
    @Test
    fun `a favicon refetched by the daemon reaches the screen without a restart`() = runBlocking {
        val asked = mutableListOf<String>()
        val loader = AttachmentImageLoader(
            fetch = { error("not the uploads route") },
            decoder = SkiaImageBytesDecoder(),
            fetchIcon = { id -> asked += id; pngBytes(16, 16) },
        )
        // The row as the list first drew it, and the SAME row an hour later: the
        // site changed its icon, the daemon re-fetched on probe, `version` is
        // untouched because nobody edited anything.
        val before = App(id = "atlas", name = "atlas", version = 4, icon = true, iconAt = 1_789_000_000)
        val after = before.copy(iconAt = 1_789_003_600)
        assertEquals(before.version, after.version, "the row's revision does NOT move with the bytes")

        loader.loadIcon(before.id, before.iconStamp)
        loader.loadIcon(after.id, after.iconStamp)
        assertEquals(listOf("atlas", "atlas"), asked, "the new picture must be fetched, not the old one served")
    }

    /**
     * A daemon that does not report the stamp keys every row on 0 — one fetch per
     * row per process, which is exactly what an id-only key did. Absence is the
     * old behaviour, not a new bug.
     */
    @Test
    fun `a daemon with no iconAt behaves as an id-only key did`() = runBlocking {
        var fetches = 0
        val loader = AttachmentImageLoader(
            fetch = { error("not the uploads route") },
            decoder = SkiaImageBytesDecoder(),
            fetchIcon = { fetches++; pngBytes(8, 8) },
        )
        val row = App(id = "jotter", name = "jotter", icon = true)
        assertEquals(0L, row.iconStamp, "no stamp reads as 0, never as a date in 1970")
        loader.loadIcon(row.id, row.iconStamp)
        loader.loadIcon(row.id, row.iconStamp)
        assertEquals(1, fetches)
    }

    /**
     * A 404 is the ordinary answer for a row the daemon has no favicon for, and
     * it must resolve to null once rather than on every scroll.
     */
    @Test
    fun `a missing icon is a remembered miss, not a request per recomposition`() = runBlocking {
        var fetches = 0
        val loader = AttachmentImageLoader(
            fetch = { error("not the uploads route") },
            decoder = SkiaImageBytesDecoder(),
            fetchIcon = { fetches++; throw HuginnClient.HuginnException(404, "no icon") },
        )
        assertNull(loader.loadIcon("bursar", iconAt = 1))
        assertNull(loader.loadIcon("bursar", iconAt = 1))
        assertEquals(1, fetches, "a 404 on an icon is permanent for those bytes")
    }

    /** The graceful story against a daemon with no icon route at all. */
    @Test
    fun `without an icon fetcher loadIcon is a quiet miss, not a crash`() = runBlocking {
        var fetches = 0
        val loader = AttachmentImageLoader({ fetches++; ByteArray(0) }, SkiaImageBytesDecoder())
        assertNull(loader.loadIcon("atlas", iconAt = 1))
        assertEquals(0, fetches, "and it does not fall back to the uploads route")
    }

    /**
     * ⚠ THE THREE KEY SPACES MUST NOT COLLIDE. An upload called `atlas`, a host
     * path `/atlas` and an app id `atlas` are three different fetches from three
     * different routes sharing one LRU.
     */
    @Test
    fun `an upload, a path and an icon with the same name are three different entries`() = runBlocking {
        val png = pngBytes(4, 4)
        val hits = mutableListOf<String>()
        val loader = AttachmentImageLoader(
            fetch = { hits += "upload:$it"; png },
            decoder = SkiaImageBytesDecoder(),
            fetchPath = { path, _ -> hits += "path:$path"; png },
            fetchIcon = { id -> hits += "icon:$id"; png },
        )
        loader.load("/uploads/atlas")
        loader.loadPath("/atlas")
        loader.loadIcon("atlas", iconAt = 1)
        assertEquals(listOf("upload:atlas", "path:/atlas", "icon:atlas"), hits)
    }

    // ------------------------------------------- the "photo attached" bug (2026-10-30)

    @Test
    fun `a transient failure is not remembered, so the next load fetches again`() = runBlocking {
        var fetches = 0
        val png = pngBytes(4, 4)
        val loader = AttachmentImageLoader(
            fetch = { if (fetches++ == 0) throw HuginnClient.HuginnException(502, "the core's proxy hiccuped") else png },
            decoder = SkiaImageBytesDecoder(),
            retryDelaysMs = emptyList(),
        )
        assertNull(loader.load("/uploads/up-1.jpg"), "the 502 itself is a miss")
        assertNotNull(loader.load("/uploads/up-1.jpg"), "but not a remembered one")
        assertEquals(2, fetches)
    }

    @Test
    fun `a transient failure is retried within the same load`() = runBlocking {
        var fetches = 0
        val png = pngBytes(4, 4)
        val loader = AttachmentImageLoader(
            fetch = { if (fetches++ == 0) throw java.io.IOException("timeout") else png },
            decoder = SkiaImageBytesDecoder(),
            retryDelaysMs = listOf(10),
        )
        assertNotNull(loader.load("/uploads/up-2.jpg"))
        assertEquals(2, fetches)
    }

    /**
     * The hang: a fetch cancelled mid-flight (its row left the screen) left its
     * in-flight entry behind and never completed it, so every later load of that
     * photo waited forever and the bubble kept its "photo attached" pill.
     */
    @Test
    fun `a load cancelled mid-fetch hands the fetch to whoever is waiting, and nobody hangs`() = runBlocking {
        val png = pngBytes(4, 4)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var fetches = 0
        val loader = AttachmentImageLoader(
            fetch = { fetches++; started.complete(Unit); gate.await(); png },
            decoder = SkiaImageBytesDecoder(),
        )
        val leader = launch { loader.load("/uploads/up-3.jpg") }
        started.await()
        val waiter = async { loader.load("/uploads/up-3.jpg") }
        kotlinx.coroutines.yield()
        leader.cancel()
        gate.complete(Unit)
        val got = withTimeout(5_000) { waiter.await() }
        assertNotNull(got, "the waiter fetched for itself once the leader left")
        assertNotNull(withTimeout(5_000) { loader.load("/uploads/up-3.jpg") }, "and a later load is not stuck either")
        assertTrue(fetches in 1..2, "fetches=$fetches")
    }
}
