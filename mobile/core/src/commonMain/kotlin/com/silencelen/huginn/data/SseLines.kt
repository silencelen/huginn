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
 * One line of the body, or one frame's `data`, ran past [limit] bytes.
 *
 * ⚠ A DEAD STREAM, NOT A SKIPPED FRAME. The reader cannot resynchronise inside
 * a line it refused to hold, so both SSE readers end the stream here exactly as
 * they do on [SseTruncatedException], and the watch side reconnects with its
 * usual backoff. The limit sits far above the largest frame the daemon writes
 * (a whole assistant text block); what reaches it is a peer that never ends a
 * line, which used to be able to grow the buffer until the app died.
 */
class SseFrameTooLargeException(val limit: Int) : Exception("stream frame longer than $limit bytes")

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
 *
 * ⚠⚠ A LINE IS AT MOST [maxLine] BYTES (security review, 2026-10). The buffer
 * used to grow for as long as the peer withheld a newline, by a doubling loop
 * that wrapped to zero and spun forever at 1 GiB, so one response with no line
 * break could take the app down. A line past the limit now ends the stream with
 * [SseFrameTooLargeException], and the buffer never holds more than one line
 * plus one read.
 */
internal class SseLines(
    private val channel: ByteReadChannel,
    chunkSize: Int = 8 * 1024,
    /** Bytes a line may carry before its `\n`, a `\r` included. */
    private val maxLine: Int = MAX_LINE,
) {
    init {
        require(chunkSize in 1..MAX_CHUNK) { "chunkSize $chunkSize" }
        require(maxLine in 1..MAX_LINE_CEILING) { "maxLine $maxLine" }
    }

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
                    if (i - start > maxLine) throw SseFrameTooLargeException(maxLine)
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
            // Still no line break, and already more than a line may hold: the
            // stream ends here, before another read can grow it.
            if (end - start > maxLine) throw SseFrameTooLargeException(maxLine)

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
            // frame costs a bounded number of copies. In Long, and never past one
            // line plus one read (next() refuses more before reading): the Int
            // `shl` this replaces wrapped negative, then to zero, and spun.
            len + n > buf.size -> {
                val need = len.toLong() + n
                var cap = buf.size.toLong()
                while (cap < need) cap *= 2
                val grown = ByteArray(minOf(cap, maxLine.toLong() + chunk.size).toInt())
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

    companion object {
        /**
         * The longest line, and the largest frame, either SSE reader accepts:
         * 1 MiB. The largest the daemon writes is one assistant text block in a
         * `data:` line — a model's whole answer, JSON-escaped — which stays
         * well under this even at the longest output a model gives today. The
         * watch stream's state frames are a few kilobytes.
         */
        const val MAX_LINE: Int = 1 shl 20

        private const val MAX_CHUNK: Int = 1 shl 20
        private const val MAX_LINE_CEILING: Int = 1 shl 30

        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}

/**
 * Adds one `data:` line's payload to the frame being built — refusing a frame
 * whose data would pass [limit] characters, with [SseFrameTooLargeException].
 *
 * The line reader bounds each line; this bounds the FRAME, because a peer that
 * sends `data:` line after `data:` line and never the blank line that ends the
 * frame would otherwise grow it just as far.
 */
internal fun StringBuilder.appendSseData(piece: String, limit: Int = SseLines.MAX_LINE) {
    if (length.toLong() + piece.length > limit) throw SseFrameTooLargeException(limit)
    append(piece)
}
