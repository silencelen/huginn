package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.TranscriptPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What opening a session loads besides its pane, and THE ORDER IT LOADS IT IN:
 * the history first.
 *
 * ⚠ THE HISTORY IS WHAT THE READER OPENED THE SESSION FOR, and it used to queue
 * behind the strip. Opening a session put the transcript, the stream strip's
 * `agents?all=1` and the overview map on the wire together. The daemon answers
 * one request at a time, and `agents?all=1` on a session that had run ~160
 * subagents took ~3 s with nothing else answered meanwhile: on the owner's phone
 * the history appeared at ~5 s, after the strip. So the optional loads now wait
 * for the history's FIRST ANSWER (a page, or a failure, so a session with no
 * transcript does not hold them): [startAgents] does, and the view model's
 * overview poll asks [awaitHistory] before its first fetch unless the Overview
 * tab is what is on screen. Once everything has loaded the screen is the same as
 * it was; only the order changed.
 *
 * Out of the view model for the reason [AgentStream] is: `HuginnViewModel` is an
 * `AndroidViewModel`, nothing here may touch an Application on this host, and
 * the open is the part with the failures in it. One instance for the view
 * model's life, reused session after session, like the polls it replaced.
 *
 * @param errText the reader's sentence for a failure ([errorTextFor]).
 * @param toast where a failed "load earlier" is said.
 */
