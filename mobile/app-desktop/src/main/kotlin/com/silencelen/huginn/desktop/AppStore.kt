package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppCreate
import com.silencelen.huginn.data.AppForm
import com.silencelen.huginn.data.AppList
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.Round
import com.silencelen.huginn.data.ArchivedSession
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.ScratchpadSaver
import com.silencelen.huginn.data.SessionMetaSaver
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.RoundDraft
import com.silencelen.huginn.ui.ARCHIVE_TRANSCRIPT_GONE
import com.silencelen.huginn.ui.ArchiveRules
import com.silencelen.huginn.ui.ScratchpadRules
import com.silencelen.huginn.ui.ProjectRules
import com.silencelen.huginn.ui.toSchedule
import com.silencelen.huginn.desktop.device.DeviceRunner
import com.silencelen.huginn.desktop.ui.shouldProbeApps
import com.silencelen.huginn.desktop.update.BuildInfo
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.SentHistory
import com.silencelen.huginn.ui.AttachmentImageLoader
import com.silencelen.huginn.ui.SkiaImageBytesDecoder
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectCreated
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectManifest
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.SpawnOutcome
import com.silencelen.huginn.data.PolishResult
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteFailures
import com.silencelen.huginn.data.RouteGuard
import com.silencelen.huginn.data.RouteHealth
import com.silencelen.huginn.data.RouteResolver
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.ui.QuickActionRules
import com.silencelen.huginn.data.Usage
import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchEvent
import com.silencelen.huginn.desktop.update.DesktopUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which of the destinations the window is showing. */
enum class View {
    CHATS,
    SESSIONS,
    ROUNDS,
    DEVICES,
    SCRATCHPADS,

    /**
     * Clusters of sessions with roles. Between Sessions and Rounds in the rail's
     * reading, and HIDDEN OUTRIGHT on a daemon that has never heard of them —
     * see [AppStore.projectsAvailable] and `railViews`.
     */
    PROJECTS,

    /** The things huginn makes and hosts itself. Hidden on the same terms. */
    APPS,
    STATUS,
    SETTINGS,
}

/**
 * Whether a freshly-fetched project dashboard is worth putting on screen.
 *
 * ⚠ THE CLOCK IS `generatedAt`, AND IT IS THE ONLY HONEST ONE. It is when the
 * daemon answered this poll — not when the project changed — so two answers
 * carrying the same stamp are the same rollup, re-sent. Adopting one anyway
 * costs a recomposition of a twelve-row table every five seconds for nothing.
 *
 * Three cases are NOT skips and each has cost somebody a frozen screen somewhere:
 *
 *  * NOTHING HELD — the first answer always lands, or the pane never fills.
 *  * A DIFFERENT PROJECT — stamps are per-answer, not per-cluster, so comparing
 *    them across two projects is comparing nothing. Walking from a cluster
 *    answered at T to one whose last answer was also T would show the first
 *    one's numbers under the second one's name.
 *  * NO STAMP AT ALL (`0`) — a daemon that does not send one cannot be skipped
 *    on it, and a client that skipped anyway would draw the first answer forever.
 *
 * And it never goes BACKWARDS: an answer older than the one on screen is a poll
 * that overtook its predecessor, not news.
 */
fun dashboardMoved(held: ProjectDashboard?, fresh: ProjectDashboard): Boolean {
    if (held == null) return true
    if (held.project?.id != fresh.project?.id) return true
    if (fresh.generatedAt <= 0L || held.generatedAt <= 0L) return true
    return fresh.generatedAt > held.generatedAt
}

/**
 * App-level state: navigation, the two lists, the status snapshot, and the watch
 * connection. Detail state (an open chat's live run) lives in [ChatController],
 * which has a lifecycle; this does not.
 *
 * Deliberately not a ViewModel and not a DI graph — one object, constructed once
 * in [main], holding StateFlows the composition reads. The Electron client's
 * equivalent is a zustand store of the same shape, and the phone's is a
 * ViewModel; all three are the same three lists and the same 5s poll, which is
 * the argument for phase 3b lifting the shape itself into `:ui`.
 */
