package com.silencelen.huginn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Previously sent messages per target, oldest first, for Up/Down recall in the
 * composer — the shell-history muscle memory Claude Code's own CLI already has.
 *
 * DraftBook's sibling on purpose: same keys ([DraftBook.chatKey] /
 * [DraftBook.sessionKey] — never mint a second key shape), same app-level-scope
 * contract, same merge-not-assign load. Differences are deliberate:
 * writes happen per SEND (rare), so there is no debounce — every mutation
 * persists immediately and there is nothing to flush on exit.
 */
class SentHistory(
    private val settings: HuginnSettings,
    private val scope: CoroutineScope,
) {

    private val _entries = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val entries: StateFlow<Map<String, List<String>>> = _entries.asStateFlow()

    /**
     * Reads what was persisted. Call once, at app start. Merges rather than
     * assigns for the same reason DraftBook.load does: a send can land in the
     * gap before the load completes, and what was just sent outranks the disk.
     */
    suspend fun load() {
        val stored = settings.sentHistory.first()
        val live = _entries.value
        _entries.value = if (live.isEmpty()) stored else stored + live
    }

    operator fun get(key: String): List<String> = _entries.value[key].orEmpty()

    /**
     * A message was submitted. Blank text is not history; an exact repeat of the
     * newest entry is not a second entry (holding Up and resending must not fill
     * the list with copies). Capped: recall is for recent muscle memory, not an
     * archive — the transcript already keeps everything.
     */
    fun record(key: String, text: String) {
        if (text.isBlank()) return
        val cur = get(key)
        if (cur.lastOrNull() == text) return
        val next = (cur + text).takeLast(CAPACITY)
        _entries.value = _entries.value.toMutableMap().apply { put(key, next) }
        persist()
    }

    /** The target was renamed: its history follows, like its draft does. */
    fun move(from: String, to: String) {
        if (from == to) return
        val hist = _entries.value[from] ?: return
        _entries.value = _entries.value.toMutableMap().apply {
            remove(from)
            if (hist.isNotEmpty()) put(to, hist)
        }
        persist()
    }

    /** The target is gone (chat deleted, session ended): drop its history. */
    fun clear(key: String) {
        if (!_entries.value.containsKey(key)) return
        _entries.value = _entries.value.toMutableMap().apply { remove(key) }
        persist()
    }

    private fun persist() {
        // Reads the map at write time (DraftBook's rule): worst case under a race
        // is a redundant write of the correct state, never a stale snapshot.
        scope.launch { settings.setSentHistory(_entries.value) }
    }

    companion object {
        const val CAPACITY: Int = 50
    }
}
