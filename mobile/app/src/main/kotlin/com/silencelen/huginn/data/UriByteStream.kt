package com.silencelen.huginn.data

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/**
 * Android's half of a streamed upload: a picked document, read straight off the
 * content provider onto the socket.
 *
 * This — and not [HuginnClient] — is where `java.io.InputStream` lives now. The
 * client used to take `() -> InputStream?` and write it into an OkHttp sink,
 * which welded the upload path to the JVM for the sake of one type. Inverting it
 * costs three lines here and lets the whole client compile for any target: the
 * desktop client will implement [ByteStream] over a file instead, and neither of
 * them holds a tens-of-megabyte backup in memory.
 *
 * The stream is opened LAZILY, on the first read, so constructing this is free
 * and the provider handle is held only for as long as the upload runs.
 */
class UriByteStream(
    private val resolver: ContentResolver,
    private val uri: Uri,
    /** From the provider, not from reading the file. -1 when it would not say. */
    override val contentLength: Long,
) : ByteStream {

    private var input: InputStream? = null

    override suspend fun read(into: ByteArray): Int {
        val stream = input ?: (resolver.openInputStream(uri) ?: throw IOException("could not open the file"))
            .also { input = it }
        return stream.read(into)
    }

    override suspend fun close() {
        runCatching { input?.close() }
        input = null
    }
}
