package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.PaneLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one place that knows whether this process is holding a tmux window at its
 * own geometry, and the only thing that hands it back.
 *
 * APP-LEVEL rather than per-view, and that is the whole reason it exists as an
 * object at all: the release paths do not share a lifetime. Leaving the session
 * view is a composition event, minimizing is a window event, and being killed is
 * neither — so the thing that owes the release cannot live inside the view that
 * took it. [PaneLease] decides *whether*; this decides *when the wire call goes
 * out* and guarantees it goes out exactly once per hold.
 *
 * What is at stake: the daemon puts the window into `window-size manual` for 90
 * seconds and the poll renews it, so a client that stops polling without releasing
 * leaves the owner's terminal pinned at this window's shape until the lease lapses
 * — and one that keeps polling while hidden pins it indefinitely. The daemon has
 * sweepers for the crash case, but a sweeper is a backstop, not a contract.
 */
class PaneLeaseHolder(
    private val client: HuginnClient,
    private val scope: CoroutineScope,
) {

    /**
     * Serializes reconciliation. Two events can want the lease changed at once — a
     * window minimizing while a resize is in flight — and interleaving them can
     * write `held` after the release that was supposed to clear it, which strands
     * the window with nothing left that knows to release it.
     */
    private val mutex = Mutex()

    /** The session whose geometry this process has asked for, if any. */
    @Volatile
    private var held: String? = null

    /** Whatever this holder currently believes it is holding, for the UI to state. */
    val heldSession: String? get() = held

    /**
     * RELEASE FIRST, then take. Called before every geometry-bearing request and on
     * every exit path with `want = null`.
     *
     * Ordering is the point: releasing after acquiring the new one would leave two
     * windows manual for the width of a round trip, and if the process dies in that
     * window the old one is stranded with no record of it anywhere.
     */
    suspend fun reconcile(want: PaneLease.Want?) {
        // NON-CANCELLABLE, and this is not belt and braces. Every caller reaches
        // here from inside a `collectLatest` whose whole job is to be cancelled the
        // instant the window is hidden or the view changes — so the release for
        // "hidden" would be cancelled by the very next thing that happens, at the
        // suspension point in the middle of the HTTP call, leaving `held` already
        // cleared and the window still manual with nothing left that knows.
        withContext(NonCancellable) {
            mutex.withLock {
                PaneLease.toRelease(held, want)?.let { name ->
                    // Cleared BEFORE the call, not after: a release that throws must
                    // still count as "we are no longer asking", or a failed release
                    // becomes a permanent belief that we hold something we do not.
                    held = null
                    // The daemon sweeps stranded leases anyway, and failing to release
                    // must never break whatever is being torn down.
                    runCatching { client.releaseSize(name) }
                }
                if (want != null) held = want.session
            }
        }
    }

    /** Hand back whatever is held. The teardown path for a view or a window. */
    suspend fun releaseAll() = reconcile(null)

    /**
     * Fire-and-forget release, launched on the APP scope.
     *
     * The caller is a view being disposed, whose own scope is cancelled on the
     * same frame; a coroutine started there would never reach the socket.
     */
    fun releaseAsync() {
        scope.launch { releaseAll() }
    }

    /**
     * The last-chance release, for `onCloseRequest` and the JVM shutdown hook.
     *
     * BLOCKING and BOUNDED. Blocking because the process is about to stop existing
     * and a launched coroutine would simply not run; bounded because a daemon that
     * is not answering must delay quitting by two seconds, not forever — a client
     * that will not close is worse than a lease that lapses on its own in ninety.
     */
    fun releaseBlocking(timeoutMs: Long = RELEASE_TIMEOUT_MS) {
        if (held == null) return
        runCatching {
            runBlocking { withTimeoutOrNull(timeoutMs) { releaseAll() } }
        }
    }

    private companion object {
        const val RELEASE_TIMEOUT_MS = 2_000L
    }
}
