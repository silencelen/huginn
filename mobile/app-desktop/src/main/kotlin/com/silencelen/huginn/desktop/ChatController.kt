package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The live layer of ONE open chat: the digest as the server tells it, plus
 * whatever the current run has streamed since.
 *
 * Created per open chat and cancelled when it closes, which is the whole reason
 * it is not part of [AppStore] — a run has a lifecycle and the store does not.
 */
class ChatController(
    private val client: HuginnClient,
    private val chatId: String,
    private val scope: CoroutineScope,
) {

    private val _detail = MutableStateFlow<ChatDetail?>(null)
    val detail: StateFlow<ChatDetail?> = _detail.asStateFlow()

    /** The answer being written right now, before it lands in the digest. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** What the run is doing this instant — the last tool, or "thinking". */
    private val _activity = MutableStateFlow<String?>(null)
    val activity: StateFlow<String?> = _activity.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Position in the queue when a message landed behind a run in flight. */
    private val _queued = MutableStateFlow<Int?>(null)
    val queued: StateFlow<Int?> = _queued.asStateFlow()

    /**
     * A message that has been posted but is not in the digest yet.
     *
     * Without it the composer clears and NOTHING appears until the run finishes —
     * the message the user just sent is simply not on screen while Claude answers
     * it, which reads as the send having failed. The digest is still the rendered
     * truth: this is cleared the moment a refresh comes back carrying the same
     * text, so the bubble is replaced rather than duplicated.
     */
    private val _pendingSend = MutableStateFlow<String?>(null)
    val pendingSend: StateFlow<String?> = _pendingSend.asStateFlow()

    private var streamJob: Job? = null

    /** Guards the reattach loop from becoming an unbounded retry against a dead route. */
    private var reattempts = 0

    fun start() {
        scope.launch {
            refresh()
            attachIfRunning()
        }
    }

    suspend fun refresh() {
        runCatching { client.chat(chatId) }
            .onSuccess {
                _detail.value = it
                _running.value = it.running
                _error.value = null
                val pending = _pendingSend.value
                if (pending != null && it.messages.any { m -> m.type == "user" && m.text == pending }) {
                    _pendingSend.value = null
                }
            }
            .onFailure { _error.value = it.message ?: "could not load chat" }
    }

    /**
     * Reattaches to a run that was already in flight when this view opened.
     *
     * SEED XOR REPLAY, never both. The digest carries `partialText` (what has been
     * written so far) and `seq` (where that text ends in the run's event stream).
     * With both, ask for what comes AFTER seq and start from partialText. Without
     * seq — an older daemon — there is no way to know where the text ends, so
     * replay the run from zero and start from an EMPTY buffer. Doing both renders
     * the answer twice, which is the exact bug this contract exists to prevent.
     */
    private fun attachIfRunning() {
        consume(reattachFlow() ?: return)
    }

    /**
     * The stream to follow to catch up with the run in the current digest, with
     * [_partial] seeded to match it — or null when nothing is running.
     *
     * One function because there are two callers (opening a view onto a running
     * chat, and recovering a dropped socket) and the seed rule must be identical
     * in both. It was written twice first, and the second copy is exactly where a
     * double-rendered answer would come from.
     */
    private fun reattachFlow(): Flow<ChatEvent>? {
        val d = _detail.value ?: return null
        if (!d.running) return null
        val seq = d.seq
        return if (seq != null) {
            _partial.value = d.partialText ?: ""
            client.streamChat(chatId, since = seq)
        } else {
            _partial.value = ""
            client.streamChat(chatId, since = 0)
        }
    }

    /** Posts and follows the run. A chat already running QUEUES instead. */
    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            if (_running.value) {
                // Counted locally: the chat DIGEST does not carry a pending
                // count (only the list row does), and re-fetching the list to
                // learn a number the user is about to see anyway is a round trip
                // for nothing.
                runCatching { client.queueMessage(chatId, body) }
                    .onSuccess { _queued.value = (_queued.value ?: 0) + 1; refresh() }
                    .onFailure { _error.value = it.message ?: "could not queue" }
                return@launch
            }
            _partial.value = ""
            _error.value = null
            _queued.value = null
            _pendingSend.value = body
            _running.value = true
            reattempts = 0
            consume(client.sendMessage(chatId, body))
        }
    }

    fun cancel() {
        scope.launch {
            runCatching { client.cancelChat(chatId) }.onFailure { _error.value = it.message }
            refresh()
        }
    }

    fun close() {
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * Follows [initial] and, if the socket dies with the run still going, follows
     * its replacement — all inside ONE job.
     *
     * The reattach loop lives here rather than in the collector's tail calling
     * `consume` again: doing that made a coroutine cancel itself (`streamJob` at
     * that moment IS the running job) and then launch its successor from inside
     * its own dying body. It happened to work, and it is exactly the kind of thing
     * that stops working after an unrelated change.
     */
    private fun consume(initial: Flow<ChatEvent>) {
        streamJob?.cancel()
        streamJob = scope.launch {
            var flow = initial
            while (true) {
                flow.collect { ev -> apply(ev) }
                // Completing without `done` means the socket went away mid-run.
                // The run is almost certainly still going on the host, so reattach
                // rather than leaving a frozen half-answer on screen — that is
                // what made every chat wedge after a laptop slept.
                if (!_running.value || reattempts >= MAX_REATTACH) break
                reattempts++
                delay(REATTACH_STEP_MS * reattempts)
                refresh()
                flow = reattachFlow() ?: break
            }
        }
    }

    private suspend fun apply(ev: ChatEvent) {
        when (ev) {
            // The daemon persists the user's message BEFORE it starts the run, so
            // this is the first moment the digest can carry it — and the earliest
            // the optimistic bubble can be retired for the real one.
            is ChatEvent.Started -> {
                _running.value = true
                _activity.value = "thinking"
                refresh()
            }
            is ChatEvent.Delta -> { _partial.value += ev.text; _activity.value = null }
            is ChatEvent.Assistant -> {
                // The digest is the rendered truth; the stream only says it grew.
                _partial.value = ""
                _activity.value = null
                refresh()
            }
            is ChatEvent.ToolStart -> _activity.value = ev.name
            is ChatEvent.Tool -> _activity.value = ev.name
            is ChatEvent.Result -> _activity.value = null
            is ChatEvent.Failure -> {
                _error.value = ev.text
                // NOT cleared here: a failure frame may precede a reattach, and
                // blanking `running` would stop the reattach from happening.
            }
            ChatEvent.Done -> {
                _running.value = false
                _activity.value = null
                _partial.value = ""
                _queued.value = null
                _pendingSend.value = null
                reattempts = 0
                refresh()
            }
        }
    }

    private companion object {
        const val MAX_REATTACH = 5

        /** Linear, not exponential: five tries reach 15s, which is the right shape for a socket that may simply have blinked. */
        const val REATTACH_STEP_MS = 1_000L
    }
}
