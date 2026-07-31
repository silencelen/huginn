package com.silencelen.huginn

import com.silencelen.huginn.data.SseLines
import com.silencelen.huginn.data.SseTruncatedException
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
}
