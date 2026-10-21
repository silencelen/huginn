package com.silencelen.huginn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ONE REQUEST ON THE WIRE PER QUESTION. A caller that asks while the same fetch
 * is already in flight waits for that answer instead of sending a second copy.
 *
 * Built for the reads several places start at the same moment for the same
 * reason. The phone's sessions list is the measured one: coming back from a
 * session to the list ran the screen's own refresh AND the list's poll starting
 * up, and a cold start ran the app-wide refresh and the poll, each a
 * `GET /v1/sessions` (~0.4 s of the daemon's single thread at p50) asking for the
 * same rows at once, with the next session's history queued behind both.
 *
 * Only CONCURRENT asks share: once an answer has landed, the next ask fetches
 * again, so nothing here is a cache and nothing is ever stale by more than the
 * one request it rode on. A failure reaches every caller that was waiting for
 * it, each of which handles it as it always did.
 *
 * @param scope where the shared fetch runs, so that one caller giving up (its
 *   screen closed) does not cancel the answer the others are waiting for. It runs
 *   under a supervisor of its own, so a failed fetch never cancels [scope].
 */
class InFlight<T>(private val scope: CoroutineScope) {

    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val lock = Mutex()
    private var current: Deferred<T>? = null

    /** [fetch]'s answer — this caller's own fetch, or the one already on the wire. */
    suspend fun get(fetch: suspend () -> T): T {
        val shared = lock.withLock {
            current?.takeIf { it.isActive } ?: scope.async(supervisor) { fetch() }.also { current = it }
        }
        return shared.await()
    }
}