class AppStore(
    val settings: DesktopSettings,
    val presence: Presence,
    val scope: CoroutineScope,
) {

    /**
     * Whether this desktop can ACTUALLY deliver a notification right now — set from
     * [main] once the notifier has been chosen (it does not exist at construction).
     * Default true so the claim is never accidentally suppressed before it is wired;
     * Main narrows it to `notifier.canDeliver()`.
     *
     * This is the third half of the claim that used to be missing: the presence and
     * the setting were consulted, but never whether a real [Notifier] backs them. A
     * machine that resolved to `NoNotifier` (no tray, no libnotify) still claimed the
     * route while every "needs you" fell on the floor and the Telegram fallback stayed
     * held back.
     */
    var canDeliver: () -> Boolean = { true }

    /**
     * ONE client for the whole app. The notify claim rides on a request header,
     * so it is read per-request from [Presence] rather than fixed at construction
     * — that is the only way an answer given now can reflect a desk that emptied
     * five minutes ago.
     *
     * `notifyEnabled && present && canDeliver`, and all three halves matter:
     * notifications turned off means this client is not a route no matter who is
     * sitting here, a claim made while nobody is looking suppresses the Telegram
     * fallback that would have reached the owner, and a claim made when nothing can
     * render suppresses it just as silently.
     */
    val client = HuginnClient(
        baseUrlProvider = { settings.baseUrlNow() },
        tokenProvider = { settings.tokenNow() },
        clientIdProvider = { settings.clientIdNow() },
        canNotifyProvider = { settings.notifyEnabledNow() && presence.present.value && canDeliver() },
    )

    /**
     * The self-updater. Deliberately NOT handed [settings]' base URL: it pulls
     * from the pinned public GitHub repo (GithubReleases.REPO), because these
     * builds are unsigned and whoever controls the feed controls what runs on
     * this machine — so the source is a compile-time constant, never a setting.
     * It downloads and verifies (sha256); INSTALLING is a button, never a
     * background decision.
     */
    val updater = DesktopUpdater()

    /**
     * "Quit, when you can" — asked for from a view, which cannot do it itself.
     *
     * Only the application scope ends this process properly: Main's `quit`
     * releases the tmux size lease, flushes the landing position and closes the
     * notifier and the single-instance socket, and none of those are reachable
     * from a screen. The self-updater's "Install and restart" needs exactly
     * that, because the installer it has just started cannot replace files this
     * process holds open.
     *
     * A latch and not an event: it is one-way and terminal, so a collector that
     * arrives a frame late must still see it rather than miss the only emission.
     */
    private val _quitRequested = MutableStateFlow(false)
    val quitRequested: StateFlow<Boolean> = _quitRequested.asStateFlow()

    fun requestQuit() { _quitRequested.value = true }

    /**
     * The keyboard cheat sheet, asked for from somewhere other than F1.
     *
     * Settings' *Appearance & behaviour* has a row into it, and the sheet is an
     * overlay the WINDOW owns (the key handler has to know one is up, or
     * shortcuts navigate the app behind it). A flag here is how a pane deep in
     * the frame asks for it without the window handing a callback down six
     * levels — the same shape `quitRequested` already uses.
     */
    private val _cheatsheet = MutableStateFlow(false)
    val cheatsheet: StateFlow<Boolean> = _cheatsheet.asStateFlow()

    fun openCheatsheet() { _cheatsheet.value = true }
    fun closeCheatsheet() { _cheatsheet.value = false }

    /**
     * The tmux size lease, held at APP level because its release paths do not
     * share a lifetime: leaving a session view is a composition event, minimizing
     * is a window event, and being killed is neither. A per-view owner could only
     * ever answer the first of those.
     */
    val paneLease = PaneLeaseHolder(client, scope)

    /**
     * Unsent composer text, for every target at once.
     *
     * APP level, and for the same reason as the lease: the flush that matters
     * happens as a view is torn down, so a book owned by that view would be
     * cancelled at the exact moment it had work to do.
     */
    val drafts = DraftBook(settings, scope)

    /** Sent-message history per target, for the composers' Up/Down recall. */
    val sentHistory = SentHistory(settings, scope)

    /**
     * Thumbnails for photo attachments in chat history, AND for image files an
     * answer names. App level so decoded bitmaps survive scrolling and view
     * switches; provided to the shared transcript renderer via
     * [com.silencelen.huginn.ui.LocalAttachmentImages].
     *
     * Two fetchers because they are two routes with two different keys — see
     * `AttachmentImageLoader.loadPath`. Against a daemon with no
     * `/v1/files/image` the second one simply 404s into the negative cache.
     */
    val attachmentImages = AttachmentImageLoader(
        fetch = { client.uploadBytes(it) },
        decoder = SkiaImageBytesDecoder(),
        fetchPath = { path, session -> client.imageBytes(path, session) },
        // And a third: an app's favicon, keyed on its id and version. Against a
        // daemon with no icon route it 404s into the negative cache and the row
        // draws its initial-letter tile.
        fetchIcon = { id -> client.appIconBytes(id) },
    )

    init {
        current = this
    }

    // ------------------------------------------------------------ navigation

    /**
     * WHERE THE LAST SESSION LEFT OFF, read synchronously — [DesktopSettings]
     * parses its file in its own constructor, so there is nothing to await and the
     * first composition draws the right view rather than snapping to it a frame
     * later. First run lands on Sessions; see [Landing] for why that is the
     * default and why Status and Settings are not remembered.
     */
    private val _view = MutableStateFlow(settings.lastViewNow())
    val view: StateFlow<View> = _view.asStateFlow()

    private val _chatId = MutableStateFlow<String?>(null)
    val chatId: StateFlow<String?> = _chatId.asStateFlow()

    private val _sessionName = MutableStateFlow<String?>(null)
    val sessionName: StateFlow<String?> = _sessionName.asStateFlow()

    /** Settings, at a named drawer — the palette's door, and the only one. */
    fun openSettings(categoryId: String) {
        settingsPane.open(categoryId)
        openView(View.SETTINGS)
    }

    fun openView(v: View) {
        // A SEARCH IS A WAY IN, NOT A STATE OF THE APP. Leaving Settings with
        // "token" still in the field would bring the reader back to a filtered
        // list next time and read as nine drawers having gone missing.
        if (_view.value == View.SETTINGS && v != View.SETTINGS) settingsPane.query = ""
        _view.value = v
        // Status is the one view the 5s list poll does not already feed, so
        // arriving on it would otherwise show an empty screen for up to five
        // seconds — indistinguishable from a daemon that is not answering.
        if (v == View.STATUS) scope.launch { refreshStatus() }
        // The same argument for the two Wave 3 panes, whose per-project and
        // per-host calls are deliberately NOT in the list poll: arriving on a
        // folded-open tree or an apps list that fills five seconds later looks
        // exactly like a feature that is not working.
        if (v == View.PROJECTS) scope.launch { refreshProjectMembers(); refreshProjectDashboard() }
        if (v == View.APPS) scope.launch { refreshApps() }
    }
    /**
     * Settings' own navigation: which drawer is open, what is typed in its search
     * field, and the row a hit marked on arrival.
     *
     * HELD HERE, beside [view] and [chatId], because it is the same kind of thing
     * — where the reader is — and because three call sites need it: the list
     * pane, the detail pane, and the command palette's "Settings · Usage &
     * headroom" rows, which are not inside the frame at all. A `remember` in the
     * shell would have been invisible to the third.
     */
    val settingsPane by lazy {
        com.silencelen.huginn.desktop.ui.settings.SettingsPaneState(
            settings.settingsSectionNow(),
            settings::setSettingsSection,
        )
    }

    fun openChat(id: String?) { _view.value = View.CHATS; _chatId.value = id }
    fun openSession(name: String?) {
        _view.value = View.SESSIONS
        // ⚠ THE ARCHIVE LETS GO. Both take the same detail half, and a live
        // session opened while an archive was up would otherwise be drawn
        // underneath a read-only conversation that is still claiming the pane.
        _archiveRead.value = null
        _sessionName.value = name
    }

    /** Escape: close the open item, or fall back to the chats list. */
    fun back() {
        when (_view.value) {
            View.CHATS -> if (_chatId.value != null) _chatId.value = null
            View.SESSIONS -> if (_sessionName.value != null) _sessionName.value = null
            // TWO STEPS OUT, in the order they were taken in: a member's session
            // first, then the project. Escape from a member landing back on the
            // tree would skip the dashboard the reader came through.
            View.PROJECTS -> when {
                _projectMember.value != null -> _projectMember.value = null
                _projectId.value != null -> openProject(null)
                else -> _view.value = View.CHATS
            }
            else -> _view.value = View.CHATS
        }
    }

    /**
     * Move through the list the current view is showing. Bound to Alt+arrow
     * rather than the bare arrows precisely so it keeps working while the
     * composer has focus — walking chats without first clicking out of what you
     * were typing is the whole point.
     */
    fun stepList(delta: Int) {
        when (_view.value) {
            View.CHATS -> {
                val list = _chats.value
                val i = com.silencelen.huginn.desktop.ui.stepIndex(
                    list.indexOfFirst { it.id == _chatId.value },
                    list.size,
                    delta,
                )
                list.getOrNull(i)?.let { _chatId.value = it.id }
            }
            View.SESSIONS -> {
                val list = _sessions.value
                val i = com.silencelen.huginn.desktop.ui.stepIndex(
                    list.indexOfFirst { it.name == _sessionName.value },
                    list.size,
                    delta,
                )
                list.getOrNull(i)?.let { _sessionName.value = it.name }
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------ the window's width

    /**
     * How wide the frame turned out to be, in dp, as the shell's own
     * `BoxWithConstraints` measured it.
     *
     * HELD HERE RATHER THAN IN [settings] because it is not a setting: nothing
     * about it is remembered, and it changes sixty times a second while a window
     * is being dragged. It lives in the store because THREE places have to agree
     * about it — the shell that draws the panes, the window's key handler (Ctrl+B
     * must mean the same thing the notch means), and the page panel's fit check.
     * Two of those are outside the composition, which is why the number has to
     * come back out of it.
     */
    private val _frameWidthDp = MutableStateFlow(WindowLayout.DEFAULT_W.toFloat())
    val frameWidthDp: StateFlow<Float> = _frameWidthDp.asStateFlow()

    /**
     * "Show me the list anyway, on this narrow window."
     *
     * In memory, never written to the settings file, and dropped the moment the
     * window is wide enough to have its own opinion again — see [noteFrameWidth].
     * Persisting it would be the auto-collapse quietly overwriting the very
     * preference it exists to leave alone.
     */
    private val _listRevealed = MutableStateFlow(false)
    val listRevealed: StateFlow<Boolean> = _listRevealed.asStateFlow()

    /** Called by the frame on every measured width. */
    fun noteFrameWidth(dp: Float) {
        if (dp == _frameWidthDp.value) return
        val wasCompact = Responsive.compact(_frameWidthDp.value)
        _frameWidthDp.value = dp
        // Leaving compact hands the persisted answer back, so the reveal must not
        // survive the trip: a window widened to 1440 would otherwise be showing a
        // list its own settings say is shut, and the notch would need TWO presses
        // to agree with what is on screen.
        if (wasCompact && !Responsive.compact(dp)) _listRevealed.value = false
    }

    /**
     * The notch, and Ctrl+B. ONE verb, because the two must not diverge.
     *
     * On a window wide enough to hold both panes this is the persisted flag it
     * always was. On a narrow one it flips the in-memory reveal instead — the
     * reader gets their list, and the remembered preference is untouched.
     */
    fun toggleList() {
        if (Responsive.compact(_frameWidthDp.value)) _listRevealed.value = !_listRevealed.value
        else settings.toggleListCollapsed()
    }

    /** Put the pane back on screen, wherever it is being hidden from. */
    fun revealList() {
        settings.setListCollapsed(false)
        _listRevealed.value = true
    }

    /** Is the list pane shut right now, by any of the three things that shut it. */
    fun listCollapsedNow(): Boolean = Responsive.listCollapsed(
        persisted = settings.listCollapsedNow(),
        compact = Responsive.compact(_frameWidthDp.value),
        revealed = _listRevealed.value,
    )

    /** What the list pane is really drawn at, window included. */
    fun listWidthNow(): Float = Responsive.listWidth(settings.listWidth.value, _frameWidthDp.value)

    // ---------------------------------------------------------------- data

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    /** The host's scheduled work. Polled with the lists, because the rail shows its count. */
    private val _rounds = MutableStateFlow<List<Round>>(emptyList())
    val rounds: StateFlow<List<Round>> = _rounds.asStateFlow()

    /**
     * This machine offering itself as a place to run work.
     *
     * Lives on the store rather than in `main` so Settings can read its status
     * without the composition holding a second reference to something with a
     * lifecycle. Constructed always, STARTED only when the setting says so —
     * see [syncDeviceRunner].
     */
    /** Machines enrolled with the daemon, including this one once it is offered. */
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    val deviceRunner: DeviceRunner by lazy {
        DeviceRunner(client, settings, scope, BuildInfo.VERSION)
    }

    /**
     * Brings the runner into line with the setting, in both directions.
     *
     * Called from the 5-second poll, so [DeviceRunner.start] MUST be idempotent —
     * an earlier version of this comment claimed it already was, and it was not:
     * start() cancelled and relaunched, so the runner was rebuilt every five
     * seconds and never held a long poll open long enough to be given work.
     * Turning it off really does stop it, which matters because a device that is
     * listed but will not run anything is worse than one that is absent.
     */
    fun syncDeviceRunner() {
        // ⚠ A pending unenrol keeps the runner ALIVE while the toggle is off. It
        // is not serving anything in that state — the supervise loop's disabled
        // branch is the only thing running — but that branch is what retries the
        // DELETE that retires this machine's row, and stopping the runner would
        // leave the row enrolled for its full thirty days with nothing left to
        // remove it. See Unenrol.
        val wanted = settings.deviceEnabledNow() || settings.deviceUnenrolPendingNow()
        if (wanted) deviceRunner.start() else deviceRunner.stop()
    }

    // ------------------------------------------------------------ scratchpads

    private val _pads = MutableStateFlow<List<Scratchpad>>(emptyList())
    val pads: StateFlow<List<Scratchpad>> = _pads.asStateFlow()

    /**
     * Whether this daemon HAS scratchpads. Null until the first probe answers.
     *
     * FEATURE DETECTION, not version parsing: a 404 is the route itself saying it
     * is not there, while a version string is a claim about what a build contains.
     * False hides the rail item, the panel toggle, the composer chip and the
     * palette verbs — a door that leads to an error is worse than no door.
     */
    private val _padsAvailable = MutableStateFlow<Boolean?>(null)
    val padsAvailable: StateFlow<Boolean?> = _padsAvailable.asStateFlow()

    /**
     * The open page and its autosave. APP level, like [drafts] and the pane lease:
     * the flush that matters happens as the panel closes, which is the exact
     * moment a scope owned by that panel would be cancelled.
     */
    val padSaver = ScratchpadSaver(scope, { id, rev, name, content ->
        client.saveScratchpad(id, rev, name = name, content = content)
    })

    /**
     * The goals and notes beside a session, and their autosave. APP level for the
     * same reason as [padSaver]: the flush happens as the Overview tab is torn
     * down, which is the exact moment a scope owned by that tab is cancelled.
     */
    val metaSaver = SessionMetaSaver(scope, { name, goals, notes ->
        client.saveSessionMeta(name, goals, notes)
    })

    /**
     * Whether the side panel is open beside the conversation.
     *
     * ONE flag for both the chat and the session view, because it is one panel as
     * far as the person is concerned: opening it in a chat and then walking to a
     * session should not close it, and a second flag would make that a coin flip.
     */
    private val _padPanel = MutableStateFlow(false)
    val padPanel: StateFlow<Boolean> = _padPanel.asStateFlow()

    fun togglePadPanel() = setPadPanel(!_padPanel.value)

    fun setPadPanel(open: Boolean) {
        _padPanel.value = open
        if (!open) padSaver.flush() else scope.launch { openDefaultPad() }
    }

    /** Which page each composer will attach, by [com.silencelen.huginn.ui.ScratchpadRules] key. */
    private val _padRefs = MutableStateFlow<Map<String, String>>(emptyMap())
    val padRefs: StateFlow<Map<String, String>> = _padRefs.asStateFlow()

    fun setPadRef(key: String, id: String?) {
        _padRefs.value = _padRefs.value.toMutableMap().apply {
            if (id == null) remove(key) else put(key, id)
        }
    }

    /** The reference a send should carry, dropped if it names a page since deleted. */
    fun padRefFor(key: String): String? =
        _padRefs.value[key]?.takeIf { id -> _pads.value.any { it.id == id } }

    /**
     * Silent on failure, and only a 404 is recorded — the same shape as
     * refreshRounds. A status bar that permanently reports a missing feature as a
     * fault is how a reader learns to stop reading it.
     */
    suspend fun refreshPads() {
        runCatching { client.scratchpads() }
            // Ordered HERE, once, for every surface that lists pages — the rail
            // view, the switcher, the composer chip and the palette all read this
            // one flow. See ScratchpadRules.ordered for why it is not the order
            // the daemon happens to send.
            .onSuccess { _pads.value = ScratchpadRules.ordered(it); _padsAvailable.value = true }
            .onFailure {
                if (it is HuginnClient.HuginnException && it.code == 404) {
                    _padsAvailable.value = false
                    // A panel that can never be drawn must not stay "open" in the
                    // flag: Esc consults it, and a flag nothing can render is the
                    // definition of an ambush.
                    _padPanel.value = false
                }
            }
    }

    /**
     * Opens a page's TEXT. The list is polled; content is fetched here and never
     * polled — a poll that replaced the text under a cursor would be an editor
     * that types back at you.
     */
    suspend fun openPad(id: String) {
        // Captured BEFORE the fetch. What comes back is the page as the daemon
        // read it, and a write of ours can land in between — see the saver's
        // invariant 1. Capturing after would prove nothing.
        val at = padSaver.generation()
        runCatching { client.scratchpad(id) }
            .onSuccess { padSaver.open(it, at) }
            .onFailure { note(Faults.ACTION, it) }
    }

    /** Main, or whatever is already open. What the panel shows when it is opened. */
    private suspend fun openDefaultPad() {
        if (padSaver.pad.value != null) return
        if (_pads.value.isEmpty()) refreshPads()
        val wanted = _pads.value.firstOrNull { it.main } ?: _pads.value.firstOrNull() ?: return
        openPad(wanted.id)
    }

    suspend fun createPad(name: String) {
        runCatching { client.createScratchpad(name) }
            .onSuccess { made -> refreshPads(); padSaver.open(made) }
            .onFailure { note(Faults.ACTION, it) }
    }

    /**
     * THROUGH THE SAVER, not straight at the wire. A rename and an autosave PATCH
     * the same row with the same rev, so a rename fired while a save was in the
     * air made one of them lose: the save losing put the server's older text back
     * over live typing, the rename losing simply did not happen. The saver's chain
     * makes them a queue.
     */
    suspend fun renamePad(id: String, name: String) {
        val rev = padSaver.pad.value?.takeIf { it.id == id }?.rev
            ?: _pads.value.firstOrNull { it.id == id }?.rev ?: return
        padSaver.rename(id, name, rev)
            .onSuccess { refreshPads() }
            .onFailure { if (it !is CancellationException) note(Faults.ACTION, it) }
    }

    suspend fun deletePad(id: String) {
        runCatching { client.deleteScratchpad(id) }
            .onSuccess {
                // forget(), not close(): a pending write for a page that has just
                // been deleted would recreate it out of a timer.
                if (padSaver.pad.value?.id == id) padSaver.forget()
                _padRefs.value = _padRefs.value.filterValues { it != id }
                refreshPads()
            }
            .onFailure { note(Faults.ACTION, it) }
    }

    /**
     * Stages text into a target's composer: APPENDED, never sent, and never
     * clobbering — a half-typed draft outranks anything arriving into it.
     *
     * Was `stagePadInDraft`, when a page was the only thing that arrived this way.
     * A selection quick action stages under exactly the same contract, so this is
     * one method with one rule ([QuickActionRules.appendToDraft], shared with the
     * phone) rather than two that drift. The separator widened from one newline to
     * a blank line with it: what lands here is a block — a page, or a quote — and
     * a blank line is how a person would have typed it.
     */
    fun appendToDraft(key: String, text: String) {
        drafts.set(key, QuickActionRules.appendToDraft(drafts[key], text))
    }

    /**
     * "Ask in new chat": makes a chat, stages [text] in ITS composer, goes there.
     *
     * CREATES AND STAGES; NEVER SENDS — the same contract as [escalateWithDraft],
     * which this is the selection-shaped sibling of. The text is a starting point
     * to be read and edited, and a client that sent it would be answering a
     * question nobody finished asking.
     *
     * A creation that fails does not lose the text: it goes into [fallbackKey],
     * which is the composer the reader is actually looking at, with a line saying
     * why it is there. Dropping it would be the worst outcome — they selected it.
     *
     * @param create the chat-making call, seamed so the rule this method exists
     *   for (which draft the text lands in) is assertable without a daemon.
     */
    suspend fun askInNewChat(
        text: String,
        mode: String?,
        fallbackKey: String,
        create: suspend () -> Chat = { client.createChat(mode ?: "ask") },
    ) {
        runCatching { create() }
            .onSuccess { made ->
                appendToDraft(DraftBook.chatKey(made.id), text)
                openChat(made.id)
                openView(View.CHATS)
                refreshChats()
            }
            .onFailure { t ->
                appendToDraft(fallbackKey, text + NEW_CHAT_FAILED + (t.message ?: "no reason given") + ")")
                // Faults.ACTION, not CHATS: a hand action's refusal belongs to the
                // source no poll clears, or the next 5s chats poll erases the
                // reason before anybody reads it.
                note(Faults.ACTION, t)
            }
    }

    // -------------------------------------------------------------- projects
    //
    // A PROJECT is a cluster of tmux sessions with roles — a lead that sizes the
    // work and members that do it. Three things are held here and they are fetched
    // on three different clocks, which is the whole of this section's design:
    //
    //   * the ROWS (`GET /v1/projects`) are a list poll like chats and sessions;
    //   * the MEMBERS are a PER-PROJECT call (`GET /v1/projects/:id`) made only for
    //     the projects the reader has folded open — the list route carries counts
    //     and no membership, deliberately, so twelve rows cost one request;
    //   * the DASHBOARD is a rollup that walks every member's transcript on the
    //     host, so it is polled only while it is on screen and only adopted when
    //     its `generatedAt` has actually moved.

    private val _projects = MutableStateFlow<List<ProjectRow>>(emptyList())

    /**
     * The rows, in the daemon's own order.
     *
     * NOT ordered here, unlike [pads] — `ProjectsListView` does it with
     * [ProjectRules.orderedProjects] because the tree is the only surface whose
     * order is a decision (needs-you first, archived last). Ordering here as well
     * would be the same rule in two places, and the one that drifts is always the
     * one the reader is looking at.
     */
    val projects: StateFlow<List<ProjectRow>> = _projects.asStateFlow()

    /**
     * Whether this daemon HAS projects. Null until the first probe answers.
     *
     * FEATURE DETECTION, not version parsing — the scratchpads precedent, and the
     * client hands it over as a null rather than a throw ([HuginnClient.projects]
     * turns the 404 into one). False hides the rail item, the palette rows and the
     * Ctrl+Shift+J chord: a door that leads to an error is worse than no door.
     *
     * ⚠ NULL HIDES TOO. A rail that drew the item optimistically and took it away
     * a second later would move every icon under it while somebody was reaching
     * for one. See `railViews`.
     */
    private val _projectsAvailable = MutableStateFlow<Boolean?>(null)
    val projectsAvailable: StateFlow<Boolean?> = _projectsAvailable.asStateFlow()

    /**
     * The live members of the projects this window has actually asked for, by
     * project id.
     *
     * ⚠ A MISSING ENTRY IS "NOT LOADED YET", WHICH IS NOT "NO MEMBERS". The tree
     * draws those two differently, so they must not collapse into one empty list
     * on the way here.
     */
    private val _projectMembers = MutableStateFlow<Map<String, List<ProjectLive>>>(emptyMap())
    val projectMembers: StateFlow<Map<String, List<ProjectLive>>> = _projectMembers.asStateFlow()

    /** The project rows folded open in the tree. Held here so it survives navigation. */
    private val _projectsExpanded = MutableStateFlow<Set<String>>(emptySet())
    val projectsExpanded: StateFlow<Set<String>> = _projectsExpanded.asStateFlow()

    /** The project the detail pane is showing, by id. */
    private val _projectId = MutableStateFlow<String?>(null)
    val projectId: StateFlow<String?> = _projectId.asStateFlow()

    /**
     * The member whose session the detail pane is showing, by TMUX name.
     *
     * ⚠ THE TMUX NAME, NEVER THE PEER NAME. `SessionView` and every session route
     * address a session as `<slug>-<role>`; `<slug>/<role>` is what a peer's
     * `SendMessage` uses and is not a tmux name at all.
     */
    private val _projectMember = MutableStateFlow<String?>(null)
    val projectMember: StateFlow<String?> = _projectMember.asStateFlow()

    /** The open project's whole record — what the manifest card is drawn from. */
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _projectDashboard = MutableStateFlow<ProjectDashboard?>(null)
    val projectDashboard: StateFlow<ProjectDashboard?> = _projectDashboard.asStateFlow()

    /**
     * The daemon's last refusal about the open project, shown VERBATIM.
     *
     * Its own flow rather than the error bar: the two that matter — an untrusted
     * working directory and the headroom arbiter's STOP sentinel — are STATES OF
     * THE HOUSE with a fix in them, and they belong under the control that was
     * pressed rather than in a line at the foot of the window that ages out.
     */
    private val _projectRefusal = MutableStateFlow<String?>(null)
    val projectRefusal: StateFlow<String?> = _projectRefusal.asStateFlow()

    fun clearProjectRefusal() { _projectRefusal.value = null }

    fun openProjects() = openView(View.PROJECTS)

    /** Open a project's dashboard. Clears any member the pane was showing. */
    fun openProject(id: String?) {
        _view.value = View.PROJECTS
        if (_projectId.value != id) {
            // The held rollup belongs to the project that is leaving. Kept, it
            // would draw the previous cluster's numbers under the new one's name
            // for a whole poll — and `adoptDashboard` would then be comparing
            // stamps across two different projects.
            _projectDashboard.value = null
            _project.value = null
            _projectRefusal.value = null
        }
        _projectId.value = id
        _projectMember.value = null
    }

    /** Show one member's ordinary session detail, without leaving Projects. */
    fun openProjectMember(tmuxName: String?) {
        _view.value = View.PROJECTS
        _projectMember.value = tmuxName
    }

    fun toggleProject(id: String) {
        val open = _projectsExpanded.value
        _projectsExpanded.value = if (id in open) open - id else open + id
    }

    /**
     * The rows, and the flag that says whether this daemon has them at all.
     *
     * Silent on failure and NOT a fault — the refreshRounds shape. The 404 does
     * not arrive as a failure here: [HuginnClient.projects] answers null for it,
     * because "this daemon has no projects" is an answer rather than an error.
     */
    suspend fun refreshProjects() {
        runCatching { client.projects() }
            .onSuccess { list ->
                if (list == null) {
                    _projectsAvailable.value = false
                    return@onSuccess
                }
                _projectsAvailable.value = true
                _projects.value = list.projects
                // A project that is gone takes its membership with it: a stale
                // entry would keep a deleted cluster's rows under a disclosure
                // that can never be refreshed.
                val alive = list.projects.map { it.id }.toSet()
                _projectsExpanded.value = _projectsExpanded.value.filterTo(mutableSetOf()) { it in alive }
                _projectMembers.value = _projectMembers.value.filterKeys { it in alive }
                if (_projectId.value != null && _projectId.value !in alive) openProject(null)
            }
    }

    /**
     * The members of the projects that are FOLDED OPEN, plus the one the detail
     * pane is showing.
     *
     * One GET per open disclosure, and none at all while Projects is off screen —
     * which is the argument for the list route carrying counts rather than
     * membership in the first place. A reader with every row folded open is
     * asking for exactly the requests they get.
     */
    suspend fun refreshProjectMembers() {
        val wanted = (_projectsExpanded.value + listOfNotNull(_projectId.value)).toList()
        if (wanted.isEmpty()) return
        val fetched = mutableMapOf<String, List<ProjectLive>>()
        for (id in wanted) {
            runCatching { client.project(id) }
                .onSuccess { detail ->
                    fetched[id] = detail.live
                    if (id == _projectId.value) _project.value = detail.project
                }
        }
        if (fetched.isEmpty()) return
        // MERGED, not replaced: a project whose fetch failed this pass keeps the
        // membership it had rather than collapsing to "not loaded yet" and
        // redrawing the disclosure as empty.
        _projectMembers.value = _projectMembers.value + fetched
    }

    /** The rollup for the open project. Adopted only when it has actually moved. */
    suspend fun refreshProjectDashboard() {
        val id = _projectId.value ?: return
        runCatching { client.projectDashboard(id) }.onSuccess { adoptDashboard(it) }
    }

    /**
     * Take a freshly-fetched rollup, or keep the one on screen.
     *
     * ⚠ THE POINT IS THE SCREEN, NOT THE REQUEST. The fetch has already happened;
     * what this skips is REPLACING the state — and with it every member row's
     * disclosure, the scroll position of a twelve-row table and a recomposition
     * of the header, five seconds apart, for a rollup the daemon has told us is
     * the same one it sent last time.
     *
     * @return whether the screen took it.
     */
    internal fun adoptDashboard(fresh: ProjectDashboard): Boolean {
        if (!dashboardMoved(_projectDashboard.value, fresh)) return false
        _projectDashboard.value = fresh
        return true
    }

    /**
     * Start a project: the daemon launches its lead and types the brief into it.
     *
     * ⚠ THE 409 IS AN ANSWER AND IT LANDS IN [projectRefusal], not on the error
     * bar. The commonest one is a working directory Claude Code has not been
     * trusted in, and the daemon's sentence about it IS the fix — so it is shown
     * under the sheet's own fields with everything the person typed still in them.
     *
     * @return whether a project was made, so the sheet knows whether to close.
     */
    suspend fun createProject(name: String, kind: String, brief: String, cwd: String?): Boolean {
        _projectRefusal.value = null
        val made: ProjectCreated = runCatching { client.createProject(name, kind, brief, cwd) }
            .getOrElse { note(Faults.ACTION, it); return false }
        if (!made.ok) {
            _projectRefusal.value = made.refusal
            return false
        }
        refreshProjects()
        made.project?.id?.let { openProject(it) }
        refreshProjectMembers()
        return true
    }

    /**
     * Create the members the owner approved.
     *
     * ⚠⚠ A 200 IS NOT A VERDICT. Spawning is a loop over tmux, so the ordinary
     * partial outcome is `ok:false` with both lists inside a 200 — the answer is
     * handed back whole for the card to draw per role. The refusal (the STOP
     * sentinel, or a manifest that moved under the card) goes to [projectRefusal]
     * verbatim for the same reason a create's does.
     */
    suspend fun spawnProject(id: String, manifestRev: Int): SpawnOutcome? {
        _projectRefusal.value = null
        val outcome = runCatching { client.spawnProject(id, manifestRev) }
            .getOrElse { note(Faults.ACTION, it); return null }
        if (!outcome.ok) _projectRefusal.value = outcome.refusal
        refreshProjects()
        refreshProjectMembers()
        refreshSessions()
        return outcome
    }

    /**
     * ADOPT a session that is already running into this project.
     *
     * ⚠ IT LAUNCHES NOTHING. The record gains a row and the session carries on
     * exactly as it was. A 409 is an ANSWER — the session already belongs to
     * another cluster and the daemon NAMES it, which is the whole fix — and it
     * goes to [projectRefusal] verbatim, like a create's and a spawn's. The other
     * refusals (400 a role taken / "lead" / not a name, 404 no such session, 503
     * tmux not answering) are the daemon's sentences too, so they land in the
     * same place rather than in the fault bar where the form cannot see them.
     */
    suspend fun adoptMember(id: String, role: String, name: String) {
        _projectRefusal.value = null
        runCatching { client.adoptMember(id, role, name) }
            .onSuccess { outcome ->
                if (outcome.refusal != null) _projectRefusal.value = outcome.refusal
                refreshProjects()
                refreshProjectMembers()
                refreshProjectDashboard()
            }
            .onFailure { _projectRefusal.value = refusalTextFor(it) }
    }

    /** DROP a member. ⚠ THE SESSION KEEPS RUNNING — nothing is ended. */
    suspend fun dropMember(id: String, role: String) {
        _projectRefusal.value = null
        runCatching { client.dropMember(id, role) }
            .onSuccess { outcome ->
                if (outcome.refusal != null) _projectRefusal.value = outcome.refusal
                refreshProjects()
                refreshProjectMembers()
                refreshProjectDashboard()
            }
            .onFailure { _projectRefusal.value = refusalTextFor(it) }
    }

    /**
     * A thrown refusal as a SENTENCE, for a form to print under itself.
     *
     * The daemon's own words where there are any: every membership refusal is
     * also the instruction ("drop it from the project first, then rename it"),
     * and a summary of ours would lose the half that says what to do.
     */
    private fun refusalTextFor(t: Throwable): String =
        (t as? HuginnClient.HuginnException)?.message?.takeIf { it.isNotBlank() }
            ?: t.message?.takeIf { it.isNotBlank() }
            ?: "that change was refused"

    /** Turn the proposal down. The manifest is kept at its rev; only the status moves. */
    suspend fun discardProposal(id: String) {
        runCatching { client.discardProposal(id) }
            .onSuccess { _project.value = it; refreshProjects() }
            .onFailure { note(Faults.ACTION, it) }
    }

    /**
     * Rename, pause, resume or archive a project.
     *
     * ⚠ THE 409 HAS TWO SHAPES and only one is a conflict. A stale rev comes back
     * as the CURRENT project to adopt; an illegal status move ("an active project
     * cannot become proposed") comes back as a refusal. Both are answers, so both
     * are handled rather than thrown — the adopt is silent, the refusal is shown.
     */
    suspend fun saveProject(
        id: String,
        rev: Int,
        name: String? = null,
        status: String? = null,
        manifest: ProjectManifest? = null,
    ) {
        val saved = runCatching { client.saveProject(id, rev, name = name, status = status, manifest = manifest) }
            .getOrElse { note(Faults.ACTION, it); return }
        saved.project?.let { _project.value = it }
        if (saved.refusal != null) _projectRefusal.value = saved.refusal
        refreshProjects()
    }

    /**
     * Forget a project, and optionally end its sessions.
     *
     * @param end null ends NOTHING (the default the daemon takes, and the only
     *   safe reading of a button labelled Delete), `graceful` winds the sessions
     *   down, `now` kills them.
     */
    suspend fun deleteProject(id: String, end: String? = null) {
        runCatching { client.deleteProject(id, end) }
            .onSuccess { done ->
                if (_projectId.value == id) openProject(null)
                // ⚠ SAID WHEN SOMETHING WAS NOT WOUND DOWN, and only then. A
                // member on a permission or folder-trust dialog cannot be typed
                // at, so the daemon skips it and removes the record anyway —
                // those sessions are alive with no project behind them. This pane
                // is the only one that knows, so it is the only one that can say.
                if (done.refused.isNotEmpty()) _projectRefusal.value = ProjectRules.deletedWords(done)
                refreshProjects()
                refreshSessions()
            }
            .onFailure { note(Faults.ACTION, it) }
    }

    /**
     * Type a line into one member, from another.
     *
     * ⚠ THIS IS NOT HOW THE SESSIONS TALK. A peer `SendMessage` goes process to
     * process and starts a turn with no keypress; this route is the daemon TYPING
     * into a pane, so it rides the send queue and its gates.
     */
    suspend fun messageProject(id: String, from: String, to: String, text: String) {
        runCatching { client.messageProject(id, from, to, text) }
            .onFailure { note(Faults.ACTION, it) }
    }

    // ------------------------------------------------------------------ apps

    private val _apps = MutableStateFlow(AppList())
    val apps: StateFlow<AppList> = _apps.asStateFlow()

    /**
     * Whether this daemon HAS apps. Same probe contract as [projectsAvailable] —
     * and the client asks BOTH `/v1/apps` and `/v1/consoles` before answering
     * false, because a daemon older than the 3.6 rename only serves the old one.
     */
    private val _appsAvailable = MutableStateFlow<Boolean?>(null)
    val appsAvailable: StateFlow<Boolean?> = _appsAvailable.asStateFlow()

    /**
     * The daemon's answer to the last add, or null.
     *
     * ⚠⚠ IT HOLDS A REFUSAL, NOT JUST A SUCCESS (decision 54). A 422 says the app
     * does not answer on the addresses this window's devices arrive from and
     * carries the lines that would fix it; the dialog stays open on it with every
     * typed field intact. A fault note would have dropped both.
     */
    private val _appAdd = MutableStateFlow<AppCreate?>(null)
    val appAdd: StateFlow<AppCreate?> = _appAdd.asStateFlow()

    fun clearAppAdd() { _appAdd.value = null }

    suspend fun refreshApps() {
        runCatching { client.apps() }
            .onSuccess { list ->
                if (list == null) {
                    _appsAvailable.value = false
                    return@onSuccess
                }
                _appsAvailable.value = true
                _apps.value = list
            }
            // ⚠⚠ A THROW IS NOT AN ANSWER, AND TREATING IT AS ONE HID THE FEATURE
            // FOR A WHOLE SESSION. This had no `onFailure` at all, which reads as
            // "leave it unknown" and was the right instinct — but the probe only
            // ran once, so unknown was final. `probeGet` already turns the
            // feature's own 404 into a null LIST above; everything that lands here
            // is a 401 during setup, a dead route or a timeout, none of which say
            // anything about whether this daemon has apps. So the flag is left
            // null and `shouldProbeApps` asks again next tick.
            .onFailure {
                if (it is HuginnClient.HuginnException && it.code == 404) _appsAvailable.value = false
            }
    }

    /** Probe one app now and adopt the refreshed row. */
    suspend fun probeApp(id: String) {
        runCatching { client.probeApp(id) }
            .onSuccess { row -> _apps.value = _apps.value.let { l -> l.copy(apps = l.apps.map { if (it.id == row.id) row else it }) } }
            .onFailure { note(Faults.ACTION, it) }
    }

    /**
     * Add an app.
     *
     * ⚠⚠ THE 422 IS PUBLISHED, NOT NOTED (decision 54). It is the daemon saying
     * the prerequisite failed and handing over the lines that would clear it; the
     * dialog draws them under the fields it still holds.
     */
    suspend fun addApp(form: AppForm) {
        runCatching {
            client.createApp(
                name = form.name.trim(),
                url = form.url.trim(),
                kind = form.kind?.trim()?.ifBlank { null },
                notes = form.notes.trim().ifBlank { null },
                unit = form.unit.trim().ifBlank { null },
            )
        }
            .onSuccess { answer ->
                _appAdd.value = answer
                if (answer.ok) refreshApps()
            }
            .onFailure { note(Faults.ACTION, it) }
    }

    /**
     * Edit one app.
     *
     * ⚠ THE 409 IS AN ANSWER, carrying the row as the host now holds it — the
     * saveScratchpad shape. The other client having saved first is the ordinary
     * outcome of two devices on one registry, so the current row is adopted
     * rather than thrown at the reader.
     */
    suspend fun saveApp(id: String, version: Int, form: AppForm) {
        runCatching {
            client.saveApp(
                id,
                version,
                name = form.name.trim(),
                url = form.url.trim(),
                kind = form.kind?.trim()?.ifBlank { null },
                notes = form.notes.trim(),
                unit = form.unit.trim(),
            )
        }
            .onSuccess { saved ->
                _apps.value = _apps.value.let { l ->
                    l.copy(apps = l.apps.map { if (it.id == saved.app.id) saved.app else it })
                }
                if (saved.conflict) refreshApps()
            }
            .onFailure { note(Faults.ACTION, it) }
    }

    suspend fun deleteApp(id: String) {
        runCatching { client.deleteApp(id) }
            .onSuccess { refreshApps() }
            .onFailure { note(Faults.ACTION, it) }
    }

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    /** Sessions ended on purpose, kept with the command that brings them back. */
    private val _archives = MutableStateFlow<List<ArchivedSession>>(emptyList())
    val archives: StateFlow<List<ArchivedSession>> = _archives.asStateFlow()

    /**
     * Whether this daemon HAS archive. Null until the first probe answers.
     *
     * FEATURE DETECTION, not version parsing — the scratchpads precedent. False
     * hides the Archived section AND the right-click verb, because a menu entry
     * whose only outcome is a 404 is worse than no entry.
     */
    private val _archiveAvailable = MutableStateFlow<Boolean?>(null)
    val archiveAvailable: StateFlow<Boolean?> = _archiveAvailable.asStateFlow()

    /** Null until the first fetch settles, so a cold start never claims "no chats". */
    private val _listsLoaded = MutableStateFlow(false)
    val listsLoaded: StateFlow<Boolean> = _listsLoaded.asStateFlow()

    /**
     * The same, for sessions, and it is a SECOND flag rather than the same one:
     * the sessions list was being told "loaded" by the chats fetch returning, so a
     * cold start where chats answered and sessions did not drew "No sessions" —
     * a confident claim about a list nothing had read yet.
     */
    private val _sessionsLoaded = MutableStateFlow(false)
    val sessionsLoaded: StateFlow<Boolean> = _sessionsLoaded.asStateFlow()

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _plan = MutableStateFlow<Plan?>(null)
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    private val _usage = MutableStateFlow<Usage?>(null)
    val usage: StateFlow<Usage?> = _usage.asStateFlow()

    /**
     * Headroom, polled WHEREVER THE READER IS. Null until the first answer, and
     * null forever on a daemon older than 3.0.0.
     *
     * Everything else about usage on this client is gated on the Status pane being
     * open ([refreshStatus] is called only there, and `/v1/usage` behind it walks
     * every transcript). That gate is right for those two and wrong for this one:
     * headroom is the number that decides whether tonight's run finishes, the
     * daemon serves it from a file it already keeps, and a number nobody sees
     * until they go looking for it is the exact failure this wave exists to fix.
     */
    private val _headroom = MutableStateFlow<Headroom?>(null)
    val headroom: StateFlow<Headroom?> = _headroom.asStateFlow()

    /**
     * What the client is failing at NOW. See [Faults] — the single nullable string
     * this replaced was written on every failure and cleared only by a click, so
     * one 401 pinned "unauthorized" to the status line for the rest of the run.
     */
    private val faults = Faults()
    val error: StateFlow<String?> = faults.current

    /** The click on the status line. Hides that message; a different one still shows. */
    fun clearError() = faults.dismiss()

    /** Whether the watch stream is currently attached. The one honest liveness mark. */
    private val _watchConnected = MutableStateFlow(false)
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    /** Bumped every time the digest changes, so views can re-read without polling. */
    private val _watchTick = MutableStateFlow(0L)
    val watchTick: StateFlow<Long> = _watchTick.asStateFlow()

    private val _route = MutableStateFlow(settings.baseUrlNow())
    val route: StateFlow<String> = _route.asStateFlow()

    // ------------------------------------------------------ pinned routes

    private val _routeBook = MutableStateFlow(settings.routeBookNow())
    val routeBook: StateFlow<RouteBook> = _routeBook.asStateFlow()

    /**
     * The state dots and "last reached" words. Never an input to selection.
     *
     * ⚠ SEEDED FROM THE STORE, not empty. An empty map makes every launch skip
     * the hysteresis and take the first address that answers in the owner's
     * order — see [com.silencelen.huginn.data.HuginnSettings.routeHealth].
     */
    private val _routeHealth = MutableStateFlow(settings.routeHealthNow())
    val routeHealth: StateFlow<Map<String, RouteHealth>> = _routeHealth.asStateFlow()

    /**
     * A route that answered and that this client WILL NOT ADOPT BY ITSELF — see
     * [RouteResolver.Choice.Stay.Candidate]. Offered under the list, null otherwise.
     */
    private val _routeCandidate = MutableStateFlow<com.silencelen.huginn.data.PinnedRoute?>(null)
    val routeCandidate: StateFlow<com.silencelen.huginn.data.PinnedRoute?> = _routeCandidate.asStateFlow()

    /**
     * Marks the active route as having just worked, from REAL traffic — the
     * phone's `noteRouteReached` twin, so the desktop's route rows say "last
     * reached" from ordinary polls and not only after "Find live route".
     *
     * See [RouteResolver.touch]: it writes `lastSeenAt`, never `lastOkAt`, so
     * the three-failures re-probe still sweeps rather than finding the dead
     * route "fresh" seconds after its last success.
     */
    private fun noteRouteReached() {
        val next = RouteResolver.touch(
            _routeHealth.value,
            _routeBook.value.active?.id,
            System.currentTimeMillis(),
        )
        if (next == _routeHealth.value) return
        saveHealth(next)
    }

    /** The health cache and its persisted copy, together. */
    private fun saveHealth(health: Map<String, RouteHealth>) {
        _routeHealth.value = health
        scope.launch { runCatching { settings.setRouteHealth(health, System.currentTimeMillis()) } }
    }

    private val _resolvingRoute = MutableStateFlow(false)
    val resolvingRoute: StateFlow<Boolean> = _resolvingRoute.asStateFlow()

    /** A refusal or an outcome, shown under the list rather than swallowed. */
    private val _routeNote = MutableStateFlow<String?>(null)
    val routeNote: StateFlow<String?> = _routeNote.asStateFlow()

    /**
     * ⚠ A 401 DOES NOT COUNT. An answering daemon that rejects the token proves
     * the route works, and re-resolving on it would answer a token problem with
     * a network search.
     */
    private val routeFailures = RouteFailures()

    /** The name the connection indicator and the diagnostics report say. */
    val routeName: String get() = _routeBook.value.activeName

    /** Forgets the note under the route list — a form opening or being cancelled. */
    fun clearRouteNote() { _routeNote.value = null }

    fun activateRoute(id: String) = editRoutes { it.activate(id).withAutoSwitch(false) }

    /**
     * Adopt the offered route — the person saying so that [RouteResolver] waits
     * for before it will send a bearer over a plain-http address nobody typed.
     */
    fun useRouteCandidate(id: String) {
        _routeCandidate.value = null
        activateRoute(id)
    }

    fun addRoute(name: String, url: String) = editRoutes { it.add(name, url, System.currentTimeMillis()) }

    fun renameRoute(id: String, name: String) = editRoutes { it.rename(id, name) }

    fun setRouteUrl(id: String, url: String) = editRoutes { it.setUrl(id, url) }

    /**
     * A name and an address saved together, as ONE book operation — which is
     * what the route form's Save is. See [routeEdits].
     */
    fun editRoute(id: String, name: String, url: String) =
        editRoutes { it.rename(id, name).setUrl(id, url) }

    fun moveRoute(id: String, delta: Int) = editRoutes { it.move(id, delta) }

    fun removeRoute(id: String) = editRoutes { it.remove(id) }

    /**
     * ⚠ PERSIST, THEN PROBE. Both halves used to be launched independently, so
     * the probe read the book with autoSwitch still false, `RouteResolver.resolve`
     * short-circuited on the pin, and turning the setting ON answered "pinned to
     * <name> — switch automatically to move". force=true as well: on a fresh
     * health map an unforced resolve answers Stay and probes nothing.
     */
    fun setAutoSwitch(on: Boolean) {
        scope.launch {
            editRoutesNow { it.withAutoSwitch(on) }
            if (on) resolveRoute(force = true)
        }
    }

    /**
     * THE MANUAL RE-PROBE THIS CLIENT NEVER HAD. Until routes, the desktop
     * resolved exactly once — from [start] — and then never again: no button, no
     * failure-driven retry. A laptop that moved between the tailnet and the mesh
     * after launch simply stayed broken until it was restarted.
     */
    fun findLiveRoute() {
        scope.launch { resolveRoute(force = true) }
    }

    /**
     * ⚠ ONE EDIT AT A TIME. Every mutation here is read-modify-write across a
     * SUSPENSION and `_routeBook` is only republished afterwards, so two edits
     * launched from one gesture — the route form's Save used to send a rename and
     * an address change as two — both read the pre-edit book and whichever wrote
     * last silently discarded the other. On this client the coroutines run on the
     * Default pool, which made the casualty random rather than merely wrong.
     */
    private val routeEdits = Mutex()

    /**
     * Every list edit runs through here, so a refusal from the guard or the
     * eight-pin cap is REPORTED rather than swallowed.
     */
    private fun editRoutes(edit: (RouteBook) -> RouteBook) {
        scope.launch { editRoutesNow(edit) }
    }

    /** [editRoutes], awaited — for a caller that must act on the SETTLED book. */
    private suspend fun editRoutesNow(edit: (RouteBook) -> RouteBook): Boolean =
        routeEdits.withLock {
            val next = runCatching { edit(_routeBook.value) }
                .onFailure { _routeNote.value = it.message ?: RouteGuard.REFUSED }
                .getOrNull() ?: return@withLock false
            _routeNote.value = null
            applyBook(next)
            true
        }

    private suspend fun applyBook(book: RouteBook) {
        val settled = book.normalized()
        settings.setRouteBook(settled)
        _routeBook.value = settled
        if (settled.activeUrl == _route.value) return
        _route.value = settled.activeUrl
        routeFailures.ok()
        // ⚠ RECONNECT NOW, not on the next poll tick. Adding the first route on a
        // fresh install is the case that made this obvious: the list said the pin
        // was in use while the status bar still carried "No route yet" and every
        // dot was grey, because the poll is gated on window visibility and the
        // last error is only cleared by a call that succeeds.
        if (settled.activeUrl.isNotBlank()) {
            refreshStatus()
            refreshChats()
            refreshSessions()
        }
    }

    /**
     * Counts a failed call against the active route and looks for another one
     * after three in a row. Called from the poll loop's own error path, which is
     * the one place that sees every ordinary request fail.
     */
    private fun noteRouteFailure(t: Throwable) {
        if (t is HuginnClient.HuginnException) { routeFailures.ok(); return }
        if (routeFailures.fail()) scope.launch { resolveRoute() }
    }

    /**
     * Every watch digest, handed to the always-on layer (the notification router
     * and the tray) on the watch loop's own coroutine.
     *
     * A callback rather than a StateFlow deliberately: a StateFlow CONFLATES equal
     * values, so two consecutive digests that happened to compare equal would
     * silently become one — and on this path a dropped digest is a notification
     * that never fires.
     */
    var onDigest: ((Watch) -> Unit)? = null

    // -------------------------------------------------------------- loading

    suspend fun refreshChats() {
        runCatching { client.chats() }
            // THE SUCCESS CLEARS THE FAULT. This line is the whole of the stale
            // status bar fix: without it the bar accumulates rather than reports.
            .onSuccess { _chats.value = it; _listsLoaded.value = true; faults.ok(Faults.CHATS) }
            .onFailure { note(Faults.CHATS, it) }
    }

    /**
     * Deliberately does NOT raise a fault on failure. A daemon older than Rounds
     * 404s here forever, and a status bar that permanently reports a missing
     * feature as a fault is how a reader learns to stop reading it.
     */
    suspend fun refreshRounds() {
        runCatching { client.rounds() }.onSuccess { _rounds.value = it }
    }

    /** Silent on failure for the same reason as rounds: an older daemon 404s here. */
    suspend fun refreshDevices() {
        runCatching { client.devices() }.onSuccess { _devices.value = it }
    }

    /**
     * Opens a chat that runs on [deviceId].
     *
     * The daemon refuses here if the machine is asleep or too narrowly scoped, and
     * that refusal is the useful moment to hear it — so the error surfaces as a
     * fault rather than being swallowed.
     */
    /**
     * A chat on whichever machine is serving, in one act. The first available
     * local row is the door — an unstarted chat can still be re-pointed at
     * another machine from its model menu (daemon 2.77.0). Throws so callers
     * report through their own channel, like every other create here.
     */
    suspend fun startLocalChat() {
        val local = client.models().firstOrNull { it.family == "local" && it.available }
            ?: throw IllegalStateException("no machine is serving local models right now")
        val made = client.createChat("ask", model = local.id)
        openChat(made.id)
        openView(View.CHATS)
        refreshChats()
    }

    /**
     * A NEW Claude chat carrying an escalation handoff in its DRAFT — the
     * user-driven half of the conduits. Nothing is sent: the person reads,
     * edits and sends. The local chat is untouched.
     */
    suspend fun escalateWithDraft(draft: String) {
        runCatching {
            val made = client.createChat("ask")
            drafts.set(com.silencelen.huginn.data.DraftBook.chatKey(made.id), draft)
            openChat(made.id)
            openView(View.CHATS)
            refreshChats()
        }.onFailure { note(Faults.ACTION, it) }
    }

    suspend fun startChatOn(deviceId: String, mode: String) {
        // Faults.ACTION, not CHATS: the audit caught these refusals filed
        // under the polled source, which the next successful 5s chats poll
        // CLEARS - the reason vanished before anyone read it. A hand action's
        // outcome belongs to the source no poll ever touches.
        runCatching { client.createChat(mode, host = deviceId) }
            .onSuccess { made -> openChat(made.id); openView(View.CHATS); refreshChats() }
            .onFailure { note(Faults.ACTION, it) }
    }

    suspend fun forgetDevice(id: String) {
        runCatching { client.deleteDevice(id) }
            .onSuccess { refreshDevices() }
            .onFailure { note(Faults.ACTION, it) }
    }

    /**
     * Takes this computer back out of huginn entirely: every row the daemon holds
     * for this machine, then the token, the enrolment handle and the drafts here.
     *
     * SERVER FIRST, and that ordering is the whole design. The token is what
     * authorises the DELETE, so a local wipe that ran first would leave rows
     * nobody could retire — the exact failure the CLI's `off` verb was fixed for.
     * On any failure NOTHING local changes, so the action is safe to press again
     * once the daemon is reachable.
     *
     * The MACHINE, not the row: a box that also serves local models holds two
     * enrolments on purpose, and "remove this computer's access" that left the
     * serving credential behind would be a lie about what it did. Same grouping
     * as the human-facing Forget button — filter by the daemon's machine key.
     *
     * Idempotent by construction: a machine with no rows left (a previous attempt
     * that deleted them and then lost the window) is success, not an error, so
     * pressing it again finishes the job.
     */
    suspend fun removeThisComputer(): Result<Int> {
        val key = DeviceRunner.machineKey(DeviceRunner.defaultName())
        // Listing is part of the server half: if this cannot be asked, nothing is
        // known about what is out there and nothing local may be touched.
        val all = runCatching { client.devices() }
            .onFailure { note(Faults.ACTION, it) }
            .getOrElse { return Result.failure(it) }

        // The machine key finds the whole box; the stored enrolment id is the
        // belt to its braces. They are not the same net: a row enrolled before
        // machine keys existed, or one whose key this build cannot compute (an
        // unresolvable hostname), would be missed by grouping alone — and missing
        // it is the one outcome that matters, because the token about to be
        // cleared is the only thing that could ever have retired it.
        val myId = settings.deviceIdNow().takeIf { it.isNotBlank() }
        val mine = all.filter { (key != null && it.machine == key) || (myId != null && it.id == myId) }
        for (d in mine) {
            val r = runCatching { client.deleteDevice(d.id) }
            if (r.isFailure) {
                val e = r.exceptionOrNull()!!
                note(Faults.ACTION, e)
                refreshDevices()
                return Result.failure(e)
            }
        }

        settings.clearForRemoval()
        syncDeviceRunner()
        _devices.value = emptyList()
        return Result.success(mine.size)
    }

    suspend fun runRound(id: String) {
        runCatching { client.runRound(id) }
            .onSuccess { refreshRounds() }
            .onFailure { note(Faults.ACTION, it) }
    }

    /**
     * This machine's IANA zone, sent when a Round is written here.
     *
     * The shared editor is multiplatform and has no calendar, so it never names a
     * zone; without this the daemon falls back to the HOST's, which is usually the
     * same and quietly is not when it isn't.
     */
    fun deviceZone(): String? =
        runCatching { java.util.TimeZone.getDefault().id?.takeIf { it.isNotBlank() } }.getOrNull()

    /** @return null on success, otherwise the daemon's reason — not ours. */
    suspend fun createRound(draft: RoundDraft): String? =
        runCatching {
            client.createRound(
                title = draft.title.trim(),
                prompt = draft.prompt.trim(),
                schedule = draft.toSchedule(deviceZone()),
                goal = draft.goal.trim(),
                mode = draft.mode,
                notifyWhen = draft.notifyWhen,
                host = draft.host.takeIf { it != "local" },
            )
        }.fold({ refreshRounds(); null }, { it.message ?: "Could not create it" })

    suspend fun saveRound(id: String, draft: RoundDraft): String? =
        runCatching {
            client.updateRound(
                id = id,
                title = draft.title.trim(),
                prompt = draft.prompt.trim(),
                schedule = draft.toSchedule(deviceZone()),
                // Sent even when blank: clearing a goal is a real edit.
                goal = draft.goal.trim(),
                mode = draft.mode,
                notifyWhen = draft.notifyWhen,
                host = draft.host,
            )
        }.fold({ refreshRounds(); null }, { it.message ?: "Could not save it" })

    /**
     * Carries on from a finished Round, in a fresh chat on the same machine.
     * The report lands as a draft, never a sent message — see the phone's twin.
     */
    suspend fun continueRound(round: Round): String? {
        // The refusal is SHOWN, not swallowed: the audit caught "Carry on"
        // doing nothing with no message when the daemon said no (machine gone,
        // scope narrowed) — a button that silently does nothing is a broken
        // button as far as the person pressing it can tell.
        val c = runCatching {
            client.createChat(mode = round.mode, model = round.model, effort = round.effort,
                host = round.host.takeIf { it != "local" })
        }.onFailure { note(Faults.ACTION, it) }.getOrNull() ?: return null
        drafts.set(com.silencelen.huginn.data.DraftBook.chatKey(c.id), com.silencelen.huginn.ui.followUpDraft(round))
        refreshChats()
        openChat(c.id)
        return c.id
    }

    /**
     * Asks the host to rewrite one field of a Round being drafted.
     *
     * Nothing is saved and no list is refreshed: this is a PROPOSAL the editor
     * shows, and the Round — if it exists at all yet — is untouched until Save.
     * A thrown failure becomes an error IN the result rather than a fault banner;
     * the editor has a quiet line for it, and "the model was busy" is not a fault.
     */
    suspend fun polishRound(draft: RoundDraft, field: String): PolishResult =
        runCatching {
            client.polishRound(
                field = field,
                title = draft.title.trim(),
                prompt = draft.prompt.trim(),
                goal = draft.goal.trim(),
                mode = draft.mode,
            )
        }.getOrElse { PolishResult(error = it.message ?: "Polish is unavailable right now") }

    /** The schedule goes; the reports it already wrote are chats and stay. */
    suspend fun deleteRound(id: String): String? =
        runCatching { client.deleteRound(id) }
            .fold({ refreshRounds(); null }, { it.message ?: "Could not delete it" })

    /** "I have read this and dealt with it", or Undo. Optimistic, then corrected. */
    suspend fun acknowledgeRound(id: String, acknowledged: Boolean) {
        val stamp = if (acknowledged) System.currentTimeMillis() / 1000 else null
        _rounds.value = _rounds.value.map { r ->
            // ⚠ A local val, not `r.lastRun` twice: it is a public property of
            // another module, so Kotlin will not smart-cast it after the null
            // check — the compiler cannot prove :core did not change it in
            // between. The same shape fails identically in the desktop store.
            val run = r.lastRun
            if (r.id == id && run != null) r.copy(lastRun = run.copy(acknowledgedAt = stamp)) else r
        }
        runCatching { client.ackRound(id, acknowledged) }
            .onSuccess { updated -> _rounds.value = _rounds.value.map { if (it.id == id) updated else it } }
            .onFailure { note(Faults.CHATS, it); refreshRounds() }
    }

    suspend fun setRoundEnabled(id: String, enabled: Boolean) {
        // Optimistic, then corrected by the server's own answer.
        _rounds.value = _rounds.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        runCatching { client.updateRound(id, enabled = enabled) }
            .onSuccess { updated -> _rounds.value = _rounds.value.map { if (it.id == id) updated else it } }
            .onFailure { note(Faults.CHATS, it); refreshRounds() }
    }

    suspend fun refreshSessions() {
        // preview=1: the list rows show what each session is doing, which is the
        // only thing that makes the list worth reading at a glance.
        runCatching { client.sessions(preview = true) }
            .onSuccess { _sessions.value = it; _sessionsLoaded.value = true; faults.ok(Faults.SESSIONS) }
            .onFailure { note(Faults.SESSIONS, it) }
    }

    /**
     * The archived list, and the flag that says whether this daemon has them.
     *
     * Silent on failure and only a 404 is recorded — the same shape as
     * [refreshPads] and refreshRounds. A status bar that permanently reports a
     * missing feature as a fault is a status bar people stop reading.
     */
    suspend fun refreshArchives() {
        runCatching { client.archives() }
            .onSuccess { _archives.value = ArchiveRules.ordered(it); _archiveAvailable.value = true }
            .onFailure {
                if (it is HuginnClient.HuginnException && it.code == 404) _archiveAvailable.value = false
            }
    }

    /**
     * Archive sessions: end them for good, keeping the way back into each.
     *
     * Graceful, like the wind-down it is built on — so this returns while they
     * are still on screen winding down, and the rows appear when they settle.
     *
     * ⚠ THE REFUSAL IS RE-THROWN, not swallowed. "answer the waiting question
     * first, then archive the session" is the most useful thing this action ever
     * says, and [act]'s note surfaces the daemon's own sentence; a caught-and-
     * summarised failure here would turn it into "could not archive".
     */
    suspend fun archiveSessions(names: List<String>, now: Boolean = false) {
        val refused = mutableListOf<String>()
        for (n in names) {
            runCatching { client.archiveSession(n, now = now) }
                .onSuccess {
                    drafts.clear(DraftBook.sessionKey(n))
                    sentHistory.clear(DraftBook.sessionKey(n))
                }
                .onFailure { refused += "$n: ${it.message}" }
        }
        refreshSessions()
        refreshArchives()
        check(refused.isEmpty()) { refused.joinToString("; ") }
    }

    /**
     * Bring one back and open it.
     *
     * The name comes from the HOST, never from the row: the old one is taken when
     * free and numbered when not, so opening `row.tmuxName` would show a session
     * that does not exist — or a stranger that reused the name.
     */
    suspend fun reviveArchive(row: ArchivedSession) {
        runCatching { client.reviveArchive(row.id) }
            .onSuccess { r ->
                refreshSessions()
                refreshArchives()
                openSession(r.name)
            }
            .onFailure { note(Faults.ACTION, it) }
    }

    suspend fun deleteArchive(row: ArchivedSession) {
        runCatching { client.deleteArchive(row.id) }
            .onSuccess {
                // The pane cannot outlive the row it is reading.
                if (_archiveRead.value?.id == row.id) _archiveRead.value = null
                refreshArchives()
            }
            .onFailure { note(Faults.ACTION, it) }
    }

    // ------------------------------------------ one archive, read only

    /**
     * The conversation of ONE archived session, as the detail pane draws it.
     *
     * ⚠ NOT A SESSION, AND NOT POLLED. Every other detail this store holds is
     * keyed on a tmux NAME and followed; this is keyed on the Claude session
     * uuid, read once from a copy on disk, and has nothing behind it to poll or
     * type at. It shares the detail half with [SessionView], so exactly one of
     * the two may be set — see [openSession].
     */
    data class ArchiveRead(
        val id: String,
        val title: String?,
        val page: TranscriptPage? = null,
        val loading: Boolean = true,
        /** Why there is nothing to show: the copy is gone, or the read failed. */
        val note: String? = null,
    )

    private val _archiveRead = MutableStateFlow<ArchiveRead?>(null)
    val archiveRead: StateFlow<ArchiveRead?> = _archiveRead.asStateFlow()

    /** Open one archive in the detail pane, read only. */
    fun openArchive(row: ArchivedSession) {
        _view.value = View.SESSIONS
        // The live session lets go of the pane, for the same reason the archive
        // does in [openSession]: one detail half, one occupant.
        _sessionName.value = null
        if (_archiveRead.value?.id == row.id && _archiveRead.value?.loading == false) return
        _archiveRead.value = ArchiveRead(id = row.id, title = ArchiveRules.label(row), loading = true)
        scope.launch {
            val outcome = runCatching { client.archiveTranscript(row.id) }
            val current = _archiveRead.value
            // A late answer must not paint itself over whatever the reader opened
            // while it was in flight.
            if (current?.id != row.id) return@launch
            _archiveRead.value = outcome.fold(
                onSuccess = { page ->
                    // ⚠ NULL IS THE 404 — "no such archive" AND "this daemon has
                    // no such route" at once. Either way there is nothing to read.
                    current.copy(
                        page = page,
                        loading = false,
                        note = if (page == null) ARCHIVE_TRANSCRIPT_GONE else null,
                    )
                },
                // The 409 carries the daemon's own sentence about which copies
                // went, which beats any summary of ours.
                onFailure = { t ->
                    current.copy(
                        loading = false,
                        note = (t as? HuginnClient.HuginnException)?.message
                            ?: t.message ?: ARCHIVE_TRANSCRIPT_GONE,
                    )
                },
            )
        }
    }

    fun closeArchive() { _archiveRead.value = null }

    /**
     * One read of `/v1/headroom`.
     *
     * A failure is left to the fault sweeper rather than raised: the ONLY
     * expected failure is a 404 from a daemon that has no headroom subsystem, and
     * putting "not found" in the status line of every client talking to an older
     * host would be a permanent error about a feature that host never had. The
     * pill simply stays hidden, which is the documented compat answer.
     */
    suspend fun refreshHeadroom() {
        runCatching { client.headroom() }.onSuccess { _headroom.value = it }
    }

    suspend fun refreshStatus() {
        runCatching { client.status() }
            .onSuccess { _status.value = it; faults.ok(Faults.STATUS); routeFailures.ok(); noteRouteReached() }
            .onFailure { note(Faults.STATUS, it) }
        runCatching { client.plan() }.onSuccess { _plan.value = it }
        runCatching { client.usage() }.onSuccess { _usage.value = it }
    }

    /**
     * `/v1/status` ALONE — the shelf, not the whole Status pane.
     *
     * Settings asks three things of it (does this host hold quick actions, what
     * is its soft-end phrase, which appd is it) and a reader who opens Settings
     * has not asked for `/v1/usage`, which walks every transcript on the host to
     * answer. Separated rather than made a parameter so no future caller can get
     * the expensive one by forgetting an argument.
     */
    suspend fun refreshStatusShelf() {
        runCatching { client.status() }
            .onSuccess { _status.value = it; faults.ok(Faults.STATUS); routeFailures.ok(); noteRouteReached() }
            .onFailure { note(Faults.STATUS, it) }
    }

    private fun note(source: String, t: Throwable) {
        // A CANCELLATION IS NOT A FAULT. `pollLoop` and `watchLoop` both hang off
        // `collectLatest`, which cancels the in-flight refresh every time presence
        // flips — so walking away from the desk and back reliably put
        // "Child of the scoped flow was cancelled" on screen, where it said nothing
        // to the reader and sat on top of any real error underneath it. Caught here
        // rather than at each call site because every one of them uses
        // `runCatching`, which does not spare CancellationException either.
        //
        // A cancellation is also NOT a success: it neither raises a fault nor
        // clears one, so a refresh cut short by a presence flip leaves whatever was
        // true before it exactly as it was.
        if (t is kotlinx.coroutines.CancellationException) return
        // Three network failures in a row on the active route and this client
        // goes looking for another one. ⚠ THE DESKTOP NEVER DID THIS: it
        // resolved once from start() and then stayed wherever it was, so a
        // laptop that moved networks was broken until it was restarted.
        noteRouteFailure(t)
        faults.fail(
            source,
            when (t) {
                is HuginnClient.HuginnException -> t.message
                else -> t.message ?: "network error"
            },
        )
    }

    /**
     * The same reporting path, for calls the SHELL makes rather than the poll loop:
     * rename, delete, interrupt, end a session. Those go straight to the client
     * from a context-menu item, and without this a failed one does nothing at all —
     * the row stays, the reason is swallowed, and the reader is left to guess
     * whether the click even registered.
     */
    fun noteError(t: Throwable) = note(Faults.ACTION, t)

    // ------------------------------------------------------------- lifecycle

    /**
     * Starts the three long-lived loops. Called once, from the window's
     * composition; each loop lives as long as [scope].
     */
    fun start() {
        scope.launch { drafts.load() }
        scope.launch { sentHistory.load() }
        scope.launch { resolveRoute() }
        scope.launch { pollLoop() }
        scope.launch { watchLoop() }
        scope.launch { presenceTicker() }
        scope.launch { restoreLanding() }
        scope.launch { rememberLanding() }
        // Not left to the poll loop alone: that loop is gated on the window being
        // VISIBLE, and an app relaunched straight into the tray would then neither
        // offer this machine nor pay off a pending unenrol until somebody happened
        // to open the window. Idempotent, so the poll's own call still costs nothing.
        syncDeviceRunner()
        updater.start(scope)
        // Records stream connects/drops, update outcomes and uncaught errors into
        // the ring buffer the Settings screen copies. Derived entirely from state
        // this store already publishes — no second source of truth, no new poll.
        com.silencelen.huginn.desktop.diag.AppLog.attach(this)
    }

    /**
     * Picks the first healthy route IN THE OWNER'S ORDER. Skipped when a route
     * was pinned by hand — auto-resolution moving off a deliberately chosen
     * route is the bug the pin exists to prevent.
     *
     * Nothing answering leaves the setting alone rather than blanking it, so a
     * laptop opened off-network still knows where home is.
     */
    private suspend fun resolveRoute(force: Boolean = false) {
        _resolvingRoute.value = true
        val outcome = RouteResolver.resolve(
            book = _routeBook.value,
            health = _routeHealth.value,
            now = System.currentTimeMillis(),
            force = force,
        ) { client.probe(it.url) }
        _resolvingRoute.value = false
        saveHealth(outcome.health)
        // Cleared on EVERY resolution before it is set again: an offer describes
        // the sweep that just ran, and a stale one invites a person to hand the
        // bearer to a host that has since gone quiet.
        _routeCandidate.value = (outcome.choice as? RouteResolver.Choice.Stay.Candidate)?.candidate
        _routeNote.value = when (val choice = outcome.choice) {
            is RouteResolver.Choice.Empty -> null
            is RouteResolver.Choice.Pinned -> "pinned to ${choice.route.name} — switch automatically to move"
            is RouteResolver.Choice.NoRoute -> "no route answered — is a VPN connected?"
            is RouteResolver.Choice.Stay -> "still on ${choice.route.name}"
            is RouteResolver.Choice.Switched -> {
                editRoutesNow { it.activate(choice.route.id) }
                "switched to ${choice.route.name}"
            }
        }
    }

    /**
     * Reopens the chat or session that was open last time — ONLY if it is still
     * there.
     *
     * The view itself was restored synchronously at construction; this is the
     * target, and it has to wait because "still there" is a question only the
     * first list fetch can answer. A chat deleted from the phone overnight, or a
     * session that ended, must not reopen into a pane addressing something the
     * daemon does not have: both detail views recover from a target vanishing
     * underneath them, but recovering from a state we chose to enter is a flash of
     * a broken pane on every launch.
     *
     * It sets the id WITHOUT touching the view, so a reader who has already
     * navigated somewhere in the second this took is not yanked back — and it
     * gives up entirely if something is already open, which is what an activation
     * (`huginn://open?...`) on the command line does before this can run.
     */
    private suspend fun restoreLanding() {
        val chat = settings.lastChatIdNow()
        val session = settings.lastSessionNameNow()
        if (chat == null && session == null) return
        // Bounded: an unreachable daemon must not leave this coroutine parked for
        // the life of the app waiting for a list that is never coming.
        val ready = kotlinx.coroutines.withTimeoutOrNull(LANDING_WAIT_MS) {
            _listsLoaded.first { it }
            _sessionsLoaded.first { it }
            true
        }
        if (ready != true) return
        if (chat != null && _chatId.value == null && _chats.value.any { it.id == chat }) {
            _chatId.value = chat
        }
        if (session != null && _sessionName.value == null && _sessions.value.any { it.name == session }) {
            _sessionName.value = session
        }
    }

    /**
     * Writes the position back, on a trailing edge.
     *
     * Debounced because Alt+↓ down a list is one of these per key repeat and this
     * file also holds the token — the same argument as the window geometry watcher
     * in `main`. Collected from the flows rather than written by `openChat` and
     * friends so that every path arrives here: the keyboard walk mutates the ids
     * directly, and a notification activation does not go through the shell at all.
     */
    private suspend fun rememberLanding() {
        kotlinx.coroutines.flow.combine(_view, _chatId, _sessionName) { v, c, s -> Triple(v, c, s) }
            .debounce(LANDING_WRITE_DEBOUNCE_MS)
            .collect { (v, c, s) -> settings.setLanding(v, c, s) }
    }

    /**
     * Writes the position NOW, without waiting out the debounce.
     *
     * A trailing-edge writer always loses whatever happened inside its last
     * window, and the change most likely to land there is the last one you make —
     * open a session and quit straight away and the debounce is still counting
     * when the process goes. The next launch then reopens the position from
     * BEFORE the thing you were most recently looking at, which reads as the
     * feature not working at all.
     *
     * Synchronous on purpose: both callers are exit paths, and a coroutine
     * launched there would be racing the process. Safe to call twice — the
     * shutdown hook runs after `quit()` on the ordinary path — because
     * `setLanding` compares before it writes.
     */
    fun flushLanding() {
        settings.setLanding(_view.value, _chatId.value, _sessionName.value)
    }

    /**
     * The 5s list poll, GATED ON VISIBILITY.
     *
     * A hidden window that keeps polling is not just wasted traffic: the pane poll
     * renews the tmux size lease, so a minimized desktop can hold another
     * operator's session at this window's geometry for as long as it stays
     * minimized. Coming back from hidden refreshes at once rather than waiting out
     * the interval, because a five-second-stale list on the frame you look at it
     * is the whole impression of the app being alive.
     */
    private suspend fun pollLoop() {
        // collectLatest, not a bare loop reading `visible.value`: becoming visible
        // has to refresh on the frame it happens, not up to five seconds later.
        presence.visible.collectLatest { visible ->
            if (!visible) return@collectLatest
            // Zero on every resume, so coming back from hidden reads headroom on
            // the first pass rather than up to thirty seconds later.
            var tick = 0
            while (scope.isActive) {
                // Before the fetches, so a fault raised by a source that has no
                // poll behind it (a rename that 400'd) ages out on the app's own
                // clock rather than waiting for a click that may never come.
                faults.sweep()
                refreshChats()
                refreshSessions()
                refreshRounds()
                refreshDevices()
                // The pages LIST only. Their text is fetched when one is opened,
                // for the same reason the transcript is not in this loop: a poll
                // that overwrote what somebody is typing is not a refresh.
                refreshPads()
                // The project ROWS only, and they are one request whatever the
                // cluster size: `GET /v1/projects` carries summed counts and no
                // membership, which is what makes a twelve-member project cost
                // the list poll nothing.
                refreshProjects()
                // The members and the rollup are the expensive halves, so they
                // are gated on the pane being the one in front of the reader.
                // A dashboard walks every member's transcript on the host — see
                // `dashboardMoved` for what stops it redrawing when it has not
                // moved — and the members are one GET per folded-open row.
                if (_view.value == View.PROJECTS) {
                    refreshProjectMembers()
                    refreshProjectDashboard()
                }
                // UNTIL IT ANSWERS, as well as while Apps is open. It used
                // to be `tick == 0`, and the comment here predicted the failure it
                // then had — a probe that only ran on the pane nobody can reach
                // hides the door to itself — while missing the trigger: the 401 a
                // fresh install's first tick gets while setup is still open is not
                // an answer about the feature. See `shouldProbeApps`.
                if (shouldProbeApps(_appsAvailable.value, _view.value)) refreshApps()
                // ⚠ THE TWO SESSION LISTS MOVE TOGETHER. A graceful archive leaves
                // the session on screen for as long as its turn runs and then
                // moves it — so a Sessions poll that did not also fetch the
                // archive would show a row vanish with nothing appearing anywhere.
                // Every fourth pass, not every one: an archive changes when
                // somebody presses something, while the sessions list changes on
                // its own.
                if (tick % 4 == 0) refreshArchives()
                // Cheap, because start() returns immediately when the runner is
                // already going. This way it survives a settings file edited
                // underneath the app as well as a toggle in the UI.
                syncDeviceRunner()
                // ⚠ ONCE PER RESUME as well as while Status is open. `/v1/status`
                // is where the host's quick-action wording lives, and the thing
                // that needs it is the right-click menu over a TRANSCRIPT —
                // nowhere near the Status pane. Fetched only on that pane, every
                // selection menu in the app offered Quote alone forever, which
                // looks exactly like the other three verbs not being built. The
                // per-view poll stays for the figures that actually move.
                if (tick == 0 || _view.value == View.STATUS) refreshStatus()
                // Every sixth pass, which is thirty seconds — the rate the design
                // costed. Counted rather than given its own loop so it cannot
                // outlive the visibility gate the rest of the polling obeys.
                if (tick % HEADROOM_EVERY == 0) refreshHeadroom()
                tick += 1
                delay(POLL_MS)
            }
        }
    }

    private suspend fun presenceTicker() {
        while (scope.isActive) {
            presence.tick()
            // The poll stops while the window is hidden; this does not. A stale
            // fault must not be waiting on screen when the window comes back.
            faults.sweep()
            delay(30_000)
        }
    }

    /**
     * The watch stream, reconnecting for as long as the app lives.
     *
     * `collectLatest` over the presence flow is the reconnect mechanism, not a
     * convenience: the notify claim is stamped on the request when the socket
     * OPENS and a parked SSE re-sends that same header on every keepalive, so
     * walking away from the desk leaves the daemon believing this client is a
     * delivery route until the 30-minute rotation. Dropping and re-opening the
     * stream when presence flips is what makes the claim true.
     *
     * Collected over [Presence.streamKey] rather than `present` itself, which
     * carries that same presence flip AND the resume-from-sleep bump — sockets are
     * black-holed by a suspend and hang until an idle timeout rather than failing.
     * One counter, because the remedy for both is identical.
     */
    private suspend fun watchLoop() {
        presence.streamKey.collectLatest {
            var backoffMs = MIN_BACKOFF_MS
            var hash: String? = null
            while (scope.isActive) {
                var sawAnything = false
                var rotated = false
                // ⚠ CLEARED ON A CONNECT, because the reason is PERSISTED and
                // nothing else ever untrue-d it: a client reconnected hours ago
                // still reported `last watch err unauthorized at …` beside `watch
                // stream connected`, which is the pair that makes a healthy
                // client look broken in a bug report.
                var cleared = false
                suspend fun connected() {
                    _watchConnected.value = true
                    if (!cleared) { cleared = true; settings.clearWatchError() }
                }
                client.watchStream(hash).collect { ev ->
                    when (ev) {
                        is WatchEvent.State -> {
                            sawAnything = true
                            connected()
                            hash = ev.watch.hash
                            _watchTick.value = _watchTick.value + 1
                            // The digest says only THAT something changed; the
                            // lists carry more than it does, so re-fetch them.
                            refreshChats()
                            refreshSessions()
                            onDigest?.invoke(ev.watch)
                        }
                        WatchEvent.Alive -> { sawAnything = true; connected() }
                        WatchEvent.Rotated -> { sawAnything = true; rotated = true }
                        is WatchEvent.Failure -> {
                            _watchConnected.value = false
                            cleared = false
                            settings.noteWatchError(ev.message, System.currentTimeMillis())
                        }
                    }
                }
                if (sawAnything) backoffMs = MIN_BACKOFF_MS
                // A clean server-side rotation, not a fault: reconnect with NO
                // delay at all. Treating it as a failure would leave the client
                // unwatched for a second every half hour for no reason, and the
                // gap is exactly when a notification would be missed.
                if (rotated) continue
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    companion object {

        /** Appended below the text when "Ask in new chat" could not make one. */
        const val NEW_CHAT_FAILED: String = "\n\n(could not open a new chat, so this is here instead: "
        const val POLL_MS: Long = 5_000

        /** Passes of the 5s poll between headroom reads: 6 × 5s = 30s. */
        const val HEADROOM_EVERY: Int = 6
        const val MIN_BACKOFF_MS: Long = 1_000
        const val MAX_BACKOFF_MS: Long = 30_000

        /** How long the landing restore waits for the first lists before giving up. */
        const val LANDING_WAIT_MS: Long = 15_000

        /** Trailing edge for writing the position back. One key repeat is not a decision. */
        const val LANDING_WRITE_DEBOUNCE_MS: Long = 800

        /**
         * The one store, for the one surface the shell still builds from a bare
         * client.
         *
         * NOT a service locator, and it should not grow a second reader: `Shell`
         * hands the store to every other view, and `ChatView` — which now needs
         * the draft book and a way to close a chat it has just deleted — takes it
         * as a defaulted parameter until that call site says `store` instead of
         * `store.client`. One line there deletes this.
         */
        @Volatile
        var current: AppStore? = null
            private set
    }
}
