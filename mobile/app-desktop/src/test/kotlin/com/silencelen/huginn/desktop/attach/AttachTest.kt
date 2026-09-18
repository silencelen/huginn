package com.silencelen.huginn.desktop.attach

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.ui.AttachBatch
import com.silencelen.huginn.ui.AttachmentText
import com.silencelen.huginn.ui.PANE_SEPARATOR
import com.silencelen.huginn.ui.composeMessage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of the attachment path.
 *
 * NOTE the argument order: kotlin.test is `assertEquals(expected, actual, message)`
 * — the REVERSE of JUnit's. Three String arguments compile clean either way and
 * assert something different, which is why this warning is on every test file in
 * this project.
 */
class ImageScaleTest {

    @Test
    fun `caps the long edge and keeps the aspect ratio`() {
        val s = ImageScale.fit(4032, 3024)
        assertEquals(2048, s.width, "long edge is the cap")
        assertEquals(1536, s.height, "short edge scales with it")
    }

    @Test
    fun `caps a portrait image on its height`() {
        val s = ImageScale.fit(3024, 4032)
        assertEquals(1536, s.width)
        assertEquals(2048, s.height)
    }

    @Test
    fun `never upscales`() {
        val s = ImageScale.fit(300, 200)
        assertEquals(300, s.width, "a small image is left alone")
        assertEquals(200, s.height)
    }

    @Test
    fun `an image exactly at the cap is untouched`() {
        val s = ImageScale.fit(2048, 1000)
        assertEquals(2048, s.width)
        assertEquals(1000, s.height)
    }

    @Test
    fun `an extreme ratio keeps at least one pixel on the short edge`() {
        // 8000x1 scaled by 2048/8000 rounds the short edge to zero, and a
        // zero-height BufferedImage throws from its constructor — a long way from
        // anything that would name this line.
        val s = ImageScale.fit(8000, 1)
        assertEquals(2048, s.width)
        assertEquals(1, s.height, "never zero")
    }

    @Test
    fun `degenerate input does not produce a zero dimension`() {
        val s = ImageScale.fit(0, -4)
        assertEquals(1, s.width)
        assertEquals(1, s.height)
    }

    @Test
    fun `the transcoded name says jpg`() {
        assertEquals("Screenshot.jpg", ImageScale.jpegName("Screenshot.png"))
        assertEquals("photo.jpg", ImageScale.jpegName("photo.jpeg"))
        assertEquals("no-extension.jpg", ImageScale.jpegName("no-extension"))
        assertEquals("shot.jpg", ImageScale.jpegName("/home/jacob/Pictures/shot.PNG"))
        // A dot in the middle is not an extension; only the trailing one is.
        assertEquals("router.backup.jpg", ImageScale.jpegName("router.backup.png"))
    }
}

class ComposeMessageTest {

    @Test
    fun `marker rides in the message text and reads back as a phrase`() {
        val marker = AttachmentText.marker("/tmp/huginn-uploads/a1b2.jpg")
        val sent = composeMessage("what is on this screen?", marker)

        assertTrue(sent.contains("/tmp/huginn-uploads/a1b2.jpg"), "Claude needs the real path")
        assertTrue(sent.startsWith("what is on this screen?"), "the typed text comes first")
        // The round trip that matters: what the sender reads back is the phrase,
        // not the daemon's storage path. A marker whose regex stops matching leaves
        // a raw path sitting in the user's own message.
        val shown = AttachmentText.displayText(sent)
        assertEquals("what is on this screen?\n\n📷 Photo attached", shown)
    }

    @Test
    fun `a file marker round-trips to its name`() {
        val marker = AttachmentText.fileMarker("/tmp/huginn-uploads/x.tar.gz", "backup.tar.gz", readable = false)
        val sent = composeMessage("", marker)
        assertEquals(marker, sent, "a blank draft sends the marker alone")
        assertTrue(sent.contains("shell tools"), "a binary must not be pointed at Read")
        assertTrue(sent.contains("act mode"), "and the reader is told what that costs")
        assertEquals("📎 backup.tar.gz", AttachmentText.displayText(sent))
    }

    @Test
    fun `no marker means the message is just the trimmed text`() {
        assertEquals("hello", composeMessage("  hello  ", null))
        assertEquals("hello", composeMessage("hello", ""))
    }

    @Test
    fun `the pane separator never introduces a newline`() {
        val marker = AttachmentText.marker("/tmp/u/a.jpg")
        val line = composeMessage("look at this", marker, PANE_SEPARATOR)
        // A pane is TYPED into and a newline is the submit key: a paragraph break
        // would send half the message and strand the marker on the next prompt.
        assertTrue('\n' !in line, "no newline may reach a tmux pane mid-message")
        assertTrue(line.startsWith("look at this "))
    }

