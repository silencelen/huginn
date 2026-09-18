package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.data.Backoff
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.PaneLease
import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.SessionGraph
import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.SessionOverview
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.TypingState
import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.ui.LocalEcho
import com.silencelen.huginn.ui.PromptGate
import com.silencelen.huginn.ui.SessionFace
import com.silencelen.huginn.ui.StreamPicker
import com.silencelen.huginn.ui.isTranscriptRestart
import com.silencelen.huginn.ui.mergeTranscriptPage
import com.silencelen.huginn.ui.prependTranscriptPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which face of one session is on screen. All stay alive; only one is selected. */
enum class SessionTab { CONVERSATION, SCREEN, OVERVIEW }

/**
 * The same thing in the form `:core` reasons about, so a rule that has to hold on
 * BOTH clients is written once against [SessionFace] rather than once here and
 * once against the phone's tab index. This mapping is the whole of what is
 * desktop-specific about it.
 */
val SessionTab.face: SessionFace
    get() = when (this) {
        SessionTab.CONVERSATION -> SessionFace.CONVERSATION
        SessionTab.SCREEN -> SessionFace.SCREEN
        SessionTab.OVERVIEW -> SessionFace.OVERVIEW
    }

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
    /**
     * The goals-and-notes autosave. Owned by [AppStore], not by this controller,
     * for the reason the whole class exists: the flush that matters happens as
     * this controller is being closed, and a saver on this scope would be
     * cancelled at exactly that moment.
     */
    private val meta: SessionMetaSaver,
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

    /**
     * Live keyboard mode on the Screen tab. Hoisted here (it used to be local to
     * the tab composable) so the composer — which sits OUTSIDE the tabs — can
     * suppress its Up/Down history recall while every keystroke belongs to the
     * pane. Per-visit, never persisted: it is a way of leaning in, not a
     * configuration; a fresh controller (per session) starts with it off.
     */
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live.asStateFlow()
    fun setLive(value: Boolean) { _live.value = value }

    private val _page = MutableStateFlow<TranscriptPage?>(null)
    val page: StateFlow<TranscriptPage?> = _page.asStateFlow()

    private val _transcriptError = MutableStateFlow<String?>(null)
    val transcriptError: StateFlow<String?> = _transcriptError.asStateFlow()

    /**
     * Which stream the Conversation body is showing: null is the session's own
     * transcript, anything else is an agent id.
     */
    private val _selectedStream = MutableStateFlow<String?>(null)
    val selectedStream: StateFlow<String?> = _selectedStream.asStateFlow()

    /** The agents this session has spawned, for the picker strip. */
    private val _agents = MutableStateFlow<List<AgentRun>>(emptyList())
    val agents: StateFlow<List<AgentRun>> = _agents.asStateFlow()

    /**
     * Whether the strip's `…` pill is unfolded.
     *
     * Per open session and NOT remembered: a reader who went digging through
     * yesterday's finished agents does not want the next session they open to
     * greet them with forty settled chips. This controller is built per session,
     * so leaving one is what resets it.
     */
    private val _streamsExpanded = MutableStateFlow(false)
    val streamsExpanded: StateFlow<Boolean> = _streamsExpanded.asStateFlow()

    fun toggleStreamsExpanded() {
        _streamsExpanded.value = !_streamsExpanded.value
    }

    /**
     * The picker strip, built HERE rather than in the composable.
     *
     * The strip's rule needs three things the view does not own together — the
     * agent list, which stream is being read, and whether the pill is open — and
     * getting that pairing wrong is invisible until the moment it matters: read
     * an agent, watch it finish, and the transcript under you is replaced by
     * nothing. One caller, under test, is the whole point.
     *
     * @param nowSec the daemon's clock off the page, not this machine's.
     */
    fun streamItems(nowSec: Long): List<StreamPicker.Item> =
        StreamPicker.items(_agents.value, nowSec, _selectedStream.value, _streamsExpanded.value)

    /**
     * The PICKED agent's transcript, kept entirely apart from [_page].
     *
     * A SEPARATE page rather than a filter on the main one, and that is the whole
     * design: the two describe different files, so the parent's byte offsets say
     * nothing about the agent's, and merging them would hand `mergeTranscriptPage`
     * two sequences of `seq` numbers that both start at 1. The main page goes on
     * ticking while this one is on screen — switching streams must not cost the
     * reader the tail they came back to.
     */
    private val _agentPage = MutableStateFlow<TranscriptPage?>(null)
    val agentPage: StateFlow<TranscriptPage?> = _agentPage.asStateFlow()

    /**
     * Why the picker is disabled, or null when it is not.
     *
     * The one expected value is "needs appd 3.0": a daemon older than 3.0.0 404s
     * the agent-transcript route entirely. The chips stay on screen saying so
     * rather than vanishing, because a control that is absent is indistinguishable
     * from a session that never spawned anything.
     */
    private val _streamNote = MutableStateFlow<String?>(null)
    val streamNote: StateFlow<String?> = _streamNote.asStateFlow()

    /** False once the host has proven it cannot serve an agent transcript. */
    private val _streamsSupported = MutableStateFlow(true)
    val streamsSupported: StateFlow<Boolean> = _streamsSupported.asStateFlow()

    /**
     * The daemon's 404/409 for a session that has no transcript. Not a failure —
     * a session that has never prompted Claude has nothing to show, and rendering
     * that as an error made a brand-new session look broken.
     */
    private val _neverRan = MutableStateFlow(false)
    val neverRan: StateFlow<Boolean> = _neverRan.asStateFlow()

    private val _overview = MutableStateFlow<SessionOverview?>(null)
    val overview: StateFlow<SessionOverview?> = _overview.asStateFlow()

    private val _graph = MutableStateFlow<SessionGraph?>(null)
    val graph: StateFlow<SessionGraph?> = _graph.asStateFlow()

    /**
     * Why the overview has nothing to show, in the daemon's own words. A plain
     * shell and a session whose first prompt has not landed both reach that route
     * legitimately and get a 409 with a reason; neither is a failure.
     */
    private val _overviewNote = MutableStateFlow<String?>(null)
    val overviewNote: StateFlow<String?> = _overviewNote.asStateFlow()

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

    /**
     * THE SECOND CURSOR PAIR, and it exists because there is no such thing as one
     * cursor for two files.
     *
     * [transcriptOffset]/[historyStart] are byte positions in the session's own
     * `.jsonl`; these are byte positions in one agent's. Reusing the first pair
     * for both would tail an agent from an offset measured in the parent — a
     * number that is meaningless there and, on a long session, past the end of the
     * file. They are reset together with [_agentPage] on every stream switch.
     */
    private var agentOffset: Long? = null
    private var agentHistoryStart: Long? = null

    /**
     * The byte the OLDEST page on screen begins at, and the handle for reading
     * further back. Null until a page has landed; 0 once the whole conversation
     * is in view.
     */
    private var historyStart: Long? = null

    private val _loadingHistory = MutableStateFlow(false)
    val loadingHistory: StateFlow<Boolean> = _loadingHistory.asStateFlow()

    private val _loadingAgentHistory = MutableStateFlow(false)
    val loadingAgentHistory: StateFlow<Boolean> = _loadingAgentHistory.asStateFlow()

    /** True while there is still conversation above what is on screen. */
    val hasEarlier: StateFlow<Boolean> = _page
        .map { (it?.windowStart ?: 0L) > 0L }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Reads the page before the oldest one on screen and prepends it.
     *
     * The tail is all a cold open gets, and on a long session that is a sliver of
     * it — measured at 51 events out of 3452 on a real transcript. Walking back
     * one page at a time keeps each request small; the pages abut because a
     * windowStart is a record boundary, so this neither duplicates nor skips.
     */
    fun loadEarlier() {
        val until = historyStart ?: _page.value?.windowStart ?: return
        if (until <= 0L || _loadingHistory.value) return
        _loadingHistory.value = true
        scope.launch {
            runCatching { client.sessionTranscript(name, until = until) }
                .onSuccess { older ->
                    historyStart = older.windowStart
                    _page.value = prependTranscriptPage(_page.value, older)
                }
                .onFailure { e ->
                    _transcriptError.value = e.message ?: "could not read earlier history"
                }
            _loadingHistory.value = false
        }
    }
    private var prevCursor: Pair<Int, Int>? = null
    private var lastPromptFingerprint: String? = null

    // ------------------------------------------------------------- lifecycle

    fun start() {
        scope.launch { transcriptLoop() }
        scope.launch { agentLoop() }
        scope.launch { agentListLoop() }
        scope.launch { screenSupervisor() }
        scope.launch { overviewSupervisor() }
        scope.launch { keyDrainer() }
    }

    /**
     * Points the Conversation body at a stream.
     *
     * @param agentId null for the session's own transcript.
     *
     * The cursors and the page are dropped TOGETHER and before anything is
     * fetched: a page left behind from the previous agent would be merged with the
     * next one's first read, which is two agents' work in one conversation with no
     * mark to say where one ended.
     */
    fun selectStream(agentId: String?) {
        val next = agentId?.trim()?.takeIf { it.isNotEmpty() }
        if (next == _selectedStream.value) return
        _selectedStream.value = next
        agentOffset = null
        agentHistoryStart = null
        _agentPage.value = null
        _loadingAgentHistory.value = false
        // The note belonged to the stream that is being left. A 404 for one agent
        // says nothing about the next.
        if (_streamsSupported.value) _streamNote.value = null
    }

    /**
     * The map, polled ONLY while its tab is the one being looked at.
     *
     * Same shape as [screenSupervisor] and for a sharper reason: reading this
     * costs the daemon a walk of the whole transcript, which reaches thirty
     * megabytes on a long run. Behind another tab it would be a poll nobody can
     * see paid for by everybody.
     *
     * The FIRST pass fetches the header on its own — cheap on the wire, and the
     * first thing somebody arriving reads — then the loop takes over with the
     * cursor, which answers "unchanged" in two numbers while nothing is moving.
     */
    private suspend fun overviewSupervisor() {
        combine(presence.visible, _tab) { visible, tab -> visible && tab == SessionTab.OVERVIEW }
            .collectLatest { watching ->
                if (!watching) {
                    // Leaving the tab is when the sentence still in the air has to land.
                    meta.flush()
                    return@collectLatest
                }
                // Only on the first visit. Re-opening on every tab flip would
                // reset the editors to the last meta the POLL returned, which
                // after a save from this client is the text before it was typed.
                if (meta.session.value != name) meta.open(name, SessionMeta())
                // The generation is captured BEFORE each fetch: what comes back
                // was read server-side at that moment, and a save of ours can land
                // in between — after which the poll is a photograph of the text as
                // it read before it was typed. See SessionMetaSaver's invariant 1.
                var at = meta.generation()
                runCatching { client.sessionOverview(name) }
                    .onSuccess { _overview.value = it; _overviewNote.value = null; meta.refresh(name, it.meta, at) }
                    .onFailure { _overviewNote.value = overviewNoteFor(it) }
                while (scope.isActive) {
                    at = meta.generation()
                    runCatching { client.sessionGraph(name, _graph.value?.cursor) }
                        .onSuccess { g ->
                            if (!g.unchanged) { _graph.value = g; meta.refresh(name, g.meta, at) }
                            _overviewNote.value = null
                        }
                        .onFailure { _overviewNote.value = overviewNoteFor(it) }
                    delay(OVERVIEW_POLL_MS)
                }
            }
    }

    /** A daemon that predates the route says nothing; everything else says why. */
    private fun overviewNoteFor(t: Throwable): String? =
        (t as? HuginnClient.HuginnException)?.takeIf { it.code != 404 }?.message

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
        // THE NOTE BELONGS TO THE CARD, so it goes where the card goes. It is the
        // card's own red line ("the question moved on", the daemon's refusal), and
        // on a face that draws no card it has no surface at all — it would simply
        // sit in the state until the reader came BACK to the conversation, where a
        // complaint about an answer attempt from before they were steered away is
        // stale and, if they then answered in the terminal, wrong. The steering
        // card's whole point is to move you to the pane; nothing of it should be
        // waiting when you return.
        if (!PromptGate.visible(hasQuestion = true, face = t.face)) _answerNote.value = null
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
                failures = if (pollMainOnce()) 0 else failures + 1
                // A session that never prompted Claude 409s forever. At the flat
                // tick that is ~24 daemon errors a minute for as long as this view
                // stays open, which is this client hammering its own host.
                delay(Backoff.transcript(failures))
            }
        }
    }

    /**
     * ONE read of the session's own transcript, merged into [_page].
     *
     * The loop body is its own function so the merge and cursor rules can be
     * driven a step at a time by a test — they are the part with the failures in
     * them, and `delay` and `collectLatest` are not.
     *
     * @return true when the read landed.
     */
    internal suspend fun pollMainOnce(): Boolean {
        var ok = false
        runCatching { client.sessionTranscript(name, transcriptOffset) }
            .onSuccess { page ->
                ok = true
                // Same tmux name, different Claude session: both handles
                // into the old transcript are void (the offset is a byte
                // position in a file this session never wrote), so drop
                // them and let the next poll read the new tail.
                if (isTranscriptRestart(_page.value, page)) {
                    transcriptOffset = null
                    historyStart = null
                } else {
                    transcriptOffset = page.nextOffset
                    // The first page defines where history begins; later tail
                    // reads are BELOW it and must not move the handle.
                    if (historyStart == null) historyStart = page.windowStart
                }
                _page.value = mergeTranscriptPage(_page.value, page)
                _transcriptError.value = null
                _neverRan.value = false
            }
            .onFailure { e ->
                // Only while nothing has ever landed. Once a page is on
                // screen a blip must leave it there rather than replacing
                // a session's whole history with an error sentence.
                if (_page.value == null) {
                    val code = (e as? HuginnClient.HuginnException)?.code
                    _neverRan.value = code == 404 || code == 409
                    _transcriptError.value = e.message ?: "could not read the transcript"
                }
            }
        return ok
    }

    // -------------------------------------------------------- agent streams

    /**
     * Tails the PICKED agent's transcript, and only while one is picked.
     *
     * A second loop rather than a branch inside [transcriptLoop], for the reason
     * [_agentPage] is a second page: the main tail must keep running underneath.
     * Reading an agent stream is a way of looking more closely at a session that
     * is still going, not a way of leaving it.
     */
    private suspend fun agentLoop() {
        combine(presence.visible, _selectedStream) { visible, stream -> visible to stream }
            .collectLatest { (visible, stream) ->
                if (!visible || stream == null) return@collectLatest
                var failures = 0
                while (currentCoroutineContext().isActive) {
                    failures = if (pollAgentOnce(stream)) 0 else failures + 1
                    delay(Backoff.transcript(failures))
                }
            }
    }

    /**
     * ONE read of one agent's transcript, merged into [_agentPage].
     *
     * TWO failures, and only one of them is about the feature.
     *
     * A **404** has exactly one expected cause and it is not a missing agent: a
     * daemon older than 3.0.0 has no such route at all. Saying so and disabling
     * the strip is the documented compat answer — an empty body under a working
     * picker would read as "this agent did nothing".
     *
     * **ANYTHING ELSE** is about this one agent, so the strip stays alive and the
     * daemon's own sentence goes on it. A 400 used to fall through here into the
     * `_agentPage == null` branch, which showed the error only while nothing had
     * ever loaded and never once turned the strip off — the reader got a chip
     * that silently did nothing. It must NOT flip [streamsSupported]: a daemon
     * that answers 400 has the route, and taking the picker away because one id
     * was rejected loses every other chip with it.
     *
     * @return true when the read landed.
     */
    internal suspend fun pollAgentOnce(agentId: String): Boolean {
        var ok = false
        runCatching { client.agentTranscript(name, agentId, agentOffset) }
            .onSuccess { page ->
                ok = true
                if (isTranscriptRestart(_agentPage.value, page)) {
                    agentOffset = null
                    agentHistoryStart = null
                } else {
                    agentOffset = page.nextOffset
                    if (agentHistoryStart == null) agentHistoryStart = page.windowStart
                }
                _agentPage.value = mergeTranscriptPage(_agentPage.value, page)
                _streamNote.value = null
            }
            .onFailure { e ->
                val code = (e as? HuginnClient.HuginnException)?.code
                if (code == 404) {
                    _streamsSupported.value = false
                    _streamNote.value = STREAMS_UNSUPPORTED
                } else {
                    // Verbatim, and whether or not a page is already on screen:
                    // the note sits on the STRIP, not in the conversation, so it
                    // costs the reader nothing and it is the only place the
                    // daemon's reason ever appears.
                    _streamNote.value = e.message ?: "could not read this agent"
                }
            }
        return ok
    }

    /**
     * The agents themselves, for the strip.
     *
     * `all = true`, and that is load-bearing now rather than habit: the strip
     * shows only what is running, but the `…` pill exists to get BACK into a
     * transcript that settled, and the route's default 45-minute window would
     * hide exactly the older runs somebody unfolds the pill to reach — while the
     * count on the pill went on claiming they were there.
     */
    private suspend fun agentListLoop() {
        presence.visible.collectLatest { visible ->
            if (!visible) return@collectLatest
            while (currentCoroutineContext().isActive) {
                pollAgentListOnce()
                delay(AGENTS_POLL_MS)
            }
        }
    }

    /** One pass of [agentListLoop]; the loop above is delay and presence only. */
    internal suspend fun pollAgentListOnce() {
        runCatching { client.sessionAgents(name, all = true) }
            .onSuccess { _agents.value = it.agents }
    }

    /** The picked agent's own history walk, the same shape as [loadEarlier]. */
    fun loadEarlierAgent() {
        val stream = _selectedStream.value ?: return
        val until = agentHistoryStart ?: _agentPage.value?.windowStart ?: return
        if (until <= 0L || _loadingAgentHistory.value) return
        _loadingAgentHistory.value = true
        scope.launch {
            runCatching { client.agentTranscript(name, stream, until = until) }
                .onSuccess { older ->
                    agentHistoryStart = older.windowStart
                    _agentPage.value = prependTranscriptPage(_agentPage.value, older)
                }
                .onFailure { e -> _streamNote.value = e.message ?: "could not read earlier history" }
            _loadingAgentHistory.value = false
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
    //
    // ⚠ NO CALLER IN THIS SHELL SINCE 2026-09-15, AND DELIBERATELY KEPT. The
    // owner's decision 23 replaced the session's answerable card with a one-line
    // steer to the Screen tab, so nothing in `SessionView` taps an option any
    // more — the reader answers in the pane, which is the surface that handles
    // every prompt type. This is still the client half of `POST /answer`,
    // including its fingerprint and its 409 vocabulary, and it is what a
    // notification action or a restored card would call. Deleting it and writing
    // it again later is how the fingerprint rule gets lost.

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
    fun answer(option: Int) = submitAnswer(_screen.value?.prompt?.fingerprint) { fp ->
        client.answerPrompt(name, option, fp)
    }

    /**
     * Multi-select. Sends the full DESIRED set; the host diffs it against the
     * dialog's current checkboxes and presses only the digits that differ, because
     * the owner may have half-answered in tmux and pressing every desired digit
     * would un-check exactly those.
     */
    fun answerMulti(options: List<Int>) = submitAnswer(_screen.value?.prompt?.fingerprint) { fp ->
        client.answerPromptMulti(name, options.sorted(), fp)
    }

    /**
     * An answer to the DEGRADED card — the hook knows the question but the pane
     * scrape could not read the dialog. The host re-checks the live pane; if the
     * run has become readable the fingerprints agree and the digit lands, else it
     * refuses with reason=undetected and the Screen tab is where answering has to
     * happen — so that refusal steers there.
     */
    fun answerDegraded(option: Int) = submitAnswer(_screen.value?.ask?.fingerprint) { fp ->
        client.answerPrompt(name, option, fp)
    }

    private fun submitAnswer(
        fingerprint: String?,
        call: suspend (String) -> com.silencelen.huginn.data.AnswerResult,
    ) {
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
                    if (!r.ok) {
                        // The dialog is on screen but unreadable to the scrape:
                        // the Screen tab is the one place it CAN be answered, so
                        // the refusal STEERS there instead of explaining itself.
                        // The note is not also set for this case, because the
                        // Screen face draws no card to put it in — it would only
                        // surface later, back on the conversation, as a complaint
                        // about an attempt the reader has since answered by hand.
                        // Through openTab so both routes to the pane behave alike.
                        if (r.reason == "undetected") openTab(SessionTab.SCREEN)
                        else _answerNote.value = r.error ?: "The question moved on."
                    }
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

    /**
     * A composed line: text and Enter in ONE request, so nothing can interleave.
     *
     * A [scratchpadId] reaches the pane as a PATH the daemon writes beside its
     * store — a page holds more than a pane accepts in one paste, and a run with
     * the file can re-read it as it changes.
     */
    fun sendLine(text: String, thenEnter: Boolean = true, scratchpadId: String? = null) {
        scope.launch { sendLineNow(text, thenEnter, scratchpadId) }
    }

    /**
     * The same send, but the caller learns whether it LANDED. The composer path
     * needs the answer: its field was emptied on press, and a network refusal
     * with no answer left the text (and the attached page) existing nowhere.
     */
    suspend fun sendLineNow(text: String, thenEnter: Boolean = true, scratchpadId: String? = null): Boolean {
        _echo.value = LocalEcho.otherKey(_echo.value)
        return runCatching {
            client.sendKeys(
                name,
                text = text,
                keys = if (thenEnter) listOf("Enter") else emptyList(),
                scratchpadId = scratchpadId,
            )
        }
            .onSuccess { noteSend(it) }
            .onFailure { _screenError.value = it.message ?: "could not send" }
            .isSuccess
    }

    // ------------------------------------------------------------ the send queue
    //
    // ⚠ THE MESSAGE THAT "JUST DISAPPEARS" (owner, P1). A send into a session that
    // is mid-turn is HELD by the daemon until the turn ends — `keys` answers
    // `{ok, queued, position, delivered:false}` and the text lands minutes later.
    // The desktop threw that answer away: the composer emptied, the transcript
    // showed nothing, and there was no way to tell a queued message from a lost
    // one. Which is the same screen as a bug, so it was read as one.
    //
    // The queue is the DAEMON'S, not this client's: two clients can have the same
    // session open and the send outlives the socket, so this is a poll of
    // `/typing` rather than anything remembered here. It is cheap (an in-memory
    // queue and a cached gate result, no transcript) and it stops the moment the
    // queue drains.

    private val _sendQueue = MutableStateFlow(TypingState())

    /** What the daemon is still holding for this session. Empty means nothing. */
    val sendQueue: StateFlow<TypingState> = _sendQueue.asStateFlow()

    private var queueWatch: Job? = null

    /**
     * What a send just did, folded in.
     *
     * [SendKeysResult.landed] rather than `delivered`, because an OLD daemon
     * answers a bare `{"ok":true}` — which decodes to `delivered = false` with
     * nothing queued. Reading `delivered` alone would put a permanent "Queued"
     * line under the composer of every pre-queue host.
     */
    fun noteSend(result: SendKeysResult) {
        if (result.landed) return
        // ⚠ AND `blockedBy`, WHICH THIS USED TO THROW AWAY. The composer line is
        // drawn from this state until the first `/typing` poll answers, and with
        // only a count in it the line fell through to "will send when Claude
        // finishes its turn" — a turn that, on a session created a second ago, has
        // not begun. Two seconds of the wrong sentence on the most common wait
        // there is, then a silent correction. appd 3.1.2 says the word on the
        // send's own answer; null from an older daemon keeps the old line.
        _sendQueue.value = TypingState(
            queued = result.queued,
            delivering = false,
            blockedBy = result.blockedBy,
        )
        watchQueue()
    }

    /**
     * One reading of the daemon's queue. Returns whether anything is still held.
     *
     * Separate from the loop so it can be driven directly in a test — the loop
     * itself is a `delay` on a real dispatcher, which is not where the mistakes
     * live and is exactly where a suite becomes a race.
     *
     * A failed poll leaves the last known state ALONE rather than clearing it: a
     * network blip is not evidence that a held message was delivered, and saying
     * so is the very claim this exists to stop making.
     */
    suspend fun pollQueueOnce(): Boolean {
        val state = runCatching { client.typingStatus(name) }.getOrNull() ?: return true
        _sendQueue.value = state
        return state.queued > 0
    }

    private fun watchQueue() {
        if (queueWatch?.isActive == true) return
        queueWatch = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(QUEUE_POLL_MS)
                if (!pollQueueOnce()) break
            }
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

        /**
         * How often to ask what is still held. Two seconds: the thing being waited
         * on is a Claude turn, which is measured in tens of seconds at best, and
         * the route is cheap but not free.
         */
        const val QUEUE_POLL_MS: Long = 2_000

        /** Scrollback depth; the daemon clamps to 2000. */
        const val HISTORY_LINES: Int = 2_000

        /** How often the map asks whether anything happened. Its cursor makes that cheap. */
        const val OVERVIEW_POLL_MS: Long = 5_000

        /**
         * How often the agent LIST is re-read. Slower than the transcript tail: a
         * new subagent is a rarer event than a new line from one, and `?all=1`
         * lifts the recency filter so the answer grows rather than churning.
         */
        const val AGENTS_POLL_MS: Long = 10_000

        /**
         * The one expected reason the strip is disabled — the daemon is older than
         * 3.0.0 and has no agent-transcript route. A literal, because the test
         * asserts the sentence a reader will actually see.
         */
        const val STREAMS_UNSUPPORTED: String = "needs appd 3.0"
    }
}

/**
 * What to say under the composer about a message the daemon is holding.
 *
 * ⚠ THE WHOLE POINT IS THAT SILENCE IS A LIE HERE. A send into a busy session is
 * accepted, queued and delivered when the turn ends — but the composer empties on
 * press, so with nothing said the screen is identical to a message that was
 * dropped. The owner reported it as exactly that: it "just disappears".
 *
 * A pure function of the daemon's own answer, so the sentence can be asserted
 * without a composition — and so the two ways it can be wrong are both testable:
 * a line that never appears (the bug) and a line that never CLEARS (worse, since
 * it would claim a delivered message is still waiting).
 */
object SendQueue {

    /**
     * The one line, or null for "say nothing".
     *
     * Errors win over the count and are shown VERBATIM: the daemon knows why it
     * could not deliver (a dead pane, a refused write) and any paraphrase here
     * would be this client guessing about the other end of a queue it does not own.
     */
    fun line(state: TypingState): String? {
        state.lastError?.takeIf { it.isNotBlank() }?.let { return "Not sent — $it" }
        if (state.queued <= 0) return null
        val waiting = if (state.queued == 1) "1 waiting" else "${state.queued} waiting"
        return when (state.blockedBy) {
            // A question in the pane blocks the queue as surely as a turn does,
            // and it is the one the reader can DO something about.
            "modal" -> "Queued · waiting on a question in the pane ($waiting)"
            // A just-created session whose Claude has not drawn its composer yet
            // (appd 3.0.7). Clears itself in about two seconds; a turn has not begun.
            "starting" -> "Queued · waiting for Claude to start ($waiting)"
            else -> "Queued · will send when Claude finishes its turn ($waiting)"
        }
    }
}
