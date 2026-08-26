package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.SuggestionCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The live layer of ONE open chat: its transcript, its metadata, and whatever the
 * current run has streamed since.
 *
 * THE HISTORY IS THE REAL CLAUDE CODE TRANSCRIPT, not the digest this daemon
 * persists — the same source the phone reads, and the same source a tmux
 * session's conversation is drawn from. The digest is a flat list of user /
 * assistant / tool lines, so a client rendering it structurally cannot show
 * thinking blocks, subagent groups or tool RESULTS: they are not in it. It is
 * still fetched, for the things only it knows (whether a run is live, where its
 * partial text ends, the model and effort that will apply to the next turn).
 *
 * The live SSE stream is still followed while a turn is in flight, because the
 * transcript is only written as blocks complete and a chat should show tokens as
 * they arrive.
 *
 * Created per open chat and cancelled when it closes, which is the whole reason
 * it is not part of [AppStore] — a run has a lifecycle and the store does not.
 */
class ChatController(
    private val client: HuginnClient,
    private val chatId: String,
    private val scope: CoroutineScope,
    /**
     * Where a REFUSED send's text goes — back into the composer's draft, so a
     * daemon 4xx (machine offline, mode refused) costs the user nothing typed.
     * The audit found the refused message sitting on screen as a delivered
     * bubble with busy() latched instead.
     */
    private val onRefusedSend: (String) -> Unit = {},
) {

    private val _detail = MutableStateFlow<ChatDetail?>(null)
    val detail: StateFlow<ChatDetail?> = _detail.asStateFlow()

    /** The conversation itself. Null until the first read settles. */
    private val _page = MutableStateFlow<TranscriptPage?>(null)
    val page: StateFlow<TranscriptPage?> = _page.asStateFlow()

    /** The answer being written right now, before it lands in the transcript. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** What the run is doing this instant — the last tool, or "thinking". */
    private val _activity = MutableStateFlow<String?>(null)
    val activity: StateFlow<String?> = _activity.asStateFlow()

    /**
     * Why the CONVERSATION could not be read, when it could not.
     *
     * Kept apart from [notice] because they are drawn in different places for a
     * reason the phone learned the hard way: a failed load that renders as the
     * pristine empty state looks exactly like a chat that has never run, which
     * reads as data loss. This one replaces the transcript; a notice never does.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The outcome of something the reader asked for — a refused delete, a failed rename. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun dismissNotice() { _notice.value = null }

    /** Set once the chat is gone, so the shell can stop showing it. */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Models the installed CLI offers, so the picker cannot go stale. */
    private val _models = MutableStateFlow<List<ModelChoice>>(emptyList())
    val models: StateFlow<List<ModelChoice>> = _models.asStateFlow()

    /**
     * A message that has been posted but is not in the transcript yet.
     *
     * Without it the composer clears and NOTHING appears until the run's first
     * block completes — the message the user just sent is simply not on screen
     * while Claude answers it, which reads as the send having failed. The
     * transcript is still the rendered truth: this is cleared the moment a read
     * comes back carrying the same text, so the bubble is replaced rather than
     * duplicated.
     */
    private val _pendingSend = MutableStateFlow<String?>(null)
    val pendingSend: StateFlow<String?> = _pendingSend.asStateFlow()

    /** Suggested next messages. The rule for when to ask lives in `:core`. */
    private val cue = SuggestionCue(scope) { client.chatSuggestions(chatId).suggestions }
    val suggestions: StateFlow<List<String>> = cue.suggestions

    private var streamJob: Job? = null
    private var loadJob: Job? = null

    /** Guards the reattach loop from becoming an unbounded retry against a dead route. */
    private var reattempts = 0

    /**
     * Whether the current flow has streamed anything at all. A Failure as the
     * VERY FIRST event is the shape of an HTTP refusal (the daemon said no
     * before any run started) — terminal, not reattachable.
     */
    private var sawStream = false

    /**
     * Everything that means "a turn is in flight". Suggestions, the queue tag and
     * the composer's placeholder all key off it, so it is one definition rather
     * than three that drift.
     */
    fun busy(): Boolean =
        _running.value || _partial.value.isNotEmpty() || _activity.value != null || _pendingSend.value != null

    fun start() {
        scope.launch {
            refresh()
            loadTranscript()
            attachIfRunning()
        }
        scope.launch {
            runCatching { client.models() }.onSuccess { _models.value = it }
        }
    }

    /** The metadata: running, mode, model, effort, and where a live run has got to. */
    suspend fun refresh() {
        runCatching { client.chat(chatId) }
            .onSuccess {
                _detail.value = it
                _running.value = it.running
            }
            .onFailure { _notice.value = it.message ?: "could not read this chat" }
    }

    /**
     * Reads the conversation.
     *
     * A 409 is the ONLY failure that means "nothing here yet" — the chat exists
     * and has never run. Everything else is a failure to read history that does
     * exist, and must not be drawn as its absence.
     */
    fun loadTranscript() {
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { client.chatTranscript(chatId) }
                .onSuccess {
                    _page.value = it
                    _error.value = null
                    val pending = _pendingSend.value
                    if (pending != null && it.events.any { e -> e.kind == "user" && e.text?.trim() == pending }) {
                        _pendingSend.value = null
                    }
                    cue.onTurnBoundary(it.nextOffset, busy())
                }
                .onFailure { e ->
                    val neverRan = e is HuginnClient.HuginnException && e.code == 409
                    if (_page.value == null) {
                        if (neverRan) _page.value = TranscriptPage()
                        else _error.value = e.message ?: "could not load this conversation"
                    }
                }
        }
    }

    /** Retries after a failure the reader can see. */
    fun retry() {
        _error.value = null
        _page.value = null
        loadTranscript()
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

    /**
     * Posts and follows the run. A chat already running QUEUES instead: the daemon
     * holds the message and delivers it when the current run ends, and the
     * transcript then carries it as a user message tagged `queued` — in place,
     * where it was typed, rather than as a banner about a message that is not
     * shown.
     */
    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        cue.clear()
        scope.launch {
            if (_running.value) {
                runCatching { client.queueMessage(chatId, body) }
                    .onSuccess { loadTranscript() }
                    .onFailure { _notice.value = it.message ?: "could not queue that message" }
                return@launch
            }
            _partial.value = ""
            _notice.value = null
            _pendingSend.value = body
            _running.value = true
            reattempts = 0
            consume(client.sendMessage(chatId, body))
        }
    }

    fun cancel() {
        scope.launch {
            runCatching { client.cancelChat(chatId) }.onFailure { _notice.value = it.message }
            refresh()
        }
    }

    /**
     * Model, effort and mode for the NEXT turn — the daemon fixes the flags when a
     * run spawns, so a turn already in flight keeps what it started with.
     *
     * An empty string clears the field back to the host's default, which is why
     * these are `String?` (absent) rather than blank meaning absent.
     */
    fun setOptions(model: String? = null, effort: String? = null, mode: String? = null) {
        scope.launch {
            runCatching { client.updateChat(chatId, model = model, effort = effort, mode = mode) }
                .onSuccess { _detail.value = it }
                .onFailure { _notice.value = it.message ?: "could not change that" }
        }
    }

    fun rename(title: String) {
        val next = title.trim()
        if (next.isEmpty()) return
        scope.launch {
            runCatching { client.renameChat(chatId, next) }
                .onSuccess { refresh() }
                .onFailure { _notice.value = it.message ?: "could not rename this chat" }
        }
    }

    /**
     * Deletes the chat. The daemon REFUSES while a run is active (409, "cancel
     * first"), and that refusal is reported rather than swallowed: a delete button
     * that silently does nothing is worse than one that says why it did not.
     */
    fun delete() {
        scope.launch {
            runCatching { client.deleteChat(chatId) }
                .onSuccess { _deleted.value = true }
                .onFailure { _notice.value = it.message ?: "could not delete this chat" }
        }
    }

    fun close() {
        streamJob?.cancel()
        streamJob = null
        loadJob?.cancel()
        loadJob = null
        cue.clear()
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
                sawStream = false
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
            // Out of tries with the run still marked live: release the composer so
            // the reader can act, and read whatever did land.
            if (reattempts >= MAX_REATTACH) {
                _running.value = false
                _activity.value = null
                _partial.value = ""
                _pendingSend.value = null
                loadTranscript()
            }
        }
    }

    private suspend fun apply(ev: ChatEvent) {
        if (ev !is ChatEvent.Failure) sawStream = true
        when (ev) {
            // The daemon persists the user's message BEFORE it starts the run, so
            // this is the first moment the transcript can carry it — and the
            // earliest the optimistic bubble can be retired for the real one.
            is ChatEvent.Started -> {
                _running.value = true
                _activity.value = "thinking"
                loadTranscript()
            }
            is ChatEvent.Delta -> { _partial.value += ev.text; _activity.value = null }
            is ChatEvent.Assistant -> {
                // The block is complete and now in the transcript, which is the
                // richer source: re-read rather than keeping a second copy.
                _partial.value = ""
                _activity.value = null
                loadTranscript()
            }
            is ChatEvent.ToolStart -> _activity.value = ev.name
            is ChatEvent.Tool -> { _activity.value = null; loadTranscript() }
            is ChatEvent.Result -> { _activity.value = null; loadTranscript() }
            is ChatEvent.Failure -> {
                _notice.value = ev.text
                _activity.value = null
                if (!sawStream) {
                    // Nothing streamed before this: the daemon REFUSED the send
                    // (a 4xx at the door — machine offline, mode not allowed).
                    // No run exists, so a reattach would only re-ask; the typed
                    // message goes back to the composer instead of sitting on
                    // screen as a delivered bubble with the spinner latched.
                    _pendingSend.value?.let(onRefusedSend)
                    _pendingSend.value = null
                    _running.value = false
                } else {
                    // Mid-run failure: `running` is NOT cleared — a failure
                    // frame may precede a reattach, and blanking it would stop
                    // the reattach happening.
                }
            }
            ChatEvent.Done -> {
                _running.value = false
                _activity.value = null
                _partial.value = ""
                _pendingSend.value = null
                reattempts = 0
                refresh()
                loadTranscript()
            }
        }
    }

    private companion object {
        const val MAX_REATTACH = 5

        /** Linear, not exponential: five tries reach 15s, which is the right shape for a socket that may simply have blinked. */
        const val REATTACH_STEP_MS = 1_000L
    }
}