    @Test
    fun `and still does not with THREE markers on the line`() {
        // THE MULTI-ATTACH VERSION OF THE SAME BUG. Joining the text to the first
        // marker with a space while joining marker TO marker with "\n" reads
        // correct for one attachment and submits half a message for three.
        val markers = listOf("/tmp/u/a.jpg", "/tmp/u/b.jpg", "/tmp/u/c.jpg").map(AttachmentText::marker)
        val line = composeMessage("look at these", markers, PANE_SEPARATOR)
        assertTrue('\n' !in line, "no newline may reach a tmux pane mid-message")
        assertEquals(3, AttachmentText.imagePaths(line).size, "all three ride the one line")
    }
}

class AppendDroppedTest {

    @Test
    fun `dropped text lands in an empty composer as itself`() {
        assertEquals("quoted line", appendDropped("", "  quoted line  "))
    }

    @Test
    fun `dropped text is separated from what is already typed`() {
        assertEquals("draft\n\nquote", appendDropped("draft", "quote"))
    }

    @Test
    fun `a composer already ending in a newline is not double-spaced`() {
        assertEquals("draft\nquote", appendDropped("draft\n", "quote"))
    }

    @Test
    fun `an empty drop changes nothing`() {
        assertEquals("draft", appendDropped("draft", "   "))
    }
}

class FileKindTest {

    @Test
    fun `image extensions are recognised case-insensitively`() {
        assertTrue(FileKind.looksLikeImage("Screenshot.PNG"))
        assertTrue(FileKind.looksLikeImage("a.jpeg"))
        assertTrue(!FileKind.looksLikeImage("backup.tar.gz"))
        assertTrue(!FileKind.looksLikeImage("notes"))
    }

    @Test
    fun `extension is the trailing one only`() {
        assertEquals("gz", FileKind.extension("router.tar.gz"))
        assertEquals("", FileKind.extension("Makefile"))
    }
}

class ExifTest {

    /** A minimal JPEG whose only content is an APP1/Exif segment carrying [orientation]. */
    private fun jpegWithOrientation(orientation: Int, little: Boolean = true): ByteArray {
        val tiff = ArrayList<Byte>()
        fun u16(v: Int) {
            if (little) { tiff.add((v and 0xFF).toByte()); tiff.add((v shr 8 and 0xFF).toByte()) }
            else { tiff.add((v shr 8 and 0xFF).toByte()); tiff.add((v and 0xFF).toByte()) }
        }
        fun u32(v: Int) {
            if (little) {
                tiff.add((v and 0xFF).toByte()); tiff.add((v shr 8 and 0xFF).toByte())
                tiff.add((v shr 16 and 0xFF).toByte()); tiff.add((v shr 24 and 0xFF).toByte())
            } else {
                tiff.add((v shr 24 and 0xFF).toByte()); tiff.add((v shr 16 and 0xFF).toByte())
                tiff.add((v shr 8 and 0xFF).toByte()); tiff.add((v and 0xFF).toByte())
            }
        }
        // TIFF header
        val bom = if (little) 0x49 else 0x4D
        tiff.add(bom.toByte()); tiff.add(bom.toByte())
        u16(42)
        u32(8)          // IFD0 sits right after the header
        u16(1)          // one entry
        u16(0x0112)     // orientation
        u16(3)          // SHORT
        u32(1)          // count
        u16(orientation)
        u16(0)          // padding of the four-byte value field
        u32(0)          // no next IFD

        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.toByteArray()
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) +
            payload +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
    }

    @Test
    fun `reads a little-endian orientation`() {
        assertEquals(Exif.ROTATE_90, Exif.orientation(jpegWithOrientation(Exif.ROTATE_90, little = true)))
    }

    @Test
    fun `reads a big-endian orientation`() {
        // The byte order is per-file and the value is a SHORT inside a four-byte
        // field: read it as an int and one endianness gives 6 while the other gives
        // 393216, and the wrong answer is silently "upright".
        assertEquals(Exif.ROTATE_270, Exif.orientation(jpegWithOrientation(Exif.ROTATE_270, little = false)))
    }

    @Test
    fun `no exif means upright`() {
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 2)
        assertEquals(Exif.NORMAL, Exif.orientation(plain))
    }

    @Test
    fun `garbage never throws`() {
        assertEquals(Exif.NORMAL, Exif.orientation(ByteArray(0)))
        assertEquals(Exif.NORMAL, Exif.orientation(ByteArray(64) { 0xFF.toByte() }))
        assertEquals(Exif.NORMAL, Exif.orientation("not a jpeg at all".toByteArray()))
    }

    @Test
    fun `only quarter turns swap the axes`() {
        assertTrue(Exif.swapsAxes(Exif.ROTATE_90))
        assertTrue(Exif.swapsAxes(Exif.ROTATE_270))
        assertTrue(Exif.swapsAxes(Exif.TRANSPOSE))
        assertTrue(!Exif.swapsAxes(Exif.ROTATE_180))
        assertTrue(!Exif.swapsAxes(Exif.NORMAL))
    }
}

