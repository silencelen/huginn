package com.silencelen.huginn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Unsent composer text, per target, held in memory and written through to
 * [HuginnSettings.drafts].
 *
 * The RULE this exists to enforce is the one the Electron client got wrong: the
 * debounced write must be cancelled when the composer is emptied deliberately —
 * on send, and when the view switches to another target — or the timer fires
 * afterwards carrying the text that was just sent, and the message reappears as
 * a draft in a chat that already has it. That bug is why [clear] and [flush] are
 * separate from [set] rather than being "set to empty".
 *
 * Two further properties are load-bearing and easy to lose in a rewrite:
 *
 *  * **The write reads the map at write TIME**, not at schedule time. A save that
 *    captured its payload when it was queued could still land stale text even
 *    after a cancel race; reading `_drafts.value` inside the coroutine means the
 *    worst case is a redundant write of the correct state.
 *  * **[scope] must outlive the view.** A composition scope is cancelled at the
 *    moment the user leaves a chat, which is exactly when the flush has to run —
 *    so this is constructed once at app level and shared, never per screen.
 */
class DraftBook(
    private val settings: HuginnSettings,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
) {

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    private var save: Job? = null

    /** True while there is a change the store has not been told about yet. */
    private var dirty = false

    /** Reads what was persisted. Call once, before the first composer is drawn. */
    suspend fun load() {
        _drafts.value = settings.drafts.first()
    }

    operator fun get(key: String): String = _drafts.value[key].orEmpty()

    /** A keystroke. Debounced: a write per character is a lot of disk for nothing. */
    fun set(key: String, text: String) {
        if (get(key) == text) return
        put(key, text)
        save?.cancel()
        save = scope.launch {
            delay(debounceMs)
            persist()
        }
    }

    /**
     * The draft is gone on purpose — it was sent, or its target was deleted. The
     * pending write dies with it, which is the whole point of this method.
     */
    fun clear(key: String) {
        if (!_drafts.value.containsKey(key)) return
        put(key, "")
        writeNow()
    }

    /** Leaving the view, or closing the app: land whatever is still in the air. */
    fun flush() {
        if (dirty) writeNow()
    }

    private fun writeNow() {
        save?.cancel()
        save = scope.launch { persist() }
    }

    private suspend fun persist() {
        settings.setDrafts(_drafts.value)
        dirty = false
    }

    private fun put(key: String, text: String) {
        _drafts.value = _drafts.value.toMutableMap().apply {
            if (text.isEmpty()) remove(key) else put(key, text)
        }
        dirty = true
    }

    companion object {
        /** Long enough that ordinary typing writes once, short enough to survive a kill. */
        const val DEBOUNCE_MS: Long = 400

        /**
         * The key shapes both clients use. Written here rather than in either app
         * so a draft typed on the phone and a draft typed on the desktop cannot
         * end up under different names for the same conversation.
         */
        fun chatKey(id: String): String = "chat:$id"

        fun sessionKey(name: String): String = "sess:$name"
    }
}
