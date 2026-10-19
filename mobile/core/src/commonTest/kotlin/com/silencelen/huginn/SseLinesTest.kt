package com.silencelen.huginn

import com.silencelen.huginn.data.SseFrameTooLargeException
import com.silencelen.huginn.data.SseLines
import com.silencelen.huginn.data.SseTruncatedException
import com.silencelen.huginn.data.appendSseData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The line reader both SSE flows sit on.
 *
 * It is hand-rolled — see the header of SseLines.kt for why Ktor's own
 * `readUTF8Line` would not do — so it gets its own tests rather than being
 * covered only through SseTest. Every case here is one the daemon or the network
 * actually produces: frame separators are empty lines, a long tool-input frame
 * outruns any fixed buffer, and a dropped link ends the body mid-line.
 *
 * The tiny chunk sizes are the point: they force the refill, compaction and
 * grow paths that an 8 KB default would only hit on a very large frame.
 */
class SseLinesTest {

    private fun lines(text: String, chunk: Int = 8 * 1024) =
        SseLines(ByteReadChannel(text.encodeToByteArray()), chunkSize = chunk)

    @Test
    fun `splits on newlines and drops the terminator`() = runTest {
        val r = lines("event: delta\ndata: {}\n")
        assertEquals("event: delta", r.next())
        assertEquals("data: {}", r.next())
        assertNull(r.next())
    }

    @Test
    fun `a blank line is a line, because it is what ends a frame`() = runTest {
        val r = lines("data: {}\n\nevent: done\n")
        assertEquals("data: {}", r.next())
        assertEquals("", r.next())
        assertEquals("event: done", r.next())
    }

    @Test
    fun `accepts CRLF as well as LF`() = runTest {
        // The daemon only ever sends LF; the spec allows CRLF and a proxy could.
        val r = lines("event: delta\r\ndata: {}\r\n\r\n")
        assertEquals("event: delta", r.next())
        assertEquals("data: {}", r.next())
        assertEquals("", r.next())
        assertNull(r.next())
    }

    @Test
    fun `a body that ends mid-line is a truncation, not an end`() = runTest {
        // The whole reason this class exists rather than readUTF8Line: this case
        // must be distinguishable from a stream that finished.
        val r = lines("event: delta\ndata: {\"text\":\"half an ans")
        assertEquals("event: delta", r.next())
        assertFailsWith<SseTruncatedException> { r.next() }
    }

    @Test
    fun `a line longer than the read chunk comes back whole`() = runTest {
        // A tool-input frame runs to hundreds of characters; the buffer has to
        // grow rather than cut the line where the socket read happened to stop.
        val long = "data: " + "{\"input\":\"" + "a".repeat(5_000) + "\"}"
        val r = lines("$long\nevent: done\n", chunk = 16)
        assertEquals(long, r.next())
        assertEquals("event: done", r.next())
        assertNull(r.next())
    }

    @Test
    fun `multi-byte characters survive a chunk boundary`() = runTest {
        // Splitting on the 0x0A byte before decoding is only safe because a
        // continuation byte can never be 0x0A. This is that claim, tested: the
        // read boundary is put in the middle of the character on purpose.
        val text = "data: {\"text\":\"héllo — ✅ 世界\"}"
        val r = lines("$text\n", chunk = 3)
        assertEquals(text, r.next())
        assertNull(r.next())
    }

    @Test
    fun `many small frames read through a tiny chunk keep their order`() = runTest {
        val body = buildString { for (i in 1..200) append("data: $i\n\n") }
        val r = lines(body, chunk = 7)
        for (i in 1..200) {
            assertEquals("data: $i", r.next())
            assertEquals("", r.next())
        }
        assertNull(r.next())
    }

    // ---------------------------------------------------- a line with no end

    private fun capped(text: String, max: Int, chunk: Int = 16) =
        SseLines(ByteReadChannel(text.encodeToByteArray()), chunkSize = chunk, maxLine = max)

    /**
     * A line is at most `maxLine` bytes before its `\n`. Exactly that many is a
     * line; one more ends the stream, whether the newline never comes or comes
     * too late.
     */
    @Test
    fun `a line longer than the limit ends the stream, one at the limit is a line`() = runTest {
        val r = capped("a".repeat(64) + "\n" + "b".repeat(65) + "\nnever read\n", max = 64)
        assertEquals("a".repeat(64), r.next())
        val e = assertFailsWith<SseFrameTooLargeException> { r.next() }
        assertEquals(64, e.limit)
        // A CR counts: it is a byte of the line until the \n proves otherwise.
        assertEquals("c".repeat(63), capped("c".repeat(63) + "\r\n", max = 64).next())
        assertFailsWith<SseFrameTooLargeException> { capped("c".repeat(64) + "\r\n", max = 64).next() }
    }

    /**
     * ⚠ THE FINDING ITSELF: a peer that never sends a newline. The buffer used to
     * double for as long as the bytes kept coming; now the stream ends a read
     * after the limit, however much more the peer has to send.
     */
    @Test
    fun `a peer that never ends a line is cut off at the limit, not buffered`() = runTest {
        var served = 0L
        val endless = CoroutineScope(Dispatchers.Default).writer {
            val block = ByteArray(4096) { 'x'.code.toByte() }
            while (served < 64L * 1024 * 1024) { channel.writeFully(block); served += block.size }
            channel.flushAndClose()
        }.channel
        val r = SseLines(endless, chunkSize = 8 * 1024, maxLine = 256 * 1024)
        assertFailsWith<SseFrameTooLargeException> { r.next() }
        endless.cancel()
        assertTrue(served < 64L * 1024 * 1024, "the reader kept taking bytes: $served")
    }

    /** At the default limit — 1 MiB, the largest frame either SSE reader accepts. */
    @Test
    fun `the default limit is one MiB`() = runTest {
        assertEquals(1 shl 20, SseLines.MAX_LINE)
        val ok = "d".repeat(SseLines.MAX_LINE)
        assertEquals(ok.length, lines("$ok\n").next()!!.length)
        assertFailsWith<SseFrameTooLargeException> { lines(ok + "d\n").next() }
    }

    @Test
    fun `a frame built from many data lines is held to the same limit`() {
        val frame = StringBuilder()
        frame.appendSseData("x".repeat(60), limit = 100)
        assertFailsWith<SseFrameTooLargeException> { frame.appendSseData("y".repeat(41), limit = 100) }
        frame.appendSseData("z".repeat(40), limit = 100)
        assertEquals(100, frame.length)
    }
}
