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
import androidx.compose.foundation.text.selection.DisableSelection
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
import com.silencelen.huginn.data.HuginnClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
    private val fetchIcon: (suspend (id: String) -> ByteArray)? = null,
    /** Waits before re-asking after a TRANSIENT failure (see [cached]); a test passes short ones. */
    private val retryDelaysMs: List<Long> = listOf(1_000, 3_000),
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

    /**
     * The favicon for one app row, through the daemon's `/v1/apps/:id/icon`
     * route, or null (no such route, no icon, undecodable — draw the tile).
     *
     * ⚠ KEYED ON THE ID **AND** `iconAt`, WHICH IS NOT THE ROW'S `version`. This
     * used to key on `version`, on the theory that the daemon re-fetches a row's
     * favicon when the row is edited — true, and not the only time it does. It
     * also re-fetches on probe, hourly, with nobody touching the row: a site
     * that changed its icon therefore served the OLD picture for the life of the
     * process, cached, with nothing in the logs and no way to let go of it short
     * of restarting the app. `iconAt` is the stamp on the BYTES, which is the
     * question this cache is actually asking.
     *
     * Rows are drawn in a list that recycles, so the cache is still what makes
     * this one request rather than one per scroll.
     *
     * @param iconAt epoch SECONDS; 0 from a daemon that does not report it, which
     *   keys every row the way an id-only key did — one fetch per process.
     */
    suspend fun loadIcon(id: String, iconAt: Long): ImageBitmap? {
        val fetcher = fetchIcon ?: return null
        if (id.isBlank()) return null
        return cached("$ICON_KEY$id@$iconAt") { fetcher(id) }
    }

    /**
     * One image by [key]: the cache, a fetch already in flight, or a fetch of our own.
     *
     * ⚠ THE "PHOTO ATTACHED" BUG (2026-10-30): a photo showed on the device that
     * sent it and as the text pill everywhere else. Two defects here, both of which
     * stuck ONE device for the life of the app:
     *  - a fetch cancelled mid-flight (its row left the screen - a queued row
     *    replaced by the delivered one, a re-window, a scroll) skipped its cleanup:
     *    the in-flight entry stayed and its waiters were never completed, so every
     *    later load of that photo awaited it forever;
     *  - every failure was remembered as "gone", so a timeout, a 502 from the core
     *    proxy or a moment offline became a miss cached until restart.
     * Now the cleanup runs on every exit (a cancelled leader hands the fetch to the
     * next caller), and only a PERMANENT answer ([permanent]: the file is gone,
     * refused, or not an image) is remembered; anything else is retried.
     */
    private suspend fun cached(key: String, fetchBytes: suspend () -> ByteArray): ImageBitmap? {
        while (true) {
            // Fast path + in-flight join decided under the lock; awaiting a peer's
            // fetch and doing our own both happen OUTSIDE it.
            var join: CompletableDeferred<ImageBitmap?>? = null
            var mine: CompletableDeferred<ImageBitmap?>? = null
            lock.withLock {
                cache[key]?.let { hit ->
                    // Touch: re-insert so eviction order tracks use.
                    cache.remove(key); cache[key] = hit
                    return hit.bitmap
                }
                val existing = inFlight[key]
                if (existing != null) join = existing
                else {
                    mine = CompletableDeferred()
                    inFlight[key] = mine!!
                }
            }
            join?.let { j ->
                try {
                    return j.await()
                } catch (e: LeaderGone) {
                    continue // its caller left; this one fetches
                }
            }
            val lead = mine!!
            var got: Loaded? = null
            try {
                val r = withContext(huginnIoDispatcher) { fetchWithRetries(fetchBytes) }
                got = r
                return r.bitmap
            } finally {
                // On EVERY exit, cancellation included - this block is the fix.
                withContext(NonCancellable) {
                    lock.withLock {
                        if (inFlight[key] === lead) inFlight.remove(key)
                        if (got?.remember == true) remember(key, got!!.bitmap)
                    }
                    val done = got
                    if (done == null) lead.completeExceptionally(LeaderGone()) else lead.complete(done.bitmap)
                }
            }
        }
    }

    /** The fetch and decode, re-asked after a transient failure. Never throws but for cancellation. */
    private suspend fun fetchWithRetries(fetchBytes: suspend () -> ByteArray): Loaded {
        var attempt = 0
        while (true) {
            val bytes = try {
                fetchBytes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (permanent(e)) return Loaded(null, remember = true)
                if (attempt >= retryDelaysMs.size) return Loaded(null, remember = false)
                delay(retryDelaysMs[attempt++])
                continue
            }
            if (bytes.isEmpty()) return Loaded(null, remember = true)
            // Bytes that will not decode will not decode next time either.
            return Loaded(runCatching { decoder.decode(bytes) }.getOrNull(), remember = true)
        }
    }

    private fun remember(key: String, bitmap: ImageBitmap?) {
        val e = Entry(bitmap)
        cache[key] = e
        spent += e.cost
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

    private class Loaded(val bitmap: ImageBitmap?, val remember: Boolean)

    /** Completes a waiter whose leader's caller was cancelled: the waiter fetches itself. */
    private class LeaderGone : Exception()

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

        /**
         * And the third space. An upload called `atlas`, a host path `/atlas`
         * and an app id `atlas` are three different fetches from three different
         * routes sharing one LRU; nothing may collide across them.
         */
        private const val ICON_KEY = "icon\u0000"

        /**
         * An answer that will not change on asking again: gone (404/410), refused
         * (401/403 - a re-pair changes that, and the app restarts its client then),
         * or not an image (400/415). Anything else - a timeout, offline, a 5xx or a
         * 502 from the core's proxy - is a moment, not a fact about the file.
         */
        internal fun permanent(e: Throwable): Boolean =
            (e as? HuginnClient.HuginnException)?.code in PERMANENT_CODES

        private val PERMANENT_CODES = setOf(400, 401, 403, 404, 410, 415)
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
        // ⚠ THE CAPTION MUST NOT JOIN THE TRANSCRIPT'S SELECTION. This viewer is
        // opened from a thumbnail inside [MarkdownText], which draws inside the
        // transcript's `SelectionContainer` — and a dialog is a separate layout
        // root that inherits the registrar anyway. A `Text` registered from here
        // makes the next press-drag in the transcript throw "layouts are not part
        // of the same hierarchy". See OverlaysDisableSelectionTest.
        DisableSelection {
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
}

/**
 * The session whose working directory the daemon may also look in when resolving
 * an image path. Null — the default — means the daemon's own roots only, which
 * is the right answer for a chat that has no session behind it.
 */
val LocalImageSession = staticCompositionLocalOf<String?> { null }
