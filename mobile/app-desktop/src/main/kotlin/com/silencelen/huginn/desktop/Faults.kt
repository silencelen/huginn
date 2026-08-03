package com.silencelen.huginn.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What this client is FAILING AT right now — the one sentence the status line
 * shows in place of everything else.
 *
 * THE BUG THIS EXISTS TO KILL. The store used to hold a single nullable string
 * that `note()` wrote and only a CLICK ever cleared. Nothing cleared it on
 * success. So one 401 — and after the token wipe of 0.3.0 there were minutes of
 * them, then the owner retyped the token and everything worked — left the word
 * "unauthorized" pinned to the foot of the window while sessions streamed in
 * front of it. A status line that reports a condition the app is demonstrably not
 * in is worse than no status line, because it makes the true ones unreadable.
 *
 * So a fault here has a LIFETIME, and two ways to end:
 *
 *  * **The next success on the same source clears it.** [ok] is called from every
 *    poll that returns, which is what makes the bar current rather than
 *    cumulative — chats failing does not hide sessions succeeding and vice versa.
 *  * **It expires.** [sweep] drops anything older than [ttlMs]. This is what
 *    covers the sources that have no poll behind them: a rename that 400s is
 *    reported once and never again, and without a clock it would sit there until
 *    somebody clicked it.
 *
 * Faults are kept PER SOURCE rather than as one slot, so a failing poll cannot be
 * masked by a different poll succeeding a millisecond later — with a shared slot
 * the two would fight every 5 seconds and the bar would strobe.
 *
 * Pure and clock-injected: what is on screen and when it goes away is exactly the
 * kind of decision that fails silently, which is the project's bar for a test.
 */
class Faults(
    private val ttlMs: Long = TTL_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private data class Fault(val message: String, val atMs: Long)

    /** Guards the map and [dismissed]: poll loop, watch loop and the UI all write. */
    private val lock = Any()
    private val live = LinkedHashMap<String, Fault>()

    /**
     * The message the reader has already acknowledged. Held as TEXT rather than as
     * a flag, so a poll that keeps failing the same way stays dismissed while a
     * DIFFERENT failure gets through — dismissing "unauthorized" must not also
     * hide "no route to host" thirty seconds later.
     */
    private var dismissed: String? = null

    private val _current = MutableStateFlow<String?>(null)

    /** What to show, or null for "nothing is wrong that the reader has not seen". */
    val current: StateFlow<String?> = _current.asStateFlow()

    /** A call against [source] failed, with the server's own words where there are any. */
    fun fail(source: String, message: String) {
        synchronized(lock) {
            live[source] = Fault(message.ifBlank { "network error" }, now())
            publish()
        }
    }

    /** A call against [source] came back. Whatever it was failing at is over. */
    fun ok(source: String) {
        synchronized(lock) {
            if (live.remove(source) != null) publish()
        }
    }

    /** Drops faults nothing has repeated within [ttlMs]. Driven by the poll loop. */
    fun sweep() {
        synchronized(lock) {
            val cutoff = now() - ttlMs
            if (live.values.none { it.atMs <= cutoff }) return
            live.values.retainAll { it.atMs > cutoff }
            publish()
        }
    }

    /**
     * The reader clicked it. Hides THIS message; a different one still gets
     * through, and a success resets the acknowledgement so the same words can be
     * shown again if the condition genuinely returns.
     */
    fun dismiss() {
        synchronized(lock) {
            dismissed = _current.value ?: dismissed
            publish()
        }
    }

    private fun publish() {
        val newest = live.values.maxByOrNull { it.atMs }
        if (newest == null) {
            dismissed = null
            _current.value = null
            return
        }
        if (newest.message == dismissed) {
            _current.value = null
            return
        }
        dismissed = null
        _current.value = newest.message
    }

    companion object {
        /**
         * Long enough that a failure which is still true is re-reported by the next
         * 5s poll before this drops it; short enough that a one-shot failure does
         * not outlive the reader's memory of causing it.
         */
        const val TTL_MS: Long = 30_000

        // The sources. Named rather than passed as literals so a typo cannot invent
        // a second slot for something already tracked — an untracked source never
        // clears, which is the bug this whole file is about.
        const val CHATS: String = "chats"
        const val SESSIONS: String = "sessions"
        const val STATUS: String = "status"

        /** Everything the SHELL does by hand: rename, delete, interrupt, end. */
        const val ACTION: String = "action"
    }
}
