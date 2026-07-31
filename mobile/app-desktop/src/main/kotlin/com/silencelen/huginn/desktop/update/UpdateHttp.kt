package com.silencelen.huginn.desktop.update

import com.silencelen.huginn.data.huginnHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The two things the updater needs off the network, behind an interface so the
 * decision logic can be tested without one.
 *
 * NOT [com.silencelen.huginn.data.HuginnClient]: that class is the daemon's API
 * surface and takes its base URL from a `() -> String` provider fed by user
 * settings — precisely the thing the feed must not be derived from. Keeping the
 * updater on its own tiny client means there is no call path by which a changed
 * setting can move where an installer comes from.
 */
interface UpdateHttp {
    /** @throws UpdateHttpException on any non-2xx, carrying the status. */
    suspend fun getText(url: String, token: String): String

    /**
     * Streams [url] into [dest]. Never buffers the whole body — an installer is
     * ~90 MB and this runs in a UI process's heap.
     *
     * @param onProgress bytes-so-far and total (-1 when the server did not say).
     */
    suspend fun download(url: String, token: String, dest: File, onProgress: (Long, Long) -> Unit)
}

class UpdateHttpException(val status: Int, message: String) : Exception(message)

class KtorUpdateHttp(
    private val client: HttpClient = HttpClient(huginnHttpEngine()) {
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            // Generous: a 90 MB installer over the tailnet from a phone hotspot
            // is minutes, and a timeout that kills it halfway is an update that
            // can never complete on a slow link.
            requestTimeoutMillis = 30 * 60_000
            socketTimeoutMillis = 60_000
        }
    },
) : UpdateHttp {

    override suspend fun getText(url: String, token: String): String = withContext(Dispatchers.IO) {
        client.prepareGet(url) { header("Authorization", "Bearer $token") }.execute { resp ->
            val body = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                throw UpdateHttpException(resp.status.value, "${resp.status.value}: ${body.take(200)}")
            }
            body
        }
    }

    override suspend fun download(
        url: String,
        token: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        // Write to a `.part` and rename only on a clean finish. A partial file
        // left under the real name would be hashed on the next launch, fail, and
        // be re-downloaded forever — or, worse, be there under a name something
        // else trusts.
        val part = File(dest.parentFile, dest.name + ".part")
        client.prepareGet(url) { header("Authorization", "Bearer $token") }.execute { resp ->
            if (!resp.status.isSuccess()) {
                throw UpdateHttpException(resp.status.value, "${resp.status.value} fetching ${dest.name}")
            }
            val total = resp.headers["Content-Length"]?.toLongOrNull() ?: -1L
            val channel = resp.bodyAsChannel()
            var seen = 0L
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    // Same shape as SseLines: readAvailable can legitimately
                    // return 0 on an open channel, and only -1 (or 0 on a closed
                    // one) means the body is spent.
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n < 0 || (n == 0 && channel.isClosedForRead)) break
                    if (n > 0) {
                        out.write(buf, 0, n)
                        seen += n
                        onProgress(seen, total)
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "could not move ${part.name} into place" }
    }
}