/**
 * The transcode for real, through ImageIO. Headless-safe: `BufferedImage` and
 * `Graphics2D` need no display, only `java.desktop` — which is also why the
 * packaged runtime image carries it.
 */
class ImageTranscodeTest {

    private fun canvas(w: Int, h: Int, type: Int) = java.awt.image.BufferedImage(w, h, type).also { img ->
        val g = img.createGraphics()
        g.color = java.awt.Color(30, 160, 220)
        g.fillRect(0, 0, w / 2, h)
        g.dispose()
    }

    @Test
    fun `a large image comes back capped, opaque and JPEG`() {
        val out = ImageTranscode.fromImage(canvas(4000, 3000, java.awt.image.BufferedImage.TYPE_INT_RGB))
        assertTrue(out != null && out.isNotEmpty(), "something came back")
        val bytes = out!!
        // SOI: the bytes really are a JPEG, not a PNG that happened to encode.
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)

        val decoded = javax.imageio.ImageIO.read(bytes.inputStream())
        assertEquals(2048, decoded.width, "capped on the long edge")
        assertEquals(1536, decoded.height)
    }

    @Test
    fun `an image with an alpha channel does not throw at the writer`() {
        // THE TRAP: the JPEG plugin cannot encode alpha and fails from deep inside
        // with a message about component counts. A small ARGB image also skips both
        // the rotate and the scale branch, so it reaches the writer untouched —
        // which is exactly the path that used to hand it a raster it refuses.
        val out = ImageTranscode.fromImage(canvas(64, 48, java.awt.image.BufferedImage.TYPE_INT_ARGB))
        assertTrue(out != null && out.isNotEmpty(), "an ARGB source must still transcode")
        val decoded = javax.imageio.ImageIO.read(out!!.inputStream())
        assertEquals(64, decoded.width, "and it is not upscaled on the way")
        assertEquals(48, decoded.height)
    }

    @Test
    fun `unreadable bytes come back null rather than throwing`() {
        // HEIC is the one that matters: the extension says image, no stock JVM has
        // a reader, and a null here is what becomes an honest chip instead of an
        // upload Claude cannot open.
        assertEquals(null, ImageTranscode.fromBytes("this is not an image".toByteArray()))
    }
}

/**
 * Reading a multi-file drop out of an AWT [Transferable].
 *
 * `files(t).firstOrNull()` was the whole multi-file drop bug: the OS hands over
 * the entire list and eight of nine dragged files were discarded at the call
 * site, silently.
 */
class AwtTransferFilesTest {

    /** Three files, offered exactly the way a file manager offers a drop. */
    private class FileListTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.javaFileListFlavor || flavor == DataFlavor.stringFlavor

        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.javaFileListFlavor) files
            // The string flavour a file manager ALSO offers for the same drop.
            // Taking it would attach the paths as prose instead of the files.
            else files.joinToString("\n") { it.absolutePath }
    }

    /** What a controller would have been told, without needing a client or a socket. */
    private class RecordingSink : AttachSink {
        val files = mutableListOf<File>()
        var images = 0
        override fun attachFiles(files: List<File>) { this.files += files }
        override fun attachImage(image: BufferedImage, name: String) { images++ }
        override fun attachImageBytes(bytes: ByteArray, name: String) { images++ }
    }

    private val three = listOf(File("/tmp/one.txt"), File("/tmp/two.pdf"), File("/tmp/three.png"))

    @Test
    fun `the whole file list comes back, in drop order`() {
        val got = AwtTransfer.files(FileListTransferable(three))
        assertEquals(3, got.size, "the OS handed over three; all three must survive")
        assertEquals(listOf("one.txt", "two.pdf", "three.png"), got.map { it.name })
    }

    @Test
    fun `and the whole list reaches the sink, not its first entry`() {
        val sink = RecordingSink()
        val took = AwtTransfer.consume(FileListTransferable(three), sink) { }
        assertTrue(took, "a file drop is consumed")
        assertEquals(listOf("one.txt", "two.pdf", "three.png"), sink.files.map { it.name })
        assertEquals(0, sink.images, "files win over every other flavour on the same drop")
    }

    @Test
    fun `a text-only transferable still falls through to the draft`() {
        val t = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor
            override fun getTransferData(flavor: DataFlavor): Any = "a quoted paragraph"
        }
        var dropped: String? = null
        assertTrue(AwtTransfer.consume(t, RecordingSink()) { dropped = it })
        assertEquals("a quoted paragraph", dropped)
    }
}

