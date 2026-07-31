package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.Backoff
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.PaneLease
import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.ui.LocalEcho
import com.silencelen.huginn.ui.mergeTranscriptPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which face of one session is on screen. Both stay alive; only one is selected. */
enum class SessionTab { CONVERSATION, SCREEN }

/**
 * One open session: its Claude transcript, its live pane, and the tmux size lease
 * that watching the pane takes out.
 *
 * Created per open session and closed when it goes away, like [ChatController] —
 * a view onto a session has a lifecycle and [AppStore] does not.
 *
 * THE LEASE IS THE REASON THIS IS CAREFUL. Reporting `?cols=&rows=` makes the
 * daemon hold that tmux window at this window's shape for 90 seconds, renewed by
 * the polling itself, and the owner works in these sessions from a terminal at the
 * same time. So geometry is reported only while the window is VISIBLE and the
 * SCREEN tab is selected, [PaneLeaseHolder] is reconciled release-first before
 * every geometry-bearing request, and the conversation view — which wants the
 * pane's question, not its shape — polls with no geometry at all and therefore
 * never leases.
 */
class SessionController(
    private val client: HuginnClient,
    val name: String,
    private val presence: Presence,
    private val lease: PaneLeaseHolder,
    appScope: CoroutineScope,
) {

    /**
     * This controller's OWN scope, a child of the app's.
     *
     * Every loop here — the tail poll, the screen supervisor, the key drainer —
     * runs forever by construction, so they have to die with the view. Launching
     * them straight onto the app scope leaked one of each per session ever opened,
     * and the leaked screen supervisor would go on RE-ACQUIRING the size lease for
     * a session nobody is looking at, which is the exact harm this file is about.
     * The release itself does not run here; it runs on the app scope, because this
     * one is cancelled at the moment the release is needed.
     */
    private val job = SupervisorJob(appScope.coroutineContext[Job])
    private val scope = CoroutineScope(job + Dispatchers.Default)

    // ------------------------------------------------------------------ state

    private val _tab = MutableStateFlow(SessionTab.CONVERSATION)
    val tab: StateFlow<SessionTab> = _tab.asStateFlow()

    private val _page = MutableStateFlow<TranscriptPage?>(null)
    val page: StateFlow<TranscriptPage?> = _page.asStateFlow()

    private val _transcriptError = MutableStateFlow<String?>(null)
    val transcriptError: StateFlow<String?> = _transcriptError.asStateFlow()

    /**
     * The daemon's 404/409 for a session that has no transcript. Not a failure —
     * a session that has never prompted Claude has nothing to show, and rendering
     * that as an error made a brand-new session look broken.
     */
    private val _neverRan = MutableStateFlow(false)
    val neverRan: StateFlow<Boolean> = _neverRan.asStateFlow()

    private val _screen = MutableStateFlow<Screen?>(null)
    val screen: StateFlow<Screen?> = _screen.asStateFlow()

    private val _screenError = MutableStateFlow<String?>(null)
    val screenError: StateFlow<String?> = _screenError.asStateFlow()

    /** The session ended under the viewer. The shell navigates back off this. */
    private val _gone = MutableStateFlow(false)
    val gone: StateFlow<Boolean> = _gone.asStateFlow()

    private val _scrollback = MutableStateFlow<List<String>?>(null)
    val scrollback: StateFlow<List<String>?> = _scrollback.asStateFlow()

    private val _loadingScrollback = MutableStateFlow(false)
    val loadingScrollback: StateFlow<Boolean> = _loadingScrollback.asStateFlow()

    private val _echo = MutableStateFlow(LocalEcho.Echo())
    val echo: StateFlow<LocalEcho.Echo> = _echo.asStateFlow()

    /** Whatever the last answer attempt has to say. Never a retry, only a report. */
    private val _answerNote = MutableStateFlow<String?>(null)
    val answerNote: StateFlow<String?> = _answerNote.asStateFlow()

    private val _answering = MutableStateFlow(false)
    val answering: StateFlow<Boolean> = _answering.asStateFlow()

    /** True while this client holds the window at its own size, for the header to say. */
    val leasedHere: Boolean get() = lease.heldSession == name

    // ------------------------------------------------------------- poll inputs

    /**
     * Everything a change of which restarts the screen poll. A parked long poll
     * cannot notice a resize, so the restart IS the delivery mechanism.
     *
     * `force` is deliberately NOT here. It is one-shot and cleared on success, and
     * an observed field that clears itself restarts the loop that just cleared it,
     * forever. [restartTick] is what a force-fit bumps instead.
     */
    private data class PollKey(
        val visible: Boolean,
        val grid: Boolean,
        val cols: Int?,
        val rows: Int?,
        val tick: Int,
    )

    private val geometry = MutableStateFlow<Pair<Int, Int>?>(null)
    private val restartTick = MutableStateFlow(0)

    @Volatile
    private var forceResize = false

    private var geometryJob: Job? = null
    private var transcriptOffset: Long? = null
    private var prevCursor: Pair<Int, Int>? = null
    private var lastPromptFingerprint: String? = null

    // ------------------------------------------------------------- lifecycle

    fun start() {
        scope.launch { transcriptLoop() }
        scope.launch { screenSupervisor() }
        scope.launch { keyDrainer() }
    }

    /**
     * Teardown. RELEASES THE LEASE, and does it on the app scope rather than the
     * caller's: this runs from a composition that is being disposed, so a
     * coroutine launched on the view's own scope would be cancelled before it
     * reached the socket — which is a release that never happens and a window left
     * at this one's shape.
     */
    fun close() {
        job.cancel()
        lease.releaseAsync()
    }

    /**
     * Both tabs stay alive; this only selects one. Leaving the grid stops WANTING
     * geometry, which the supervisor turns into a release on the same frame —
     * that is why the tab is one of the supervisor's inputs rather than a flag the
     * poll reads.
     */
    fun openTab(t: SessionTab) {
        _tab.value = t
    }

    /**
     * The measured grid, in cells.
     *
     * DEBOUNCED, unlike the phone's, and the difference is real: a phone changes
     * geometry on rotation, a desktop window changes it on every frame of a drag.
     * Each distinct size would otherwise restart the poll and issue a tmux resize,
     * so dragging a window edge would walk the owner's pane through fifty shapes.
     * The first measurement is applied at once — waiting a beat to draw anything is
     * a blank pane on open.
     */
    fun setGeometry(cols: Int, rows: Int) {
        val next = PaneLease.clampCols(cols) to PaneLease.clampRows(rows)
        if (geometry.value == next) return
        if (geometry.value == null) {
            geometry.value = next
            return
        }
        geometryJob?.cancel()
        geometryJob = scope.launch {
            delay(GEOMETRY_DEBOUNCE_MS)
            geometry.value = next
        }
    }

    /**
     * Resize even though another client is attached. USER-DRIVEN ONLY: the daemon
     * refuses by default because the resize would shrink somebody's real terminal,
     * and forcing it silently is deciding that on their behalf.
     */
    fun fitAnyway() {
        forceResize = true
        restartTick.value += 1
    }

    // ------------------------------------------------------------ transcript

    /**
     * Tails the session's Claude transcript.
     *
     * The offset is a CONTROLLER field, not a loop local, precisely because this
     * loop restarts when the window is hidden and shown: restarting from null
     * re-reads the tail window, and the merge would append it to the events already
     * on screen — the same paragraphs twice.
     */
    private suspend fun transcriptLoop() {
        presence.visible.collectLatest { visible ->
            if (!visible) return@collectLatest
            var failures = 0
            while (currentCoroutineContext().isActive) {
                runCatching { client.sessionTranscript(name, transcriptOffset) }
                    .onSuccess { page ->
                        failures = 0
                        transcriptOffset = page.nextOffset
                        _page.value = mergeTranscriptPage(_page.value, page)
                        _transcriptError.value = null
                        _neverRan.value = false
                    }
                    .onFailure { e ->
                        failures += 1
                        // Only while nothing has ever landed. Once a page is on
                        // screen a blip must leave it there rather than replacing
                        // a session's whole history with an error sentence.
                        if (_page.value == null) {
                            val code = (e as? HuginnClient.HuginnException)?.code
                            _neverRan.value = code == 404 || code == 409
                            _transcriptError.value = e.message ?: "could not read the transcript"
                        }
                    }
                // A session that never prompted Claude 409s forever. At the flat
                // tick that is ~24 daemon errors a minute for as long as this view
                // stays open, which is this client hammering its own host.
                delay(Backoff.transcript(failures))
            }
        }
    }

    // ---------------------------------------------------------------- screen

    /**
     * Owns the lease across every restart of the screen poll.
     *
     * `collectLatest` cancels the running poll on any change, so the reconcile
     * below runs on EVERY transition including becoming hidden — which is the
     * transition that matters, because a hidden window that keeps its lease is the
     * failure this whole design is shaped around.
     */
    private suspend fun screenSupervisor() {
        combine(presence.visible, _tab, geometry, restartTick) { visible, tab, geom, tick ->
            PollKey(visible, tab == SessionTab.SCREEN, geom?.first, geom?.second, tick)
        }.collectLatest { key ->
            val want = PaneLease.wanted(name, key.visible, key.grid, key.cols, key.rows)
            // Release-first, before a single byte of the new geometry goes out.
            lease.reconcile(want)
            // Not visible: no poll at all. The poll is what renews the lease, so
            // this line and the release above are the same safety property twice.
            if (!key.visible) return@collectLatest
            screenLoop(want)
        }
    }

    private suspend fun screenLoop(want: PaneLease.Want?) {
        var known: String? = _screen.value?.hash
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val useForce = forceResize
            runCatching {
                client.screen(
                    name = name,
                    cols = want?.cols,
                    rows = want?.rows,
                    knownHash = known,
                    // The first request of a loop asks for the frame outright; a
                    // parked poll on a screen we have never seen is a blank pane
                    // for up to 25 seconds.
                    waitMs = if (known == null) 0 else SCREEN_WAIT_MS,
                    force = useForce,
                )
            }.onSuccess { s ->
                failures = 0
                _screenError.value = null
                // ONE SHOT. A force that stuck would renew the lease forcibly on
                // every later poll, so its expiry could never fire.
                if (useForce) forceResize = false
                if (s.unchanged) {
                    // No repaint, but the flags moved: whether somebody attached,
                    // whether the size is still leased, whether a resize is being
                    // refused. Copying only those preserves `hash`, which is what
                    // keeps the echo from re-consuming on a no-change timeout.
                    known = s.hash
                    _screen.value = _screen.value?.copy(
                        attachedClients = s.attachedClients,
                        sizeLeased = s.sizeLeased,
                        resizeBlocked = s.resizeBlocked,
                    )
                } else {
                    known = s.hash
                    _screen.value = s
                    onFrame(s)
                }
            }.onFailure { e ->
                if ((e as? HuginnClient.HuginnException)?.code == 404) {
                    // The session ended under the viewer. Looping on a 404 forever
                    // is the one failure mode worse than showing a stale pane.
                    _gone.value = true
                    return
                }
                failures += 1
                if (_screen.value == null) _screenError.value = e.message ?: "could not read the pane"
                // Never null the screen already on display: a network blip must not
                // blank a pane the reader is using.
                delay(Backoff.screen(failures))
            }
        }
    }

    /** An authoritative frame. It settles the optimistic echo and the prompt note. */
    private fun onFrame(s: Screen) {
        val cur = s.cursorX to s.cursorY
        _echo.value = LocalEcho.frame(_echo.value, prevCursor, cur)
        prevCursor = cur
        val fp = s.prompt?.fingerprint
        // A different question is a different answer: whatever the last one had to
        // say about a refusal does not apply to this one.
        if (fp != lastPromptFingerprint) {
            lastPromptFingerprint = fp
            _answerNote.value = null
        }
    }

    /**
     * Scrollback, on demand and ONCE. Tens of kilobytes that do not change while
     * they are read, so folding them into the poll would pay for them every second
     * for nothing. Carries no geometry, so it takes no lease.
     */
    fun loadScrollback() {
        if (_loadingScrollback.value || _scrollback.value != null) return
        _loadingScrollback.value = true
        scope.launch {
            runCatching { client.screen(name, history = HISTORY_LINES) }
                .onSuccess { _scrollback.value = it.scrollback }
                .onFailure { _screenError.value = it.message ?: "could not load history" }
            _loadingScrollback.value = false
        }
    }

    // --------------------------------------------------------------- answering

    /**
     * Answers the question on the pane.
     *
     * THE FINGERPRINT IS MANDATORY. It identifies which question is being answered
     * and the host refuses an answer whose pane has moved on — without it, a digit
     * lands in whatever is on screen now, on a root-equivalent agent host. The
     * daemon publishes one with every prompt, so the refusal below cannot fire in
     * practice; it exists so that a daemon which stops publishing one degrades to
     * "cannot answer" instead of "answers blind".
     */
    fun answer(option: Int) = submitAnswer { fp -> client.answerPrompt(name, option, fp) }

    /**
     * Multi-select. Sends the full DESIRED set; the host diffs it against the
     * dialog's current checkboxes and presses only the digits that differ, because
     * the owner may have half-answered in tmux and pressing every desired digit
     * would un-check exactly those.
     */
    fun answerMulti(options: List<Int>) =
        submitAnswer { fp -> client.answerPromptMulti(name, options.sorted(), fp) }

    private fun submitAnswer(call: suspend (String) -> com.silencelen.huginn.data.AnswerResult) {
        val fingerprint = _screen.value?.prompt?.fingerprint
        if (fingerprint.isNullOrEmpty()) {
            _answerNote.value = "This question carries no fingerprint, so it cannot be answered safely from here."
            return
        }
        if (_answering.value) return
        _answering.value = true
        _answerNote.value = null
        scope.launch {
            runCatching { call(fingerprint) }
                .onSuccess { r ->
                    if (!r.ok) _answerNote.value = r.error ?: "The question moved on."
                }
                // A 409 arrives here, carrying the daemon's own sentence. It is an
                // ORDINARY outcome — the click was right when it was offered — so
                // it is reported and never retried.
                .onFailure { e -> _answerNote.value = e.message ?: "Could not answer." }
            _answering.value = false
        }
    }

    // ------------------------------------------------------------- live input

    private val ops = Channel<LiveInput.Op>(Channel.UNLIMITED)

    /** Typed text: echoed optimistically, then delivered. */
    fun typeText(text: String) {
        if (text.isEmpty()) return
        _echo.value = LocalEcho.typed(_echo.value, text)
        ops.trySend(LiveInput.Op.Text(text))
    }

    /**
     * Named tmux keys. Only an all-backspace batch is predictable; anything else —
     * Enter, arrows, Tab, a control key — mutes the echo until a real frame
     * settles what it did, because a wrong prediction is a ghost character
     * floating in a live pane.
     */
    fun sendKeys(keys: List<String>) {
        if (keys.isEmpty()) return
        _echo.value =
            if (keys.all { it == "BSpace" }) keys.fold(_echo.value) { acc, _ -> LocalEcho.backspace(acc) }
            else LocalEcho.otherKey(_echo.value)
        ops.trySend(LiveInput.Op.Key(keys))
    }

    /** A composed line: text and Enter in ONE request, so nothing can interleave. */
    fun sendLine(text: String, thenEnter: Boolean = true) {
        _echo.value = LocalEcho.otherKey(_echo.value)
        scope.launch {
            runCatching {
                client.sendKeys(name, text = text, keys = if (thenEnter) listOf("Enter") else emptyList())
            }.onFailure { _screenError.value = it.message ?: "could not send" }
        }
    }

    /**
     * ONE drainer, sending sequentially.
     *
     * The version this replaces on the phone launched a coroutine per keystroke,
     * and independent requests are not ordered — type `ls` fast enough and the pane
     * received `sl`. A single drainer sending merged ops in turn makes ordering a
     * property of the design rather than of network luck. The beat before draining
     * is what lets a burst coalesce into one request.
     */
    private suspend fun keyDrainer() {
        while (currentCoroutineContext().isActive) {
            val first = ops.receive()
            delay(BURST_MS)
            val batch = ArrayList<LiveInput.Op>()
            batch.add(first)
            while (true) {
                val more = ops.tryReceive().getOrNull() ?: break
                batch.add(more)
            }
            for (op in LiveInput.merge(batch)) {
                runCatching {
                    when (op) {
                        is LiveInput.Op.Text -> client.sendKeys(name, text = op.text)
                        is LiveInput.Op.Key -> client.sendKeys(name, keys = op.keys)
                    }
                }.onFailure { _screenError.value = it.message ?: "could not send keys" }
            }
        }
    }

    val prompt: PanePrompt? get() = _screen.value?.prompt

    companion object {
        /** Long-poll window. The daemon caps `wait` at 30s; this leaves it room. */
        const val SCREEN_WAIT_MS: Int = 25_000

        /** A drag changes geometry every frame; a tmux resize per frame is absurd. */
        const val GEOMETRY_DEBOUNCE_MS: Long = 250

        /** Let a burst of keystrokes accumulate into one request. */
        const val BURST_MS: Long = 15

        /** Scrollback depth; the daemon clamps to 2000. */
        const val HISTORY_LINES: Int = 2_000
    }
}
