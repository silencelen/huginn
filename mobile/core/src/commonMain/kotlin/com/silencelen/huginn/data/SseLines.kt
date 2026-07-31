package com.silencelen.huginn.data

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/**
 * The body ended part-way through a line.
 *
 * Its own type because the distinction is the whole point: a body that ends on a
 * frame boundary is a stream that finished, and a body that ends mid-line is a
 * link that dropped. okio's `readUtf8LineStrict()` drew exactly this line by
 * throwing `EOFException`, and both SSE readers depended on it — the watch
 * stream to report the socket dead, the chat stream to stop a half-delivered
 * answer looking like a completed one.
 */
class SseTruncatedException : Exception("stream ended mid-frame")

/**
 * Reads `\n`-terminated lines off an SSE body.
 *
 * Hand-rolled rather than [io.ktor.utils.io.readUTF8Line], and deliberately: that
 * function returns an unterminated trailing line as though it were a whole one,
 * which erases the truncated-vs-finished distinction the readers above are built
 * on. This is a port of `okio.BufferedSource.readUtf8LineStrict()` reduced to
 * what SSE needs — null at a clean end of body, [SseTruncatedException] when
 * bytes are left over.
 *
 * Splitting on the `\n` BYTE before decoding is safe for UTF-8 and only for
 * UTF-8: 0x0A cannot appear inside a multi-byte sequence, so a line break is
 * never mistaken for part of a character. `\r\n` is accepted because the spec
 * allows it, even though huginn-appd only ever sends `\n`.
 */
internal class SseLines(
    private val channel: ByteReadChannel,
    chunkSize: Int = 8 * 1024,
) {
    private val chunk = ByteArray(chunkSize)

    /** Unconsumed bytes live in `buf[start until end]`. */
    private var buf = ByteArray(chunkSize)
    private var start = 0
    private var end = 0

    /** How far into [buf] the newline search has already looked. */
    private var scanned = 0

    /**
     * @return the next complete line without its terminator, or null once the
     *   body has ended cleanly.
     * @throws SseTruncatedException when the body ended with a partial line.
     */
    suspend fun next(): String? {
        while (true) {
            var i = scanned
            while (i < end) {
                if (buf[i] == NL) {
                    var stop = i
                    if (stop > start && buf[stop - 1] == CR) stop--
                    val line = buf.decodeToString(start, stop)
                    start = i + 1
                    scanned = start
                    return line
                }
                i++
            }
            scanned = end

            val n = channel.readAvailable(chunk, 0, chunk.size)
            if (n < 0 || (n == 0 && channel.isClosedForRead)) {
                if (end > start) throw SseTruncatedException()
                return null
            }
            if (n > 0) append(n)
        }
    }

    private fun append(n: Int) {
        val len = end - start
        when {
            // Outgrown: a single frame larger than the buffer. Double until it
            // fits rather than growing by a chunk at a time, so a long tool-input
            // frame costs a bounded number of copies.
            len + n > buf.size -> {
                var cap = buf.size
                while (len + n > cap) cap = cap shl 1
                val grown = ByteArray(cap)
                buf.copyInto(grown, 0, start, end)
                buf = grown
                shiftTo(len)
            }
            // Fits, but not where it is: slide the unconsumed remainder down.
            start + len + n > buf.size -> {
                buf.copyInto(buf, 0, start, end)
                shiftTo(len)
            }
        }
        chunk.copyInto(buf, end, 0, n)
        end += n
    }

    private fun shiftTo(len: Int) {
        scanned -= start
        start = 0
        end = len
    }

    private companion object {
        const val NL = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
    }
}