/**
 * The batch contract: order, partial failure, and ONE settle budget.
 *
 * Driven through a real [AttachmentController] against a mock engine, because the
 * three things that matter here — that order survives uploads finishing out of
 * order, that a failure does not take the batch with it, and that the wait is
 * whole-batch — are all properties of the controller rather than of a pure
 * function it calls.
 */
class AttachmentBatchTest {

    // runBlocking, NOT runTest, for everything that calls take(): runTest's
    // virtual clock fast-forwards the 20s settle budget the instant the test
    // coroutine idles, which cancels uploads that are merely running on another
    // dispatcher and makes a clean batch look like a wholly failed one.
    private val BASE = "http://h"

    /** A file with a known name, so the marker can be read back by it. */
    private fun tempFile(name: String, size: Int = 32): File =
        File(System.getProperty("java.io.tmpdir"), "w2attach-$name").apply {
            writeBytes(ByteArray(size) { 'x'.code.toByte() })
            deleteOnExit()
        }

    /** Uploads succeed and echo the name back as a path, except [failing]. */
    private fun client(failing: Set<String> = emptySet()) = HuginnClient(
        baseUrlProvider = { BASE },
        tokenProvider = { "t" },
        engine = MockEngine { request ->
            val name = request.url.parameters["name"].orEmpty()
            if (name in failing) {
                respond("""{"error":"that type is not allowed"}""", HttpStatusCode.UnsupportedMediaType,
                    headersOf("Content-Type", listOf("application/json")))
            } else {
                respond("""{"ok":true,"path":"/up/$name","bytes":32,"readable":true}""",
                    HttpStatusCode.OK, headersOf("Content-Type", listOf("application/json")))
            }
        },
    )

    @Test
    fun `markers come back in attach order, one per file`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = AttachmentController(client(), scope)
        val files = listOf(tempFile("a.txt"), tempFile("b.txt"), tempFile("c.txt"))

        c.attachFiles(files)
        val taken = c.take()

        // THE SIZE IS THE ASSERTION. The slot this replaced returned one marker
        // for a three-file drop and read as working.
        assertEquals(3, taken.markers.size, "three files, three markers")
        assertEquals(
            listOf("w2attach-a.txt", "w2attach-b.txt", "w2attach-c.txt"),
            taken.markers.map { it.substringAfter("/up/").substringBefore(" ") },
            "intake order is marker order",
        )
        assertTrue(taken.failed.isEmpty())
        assertTrue(c.items.value.isEmpty(), "taking clears the composer")
    }

    @Test
    fun `a failed upload is named in the composer line and the rest still send`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = AttachmentController(client(failing = setOf("w2attach-bad.txt")), scope)
        c.attachFiles(listOf(tempFile("ok1.txt"), tempFile("bad.txt"), tempFile("ok2.txt")))

        val taken = c.take()

        assertEquals(2, taken.markers.size, "the two that landed still go")
        assertEquals(listOf("w2attach-bad.txt"), taken.failed, "and the one that did not is named")
        val line = c.failure.value
        assertTrue(line != null && "w2attach-bad.txt" in line, "the composer says which: $line")
        assertEquals(AttachBatch.failureLine(taken.failed, 3), line, "one wording, from :core")
    }

    @Test
    fun `the cap is ten, and the eleventh is refused out loud`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = AttachmentController(client(), scope)
        c.attachFiles((1..12).map { tempFile("f$it.txt") })

        assertEquals(10, c.items.value.size, "ten is the cap on both shells")
        assertTrue(c.failure.value!!.contains("2 left off"))
    }

    @Test
    fun `the settle budget is whole-batch, not per item`() = runBlocking {
        // Three uploads that are never coming back, and a 300ms budget. Per item
        // this waits 900ms; as a batch it waits 300. At ten files that is the
        // difference between 20 seconds and three minutes of held composer, which
        // is exactly the wedged-socket case the timeout exists to prevent.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = (1..3).map { scope.launch { kotlinx.coroutines.delay(60_000) } }
        val started = System.currentTimeMillis()
        val settled = settleAll(jobs, 300)
        val took = System.currentTimeMillis() - started
        jobs.forEach { it.cancel() }

        assertTrue(!settled, "nothing settled")
        assertTrue(took < 700, "one budget for the batch, not one each — waited ${took}ms")
    }

    @Test
    fun `removing one chip leaves the others, by id`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = AttachmentController(client(), scope)
        c.attachFiles(listOf(tempFile("x.txt"), tempFile("y.txt"), tempFile("z.txt")))

        val second = c.items.value[1].id
        c.remove(second)

        assertEquals(
            listOf("w2attach-x.txt", "w2attach-z.txt"),
            c.items.value.map { it.label },
            "identity is the id, never the index",
        )
        assertNull(c.items.value.firstOrNull { it.id == second })
    }
}
