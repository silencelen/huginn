package com.silencelen.huginn.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * When suggested next messages may be asked for, and when they may be shown.
 *
 * A suggestion is a DRAFT, not a decision: picking one fills the composer and
 * the reader still presses send. Everything here follows from that — chips that
 * cover a composer with typing in it, or that offer a follow-up to an answer
 * still being written, are worse than no chips at all.
 */
object Suggest {

    /**
     * Whether the chips belong on screen right now.
     *
     * Three suppressions, each one a way of being stale rather than a preference:
     * a turn in flight (the reply they would follow is not written yet), a draft
     * already typed (the reader has said what they want), and an empty list.
     */
    fun visible(suggestions: List<String>, busy: Boolean, draft: String): Boolean =
        suggestions.isNotEmpty() && !busy && draft.isBlank()

    /**
     * Whether to ask the daemon for a new set.
     *
     * Keyed on the transcript's offset, which is what the daemon itself caches on:
     * the same offset is the same conversation, so asking again would spend a
     * model call to be told what we were told last time.
     */
    fun shouldFetch(offset: Long?, lastOffset: Long, busy: Boolean, inFlight: Boolean): Boolean =
        offset != null && !busy && !inFlight && offset != lastOffset
}

/**
 * The fetching half: holds the current set and asks for a new one at a turn
 * boundary — the transcript grew and nothing is running.
 *
 * Failure is SILENT on purpose. Suggestions are a nicety; an error banner
 * because a convenience could not be generated is noise about something nobody
 * asked for.
 */
class SuggestionCue(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> List<String>,
) {

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private var lastOffset = NEVER
    private var job: Job? = null

    /**
     * Called whenever the transcript or the run state changes.
     *
     * A busy chat drops what it has: suggestions written for the previous reply
     * are stale the moment there is a newer one coming. The offset is REMEMBERED
     * across that, so the answer that just arrived is not re-fetched for the
     * offset it was already generated at.
     */
    fun onTurnBoundary(offset: Long?, busy: Boolean) {
        if (busy) {
            if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
            return
        }
        if (!Suggest.shouldFetch(offset, lastOffset, busy = false, inFlight = job?.isActive == true)) return
        lastOffset = offset ?: return
        job = scope.launch {
            runCatching { fetch() }.onSuccess { _suggestions.value = it }
        }
    }

    /** Leaving the conversation: forget the set and the offset it was for. */
    fun clear() {
        job?.cancel()
        job = null
        _suggestions.value = emptyList()
        lastOffset = NEVER
    }

    private companion object {
        const val NEVER = -1L
    }
}
