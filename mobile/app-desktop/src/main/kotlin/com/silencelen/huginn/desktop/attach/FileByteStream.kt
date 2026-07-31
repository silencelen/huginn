package com.silencelen.huginn.desktop.attach

import com.silencelen.huginn.data.ByteStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * A file on disk as a [ByteStream], pulled a chunk at a time straight onto the
 * socket.
 *
 * The point of the interface is that this NEVER holds the file. A router backup or
 * an NVR clip is tens of megabytes; `File.readBytes()` would hold it once in the
 * array and again in whatever the HTTP layer copies it into, on a client that is
 * also rendering a UI. The phone's implementation reads a content-provider stream
 * for exactly the same reason.
 *
 * The handle is opened LAZILY so constructing one and never sending it — an
 * attachment the user removed before the upload started — does not leave a
 * descriptor to close.
 */
class FileByteStream(private val file: File) : ByteStream {

    private var input: InputStream? = null
    private var opened = false

    override val contentLength: Long get() = file.length()

    override suspend fun read(into: ByteArray): Int = withContext(Dispatchers.IO) {
        val s = input ?: file.inputStream().buffered().also { input = it; opened = true }
        s.read(into)
    }

    /**
     * Called exactly once by `HuginnClient.uploadStream`, including when the
     * request fails before a byte is written.
     */
    override suspend fun close() {
        withContext(Dispatchers.IO) {
            if (opened) runCatching { input?.close() }
            input = null
        }
    }
}

/** What to tell the daemon a file is, and whether to treat it as an image. */
object FileKind {

    /** Extensions ImageIO can read AND that are worth re-encoding rather than streaming. */
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff")

    /**
     * A conservative extension table, consulted only when the OS cannot say.
     * `Files.probeContentType` answers on a normal Linux desktop and returns null
     * often enough elsewhere (and always for an unknown extension) that a fallback
     * is not optional — a null content type on the wire is what makes the daemon
     * guess, and its guess decides `readable`.
     */
    private val BY_EXT = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "log" to "text/plain",
        "json" to "application/json", "yaml" to "application/yaml", "yml" to "application/yaml",
        "csv" to "text/csv", "xml" to "application/xml", "html" to "text/html",
        "pdf" to "application/pdf", "zip" to "application/zip", "gz" to "application/gzip",
        "tar" to "application/x-tar", "sh" to "text/x-shellscript", "kt" to "text/x-kotlin",
        "py" to "text/x-python", "ts" to "text/plain", "js" to "text/javascript",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic",
        "conf" to "text/plain", "ini" to "text/plain", "toml" to "text/plain",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun looksLikeImage(name: String): Boolean = extension(name) in IMAGE_EXT

    fun mime(file: File): String {
        val probed = runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
        if (!probed.isNullOrBlank()) return probed
        return BY_EXT[extension(file.name)] ?: "application/octet-stream"
    }
}