internal class SessionLoads(
    private val scope: CoroutineScope,
    private val client: HuginnClient,
    private val errText: (Throwable) -> String,
    private val toast: (String) -> Unit,
    private val maxEvents: Int = MAX_TRANSCRIPT_EVENTS,
    private val tailMs: Long = TAIL_MS,
    private val agentsMs: Long = HuginnViewModel.AGENTS_POLL_MS,
) {
    private val _history = MutableStateFlow<TranscriptPage?>(null)

    /** The session's own transcript, tailed. Null until its first page lands. */
    val history: StateFlow<TranscriptPage?> = _history.asStateFlow()

    private val _historyError = MutableStateFlow<String?>(null)

    /** Why there is no history to show — only while none has ever landed. */
    val historyError: StateFlow<String?> = _historyError.asStateFlow()

    private val _loadingEarlier = MutableStateFlow(false)
    val loadingEarlier: StateFlow<Boolean> = _loadingEarlier.asStateFlow()

    private val _agents = MutableStateFlow<List<AgentRun>>(emptyList())

    /** Every agent the session has spawned, for the picker strip. See [fetchStreamAgents]. */
    val agents: StateFlow<List<AgentRun>> = _agents.asStateFlow()

    /** The session whose history is loading or loaded here, or null. */
    private var openFor: String? = null

    /**
     * The byte the OLDEST page on screen begins at, and the handle for reading
     * further back. Null until a page lands; 0 once the whole conversation is in
     * view.
     */
    private var historyStart: Long? = null

    private var tailJob: Job? = null
    private var agentsJob: Job? = null

    /**
     * Completed by the open session's first answer. Starts completed, so nothing
     * waits on a session that is not loading here.
     */
    private var firstAnswer: CompletableDeferred<Unit> = CompletableDeferred(Unit)

    /** Tails the session's Claude transcript: the structured conversation view. */
    fun startHistory(name: String) {
        tailJob?.cancel()
        _history.value = null
        _historyError.value = null
        historyStart = null
        openFor = name
        // Whatever waited on the previous open is released, never stranded.
        firstAnswer.complete(Unit)
        val answered = CompletableDeferred<Unit>()
        firstAnswer = answered
        tailJob = scope.launch {
            var offset: Long? = null
            try {
                while (isActive) {
                    val r = runCatching { client.sessionTranscript(name, offset) }
                    // ⚠ A CANCELLATION IS THIS POLL ENDING, NOT A HISTORY THAT
                    // WOULD NOT LOAD. Caught by runCatching, it reached the branch
                    // below and could draw the cancellation as an error on the
                    // NEXT session's empty screen. The chat's loader learned this
                    // first (loadChatTranscript).
                    if (r.isFailure) ensureActive()
                    r.onSuccess { page ->
                        _historyError.value = null
                        // The tmux name now belongs to a different Claude session, so
                        // both handles into the old transcript are void: the offset is
                        // a byte position in a file this session never wrote, and the
                        // history handle points into its history. Start over; the next
                        // poll reads the new session's tail from scratch.
                        if (isTranscriptRestart(_history.value, page)) {
                            offset = null
                            historyStart = null
                        } else {
                            offset = page.nextOffset
                            // The first page decides where history begins; every tail
                            // read after it is BELOW that and must not move the handle.
                            if (historyStart == null) historyStart = page.windowStart
                        }
                        // :core's merge, not a copy of it: a hand-rolled one here once
                        // dropped `state`, `modelDisplay`, `mode` and `claudeSessionId`
                        // on every tail read that did not carry them, and never cleared
                        // a delivered message's `queued` badge.
                        _history.value = mergeTranscriptPage(_history.value, page, maxEvents)
                    }.onFailure { e ->
                        if (_history.value == null) {
                            _historyError.value = when {
                                e is HuginnClient.HuginnException && e.code == 409 -> e.message
                                else -> errText(e)
                            }
                        }
                    }
                    answered.complete(Unit)
                    delay(tailMs)
                }
            } finally {
                // Ended before it answered: what was waiting goes ahead.
                answered.complete(Unit)
            }
        }
    }

    /**
     * Waits until [name]'s history has had its first answer, when [name]'s history
     * is what is loading here. Returns at once for anything else, so a caller can
     * never be held by a session that is not open.
     */
    suspend fun awaitHistory(name: String) {
        if (openFor == name) firstAnswer.await()
    }

    /**
     * Leaving the session. Its handles go with it: kept, they made the NEXT
     * session's first "load earlier" ask for a byte offset into a file it never
     * wrote, and a spinner left true could never be cleared, since only the
     * in-flight request clears it and that request is now for somebody else.
     */
    fun stopHistory() {
        tailJob?.cancel()
        tailJob = null
        openFor = null
        firstAnswer.complete(Unit)
        _history.value = null
        _historyError.value = null
        historyStart = null
        _loadingEarlier.value = false
    }

    /**
     * Reads the page before the oldest one on screen and puts it in front.
     *
     * A cold open only gets the tail, and on a long session that is a sliver of
     * it — 51 events out of 3452 on a real transcript here. Pages abut, because
     * a windowStart is a record boundary, so this neither repeats nor skips.
     */
    fun loadEarlier(name: String) {
        val until = historyStart ?: _history.value?.windowStart ?: return
        if (until <= 0L || _loadingEarlier.value) return
        _loadingEarlier.value = true
        scope.launch {
            val page = runCatching { client.sessionTranscript(name, until = until) }
                .onFailure { if (stillReading(name)) toast(errText(it)) }
                .getOrNull()
            // ⚠ THE ANSWER IS ONLY FOR THE SESSION THAT ASKED. This launch is
            // tracked by no field and outlives the screen, so a page that arrives
            // after the reader has opened another session used to be welded on top
            // of THAT session's transcript — another conversation, permanently,
            // with no restart guard to undo it (prependTranscriptPage keeps the
            // current page's claudeSessionId). The request can be in flight for
            // tens of seconds: the daemon reads backwards with a doubling window
            // and this call has no timeout.
            if (page != null && stillReading(name)) {
                historyStart = page.windowStart
                _history.value = prependTranscriptPage(_history.value, page)
            }
            if (stillReading(name)) _loadingEarlier.value = false
        }
    }

    /** Whether [name] is still the session on screen — see [loadEarlier]. */
    private fun stillReading(name: String): Boolean = pageStillWanted(name, openFor)

    /**
     * The agents themselves, for the strip — after the history has answered.
     *
     * Lifecycle-gated by its caller like every poll on the session screen:
     * `?all=1` lifts the daemon's recency filter, so this is the one agents call
     * that grows without bound and must not run from a pocket.
     */
    fun startAgents(name: String) {
        agentsJob?.cancel()
        agentsJob = scope.launch {
            awaitHistory(name)
            while (isActive) {
                runCatching { fetchStreamAgents(client, name) }
                    .onSuccess { _agents.value = it }
                delay(agentsMs)
            }
        }
    }

    fun stopAgents() {
        agentsJob?.cancel()
        agentsJob = null
        _agents.value = emptyList()
    }

    companion object {
        /** The history tail's pace while a session is open. */
        const val TAIL_MS: Long = 2_500
    }
}
