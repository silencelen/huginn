package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.Round
import com.silencelen.huginn.ui.RoundDraft
import com.silencelen.huginn.ui.toSchedule
import com.silencelen.huginn.desktop.device.DeviceRunner
import com.silencelen.huginn.desktop.update.BuildInfo
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.SentHistory
import com.silencelen.huginn.ui.AttachmentImageLoader
import com.silencelen.huginn.ui.SkiaImageBytesDecoder
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.PolishResult
import com.silencelen.huginn.data.RouteResolver
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.Usage
import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchEvent
import com.silencelen.huginn.desktop.update.DesktopUpdater
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

/** Which of the four destinations the window is showing. */
enum class View { CHATS, SESSIONS, ROUNDS, DEVICES, STATUS, SETTINGS }

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
     * ONE client for the whole app. The notify claim rides on a request header,
     * so it is read per-request from [Presence] rather than fixed at construction
     * — that is the only way an answer given now can reflect a desk that emptied
     * five minutes ago.
     *
     * `notifyEnabled && present`, and both halves matter: notifications turned off
     * means this client is not a route no matter who is sitting here, and a claim
     * made while nobody is looking suppresses the Telegram fallback that would
     * have reached the owner.
     */
    val client = HuginnClient(
        baseUrlProvider = { settings.baseUrlNow() },
        tokenProvider = { settings.tokenNow() },
        clientIdProvider = { settings.clientIdNow() },
        canNotifyProvider = { settings.notifyEnabledNow() && presence.present.value },
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
     * Thumbnails for photo attachments in chat history. App level so decoded
     * bitmaps survive scrolling and view switches; provided to the shared
     * transcript renderer via [com.silencelen.huginn.ui.LocalAttachmentImages].
     */
    val attachmentImages = AttachmentImageLoader({ client.uploadBytes(it) }, SkiaImageBytesDecoder())

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

    fun openView(v: View) {
        _view.value = v
        // Status is the one view the 5s list poll does not already feed, so
        // arriving on it would otherwise show an empty screen for up to five
        // seconds — indistinguishable from a daemon that is not answering.
        if (v == View.STATUS) scope.launch { refreshStatus() }
    }
    fun openChat(id: String?) { _view.value = View.CHATS; _chatId.value = id }
    fun openSession(name: String?) { _view.value = View.SESSIONS; _sessionName.value = name }

    /** Escape: close the open item, or fall back to the chats list. */
    fun back() {
        when (_view.value) {
            View.CHATS -> if (_chatId.value != null) _chatId.value = null
            View.SESSIONS -> if (_sessionName.value != null) _sessionName.value = null
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

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

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

    suspend fun refreshStatus() {
        runCatching { client.status() }
            .onSuccess { _status.value = it; faults.ok(Faults.STATUS) }
            .onFailure { note(Faults.STATUS, it) }
        runCatching { client.plan() }.onSuccess { _plan.value = it }
        runCatching { client.usage() }.onSuccess { _usage.value = it }
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
     * Picks a reachable address before the first real call. Skipped when the route
     * was pinned by hand — auto-resolution moving off a deliberately chosen route
     * is the bug the pin exists to prevent.
     */
    private suspend fun resolveRoute() {
        if (settings.routePinnedNow()) return
        val current = settings.baseUrlNow()
        val found = RouteResolver.resolve(AppdRoutes.candidates(current)) { client.probe(it) }
        // Null means nothing answered: leave the setting alone rather than blank
        // it, so a laptop opened off-network still knows where home is.
        if (found != null && found != current) {
            runCatching { settings.selectRoute(found, pinned = false) }
            _route.value = found
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
            while (scope.isActive) {
                // Before the fetches, so a fault raised by a source that has no
                // poll behind it (a rename that 400'd) ages out on the app's own
                // clock rather than waiting for a click that may never come.
                faults.sweep()
                refreshChats()
                refreshSessions()
                refreshRounds()
                refreshDevices()
                // Cheap, because start() returns immediately when the runner is
                // already going. This way it survives a settings file edited
                // underneath the app as well as a toggle in the UI.
                syncDeviceRunner()
                if (_view.value == View.STATUS) refreshStatus()
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
                client.watchStream(hash).collect { ev ->
                    when (ev) {
                        is WatchEvent.State -> {
                            sawAnything = true
                            _watchConnected.value = true
                            hash = ev.watch.hash
                            _watchTick.value = _watchTick.value + 1
                            // The digest says only THAT something changed; the
                            // lists carry more than it does, so re-fetch them.
                            refreshChats()
                            refreshSessions()
                            onDigest?.invoke(ev.watch)
                        }
                        WatchEvent.Alive -> { sawAnything = true; _watchConnected.value = true }
                        WatchEvent.Rotated -> { sawAnything = true; rotated = true }
                        is WatchEvent.Failure -> {
                            _watchConnected.value = false
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
        const val POLL_MS: Long = 5_000
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
