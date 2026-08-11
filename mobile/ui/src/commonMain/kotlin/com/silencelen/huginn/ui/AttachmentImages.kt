package com.silencelen.huginn.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.silencelen.huginn.data.huginnIoDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Decodes encoded image bytes into something Compose can draw. An INTERFACE
 * handed down like [CellPainter], not `expect`/`actual`: each shell passes its
 * platform's decoder (skia on desktop, BitmapFactory on the phone), and a test
 * can pass a stub without owning either platform.
 */
fun interface ImageBytesDecoder {
    /** Null for anything undecodable — the caller falls back to the text pill. */
    fun decode(bytes: ByteArray): ImageBitmap?
}

/**
 * Fetches and decodes attachment thumbnails for the chat history, remembering
 * what it has seen.
 *
 * App-level and not per-composition, because the transcript recomposes
 * constantly and a LazyColumn recycles rows out and back on every scroll — a
 * `remember`-scoped cache would refetch the same photo every time it scrolled
 * into view. Three rules carry the weight:
 *
 *  * **Byte-budgeted LRU.** Decoded bitmaps are RAM (w*h*4); the budget bounds
 *    the total and evicts oldest-touched first.
 *  * **Negative caching.** Uploads are immutable and a post-prune 404 is
 *    permanent, so a miss is remembered as a miss — without this every
 *    recomposition of an old message re-asks the server for a file that will
 *    never come back.
 *  * **In-flight dedupe.** Two rows showing the same photo (or one row
 *    recomposing mid-fetch) share a single fetch.
 *
 * Every failure path — 404, network, undecodable bytes — resolves to null and
 * never throws into composition.
 */
class AttachmentImageLoader(
    private val fetch: suspend (name: String) -> ByteArray,
    private val decoder: ImageBytesDecoder,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {

    private class Entry(val bitmap: ImageBitmap?) {
        val cost: Long = bitmap?.let { it.width.toLong() * it.height * 4 } ?: NEGATIVE_COST
    }

    private val lock = Mutex()
    private val cache = LinkedHashMap<String, Entry>()   // access-ordered by hand
    private val inFlight = HashMap<String, CompletableDeferred<ImageBitmap?>>()
    private var spent = 0L

    /**
     * The bitmap for a message's attachment path, or null (missing, pruned,
     * undecodable — show the pill). Safe to call repeatedly from composition
     * effects; only the first call per name does work.
     */
    suspend fun load(path: String): ImageBitmap? {
        val name = AttachmentText.uploadName(path) ?: return null
        // Fast path + in-flight join decided under the lock; awaiting a peer's
        // fetch and doing our own both happen OUTSIDE it.
        var join: CompletableDeferred<ImageBitmap?>? = null
        var waitFor: CompletableDeferred<ImageBitmap?>? = null
        lock.withLock {
            cache[name]?.let { hit ->
                // Touch: re-insert so eviction order tracks use.
                cache.remove(name); cache[name] = hit
                return hit.bitmap
            }
            val existing = inFlight[name]
            if (existing != null) join = existing
            else {
                waitFor = CompletableDeferred()
                inFlight[name] = waitFor
            }
        }
        join?.let { return it.await() }
        val bitmap = withContext(huginnIoDispatcher) {
            val bytes = runCatching { fetch(name) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) null
            else runCatching { decoder.decode(bytes) }.getOrNull()
        }
        lock.withLock {
            val e = Entry(bitmap)
            cache[name] = e
            spent += e.cost
            inFlight.remove(name)
            // Evict oldest-touched until back under budget; never evict what was
            // just inserted (a single oversized decode still renders once).
            val it = cache.entries.iterator()
            while (spent > budgetBytes && it.hasNext()) {
                val oldest = it.next()
                if (oldest.key == name) continue
                spent -= oldest.value.cost
                it.remove()
            }
        }
        waitFor?.complete(bitmap)
        return bitmap
    }

    companion object {
        const val DEFAULT_BUDGET_BYTES: Long = 48L * 1024 * 1024
        /** What a remembered miss "costs" — nominal, so misses never starve real entries. */
        private const val NEGATIVE_COST: Long = 1024
    }
}

/**
 * The shell's loader, handed down like [LocalTranscriptMetrics]. Null default =
 * no thumbnails (the pill renders instead) — which is also the graceful story
 * against an old daemon with no uploads GET.
 */
val LocalAttachmentImages = staticCompositionLocalOf<AttachmentImageLoader?> { null }
