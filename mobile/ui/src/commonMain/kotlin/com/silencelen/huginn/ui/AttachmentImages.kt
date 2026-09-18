package com.silencelen.huginn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
    private val fetchPath: (suspend (path: String, session: String?) -> ByteArray)? = null,
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
        return cached(name) { fetch(name) }
    }

    /**
     * The bitmap for a file path the ASSISTANT named, through the daemon's
     * `/v1/files/image` route, or null (no such route, refused, missing,
     * undecodable — draw the placeholder).
     *
     * ⚠ KEYED ON THE FULL PATH, unlike [load]. An uploads basename is unique by
     * construction because the server minted it; an assistant's is not — every
     * session writes `screenshot.png` into its own directory, and a basename key
     * would serve the first one to all of them. The wrong picture, cached, with
     * nothing in the logs.
     *
     * @param session asks the daemon to also consider that session's working
     * directory. It widens the DAEMON's search, never this client's.
     */
    suspend fun loadPath(path: String, session: String? = null): ImageBitmap? {
        val fetcher = fetchPath ?: return null
        if (path.isBlank()) return null
        return cached(PATH_KEY + path) { fetcher(path, session) }
    }

    private suspend fun cached(key: String, fetchBytes: suspend () -> ByteArray): ImageBitmap? {
        // Fast path + in-flight join decided under the lock; awaiting a peer's
        // fetch and doing our own both happen OUTSIDE it.
        var join: CompletableDeferred<ImageBitmap?>? = null
        var waitFor: CompletableDeferred<ImageBitmap?>? = null
        lock.withLock {
            cache[key]?.let { hit ->
                // Touch: re-insert so eviction order tracks use.
                cache.remove(key); cache[key] = hit
                return hit.bitmap
            }
            val existing = inFlight[key]
            if (existing != null) join = existing
            else {
                waitFor = CompletableDeferred()
                inFlight[key] = waitFor
            }
        }
        join?.let { return it.await() }
        val bitmap = withContext(huginnIoDispatcher) {
            val bytes = runCatching { fetchBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) null
            else runCatching { decoder.decode(bytes) }.getOrNull()
        }
        lock.withLock {
            val e = Entry(bitmap)
            cache[key] = e
            spent += e.cost
            inFlight.remove(key)
            // Evict oldest-touched until back under budget; never evict what was
            // just inserted (a single oversized decode still renders once).
            val it = cache.entries.iterator()
            while (spent > budgetBytes && it.hasNext()) {
                val oldest = it.next()
                if (oldest.key == key) continue
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

        /**
         * Namespaces the full-path keys away from [load]'s basename keys. The two
         * share one LRU (one budget, one eviction order) but must never collide:
         * an upload called `a.png` and a host path `/tmp/a.png` are two different
         * fetches from two different routes.
         */
        private const val PATH_KEY = "path\u0000"
    }
}

/**
 * The shell's loader, handed down like [LocalTranscriptMetrics]. Null default =
 * no thumbnails (the pill renders instead) — which is also the graceful story
 * against an old daemon with no uploads GET.
 */
val LocalAttachmentImages = staticCompositionLocalOf<AttachmentImageLoader?> { null }

/**
 * Which image, if any, is open at full size.
 *
 * A hoisted holder rather than a `remember` inside the composable, so the rules —
 * what opens it, what closes it, and what must NOT open it — are asserted in a
 * test instead of by looking at a screenshot.
 */
class ImageViewerState {

    private var shown by mutableStateOf<Pair<String, ImageBitmap>?>(null)

    val path: String? get() = shown?.first
    val bitmap: ImageBitmap? get() = shown?.second
    val isOpen: Boolean get() = shown != null

    /**
     * Opens [path] at full size. A null [bitmap] is a no-op: a thumbnail that
     * never decoded has nothing to show, and an empty viewer someone has to
     * dismiss is worse than a tap that did nothing.
     */
    fun open(path: String, bitmap: ImageBitmap?) {
        if (bitmap != null) shown = path to bitmap
    }

    /** Closes it, and lets go of the bitmap rather than pinning it in RAM. */
    fun close() { shown = null }
}

/**
 * The full-size view of a thumbnail that was tapped. A dismissable overlay with
 * the picture and its path — no zoom, no pan, no gallery: the question this
 * answers is "what does that actually say", and the next tap is always away.
 */
@Composable
fun FullImageViewer(state: ImageViewerState) {
    val bitmap = state.bitmap ?: return
    Dialog(onDismissRequest = state::close) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 3.dp,
        ) {
            Column(
                Modifier
                    .padding(10.dp)
                    .clickable(onClick = state::close),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = state.path,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 720.dp).widthIn(max = 960.dp),
                )
                state.path?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The session whose working directory the daemon may also look in when resolving
 * an image path. Null — the default — means the daemon's own roots only, which
 * is the right answer for a chat that has no session behind it.
 */
val LocalImageSession = staticCompositionLocalOf<String?> { null }
