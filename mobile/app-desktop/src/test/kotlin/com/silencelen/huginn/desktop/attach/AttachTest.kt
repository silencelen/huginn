package com.silencelen.huginn.desktop.attach

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.ui.AttachBatch
import com.silencelen.huginn.ui.AttachmentText
import com.silencelen.huginn.ui.PANE_SEPARATOR
import com.silencelen.huginn.ui.TakeResult
import com.silencelen.huginn.ui.composeMessage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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

    /**
     * ONE batch under test: a controller, its client, its files and its thread.
     *
     * THE FLAKE THIS REPLACES was three things, all of them properties of the
     * test rather than of the controller:
     *
     * 1. THE SCOPE WAS [kotlinx.coroutines.Dispatchers.Unconfined], so every
     *    upload resumed on whichever ktor engine thread answered it and three
     *    `_items` read-modify-writes ran at once. A lost write is either a chip
     *    that never reaches READY (2 markers for 3 files, the third named as
     *    failed) or an item that drops out of the list entirely (2 markers, 0
     *    failed) — both were reproduced, ~7% of 150 attempts. Production hands
     *    this controller a `rememberCoroutineScope()`, which is Compose's single
     *    UI thread; ONE thread here is that arrangement, so a failure here means
     *    something the shipped client can actually do.
     *
     * 2. THE FILES WERE A FIXED NAME IN THE SHARED TMPDIR (`$TMPDIR/w2attach-a.txt`),
     *    so a second checkout running this same suite truncated the file this one
     *    was streaming. A private directory per batch, deleted with it.
     *
     * 3. UPLOADS FINISHED WHENEVER THE ENGINE GOT TO THEM. Each response now
     *    waits on a [CompletableDeferred] that [settle] completes, so "they came
     *    back out of order" is an order the test CHOOSES and can name in the
     *    assertion, with no sleeps and no clock.
     */
    private class Batch(private val failing: Set<String> = emptySet()) : AutoCloseable {

        private val dir: File = Files.createTempDirectory("huginn-attach-batch").toFile()
        private val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "attach-test-ui").apply { isDaemon = true }
        }
        private val dispatcher = pool.asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)

        /** One per upload name; the engine answers only once the test completes it. */
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        private fun gate(name: String) = gates.getOrPut(name) { CompletableDeferred() }

        /** Uploads echo the name back as a path, except [failing], which the daemon refuses. */
        val controller = AttachmentController(
            HuginnClient(
                baseUrlProvider = { "http://h" },
                tokenProvider = { "t" },
                engine = MockEngine { request ->
                    val name = request.url.parameters["name"].orEmpty()
                    gate(name).await()
                    if (name in failing) {
                        respond(
                            """{"error":"that type is not allowed"}""",
                            HttpStatusCode.UnsupportedMediaType,
                            headersOf("Content-Type", listOf("application/json")),
                        )
                    } else {
                        respond(
                            """{"ok":true,"path":"/up/$name","bytes":32,"readable":true}""",
                            HttpStatusCode.OK,
                            headersOf("Content-Type", listOf("application/json")),
                        )
                    }
                },
            ),
            scope,
        )

        val items get() = controller.items.value
        val failure get() = controller.failure.value

        private fun file(name: String, size: Int = 32): File =
            File(dir, name).apply { writeBytes(ByteArray(size) { 'x'.code.toByte() }) }

        /** Intake ON the controller's thread — a composer attaches from the UI thread. */
        suspend fun attach(vararg names: String) = withContext(dispatcher) {
            controller.attachFiles(names.map { file(it) })
        }

        suspend fun remove(id: String) = withContext(dispatcher) { controller.remove(id) }

        suspend fun take(): TakeResult = withContext(dispatcher) { controller.take() }

        /**
         * Lets ONE upload answer and does not return until its chip has reached a
         * terminal state, so the caller knows exactly what order the batch
         * settled in. Uploads past [AttachmentController.MAX_IN_FLIGHT] have not
         * reached the engine yet, so settle a batch of at most four this way.
         *
         * The timeout is a watchdog, not a schedule: nothing here asserts on it,
         * it only turns "this test hangs the build" into a named failure.
         */
        suspend fun settle(name: String) {
            gate(name).complete(Unit)
            withTimeout(30_000) {
                controller.items.first { list ->
                    list.none { it.label == name } ||
                        list.any {
                            it.label == name &&
                                (it.status == AttachStatus.READY || it.status == AttachStatus.FAILED)
                        }
                }
            }
        }

        override fun close() {
            gates.values.forEach { it.cancel() }
            scope.cancel()
            pool.shutdownNow()
            dir.deleteRecursively()
        }
    }

    // runBlocking, NOT runTest, for everything that drives a controller: runTest's
    // virtual clock fast-forwards the 20s settle budget the instant the test
    // coroutine idles, which cancels uploads that are merely running on the ktor
    // engine's own dispatcher and makes a clean batch look like a wholly failed
    // one. The one test below that owns its own jobs DOES use the virtual clock,
    // because there it is the point.

    @Test
    fun `markers come back in attach order, one per file`() = runBlocking {
        Batch().use { b ->
            b.attach("a.txt", "b.txt", "c.txt")
            // SETTLED BACKWARDS, deliberately. Surviving out-of-order completion
            // is the promise; leaving the order to the engine tests nothing.
            b.settle("c.txt")
            b.settle("b.txt")
            b.settle("a.txt")

            val taken = b.take()

            // THE SIZE IS THE ASSERTION. The slot this replaced returned one marker
            // for a three-file drop and read as working.
            assertEquals(3, taken.markers.size, "three files, three markers")
            assertEquals(
                listOf("a.txt", "b.txt", "c.txt"),
                taken.markers.map { it.substringAfter("/up/").substringBefore(" ") },
                "intake order is marker order — NOT completion order",
            )
            assertTrue(taken.failed.isEmpty())
            assertTrue(b.items.isEmpty(), "taking clears the composer")
        }
    }

    @Test
    fun `a failed upload is named in the composer line and the rest still send`() = runBlocking {
        Batch(failing = setOf("bad.txt")).use { b ->
            b.attach("ok1.txt", "bad.txt", "ok2.txt")
            // The refusal comes back FIRST and the two good ones after it.
            b.settle("bad.txt")
            b.settle("ok2.txt")
            b.settle("ok1.txt")

            val taken = b.take()

            assertEquals(2, taken.markers.size, "the two that landed still go")
            assertEquals(
                listOf("ok1.txt", "ok2.txt"),
                taken.markers.map { it.substringAfter("/up/").substringBefore(" ") },
                "and they keep their intake order with a hole punched in the middle",
            )
            assertEquals(listOf("bad.txt"), taken.failed, "and the one that did not is named")
            val line = b.failure
            assertTrue(line != null && "bad.txt" in line, "the composer says which: $line")
            assertEquals(AttachBatch.failureLine(taken.failed, 3), line, "one wording, from :core")
        }
    }

    @Test
    fun `two failures are named in attach order, not in the order they gave up`() = runBlocking {
        Batch(failing = setOf("bad1.txt", "bad2.txt")).use { b ->
            b.attach("bad1.txt", "ok.txt", "bad2.txt")
            // The LAST one fails first. The line the composer prints is a list of
            // attachments, so it has to read in the order they were attached —
            // otherwise the same failed batch is named two different ways on two
            // runs and neither matches the chips still on screen.
            b.settle("bad2.txt")
            b.settle("ok.txt")
            b.settle("bad1.txt")

            val taken = b.take()

            assertEquals(listOf("bad1.txt", "bad2.txt"), taken.failed, "attach order, always")
            assertEquals(1, taken.markers.size, "the one that landed still goes")
            assertEquals(AttachBatch.failureLine(listOf("bad1.txt", "bad2.txt"), 3), b.failure)
        }
    }

    @Test
    fun `the cap is ten, and the eleventh is refused out loud`() = runBlocking {
        Batch().use { b ->
            b.attach(*(1..12).map { "f$it.txt" }.toTypedArray())

            assertEquals(10, b.items.size, "ten is the cap on both shells")
            assertTrue(b.failure!!.contains("2 left off"))
        }
    }

    @Test
    fun `the settle budget is whole-batch, not per item`() = runTest {
        // Three uploads that are never coming back, and a 300ms budget. Per item
        // this waits 900ms; as a batch it waits 300. At ten files that is the
        // difference between 20 seconds and three minutes of held composer, which
        // is exactly the wedged-socket case the timeout exists to prevent.
        //
        // ON THE VIRTUAL CLOCK, and this one belongs on it: the old version timed
        // a real 300ms wait and asserted it came in under 700, which is a coin
        // toss on a build host running three other test JVMs. These jobs are the
        // test's own — no engine, no other dispatcher — so there is nothing for
        // the clock to fast-forward out from under.
        val jobs = (1..3).map { backgroundScope.launch { awaitCancellation() } }
        val before = currentTime
        val settled = settleAll(jobs, 300)
        val waited = currentTime - before
        jobs.forEach { it.cancel() }

        assertTrue(!settled, "nothing settled")
        assertEquals(300L, waited, "one budget for the batch, not one each")
    }

    @Test
    fun `removing one chip leaves the others, by id`() = runBlocking {
        Batch().use { b ->
            b.attach("x.txt", "y.txt", "z.txt")

            val second = b.items[1].id
            b.remove(second)

            assertEquals(
                listOf("x.txt", "z.txt"),
                b.items.map { it.label },
                "identity is the id, never the index",
            )
            assertNull(b.items.firstOrNull { it.id == second })
        }
    }
}
