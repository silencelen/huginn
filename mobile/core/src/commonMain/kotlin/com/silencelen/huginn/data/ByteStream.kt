package com.silencelen.huginn.data

/**
 * A source of bytes of known length, pulled a chunk at a time.
 *
 * This exists so [HuginnClient.uploadStream] can take "a big file" without
 * naming one platform's idea of a file. The phone hands it a `ContentResolver`
 * stream over a picked document; the desktop client will hand it a path. Neither
 * of them — and neither does this module — ever holds the whole thing: a router
 * or NVR backup is tens of megabytes, and reading one into a `ByteArray` on a
 * phone to hand to the HTTP layer means holding it twice.
 *
 * PULL rather than push (no `writeTo(sink)`) on purpose: a push interface would
 * have to name Ktor's `ByteWriteChannel`, which would make every implementor —
 * including code in :app that otherwise never sees the HTTP layer — depend on
 * it. A `read` into a caller-owned array is the smallest thing both platforms
 * can implement with what they already have.
 */
interface ByteStream {

    /**
     * Total bytes, or -1 when the size is not known — in which case the upload
     * is sent chunked, exactly as an OkHttp `RequestBody` returning -1 was.
     * The phone reads this from the content provider rather than by loading the
     * file, so -1 means "the provider would not say", not "nobody asked".
     */
    val contentLength: Long

    /**
     * Fills as much of [into] as is available.
     *
     * @return the number of bytes written, or -1 once the source is spent. A
     *   return of 0 is allowed and means "nothing right now, ask again".
     */
    suspend fun read(into: ByteArray): Int

    /** Releases the handle. Called exactly once, including when the upload fails. */
    suspend fun close() {}
}

/**
 * The whole array as a [ByteStream]. For tests and for callers that genuinely do
 * have the bytes already — an image transcoded in memory, say — so they can take
 * the same path as a streamed file instead of a second upload API.
 */
fun ByteArray.asByteStream(): ByteStream = object : ByteStream {
    private var offset = 0
    override val contentLength: Long get() = this@asByteStream.size.toLong()
    override suspend fun read(into: ByteArray): Int {
        if (offset >= size) return -1
        val n = minOf(into.size, size - offset)
        copyInto(into, 0, offset, offset + n)
        offset += n
        return n
    }
}
