package com.silencelen.huginn.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silencelen.huginn.appVersion
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.ArchivedSession
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppCreate
import com.silencelen.huginn.data.AppForm
import com.silencelen.huginn.data.AppList
import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.notify.DeliveryCopy
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.data.PolishResult
import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectDetail
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectManifest
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.ui.ModelLabels
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.data.Usage
import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.Autoswitch
import com.silencelen.huginn.data.Alerts
import com.silencelen.huginn.data.ClientsInfo
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.PushStatus
import com.silencelen.huginn.data.Round
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.ScratchpadSaver
import com.silencelen.huginn.data.SessionGraph
import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import com.silencelen.huginn.data.SessionOverview
import com.silencelen.huginn.data.LoginSession
import com.silencelen.huginn.data.LoginState
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteFailures
import com.silencelen.huginn.data.RouteGuard
import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteHealth
import com.silencelen.huginn.data.RouteResolver
import com.silencelen.huginn.data.UriByteStream
import com.silencelen.huginn.data.Watchers
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.data.PaneLease
import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.Heartbeat
import com.silencelen.huginn.notify.PushTally
import com.silencelen.huginn.notify.HuginnMessagingService
import com.silencelen.huginn.notify.WatchService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// `mergeTranscript` moved to :core in phase 3c — same package, so every call site
// here is unchanged. The desktop client needs the identical row-identity rule, and
// two implementations of "which row is this" is the divergence this migration
// exists to stop.

/** What to show and where to resume when reattaching to a running chat. */
internal data class Reattach(val seed: String, val since: Long)

/**
 * How to pick a running chat back up, or null when there is nothing to follow.
 *
 * The seed (`partialText`) and the replay are two accounts of the SAME text, so the
 * subscription has to start where the seed ends. Subscribing from 0 replays the
 * deltas the seed already contains and renders the answer twice — for as long as
 * the block keeps streaming, since live deltas then append to a doubled base.
 *
 * A daemon older than 2.48.0 reports no position. Then the replay alone is the only
 * non-doubling choice, and it is also the more complete one: the seed is merely an
 * accumulation the server kept, while the replay is the same event stream that
 * drives live rendering.
 */
internal fun reattachPlan(meta: ChatDetail?): Reattach? {
    if (meta?.running != true) return null
    val seq = meta.seq ?: return Reattach(seed = "", since = 0)
    return Reattach(seed = meta.partialText ?: "", since = seq)
}

/**
 * ⚠ WHAT A SESSION NAME IS, on this client. One rule, shared by create and
 * rename, matching the daemon's own and the other three clients' (contract 1 of
 * the 2026-09-17 edge hunt).
 *
 * DOTS ARE BANNED. tmux silently rewrites `.` to `_` in a session name and
 * reports the rewritten one nowhere the old readbacks looked, so a session
 * created or renamed with a dot existed under a name nothing could route to:
 * every subsequent call 404ed. Dashes survive tmux untouched and are ordinary
 * in names typed at a keyboard, so they are allowed — the phone used to refuse
 * them on create and accept dots on rename, which was exactly backwards.
 */
internal val SESSION_NAME = Regex("^[a-z0-9_][a-z0-9_-]{0,49}$")

internal const val SESSION_NAME_HELP: String =
    "Start with a letter, digit or _; letters, digits, _ and - after that"

/**
 * What to say when the pane poll 404s.
 *
 * ⚠ A NAME STILL IN THE SESSION LIST DID NOT END. The daemon lists it and then
 * cannot address it — the dotted-name case above, from before the rule was
 * enforced — and "Session x ended" about a session the reader can still see in
 * the list is a lie that sends them looking for the wrong problem. (The Android
 * half of the desktop's #85.)
 */
internal fun sessionGoneWords(name: String, known: List<String>): String =
    if (known.contains(name)) "huginn cannot address a session named \"$name\" — rename it in tmux"
    else "Session $name ended"

/**
 * Whether a chat screen's teardown is still tearing down the CURRENT chat.
 *
 * Pure for the same reason [pageStillWanted] is: the check was simply absent.
 * A null [disposingChat] is an unconditional detach — leaving the chat surface
 * rather than hopping between two of them.
 */
internal fun detachWanted(disposingChat: String?, openNow: String?): Boolean =
    disposingChat == null || disposingChat == openNow

/**
 * Whether a history page that has just arrived still belongs on the screen.
 *
 * Pure and top-level beside [reattachPlan] and [applyAutoSwitch], because the
 * thing it decides is an identity check that was simply absent: "load earlier"
 * launched an untracked coroutine, the view model outlives the session screen,
 * and `TranscriptPage` carries no session of its own — so a page fetched for
 * session A landed in whichever session was open when it came back, welded
 * permanently above B's tail with none of the restart guards firing (the merge
 * keeps the CURRENT page's claudeSessionId, so `isTranscriptRestart` stays
 * false from then on). The request can be in flight for tens of seconds.
 *
 * Null [openNow] — the reader left the session list entirely — wants nothing.
 */
internal fun pageStillWanted(requestedFor: String, openNow: String?): Boolean =
    openNow != null && openNow == requestedFor

/**
 * Turning "switch automatically" on is TWO steps, and their ORDER is the whole
 * rule: persist first, then probe.
 *
 * Pure and top-level for the same reason [reattachPlan] is — the mistake it
 * prevents is arithmetic on sequencing, which no screen shows. Both steps used
 * to be launched independently: the persist suspends inside DataStore before
 * `_routeBook` is republished, so the probe read the OLD book with autoSwitch
 * still false, `RouteResolver.resolve` short-circuited, and the reader who had
 * just enabled auto-switching was told "Route is pinned — unpin to switch
 * automatically" while nothing was probed at all.
 *
 * ⚠ AND `force`. Once the book is right, an unforced resolve on a fresh health
 * map answers Stay and still probes nothing; the desktop already passed force
 * on this path and the phone did not.
 */
internal suspend fun applyAutoSwitch(
    on: Boolean,
    persist: suspend (Boolean) -> Unit,
    resolve: suspend (force: Boolean) -> Unit,
) {
    persist(on)
    if (on) resolve(true)
}

/**
 * What the reader is told when a request fails.
 *
 * A top-level function rather than a method so the rule can be tested without an
 * Application — and it is a rule, not a formatting detail. A 401 is the one code
 * whose own text ("Unauthorized") tells nobody what to do, so it is replaced. **Every
 * other code keeps the daemon's own sentence, verbatim.** That matters most for the
 * 409 an account switch raises: the host answers "<email> cannot be switched to: its
 * login expired on <date> — sign in again", which is the entire answer to the
 * question the reader pressed the button to ask. The desktop replaced that with
 * "could not switch" and threw it away; this client must never learn to.
 *
 * ⚠ ANYTHING THAT IS NOT THE DAEMON goes through [DeliveryCopy.trouble]. A
 * transport exception is not huginn speaking — it is Ktor/OkHttp, and its
 * message carries the daemon's address ("Connect timeout has expired
 * [url=http://<host>:<port>/v1/status…]", "Failed to connect to /<host>:<port>",
 * the hostname on a DNS failure). That went verbatim to the Status tab's red
 * banner and to every toast. `trouble` is the household sentence for the same
 * failure, with the address scrubbed; it was written for exactly this and was
 * wired into one settings page only. Sentence-cased here because a banner is a
 * sentence, not a clause.
 */
internal fun errorTextFor(e: Throwable): String = when (e) {
    is HuginnClient.HuginnException ->
        if (e.code == 401) "Rejected by huginn: check the token in Settings" else e.message
    else -> {
        val raw = e.message ?: e::class.java.simpleName
        DeliveryCopy.trouble(raw)
            .replaceFirstChar { it.uppercaseChar() }
            .ifBlank { "This phone could not reach huginn." }
    }
}

/**
 * The headroom settings as a PATCH body.
 *
 * Pure and top-level for the same reason [reattachPlan] is: it is the shape of a
 * request, and a request's shape is worth a test.
 */
internal fun headroomPatch(s: com.silencelen.huginn.data.HeadroomSettings): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("headsUpPct", kotlinx.serialization.json.JsonPrimitive(s.headsUpPct))
        put("ladderPct", kotlinx.serialization.json.JsonPrimitive(s.ladderPct))
        put("ladderUpBelowPct", kotlinx.serialization.json.JsonPrimitive(s.ladderUpBelowPct))
        put("stopPct", kotlinx.serialization.json.JsonPrimitive(s.stopPct))
        put("stopFablePct", kotlinx.serialization.json.JsonPrimitive(s.stopFablePct))
        put("clearBelowPct", kotlinx.serialization.json.JsonPrimitive(s.clearBelowPct))
        put("cooldownMs", kotlinx.serialization.json.JsonPrimitive(s.cooldownMs))
        put("ladder", kotlinx.serialization.json.JsonArray(s.ladder.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("defaultModel", kotlinx.serialization.json.JsonPrimitive(s.defaultModel))
        put("autoResume", kotlinx.serialization.json.JsonPrimitive(s.autoResume))
        put("resumePhrase", kotlinx.serialization.json.JsonPrimitive(s.resumePhrase))
        put("headsUpText", kotlinx.serialization.json.JsonPrimitive(s.headsUpText))
        put("accountSwitch", kotlinx.serialization.json.buildJsonObject {
            put("enabled", kotlinx.serialization.json.JsonPrimitive(s.accountSwitch.enabled))
            put("threshold", kotlinx.serialization.json.JsonPrimitive(s.accountSwitch.threshold))
            put("margin", kotlinx.serialization.json.JsonPrimitive(s.accountSwitch.margin))
        })
        put("keepAwake", kotlinx.serialization.json.JsonPrimitive(s.keepAwake))
        put("keepAwakeModel", kotlinx.serialization.json.JsonPrimitive(s.keepAwakeModel))
        // null, not omitted: "no quiet hours" is a value the form has to be able
        // to SEND, and a key left out of a PATCH means "leave it alone" — so
        // clearing the field would silently keep the old span.
        put(
            "keepAwakeQuietHours",
            s.keepAwakeQuietHours?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                ?: kotlinx.serialization.json.JsonNull,
        )
    }

/**
 * THE SECOND CURSOR PAIR, and everything that has to move with it.
 *
 * There is no such thing as one cursor for two files. `transcriptOffset` and
 * `historyStart` on the view model are byte positions in the SESSION's own
 * `.jsonl`; these are byte positions in one agent's. Reusing the first pair for
 * both would tail an agent from an offset measured in the parent — meaningless
 * there and, on a long session, past the end of the file.
 *
 * A class rather than four more fields on a 2500-line view model, because this is
 * the part with the failures in it and the view model cannot be built without an
 * Application. Pure Kotlin: the state machine is testable, and `delay` and the
 * HTTP call around it are not the part worth testing.
 */
/**
 * The stream strip's agent list.
 *
 * A function of its own, and `internal`, for one reason: the ARGUMENT is the
 * behaviour. `all = true` is load-bearing rather than habit — the strip shows
 * only what is running, but the `…` pill exists to get BACK into a transcript
 * that settled, and the route's default 45-minute window would hide exactly the
 * older runs somebody unfolds the pill to reach while the pill's count went on
 * claiming they were there. The view model cannot be built without an
 * Application, so this is the only place that choice can be held to.
 */
internal suspend fun fetchStreamAgents(
    client: HuginnClient,
    name: String,
): List<com.silencelen.huginn.data.AgentRun> = client.sessionAgents(name, all = true).agents

/**
 * The composer templates, saved.
 *
 * A function of its own, `internal`, for the same reason [fetchStreamAgents] is:
 * the ARGUMENTS are the behaviour, and the view model cannot be built without an
 * Application, so this is the only place they can be held to.
 *
 * ⚠ THE `rev` IS THE POINT. It is the copy the editor was opened on, and the
 * daemon 409s a stale one rather than silently taking the older text — two
 * clients editing the same four templates is the ordinary case here, not the
 * exotic one, and without the guard the second Save wins by being second. All
 * four fields go every time because the editor holds all four: sending only what
 * changed would need this to know what "changed" means, and the editor already
 * reloaded itself from the answer.
 */
internal suspend fun saveQuickActions(
    client: HuginnClient,
    edited: com.silencelen.huginn.data.QuickActions,
): com.silencelen.huginn.data.QuickActions = client.setQuickActions(
    explain = edited.explain,
    execute = edited.execute,
    askInNewChat = edited.askInNewChat,
    quote = edited.quote,
    rev = edited.rev,
)

internal class AgentStream {

    /** The picked agent id, or null for the session's own transcript. */
    var selected: String? = null
        private set

    var page: TranscriptPage? = null
        private set

    var offset: Long? = null
        private set

    var historyStart: Long? = null
        private set

    /** Why the strip is disabled, or null when it is not. */
    var note: String? = null
        private set

    /** False once the host has proven it cannot serve an agent transcript at all. */
    var supported: Boolean = true
        private set

    /**
     * Points at a stream.
     *
     * The cursors and the page are dropped TOGETHER and before anything is
     * fetched: a page left behind from the previous agent would be merged into
     * the next one's first read, which is two agents' work in one conversation
     * with no mark to say where one ended.
     *
     * @return true when the selection actually moved.
     */
    fun select(agentId: String?): Boolean {
        val next = agentId?.trim()?.takeIf { it.isNotEmpty() && it != StreamPicker.MAIN_KEY }
        if (next == selected) return false
        selected = next
        offset = null
        historyStart = null
        page = null
        // The note belonged to the stream being left. A failure reading one agent
        // says nothing about the next — unless the whole ROUTE is missing, which
        // is a fact about the host and outlives any selection.
        if (supported) note = null
        return true
    }

    /** One page landed. */
    fun land(fresh: TranscriptPage) {
        if (isTranscriptRestart(page, fresh)) {
            offset = null
            historyStart = null
        } else {
            offset = fresh.nextOffset
            // The first page defines where history begins; later tail reads are
            // BELOW it and must not move the handle.
            if (historyStart == null) historyStart = fresh.windowStart
        }
        page = mergeTranscriptPage(page, fresh)
        note = null
    }

    /**
     * Whether the strip's `…` pill is unfolded.
     *
     * Per open session and NOT remembered: a reader who went digging through
     * yesterday's finished agents does not want the next session they open to
     * greet them with forty settled chips. [reset] is the session boundary.
     */
    var expanded: Boolean = false
        private set

    fun toggleExpanded() {
        expanded = !expanded
    }

    /** Leaving the session: the pick and the fold both go with it. */
    fun reset() {
        select(null)
        expanded = false
    }

    /**
     * The picker strip for this session.
     *
     * Here, on the thing that OWNS the selection and the fold, because the strip
     * keeps the agent being read even once it has finished — a rule that needs
     * the rows, the pick and the fold together, and that is silently wrong if a
     * render site pairs them itself and forgets an argument. The screen would
     * then replace a transcript somebody is scrolling with nothing.
     *
     * @param nowSec the daemon's clock off the transcript, not the phone's.
     */
    fun items(
        agents: List<com.silencelen.huginn.data.AgentRun>,
        nowSec: Long,
    ): List<StreamPicker.Item> = StreamPicker.items(agents, nowSec, selected, expanded)

    /** An older page, read backwards from [historyStart]. */
    fun prepend(older: TranscriptPage) {
        historyStart = older.windowStart
        page = prependTranscriptPage(page, older)
    }

    /**
     * A read failed.
     *
     * A 404 here has exactly one expected cause and it is not a missing agent: a
     * daemon older than 3.0.0 has no such route. Saying so and disabling the
     * strip is the documented compat answer — an empty body under a working
     * picker would read as "this agent did nothing".
     */
    fun fail(code: Int?, message: String?) {
        if (code == 404) {
            supported = false
            note = HuginnViewModel.STREAMS_UNSUPPORTED
        } else if (page == null) {
            note = message ?: "could not read this agent"
        }
    }
}

class HuginnViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    // ⚠ EMPTY UNTIL THE STORE ANSWERS, not the tailnet address. This is what
    // every call built before `init` finishes would dial, and on a fresh install
    // — which now pins nothing — a hardcoded seed would have the app quietly
    // talking to an address nobody had chosen.
    private var baseUrlNow = ""
    private var tokenNow = ""

    /**
     * Settings have been read off disk, so `tokenNow`/`baseUrlNow` mean something.
     *
     * Every public refresh waits on this. `init` loads credentials asynchronously,
     * but the UI starts calling the moment it composes — the sessions screen's
     * lifecycle effect fires immediately — so those calls used to go out with an
     * empty bearer and come back 401. Measured on the live daemon: 63 rejected
     * `GET /v1/sessions` in one day, one per cold start and screen resume, each
     * one flashing an error toast and leaving the list briefly empty.
     *
     * A gate rather than another one-off await (the share path already grew one)
     * so the rule holds for every caller, including ones added later.
     */
    private val ready = MutableStateFlow(false)

    private suspend fun awaitReady() {
        if (!ready.value) ready.first { it }
    }

    private var clientIdNow = ""

    private val client = HuginnClient(
        baseUrlProvider = { baseUrlNow },
        tokenProvider = { tokenNow },
        // ⚠ THE LEASE IS KEYED ON THIS. One pane-size lease per session now, and
        // the daemon decides "is this the holder asking?" by the install id on the
        // request. With none, this phone and every other anonymous client are the
        // same client as far as the lease is concerned, and the flap comes back.
        clientIdProvider = { clientIdNow },
    )

    /**
     * Thumbnails for photo attachments in chat history. Built here (the client is
     * private, so nothing outside can) and provided to the shared transcript
     * renderer; app-scoped so decoded bitmaps survive scrolling and recomposition.
     */
    val attachmentImages: com.silencelen.huginn.ui.AttachmentImageLoader =
        com.silencelen.huginn.ui.AttachmentImageLoader(
            fetch = { client.uploadBytes(it) },
            decoder = com.silencelen.huginn.ui.AndroidImageBytesDecoder(),
            // Image files an answer NAMES, through the daemon's own containment
            // check. Against a daemon with no such route this 404s into the
            // negative cache and the placeholder renders.
            fetchPath = { path, session -> client.imageBytes(path, session) },
        )

    /**
     * Self-update from the public GitHub releases (not the private devstore).
     * Find/download/install are three explicit steps — see [PhoneUpdater].
     */
    private val updater = com.silencelen.huginn.update.PhoneUpdater.forApp(getApplication())
    val updateState: StateFlow<com.silencelen.huginn.update.AppUpdateState> = updater.state
    val installedVersion: String get() = updater.installedVersion
    val updateSourceRepo: String get() = updater.sourceRepo

    fun checkForUpdate() { viewModelScope.launch { updater.check() } }
    fun downloadUpdate() { viewModelScope.launch { updater.download() } }
    fun installUpdate() {
        if (!updater.install()) _toast.value = "Could not start the installer"
    }

    // ---- shared UI state

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }
    fun showToast(text: String) { _toast.value = text }

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _statusError = MutableStateFlow<String?>(null)
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    /** Sessions ended on purpose, kept with the command that brings them back. */
    private val _archives = MutableStateFlow<List<ArchivedSession>>(emptyList())
    val archives: StateFlow<List<ArchivedSession>> = _archives.asStateFlow()

    /**
     * Whether this daemon HAS archive. Null until the first probe answers.
     *
     * FEATURE DETECTION, not version parsing — the scratchpads precedent. False
     * hides the Archived section AND the row action, because a control whose only
     * outcome is a 404 is worse than no control.
     */
    private val _archiveAvailable = MutableStateFlow<Boolean?>(null)
    val archiveAvailable: StateFlow<Boolean?> = _archiveAvailable.asStateFlow()

    /**
     * The host's scheduled work. Refreshed alongside chats rather than on its own
     * timer: a Round changes at most every few minutes, and a second poll would
     * buy nothing but battery.
     */
    private val _rounds = MutableStateFlow<List<Round>>(emptyList())
    val rounds: StateFlow<List<Round>> = _rounds.asStateFlow()

    /**
     * Machines that have offered themselves to huginn.
     *
     * Refreshed with the lists rather than on its own timer: a device changes
     * state at human speed, and a second poll would buy nothing but battery.
     */
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected.asStateFlow()

    private val _fontScale = MutableStateFlow(SettingsStore.DEFAULT_FONT_SCALE)
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    private val _notifyEnabled = MutableStateFlow(true)
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    private val _watchEnabled = MutableStateFlow(false)
    val watchEnabled: StateFlow<Boolean> = _watchEnabled.asStateFlow()

    /**
     * Alerts sent by the HOST, which is the only kind that arrives when the app is
     * not running. Its state lives on the server, not here, because the server is
     * what does the sending.
     */
    private val _alerts = MutableStateFlow<Alerts?>(null)
    val alerts: StateFlow<Alerts?> = _alerts.asStateFlow()

    /**
     * What the host has seen of this phone — the evidence that background delivery
     * is working, gathered by a machine that was awake while the phone was not.
     */
    private val _clients = MutableStateFlow<ClientsInfo?>(null)
    val clients: StateFlow<ClientsInfo?> = _clients.asStateFlow()

    /**
     * The app-lock toggle. The cached copy in [AppLock] is what the activity's
     * ON_START decision reads, because that decision cannot wait on DataStore.
     */
    private val _appLock = MutableStateFlow(false)
    val appLock: StateFlow<Boolean> = _appLock.asStateFlow()

    fun setAppLock(on: Boolean) {
        _appLock.value = on
        AppLock.enabledCache = on
        viewModelScope.launch { settings.setAppLock(on) }
    }

    /** Whether the host can push, and whether THIS phone has registered to receive it. */
    private val _push = MutableStateFlow<PushStatus?>(null)
    val push: StateFlow<PushStatus?> = _push.asStateFlow()

    fun refreshDelivery() {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.alerts() }.onSuccess { _alerts.value = it }
            runCatching { client.clients() }.onSuccess { _clients.value = it }
            runCatching { client.push() }.onSuccess { _push.value = it }
            _health.value = readHealth()
        }
    }

    /** The app's own side of the story: when it last reached huginn, and how. */
    data class DeliveryHealth(
        val lastContactAt: Long = 0,
        val lastAlarmAt: Long = 0,
        val lastError: String = "",
        val lastErrorAt: Long = 0,
        val dozeExempt: Boolean = false,
        /**
         * The two counts the wake-up cadence is decided from. Surfaced because the
         * decision was otherwise invisible: the alarm quietly chose hourly or
         * ten-minutely and nothing said which, or why — so the one bug it has
         * already had could only be found by reading a night of server logs.
         */
        val pushesSent: Long = 0,
        val pushesReceived: Long = 0,
        /** The tally has been re-based at least once against a host restart. */
        val pushRebaselined: Boolean = false,
    ) {
        /**
         * What actually arrived, as the page is allowed to print it.
         *
         * ⚠ NEVER MORE THAN WERE SENT. Reconciliation happens when a watch
         * response lands ([com.silencelen.huginn.notify.PushTally]); this screen
         * can be opened before the first one does, and "1274 of 916 pushes
         * arrived" must not be reachable by being quick.
         */
        val pushesArrived: Long get() = PushTally.arrived(pushesReceived, pushesSent)

        /** What the alarm will do next, in the same terms the rule is written in. */
        val relaxed: Boolean get() = Heartbeat.intervalFor(pushesSent, pushesArrived) ==
            Heartbeat.RELAXED_INTERVAL_MS

        /** The interval the alarm is actually armed at, for the cadence line. */
        val heartbeatIntervalMs: Long get() = Heartbeat.intervalFor(pushesSent, pushesArrived)

        val pushesMissing: Long get() = PushTally.missing(pushesReceived, pushesSent)
    }

    private val _health = MutableStateFlow(DeliveryHealth())
    val health: StateFlow<DeliveryHealth> = _health.asStateFlow()

    private suspend fun readHealth() = DeliveryHealth(
        lastContactAt = settings.lastContactAt.first(),
        lastAlarmAt = settings.lastAlarmAt.first(),
        lastError = settings.lastWatchError.first(),
        lastErrorAt = settings.lastWatchErrorAt.first(),
        dozeExempt = Heartbeat.isExemptFromDoze(getApplication()),
        pushesSent = settings.pushesSent.first(),
        pushesReceived = settings.pushesReceived.first(),
        pushRebaselined = settings.pushRebaselined.first(),
    )

    /**
     * Opens the system dialogue for the Doze allowlist. Nothing to persist: the
     * answer lives with the system, and is re-read every time the screen is shown so
     * a revoked exemption cannot keep reading as granted.
     */
    fun requestDozeExemption() {
        Heartbeat.requestDozeExemption(getApplication())
    }

    /** `fallback` (only when the phone is out of contact) or `always`. */
    fun setAlertsMode(mode: String) {
        viewModelScope.launch {
            runCatching { client.setAlerts(mode = mode) }
                .onSuccess {
                    _alerts.value = _alerts.value?.copy(mode = it.mode) ?: it
                    _toast.value = if (it.mode == "always")
                        "Telegram will carry every alert"
                    else "Telegram only when the app is out of contact"
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun setAlertsEnabled(on: Boolean) {
        viewModelScope.launch {
            runCatching { client.setAlerts(enabled = on) }
                .onSuccess {
                    _alerts.value = _alerts.value?.copy(enabled = it.enabled) ?: it
                    _toast.value = if (it.enabled)
                        "huginn will message you when a session needs you"
                    else "huginn will stop messaging you"
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * The instant path, on top of the background ones.
     *
     * These used to be mutually exclusive — the service cancelled the worker —
     * because two watchers racing on the same transition would notify twice. They
     * can now coexist, and should: the comparison baseline moved into storage, so
     * whichever mechanism sees a transition first consumes it and the others find
     * nothing to announce. That matters because they fail in different conditions,
     * and the one that survives a sleeping phone is not the fast one.
     */
    fun setWatchEnabled(on: Boolean) {
        _watchEnabled.value = on
        val app = getApplication<Application>()
        viewModelScope.launch {
            settings.setWatchEnabled(on)
            if (on) WatchService.start(app) else WatchService.stop(app)
        }
    }

    /** Discovered on the host, so a `claude update` changes the menu, not the app. */
    private val _models = MutableStateFlow<List<ModelChoice>>(emptyList())
    val models: StateFlow<List<ModelChoice>> = _models.asStateFlow()

    private var modelsAt = 0L

    fun refreshModels() {
        // Local rows carry a time-dependent `available` the daemon computes per
        // request and deliberately never caches; one fetch per PROCESS froze
        // every machine in whatever state the app launched into — the audit's
        // highest phone finding. Refetched when stale instead; called on every
        // chat open, so the menu tracks reality within a minute.
        if (_models.value.isNotEmpty() && System.currentTimeMillis() - modelsAt < 60_000) return
        viewModelScope.launch {
            awaitReady()
            runCatching { client.models() }.onSuccess {
                _models.value = it
                modelsAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * Unsent composer text per target. Held here rather than in the composable so
     * it survives navigating away, and written through to storage (debounced) so
     * it survives the process being killed.
     */
    /**
     * What is staged for the open composer's next message.
     *
     * A LIST, not a slot. It was a slot, and the comment here defended that:
     * "the message marker carries one path, the composer shows one chip, and
     * 'which of my three photos did it answer about' is not a question this UI
     * should ever pose." The first half was simply wrong — `humanizeUserText`
     * has replaced markers globally since the daemon was written — and the
     * second is answered by order: markers ride in attach order, which is chip
     * order, which is the order they were picked in.
     *
     * The rules live in [AttachmentSlots], outside the view model, because this
     * is an `AndroidViewModel` and nothing that needs an `Application` can be
     * tested on this host.
     */
    private val slots = AttachmentSlots()

    /** Everything staged, for every composer; each item names its own owner. */
    val attachments: StateFlow<List<PendingAttachment>> = slots.items

    /** Clears a composer's staged attachments — everyone's when [owner] is null. */
    fun clearAttachment(owner: String? = null) = slots.clear(owner)

    /** Drops one chip, by the id the chip row hands back. */
    fun removeAttachment(id: String) = slots.remove(id)

    /**
     * A non-image document from the file picker. Images that arrive this way are
     * routed through the photo pipeline (transcode, EXIF); everything else is
     * uploaded as-is and stands or falls on the server's type allowlist — a
     * refused docx fails HERE with the server's own words, not later as a chat
     * shrugging at unreadable bytes.
     */
    fun attachFile(uri: android.net.Uri, owner: String) {
        // Staged on the CALLING thread so a batch keeps its order and the chip
        // appears at once — with a provisional label, because the real display
        // name is a content-provider query and every one of those (getType,
        // query, openAssetFileDescriptor) is a binder call that can block on a
        // cloud-backed DocumentsProvider. They belong on IO, which is where the
        // single-slot version ran them and where they stay.
        val provisional = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        val id = slots.stage(owner, provisional, image = false)
            ?: run { _toast.value = AttachBatch.refusedNote(slots.countFor(owner), 1); return }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // The WHOLE body is caught: an uncaught throw in viewModelScope kills
            // the process, and a picker that sometimes crashes the app is worse
            // than one that says why it failed. Anything thrown becomes a chip.
            runCatching {
                val cr = getApplication<Application>().contentResolver
                val mime = runCatching { cr.getType(uri) }.getOrNull() ?: "application/octet-stream"
                if (mime.startsWith("image/")) {
                    // Images go through the photo pipeline (transcode, EXIF)
                    // wherever they came from; this chip hands over to that one.
                    slots.remove(id)
                    attachImage(uri, owner)
                    return@launch
                }
                val name = runCatching {
                    cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()
                // Size read from the provider rather than by loading the file: a
                // backup is tens of megabytes and reading it into a ByteArray to
                // hand to the uploader would hold it twice on a phone. It is also
                // what the chip shows.
                val size = runCatching {
                    cr.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: -1L
                slots.describe(id, name ?: provisional, size.takeIf { it >= 0 })
                // The HOST owns the size limit now, and says so in its own words —
                // one place to change it, and no stale number here quietly
                // refusing what the daemon would have accepted.
                val out = client.uploadStream(mime, name, UriByteStream(cr, uri, size))
                slots.ready(id, out.path, name = name, image = false, readable = out.readable, bytes = out.bytes)
            }.onFailure { slots.fail(id, errText(it)) }
        }
    }

    /** Several documents from one pick, in the order the picker handed them over. */
    fun attachFiles(uris: List<android.net.Uri>, owner: String) =
        acceptBatch(uris, owner) { attachFile(it, owner) }

    /** Transcodes to JPEG off the main thread, uploads, and stages the path. */
    fun attachImage(uri: android.net.Uri, owner: String) {
        val id = slots.stage(owner, "Photo", image = true)
            ?: run { _toast.value = AttachBatch.refusedNote(slots.countFor(owner), 1); return }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = Attachments.toJpeg(getApplication(), uri)
            if (bytes == null) { slots.fail(id, "Could not read that image"); return@launch }
            runCatching { client.upload(bytes, Attachments.MIME) }
                .onSuccess { slots.ready(id, it.path, name = null, image = true, readable = true, bytes = it.bytes) }
                .onFailure { slots.fail(id, errText(it)) }
        }
    }

    /** Several photos from one pick, in pick order. */
    fun attachImages(uris: List<android.net.Uri>, owner: String) =
        acceptBatch(uris, owner) { attachImage(it, owner) }

    /**
     * An image off the system clipboard — the "paste a screenshot" path.
     *
     * Behind [ImageClipboard] so the rule (nothing there / no room / go) is a
     * pure function this project can assert, and so the Android half is one
     * class that can be swapped in a test. It lands in the SAME
     * [Attachments.toJpeg] pipeline as the picker and the camera, which is what
     * makes HEIC safe: this phone shoots HEIC by default, Read cannot open it,
     * and the transcode is the only reason an attached photo works at all.
     */
    fun pasteImage(owner: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val clip = runCatching { clipboardImages.takeImage() }.getOrNull()
            when (val plan = pastePlan(clip, slots.countFor(owner))) {
                is PasteOutcome.Refused -> _toast.value = plan.why
                is PasteOutcome.Attach -> {
                    val id = slots.stage(owner, plan.name, image = true, bytes = plan.jpeg.size.toLong())
                        ?: return@launch
                    runCatching { client.upload(plan.jpeg, Attachments.MIME, plan.name) }
                        .onSuccess { slots.ready(id, it.path, name = null, image = true, readable = true, bytes = it.bytes) }
                        .onFailure { slots.fail(id, errText(it)) }
                }
            }
        }
    }

    /** The system clipboard, read lazily — constructing it needs a Context. */
    private val clipboardImages: ImageClipboard by lazy { AndroidImageClipboard(getApplication()) }

    /** Takes what fits and says so when it had to leave some behind. */
    private fun acceptBatch(uris: List<android.net.Uri>, owner: String, one: (android.net.Uri) -> Unit) {
        val pending = slots.countFor(owner)
        AttachBatch.refusedNote(pending, uris.size)?.let { _toast.value = it }
        AttachBatch.accept(pending, uris).forEach(one)
    }

    /**
     * A share handed in from another app: a new chat, pre-staged. Text becomes
     * the draft (editable before sending, like every share target worth using);
     * an image starts uploading immediately so the chip is usually Ready by the
     * time a first word is typed.
     */
    /** Stages shared content into an EXISTING chat: draft appended, photo owned. */
    fun stageShareInChat(id: String, text: String?, image: android.net.Uri?) {
        val key = chatDraftKey(id)
        // Appended, never clobbered: a half-typed draft outranks a share.
        if (!text.isNullOrBlank()) appendToDraft(key, text)
        if (image != null) attachImage(image, key)
    }

    /** The same, into a session's conversation composer. */
    fun stageShareInSession(name: String, text: String?, image: android.net.Uri?) {
        val key = sessionDraftKey(name)
        if (!text.isNullOrBlank()) appendToDraft(key, text)
        if (image != null) attachImage(image, key)
    }

    fun newChatForShare(text: String?, image: android.net.Uri?, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            // A share often arrives in a BRAND NEW activity with a brand new view
            // model, and this used to fire on first composition — racing the
            // settings load, so createChat left with a blank bearer and 401'd
            // (measured: the share reached the daemon as POST /v1/chats 401 and
            // died silently on the Sessions screen). Wait for the token first;
            // the timeout means a genuinely unconfigured app still fails visibly
            // in newChat rather than hanging the share forever.
            kotlinx.coroutines.withTimeoutOrNull(5_000) { token.first { it.isNotBlank() } }
            newChat(_chatMode.value) { id ->
                openChat(id)
                if (!text.isNullOrBlank()) setDraft(chatDraftKey(id), text)
                if (image != null) attachImage(image, chatDraftKey(id))
                onOpened(id)
            }
        }
    }

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()
    private var draftSaveJob: Job? = null

    fun setDraft(key: String, text: String) {
        _drafts.value = _drafts.value.toMutableMap().apply {
            if (text.isEmpty()) remove(key) else put(key, text)
        }
        // Debounced: a write per keystroke would be a lot of disk for nothing.
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(400)
            settings.setDrafts(_drafts.value)
        }
    }

    private fun clearDraft(key: String) {
        if (_drafts.value.containsKey(key)) setDraft(key, "")
    }

    /**
     * The long-press verbs, bound to a target's composer.
     *
     * A PLAIN CLASS holds the rules ([SelectionStaging]) because this is an
     * `AndroidViewModel` and nothing inside one can be asserted on a host with no
     * device and no `/dev/kvm`. Everything below is delegation.
     */
    val staging: SelectionStaging = SelectionStaging(
        draftOf = { key -> _drafts.value[key].orEmpty() },
        setDraft = { key, text -> setDraft(key, text) },
        chatKeyOf = { id -> chatDraftKey(id) },
    )

    /**
     * Stages text in a composer: APPENDED, never clobbered, never sent.
     *
     * ONE METHOD WITH ONE RULE ([QuickActionRules.appendToDraft], shared with the
     * desktop) rather than the four hand-written copies this file used to hold —
     * share-into-chat, share-into-session, page-into-chat, page-into-session, each
     * with its own `if (cur.isBlank())`. The separator widens from one newline to
     * a blank line with it: what lands here is a BLOCK — a page, a quote, a
     * template — and a blank line is how a person would have typed it.
     */
    fun appendToDraft(key: String, text: String) = staging.append(key, text)

    /**
     * Runs one selection verb. Explain / Execute / Quote stage into [draftKey];
     * "Ask in new chat" makes a chat, stages into ITS draft and reports the new id
     * for the shell to navigate to.
     *
     * ⚠ VIEWMODEL SCOPE, not a composition's. "Ask in new chat" navigates, which
     * tears down the composition that launched it — the same lesson the desktop's
     * `rememberSelectionVerbs` carries in its header. A `rememberCoroutineScope`
     * there is cancelled at its first suspension point, and the chat gets created,
     * or not, depending on timing, with the staging never running.
     */
    fun runSelectionAction(
        action: SelectionAction,
        selection: String,
        draftKey: String,
        actions: com.silencelen.huginn.data.QuickActions?,
        mode: String? = null,
        onOpened: (String) -> Unit = {},
    ) {
        if (action != SelectionAction.ASK_IN_NEW_CHAT) {
            staging.stage(action, selection, actions, draftKey)
            return
        }
        viewModelScope.launch {
            awaitReady()
            staging.askInNewChat(
                selection = selection,
                actions = actions,
                fallbackKey = draftKey,
                create = { client.createChat(mode ?: _chatMode.value) },
                onOpened = { id -> refreshChats(); onOpened(id) },
                onFailure = { _toast.value = errText(it) },
            )
        }
    }

    init {
        viewModelScope.launch {
            baseUrlNow = settings.baseUrl.first()
            tokenNow = settings.token.first()
            clientIdNow = settings.clientId()
            _baseUrl.value = baseUrlNow
            _token.value = tokenNow
            _fontScale.value = settings.fontScale.first()
            _notifyEnabled.value = settings.notifyEnabled.first()
            _watchEnabled.value = settings.watchEnabled.first()
            _drafts.value = settings.drafts.first()
            _health.value = readHealth()
            _appLock.value = settings.appLock.first()
            _routeBook.value = settings.routeBook.first()
            // ⚠ BEFORE THE FIRST resolveRoute BELOW. With an empty map every cold
            // start skips the hysteresis and takes the first address that answers
            // in the owner's order — which is how a stranger on a route pinned
            // above the real daemon wins on every launch. See HuginnSettings.routeHealth.
            _routeHealth.value = settings.routeHealth.first()
            AppLock.enabledCache = _appLock.value
            // Opened even when no token is configured: a caller must unblock and
            // get a real "not configured" failure rather than hang forever.
            ready.value = true
            if (tokenNow.isNotBlank()) {
                // Only one VPN can hold the tunnel slot, so which pinned route is
                // reachable changes when the owner switches tunnels. Re-pick
                // before the first fan-out of calls.
                resolveRoute(silent = true)
                refreshAll()
                refreshModels()
                if (_notifyEnabled.value) ensureBackgroundDelivery()
                // Handed over on every start, not just when Firebase issues a new one:
                // a token minted before the server URL was configured, or while huginn
                // was unreachable, would otherwise never arrive — and push would look
                // set up while nothing could actually be delivered.
                HuginnMessagingService.syncToken(getApplication())
                if (_watchEnabled.value) WatchService.start(getApplication())
            }
        }
    }

    /** See [errorTextFor] — the rule lives out there so it can be tested. */
    private fun errText(e: Throwable): String = errorTextFor(e)

    fun copy(text: String, label: String = "huginn") {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        _toast.value = "Copied"
    }

    // ------------------------------------------------------ pinned routes

    private val _routeBook = MutableStateFlow(RouteBook())
    val routeBook: StateFlow<RouteBook> = _routeBook.asStateFlow()

    /** The state dots and "last reached" words. Never an input to selection. */
    private val _routeHealth = MutableStateFlow<Map<String, RouteHealth>>(emptyMap())
    val routeHealth: StateFlow<Map<String, RouteHealth>> = _routeHealth.asStateFlow()

    private val _resolvingRoute = MutableStateFlow(false)
    val resolvingRoute: StateFlow<Boolean> = _resolvingRoute.asStateFlow()

    /** A refusal or an outcome, shown under the list rather than as a toast. */
    private val _routeNote = MutableStateFlow<String?>(null)
    val routeNote: StateFlow<String?> = _routeNote.asStateFlow()

    /**
     * A route that answered and that this client WILL NOT ADOPT BY ITSELF — see
     * [RouteResolver.Choice.Stay.Candidate]. Offered under the list; null the
     * rest of the time, which is almost always.
     */
    private val _routeCandidate = MutableStateFlow<PinnedRoute?>(null)
    val routeCandidate: StateFlow<PinnedRoute?> = _routeCandidate.asStateFlow()

    /**
     * Consecutive network failures on the active route. ⚠ A 401 does not count:
     * an answering daemon that rejects the token proves the ROUTE is fine, and
     * re-resolving on it would go looking for a network problem that is not
     * there.
     */
    private val routeFailures = RouteFailures()

    /**
     * Moves to the first healthy route IN THE OWNER'S ORDER. Leaves the current
     * setting alone when nothing answers — blanking it would turn "the network
     * is down" into "the app is misconfigured".
     */
    fun resolveRoute(silent: Boolean = false, force: Boolean = false) {
        viewModelScope.launch {
            _resolvingRoute.value = true
            val outcome = RouteResolver.resolveProving(
                book = _routeBook.value,
                health = _routeHealth.value,
                now = System.currentTimeMillis(),
                force = force,
            ) { client.probeProof(it.url) }
            _resolvingRoute.value = false
            saveHealth(outcome.health)
            // Cleared on EVERY resolution before it is set again: an offer is a
            // fact about the sweep that just ran, and one left standing from five
            // minutes ago invites a person to hand the bearer to a host that has
            // since gone quiet.
            _routeCandidate.value = (outcome.choice as? RouteResolver.Choice.Stay.Candidate)?.candidate
            when (val choice = outcome.choice) {
                is RouteResolver.Choice.Empty ->
                    if (!silent) _toast.value = "No routes yet — add the address huginn answers on"
                is RouteResolver.Choice.Pinned ->
                    if (!silent) _toast.value = "Route is pinned — unpin to switch automatically"
                is RouteResolver.Choice.NoRoute ->
                    if (!silent) _toast.value = "No route to huginn — is a VPN connected?"
                is RouteResolver.Choice.Stay ->
                    if (!silent) _toast.value = "Still on ${choice.route.name}"
                is RouteResolver.Choice.Switched -> {
                    editRoutesNow { it.activate(choice.route.id) }
                    _toast.value = "Switched to ${choice.route.name}"
                }
            }
        }
    }

    /**
     * Adopt the offered route: the person saying so that [RouteResolver] waits
     * for. Pinned, exactly as a Use on any other row is — the reader has just
     * chosen an address, and auto-switching away from it on the next sweep would
     * make the choice look like it did not take.
     */
    fun useRouteCandidate(id: String) {
        _routeCandidate.value = null
        activateRoute(id)
    }

    /** The health cache and its persisted copy, together. */
    private suspend fun saveHealth(health: Map<String, RouteHealth>) {
        _routeHealth.value = health
        runCatching { settings.setRouteHealth(health, System.currentTimeMillis()) }
    }

    /**
     * Marks the active route as having just worked, from REAL traffic.
     *
     * See [RouteResolver.touch]: it writes `lastSeenAt`, never `lastOkAt`, so the
     * three-failures re-probe still sweeps rather than finding the dead route
     * "fresh" seconds after its last success.
     */
    private fun noteRouteReached() {
        val next = RouteResolver.touch(
            _routeHealth.value,
            _routeBook.value.active?.id,
            System.currentTimeMillis(),
        )
        if (next == _routeHealth.value) return
        _routeHealth.value = next
        // Persisted too, and this is the witness that matters most on a phone:
        // ordinary traffic proves the route far more often than a probe does, and
        // a restart that forgot it would sweep the whole book on next launch.
        viewModelScope.launch { runCatching { settings.setRouteHealth(next, System.currentTimeMillis()) } }
    }

    /**
     * Forgets the note under the route list. Opening or cancelling a route form
     * is the moment it stops being true, and on this client nothing else ever
     * cleared it — a refusal from ten minutes ago sat under the list for the
     * life of the screen.
     */
    fun clearRouteNote() { _routeNote.value = null }

    /** Manual pin from the list: this route, and stay on it until unpinned. */
    fun activateRoute(id: String) = editRoutes { it.activate(id).withAutoSwitch(false) }

    fun addRoute(name: String, url: String) = editRoutes { it.add(name, url, System.currentTimeMillis()) }

    fun renameRoute(id: String, name: String) = editRoutes { it.rename(id, name) }

    fun setRouteUrl(id: String, url: String) = editRoutes { it.setUrl(id, url) }

    /**
     * A name and an address saved together, as ONE book operation.
     *
     * The route form's Save changes both fields at once whenever a pin is
     * re-pointed, and two separate mutations for one gesture is a race whichever
     * way it is dispatched — see [routeEdits].
     */
    fun editRoute(id: String, name: String, url: String) =
        editRoutes { it.rename(id, name).setUrl(id, url) }

    fun moveRoute(id: String, delta: Int) = editRoutes { it.move(id, delta) }

    fun removeRoute(id: String) = editRoutes { it.remove(id) }

    fun setAutoSwitch(on: Boolean) {
        viewModelScope.launch {
            applyAutoSwitch(
                on = on,
                persist = { editRoutesNow { b -> b.withAutoSwitch(it) } },
                resolve = { force -> resolveRoute(force = force) },
            )
        }
    }

    /**
     * ⚠ ONE EDIT AT A TIME. Every mutation here is read-modify-write across a
     * SUSPENSION — `settings.setRouteBook` is a DataStore write — and `_routeBook`
     * is only republished after it. Two edits launched from one gesture (the
     * route form's Save used to send a rename and an address change as two) both
     * read the pre-edit book, and whichever wrote last silently discarded the
     * other: the address landed, the new name did not, in memory and on disk.
     */
    private val routeEdits = Mutex()

    /**
     * Every list edit runs through here, so a refusal from [RouteGuard] or the
     * eight-pin cap is REPORTED rather than swallowed — a setting that silently
     * does not take is worse than one that says no.
     */
    private fun editRoutes(edit: (RouteBook) -> RouteBook) {
        viewModelScope.launch { editRoutesNow(edit) }
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

    /**
     * Persists the book and reconnects on the address it derives. ⚠ The store
     * writes `base_url` through in the same edit, so the watch service and the
     * notification receivers move with it.
     */
    private suspend fun applyBook(book: RouteBook) {
        val settled = book.normalized()
        settings.setRouteBook(settled)
        _routeBook.value = settled
        val moved = settled.activeUrl != baseUrlNow
        baseUrlNow = settled.activeUrl
        _baseUrl.value = baseUrlNow
        if (moved) {
            routeFailures.ok()
            _connected.value = null
            refreshAll()
        }
    }

    // ---------------------------------------------------------- settings

    /**
     * The bearer, and a reconnect with it. The ADDRESS is not here any more: it
     * belongs to whichever route is active, which is the whole point of pinning
     * them.
     */
    fun saveSettings(tok: String) {
        viewModelScope.launch {
            settings.setToken(tok)
            // A token registered against the previous host means nothing to the new one.
            HuginnMessagingService.syncToken(getApplication())
            tokenNow = tok.trim()
            _token.value = tokenNow
            _connected.value = null
            testConnection()
        }
    }

    fun setFontScale(v: Float) {
        _fontScale.value = v.coerceIn(5.5f, 22f)
        viewModelScope.launch { settings.setFontScale(v) }
    }

    fun setNotifyEnabled(on: Boolean) {
        _notifyEnabled.value = on
        val app = getApplication<Application>()
        viewModelScope.launch {
            settings.setNotifyEnabled(on)
            if (on) {
                ensureBackgroundDelivery()
            } else {
                Heartbeat.cancel(app)
                SessionWatchWorker.cancel(app)
                WatchService.stop(app)
            }
        }
    }

    /**
     * Arms both background paths. Idempotent, and called on every app start rather
     * than only when the switch is flipped: an app update cancels pending alarms, so
     * a heartbeat armed once at install time would not survive the next release.
     */
    private fun ensureBackgroundDelivery() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            // At the cadence push health has earned, so a restart does not reset a
            // relaxed alarm back to waking the device every ten minutes.
            Heartbeat.arm(app, Heartbeat.intervalFor(
                runCatching { settings.pushesSent.first() }.getOrDefault(0L),
                runCatching { settings.pushesReceived.first() }.getOrDefault(0L),
            ))
        }
        // Created up front, not on first use: a channel Android has never seen does
        // not appear in the app's notification settings, so the two kinds could only
        // be tuned separately AFTER each had already interrupted you once.
        SessionWatchWorker.ensureChannels(app)
        SessionWatchWorker.schedule(app)
    }

    private fun testConnection() {
        viewModelScope.launch {
            runCatching { client.ping() }
                .onSuccess {
                    _connected.value = it.ok
                    // Both numbers, labelled: they version independently, and a bare
                    // "appd 2.33.0" reads as this app's version to anyone who has
                    // not internalised that phone and host are separate lines.
                    _toast.value = "Connected to ${it.host ?: "huginn"} — " +
                        "app ${appVersion(getApplication())}, appd ${it.version ?: "?"}"
                    refreshAll()
                }
                .onFailure {
                    _connected.value = false
                    _toast.value = errText(it)
                }
        }
    }

    // -------------------------------------------------- account + usage

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _usage = MutableStateFlow<Usage?>(null)
    val usage: StateFlow<Usage?> = _usage.asStateFlow()

    private val _plan = MutableStateFlow<Plan?>(null)
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    private var usagePollJob: Job? = null

    fun refreshPlan() {
        viewModelScope.launch {
            runCatching { client.plan() }
                .onSuccess { _plan.value = it }
                .onFailure { /* the settings screen shows its own empty state */ }
        }
    }

    // --------------------------------------------------------------- headroom

    /**
     * The whole headroom picture, polled WHEREVER THE READER IS.
     *
     * Everything else about usage on this client is a destination: the plan and
     * the token tally are fetched only from the Status screen, and `/v1/usage`
     * behind the second one walks every transcript on the host. That gate is
     * right for those and wrong for this one — headroom is the number that
     * decides whether tonight's run finishes, the daemon serves it from a file it
     * already keeps, and a number nobody sees until they go looking for it is the
     * exact failure this wave exists to fix.
     *
     * Null until the first answer, and null forever against a daemon older than
     * 3.0.0, which has no such route. Null draws no pill: a pill at 0 % would
     * claim somebody looked and found plenty.
     */
    private val _headroom = MutableStateFlow<com.silencelen.huginn.data.Headroom?>(null)
    val headroom: StateFlow<com.silencelen.huginn.data.Headroom?> = _headroom.asStateFlow()

    private var headroomJob: Job? = null

    /**
     * The pill's input, preferring the FULL answer over the summary.
     *
     * Both are on the wire and they age differently: `status.headroom` rides a
     * poll that only runs on demand, while `/v1/headroom` is the one this screen
     * keeps current. Folded into one shape by `:ui` so neither client builds a
     * second opinion about the same reading.
     */
    val headroomPill: StateFlow<com.silencelen.huginn.data.StatusHeadroom?> =
        kotlinx.coroutines.flow.combine(_headroom, _status) { h, s ->
            statusHeadroomOf(h) ?: s?.headroom
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The fill line under the Status destination's icon.
     *
     * A DIFFERENT NUMBER FROM THE PILL and computed from the same two flows: the
     * pill reports the worst window anywhere (usually the Fable week), this
     * reports the live account's 5-hour session window, which is the one that
     * decides whether the next hour of work finishes. `:core` decides which is
     * which; this only hands it both halves.
     */
    val sessionUsage: StateFlow<UsageFill?> =
        kotlinx.coroutines.flow.combine(_headroom, _status) { h, s ->
            SessionUsageFill.of(h, statusHeadroomOf(h) ?: s?.headroom)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Starts the headroom poll. LIFECYCLE-SCOPED by its caller, never a
     * background poll: a backgrounded app that keeps asking is a battery cost for
     * a chip nobody can see, and the notifications — which are what matters while
     * the phone is in a pocket — come from the watch cycle and FCM instead.
     */
    fun startHeadroomPolling() {
        headroomJob?.cancel()
        headroomJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                refreshHeadroomOnce()
                delay(HEADROOM_POLL_MS)
            }
        }
    }

    fun stopHeadroomPolling() {
        headroomJob?.cancel()
        headroomJob = null
    }

    /**
     * One read.
     *
     * A failure is swallowed rather than toasted: the only expected failure is a
     * 404 from a daemon with no headroom subsystem, and an error message on every
     * screen about a feature that host never had would be permanent noise.
     */
    private suspend fun refreshHeadroomOnce() {
        runCatching { client.headroom() }.onSuccess { _headroom.value = it }
    }

    /** For a screen that has just opened and cannot wait 30 s for the poll. */
    fun refreshHeadroom() {
        viewModelScope.launch { awaitReady(); refreshHeadroomOnce() }
    }

    private val _headroomSaving = MutableStateFlow(false)
    val headroomSaving: StateFlow<Boolean> = _headroomSaving.asStateFlow()

    private val _headroomNote = MutableStateFlow<String?>(null)
    val headroomNote: StateFlow<String?> = _headroomNote.asStateFlow()

    /**
     * Saves the whole settings object as a PATCH body.
     *
     * Whole rather than a diff, deliberately: the route is a PATCH so two open
     * forms cannot clobber each other's UNTOUCHED fields, and this form edits
     * every field it can see. Computing a diff would only add a second place for
     * the two to disagree about what changed.
     */
    fun saveHeadroomSettings(edited: com.silencelen.huginn.data.HeadroomSettings) {
        if (_headroomSaving.value) return
        _headroomSaving.value = true
        _headroomNote.value = null
        viewModelScope.launch {
            awaitReady()
            runCatching { client.setHeadroomSettings(headroomPatch(edited)) }
                .fold(
                    // The daemon's 400 NAMES the rule it refused. Shown as it
                    // came: a form that says "invalid" about a sentence the host
                    // already explained is throwing the answer away.
                    onSuccess = { _headroomNote.value = "saved" },
                    onFailure = { _headroomNote.value = errText(it) },
                )
            refreshHeadroomOnce()
            _headroomSaving.value = false
        }
    }

    /** Per-session auto-resume, straight onto the session's meta record. */
    fun setSessionAutoResume(name: String, value: Boolean?) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.setSessionAutoResume(name, value) }
                .onSuccess { refreshSessions(); refreshHeadroomOnce() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    private val _savedAccounts = MutableStateFlow<List<SavedAccount>>(emptyList())
    val savedAccounts: StateFlow<List<SavedAccount>> = _savedAccounts.asStateFlow()

    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /** @param withPlan also read each saved account's headroom (a call per account). */
    fun refreshSavedAccounts(withPlan: Boolean = true) {
        viewModelScope.launch {
            runCatching { client.savedAccounts(withPlan) }
                .onSuccess { _savedAccounts.value = it }
                .onFailure { /* settings shows its own empty state */ }
        }
    }

    fun activateAccount(slug: String) {
        if (_switching.value) return
        _switching.value = true
        viewModelScope.launch {
            runCatching { client.activateAccount(slug) }
                .onSuccess {
                    _account.value = it
                    _plan.value = null
                    refreshPlan()
                    refreshSavedAccounts()
                    _toast.value = "Now signed in as ${it.email ?: "another account"}. " +
                        "Running sessions keep the old one until they restart."
                }
                .onFailure { _toast.value = errText(it) }
            _switching.value = false
        }
    }

    /**
     * Refreshes ONE saved profile's token in the background, now.
     *
     * A token that has expired but whose refresh token has not is one request
     * away from working, and until now the only way to find that out was to press
     * Use and read the failure. The daemon answers with a STATUS WORD rather than
     * a boolean — `invalid_grant` means only a re-login helps, `lock_busy` means
     * try again in a moment, a transport word means the network — so it is shown
     * verbatim, because a bare "failed" said the same thing to all three.
     */
    fun refreshSavedAccount(slug: String) {
        if (_switching.value) return
        _switching.value = true
        viewModelScope.launch {
            awaitReady()
            runCatching { client.refreshAccount(slug) }
                .fold(
                    onSuccess = { word -> _toast.value = "$slug: $word" },
                    onFailure = { _toast.value = errText(it) },
                )
            refreshSavedAccounts()
            _switching.value = false
        }
    }

    fun forgetAccount(slug: String) {
        viewModelScope.launch {
            runCatching { client.forgetAccount(slug) }
                .onSuccess { refreshSavedAccounts() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun refreshAccount() {
        viewModelScope.launch {
            runCatching { client.account() }
                .onSuccess { _account.value = it; refreshSavedAccounts() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Usage is computed by walking every transcript on the host and takes ~30 s,
     * so the server serves a cache and recomputes in the background. Poll while
     * it is refreshing so the number appears when it is ready.
     */
    fun refreshUsage() {
        usagePollJob?.cancel()
        usagePollJob = viewModelScope.launch {
            repeat(30) {
                val r = runCatching { client.usage() }.getOrNull()
                if (r != null) _usage.value = r
                if (r != null && !r.refreshing) return@launch
                delay(4000)
            }
        }
    }

    fun stopUsagePolling() {
        usagePollJob?.cancel()
        usagePollJob = null
    }

    private val _loginUrl = MutableStateFlow<String?>(null)
    val loginUrl: StateFlow<String?> = _loginUrl.asStateFlow()
    fun loginUrlHandled() { _loginUrl.value = null }

    /** Non-null while a sign-in is being run from inside the app. */
    private val _login = MutableStateFlow<LoginState?>(null)
    val login: StateFlow<LoginState?> = _login.asStateFlow()

    private val _loginBusy = MutableStateFlow(false)
    val loginBusy: StateFlow<Boolean> = _loginBusy.asStateFlow()

    fun dismissLogin() {
        _login.value = null
        _loginBusy.value = false
    }

    /**
     * Hands the pasted code to the waiting sign-in. Kept in the app because
     * sending somebody into a terminal to paste a code is not a flow, it is an
     * apology for not having one.
     */
    fun submitLoginCode(code: String) {
        if (_loginBusy.value) return
        _loginBusy.value = true
        viewModelScope.launch {
            runCatching { client.submitLoginCode(code.trim()) }
                .onSuccess { st ->
                    _login.value = st
                    if (st.done) {
                        refreshAccount()
                        refreshSavedAccounts()
                        // A duplicate or a mismatch is the whole point of asking, so
                        // the dialog stays up to say what happened. Only a clean
                        // result closes it.
                        if (!st.duplicate && !st.mismatch) {
                            _toast.value = "Signed in as ${st.email ?: "the new account"}"
                            _login.value = null
                        }
                    }
                }
                .onFailure { _toast.value = errText(it) }
            _loginBusy.value = false
        }
    }

    fun refreshLoginState() {
        viewModelScope.launch {
            runCatching { client.loginState() }.onSuccess { if (it.running) _login.value = it }
        }
    }

    /**
     * Opens an interactive `claude auth login` in a session and hands back both
     * the session (so the Screen tab can take the pasted code) and the sign-in
     * URL, which the pane hard-wraps and a phone cannot copy.
     */
    /**
     * Starts sign-in and stays in the app: the URL goes to the browser, and the
     * code comes back into a field here rather than into a tmux pane.
     */
    /** Opens the "which account?" step; nothing happens on the host yet. */
    fun beginAddAccount() {
        _login.value = LoginState(running = false, message = null)
    }

    fun startLogin(email: String?) {
        if (_loginBusy.value) return
        _loginBusy.value = true
        _login.value = LoginState(running = true, intendedEmail = email, message = "Starting sign-in…")
        viewModelScope.launch {
            runCatching { client.startLogin(email) }
                .onSuccess {
                    _loginUrl.value = it.url                     // opens the browser
                    _login.value = LoginState(
                        running = true, awaitingCode = true, url = it.url,
                        intendedEmail = email,
                        message = "Paste the code from your browser",
                    )
                    refreshSessions()
                }
                .onFailure { _toast.value = errText(it); _login.value = null }
            _loginBusy.value = false
        }
    }

    // ------------------------------------------------- the shared editors' IO
    //
    // `AccountsEditor` is `:ui`'s and reaches the daemon through its own small
    // interface; these are the phone's side of it. SUSPEND AND THROWING, on
    // purpose: the editor's whole value is that it reads the daemon's own
    // refusal — a duplicate, a mismatch, a 409 naming the expired login — and a
    // forwarder that swallowed the exception into a toast would leave it with a
    // blank where the sentence should be.
    //
    // They still go through the view model rather than handing the editor a
    // client, so a switch made in the editor invalidates the caches every other
    // switch does and the rest of the app hears about it.

    suspend fun fetchAccount(): Account =
        client.account().also { _account.value = it }

    /** withPlan: the weekly figure is what says which login to switch TO. */
    suspend fun fetchSavedAccounts(): List<SavedAccount> =
        client.savedAccounts(withPlan = true).also { _savedAccounts.value = it }

    suspend fun fetchAutoswitch(): Autoswitch =
        client.autoswitch().also { _autoswitch.value = it }

    /** Answers a STATUS WORD, not a boolean — `invalid_grant`, `lock_busy`, … */
    suspend fun refreshAccountToken(slug: String): String =
        client.refreshAccount(slug).also { runCatching { fetchSavedAccounts() } }

    suspend fun activateAccountNow(slug: String) {
        _account.value = client.activateAccount(slug)
        // The plan figure belongs to the account that just stopped serving.
        _plan.value = null
        refreshPlan()
        runCatching { fetchSavedAccounts() }
    }

    suspend fun forgetAccountNow(slug: String) {
        client.forgetAccount(slug)
        runCatching { fetchSavedAccounts() }
    }

    /** The URL goes to the editor, which opens it; this app never pastes it. */
    suspend fun startLoginNow(email: String?): LoginSession = client.startLogin(email)

    suspend fun submitLoginCodeNow(code: String): LoginState =
        client.submitLoginCode(code.trim()).also { st ->
            if (st.done) {
                runCatching { fetchAccount() }
                runCatching { fetchSavedAccounts() }
            }
        }

    // -------------------------------------------------------- quick actions

    private val _quickActionsSaving = MutableStateFlow(false)
    val quickActionsSaving: StateFlow<Boolean> = _quickActionsSaving.asStateFlow()

    /** The daemon's own words about the last save. Null until there has been one. */
    private val _quickActionsNote = MutableStateFlow<String?>(null)
    val quickActionsNote: StateFlow<String?> = _quickActionsNote.asStateFlow()

    /**
     * Saves the composer templates the host owns.
     *
     * ⚠ THE REFUSALS ARE THE DAEMON'S. `{selection}` exactly once, never in the
     * quote lead-in, 400 characters each, and a `rev` guard that 409s a stale
     * copy rather than silently taking the older text. None of those rules are
     * re-implemented here: a second copy would eventually disagree with the one
     * that decides, and then the app would refuse something the host accepts.
     * What comes back is written straight into `status` so every composer on
     * this phone stages the new wording immediately.
     */
    fun setQuickActions(edited: com.silencelen.huginn.data.QuickActions) {
        if (_quickActionsSaving.value) return
        _quickActionsSaving.value = true
        _quickActionsNote.value = null
        viewModelScope.launch {
            runCatching { saveQuickActions(client, edited) }
                .onSuccess { saved ->
                    _status.value = _status.value?.copy(quickActions = saved)
                    _quickActionsNote.value = "Saved."
                }
                .onFailure { _quickActionsNote.value = errText(it) }
            _quickActionsSaving.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { client.logout() }
                .onSuccess {
                    _account.value = it
                    _toast.value = "Signed out. huginn cannot run until you sign in again."
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    // ------------------------------------------------------------- data

    fun refreshAll() {
        viewModelScope.launch {
            awaitReady()
            _loading.value = true
            runCatching { client.status() }
                .onSuccess {
                    _status.value = it; _statusError.value = null; _connected.value = true
                    routeFailures.ok()
                    // ⚠ ORDINARY TRAFFIC IS A WITNESS AND NOTHING ELSE WAS ONE.
                    // Only `RouteResolver.resolve()` ever wrote the health map, so
                    // a route this app had been talking to all day still read
                    // "never reached" on the Settings list until somebody pressed
                    // Find live route. `touch` records it as `lastSeenAt`, which
                    // the rows read and the hysteresis deliberately does not.
                    noteRouteReached()
                }
                .onFailure {
                    _statusError.value = errText(it)
                    if (it is HuginnClient.HuginnException && it.code == 401) _connected.value = false
                    // ⚠ ONLY A NETWORK FAILURE COUNTS. A daemon that answered and
                    // said 401 is proof the route works; looking for another one
                    // would be answering a token problem with a network search.
                    else if (it !is HuginnClient.HuginnException && routeFailures.fail()) {
                        resolveRoute(silent = true)
                    }
                }
            runCatching { client.sessions(preview = true) }.onSuccess { _sessions.value = it }
            runCatching { client.chats() }.onSuccess { _chats.value = it }
            // Silent on failure like the two above: a daemon too old to know about
            // Rounds 404s here, and that must leave the rest of the screen working
            // rather than raising an error about a feature the user never asked for.
            runCatching { client.rounds() }.onSuccess { _rounds.value = it }
            runCatching { client.devices() }.onSuccess { _devices.value = it }
            // The scratchpad probe rides the app-wide refresh: it is the one place
            // that runs once per connection, which is exactly the cadence feature
            // detection wants. A 404 here turns every scratchpad control off.
            runCatching { client.scratchpads() }
                .onSuccess { landPads(it) }
                .onFailure { if (it is HuginnClient.HuginnException && it.code == 404) _scratchpadsAvailable.value = false }
            // The archive probe rides the same once-per-connection refresh, which
            // is exactly the cadence feature detection wants. Silent on every
            // other failure, like rounds and pages beside it: a status bar that
            // permanently reports a missing feature as a fault is a status bar
            // people stop reading.
            landArchives()
            // The projects and apps probes ride the same once-per-connection
            // refresh, for the reason the two above do: it is the one place that
            // runs once per connection, which is exactly the cadence feature
            // detection wants. A 404 at either turns every way in off.
            landProjects()
            landApps()
            _loading.value = false
        }
    }

    private var sessionsPollJob: Job? = null

    /** Keeps the sessions list live while it is on screen. */
    fun startSessionsPolling() {
        sessionsPollJob?.cancel()
        sessionsPollJob = viewModelScope.launch {
            // The poller starts the instant the sessions screen composes, which on
            // a cold start is before credentials have loaded — its first tick was
            // the one remaining 401.
            awaitReady()
            var tick = 0
            while (isActive) {
                runCatching { client.sessions(preview = true) }.onSuccess { _sessions.value = it }
                // ⚠ THE TWO LISTS MOVE TOGETHER AND MUST BE REFRESHED TOGETHER. A
                // graceful archive leaves the session on screen for as long as its
                // turn runs and then moves it — so a Sessions poll that did not
                // also fetch the archive would show the row vanish with nothing
                // appearing anywhere. Every fourth tick, not every one: an
                // archive changes when somebody presses something, and this list
                // is 64 rows of JSON rather than two.
                if (tick % 4 == 0) landArchives()
                tick++
                delay(5000)
            }
        }
    }

    fun stopSessionsPolling() {
        sessionsPollJob?.cancel()
        sessionsPollJob = null
    }

    fun refreshSessions() {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.sessions(preview = true) }
                .onSuccess { _sessions.value = it }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun refreshChats() {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.chats() }
                .onSuccess { _chats.value = it }
                .onFailure { _toast.value = errText(it) }
        }
    }

    // ------------------------------------------------------------ rounds

    fun refreshRounds() {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.rounds() }.onSuccess { _rounds.value = it }
        }
    }

    private var roundsPollJob: Job? = null

    /**
     * Polls only while the Rounds tab is on screen, the same shape as the sessions
     * poller — the app-wide refresh runs on resume, which meant tapping Run now
     * left the row showing last week's verdict until you pulled to refresh.
     *
     * Ten seconds, not five: a Round changes at human speed and the row's own
     * times are rounded to minutes, so anything faster redraws identical text.
     */
    fun startRoundsPolling() {
        roundsPollJob?.cancel()
        roundsPollJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                runCatching { client.rounds() }.onSuccess { _rounds.value = it }
                delay(10_000)
            }
        }
    }

    fun stopRoundsPolling() {
        roundsPollJob?.cancel()
        roundsPollJob = null
    }

    // -------------------------------------------------- the session overview

    private val _overview = MutableStateFlow<SessionOverview?>(null)
    val overview: StateFlow<SessionOverview?> = _overview.asStateFlow()

    private val _sessionGraph = MutableStateFlow<SessionGraph?>(null)
    val sessionGraph: StateFlow<SessionGraph?> = _sessionGraph.asStateFlow()

    /**
     * Why there is nothing to show, in the daemon's own words. A plain shell and a
     * session whose first prompt has not landed both reach this route legitimately
     * and get a 409 with a reason — neither is a fault, and neither belongs in the
     * error path beside "no route to host".
     */
    private val _overviewNote = MutableStateFlow<String?>(null)
    val overviewNote: StateFlow<String?> = _overviewNote.asStateFlow()

    /**
     * The goals and notes beside a run, and their autosave. APP-SCOPED for the
     * same reason [padSaver] is: the flush that matters happens as the tab is torn
     * down, and a scope owned by that tab is cancelled at exactly that moment.
     */
    val metaSaver = SessionMetaSaver(viewModelScope, { name, goals, notes ->
        client.saveSessionMeta(name, goals, notes)
    })

    private var overviewJob: Job? = null

    /**
     * Live only while the Overview tab is on screen, and only for the session it
     * is showing.
     *
     * The map is a whole-transcript walk on the host — thirty megabytes in the
     * worst case — so it is polled ONLY here and never from the sessions list. The
     * cursor is what makes the poll cheap: an unchanged session answers with two
     * numbers and nothing else.
     */
    fun startOverviewPolling(name: String) {
        overviewJob?.cancel()
        _overview.value = null
        _sessionGraph.value = null
        _overviewNote.value = null
        // Opened before the fetch so typing works the instant the tab is up; the
        // server's copy arrives underneath it through refresh(), which never
        // overwrites a field somebody is already in. Only on the first visit,
        // though: re-opening on every return to the tab would reset the editors
        // to the last meta the POLL returned, which after a save from this client
        // is the text as it read before it was typed.
        if (metaSaver.session.value != name) metaSaver.open(name, SessionMeta())
        overviewJob = viewModelScope.launch {
            awaitReady()
            // The header first: it is the cheapest thing on this wire and the
            // first thing somebody arriving actually reads.
            // The generation is captured BEFORE each fetch: what comes back was
            // read server-side at that moment, and a save of ours can land in
            // between — after which the poll is a photograph of the text as it
            // read before it was typed. See SessionMetaSaver's invariant 1.
            var at = metaSaver.generation()
            runCatching { client.sessionOverview(name) }
                .onSuccess { _overview.value = it; _overviewNote.value = null; metaSaver.refresh(name, it.meta, at) }
                .onFailure { _overviewNote.value = noteFor(it) }
            while (isActive) {
                at = metaSaver.generation()
                runCatching { client.sessionGraph(name, _sessionGraph.value?.cursor) }
                    .onSuccess { g ->
                        if (!g.unchanged) {
                            _sessionGraph.value = g
                            metaSaver.refresh(name, g.meta, at)
                        }
                        _overviewNote.value = null
                    }
                    .onFailure { _overviewNote.value = noteFor(it) }
                delay(5_000)
            }
        }
    }

    fun stopOverviewPolling() {
        overviewJob?.cancel()
        overviewJob = null
        // The tab is gone; the sentence that was still in the air is not.
        metaSaver.flush()
    }

    private fun noteFor(t: Throwable): String? =
        (t as? HuginnClient.HuginnException)?.let { e ->
            when (e.code) {
                // The daemon predates this feature. Nothing to say about it.
                404 -> null
                else -> e.message
            }
        }

    // ------------------------------------------------------- scratchpads

    private val _scratchpads = MutableStateFlow<List<Scratchpad>>(emptyList())
    val scratchpads: StateFlow<List<Scratchpad>> = _scratchpads.asStateFlow()

    /**
     * Whether this daemon HAS scratchpads. Null until the first probe answers.
     *
     * A 404 hides every scratchpad control — the top-bar icon, the composer chip,
     * the destinations. FEATURE DETECTION rather than version parsing, and the
     * distinction is the point: a version string is a claim about what a build
     * contains, while a 404 is the route itself answering. Same silent-404 shape
     * as refreshRounds, which is the house precedent.
     */
    private val _scratchpadsAvailable = MutableStateFlow<Boolean?>(null)
    val scratchpadsAvailable: StateFlow<Boolean?> = _scratchpadsAvailable.asStateFlow()

    /**
     * The open page and its autosave. APP-SCOPED, like the draft book and for the
     * same reason: the flush that matters happens as the editor is torn down, and
     * a scope owned by that editor is cancelled at the exact moment it has work.
     */
    val padSaver = ScratchpadSaver(viewModelScope, { id, rev, name, content ->
        client.saveScratchpad(id, rev, name = name, content = content)
    })

    /**
     * Which page each composer will attach, by [ScratchpadRules] key.
     *
     * In memory only. A reference is a decision about the message being written
     * right now, and one restored from disk days later would silently put a page
     * into the next thing typed.
     */
    private val _padRefs = MutableStateFlow<Map<String, String>>(emptyMap())
    val padRefs: StateFlow<Map<String, String>> = _padRefs.asStateFlow()

    fun setPadRef(key: String, id: String?) {
        _padRefs.value = _padRefs.value.toMutableMap().apply {
            if (id == null) remove(key) else put(key, id)
        }
    }

    /** The reference a send should carry, dropped if it names a page since deleted. */
    private fun padRefFor(key: String): String? =
        _padRefs.value[key]?.takeIf { id -> _scratchpads.value.any { it.id == id } }

    fun refreshScratchpads() {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.scratchpads() }
                .onSuccess { landPads(it) }
                .onFailure { if (it is HuginnClient.HuginnException && it.code == 404) _scratchpadsAvailable.value = false }
        }
    }

    /**
     * Ordered HERE, once, for every surface that lists pages — the list, the
     * switcher, the composer chip. See ScratchpadRules.ordered for why it is not
     * the order the daemon happens to send.
     */
    private fun landPads(pads: List<Scratchpad>) {
        _scratchpads.value = ScratchpadRules.ordered(pads)
        _scratchpadsAvailable.value = true
    }

    private var padsPollJob: Job? = null

    /**
     * Which surfaces are watching the pages list.
     *
     * ⚠ A SET, not a boolean, because two of them share the poll on a wide
     * screen: the list and the editor sit side by side, and closing the editor
     * used to stop the poll the LIST was still reading from — after which it sat
     * frozen with nothing on screen looking wrong. See [Watchers].
     */
    private val padWatchers = Watchers()

    /**
     * Live only while a scratchpad surface is on screen. Ten seconds, like Rounds:
     * a page changes at human speed, and the row's own line is rounded to minutes.
     *
     * @param surface which one is asking — "list" or "editor". Balanced by
     *   [stopScratchpadsPolling] with the same name.
     */
    fun startScratchpadsPolling(surface: String = "list") {
        // The job is checked as well as the count: a loop that ended on its own
        // (a throw out of awaitReady) would otherwise stay dead for as long as
        // anybody was still nominally watching it.
        if (!padWatchers.enter(surface) && padsPollJob?.isActive == true) return
        padsPollJob?.cancel()
        padsPollJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                runCatching { client.scratchpads() }
                    .onSuccess { landPads(it) }
                    .onFailure { if (it is HuginnClient.HuginnException && it.code == 404) _scratchpadsAvailable.value = false }
                delay(10_000)
            }
        }
    }

    fun stopScratchpadsPolling(surface: String = "list") {
        if (!padWatchers.leave(surface)) return
        padsPollJob?.cancel()
        padsPollJob = null
    }

    /**
     * Fetches a page's TEXT and opens it. The list is polled; content is not —
     * a poll that replaced the text under a cursor would be an editor that types
     * back at you.
     */
    fun openScratchpad(id: String) {
        viewModelScope.launch {
            awaitReady()
            // Captured BEFORE the fetch: what comes back is the page as the daemon
            // read it, and a write of ours can land in between. See the saver's
            // invariant 1 — capturing after would prove nothing.
            val at = padSaver.generation()
            runCatching { client.scratchpad(id) }
                .onSuccess { padSaver.open(it, at) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun closeScratchpad() = padSaver.close()

    fun createScratchpad(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.createScratchpad(name) }
                .onSuccess { made ->
                    landPads(_scratchpads.value + made)
                    refreshScratchpads()
                    padSaver.open(made)
                    onCreated(made.id)
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * THROUGH THE SAVER, not straight at the wire. A rename and an autosave PATCH
     * the same row with the same rev, so a rename fired while a save was in the
     * air made one of them lose: the save losing put the server's older text back
     * over live typing, the rename losing simply did not happen. The saver's chain
     * makes them a queue.
     */
    fun renameScratchpad(id: String, name: String) {
        viewModelScope.launch {
            awaitReady()
            val rev = padSaver.pad.value?.takeIf { it.id == id }?.rev
                ?: _scratchpads.value.firstOrNull { it.id == id }?.rev ?: return@launch
            padSaver.rename(id, name, rev)
                .onSuccess { refreshScratchpads() }
                .onFailure { if (it !is CancellationException) _toast.value = errText(it) }
        }
    }

    fun deleteScratchpad(id: String) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.deleteScratchpad(id) }
                .onSuccess {
                    // forget(), not close(): a pending write for a page that has
                    // just been deleted would recreate it out of a timer.
                    if (padSaver.pad.value?.id == id) padSaver.forget()
                    _padRefs.value = _padRefs.value.filterValues { it != id }
                    refreshScratchpads()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Stages a page's text into a target's composer: APPENDED, never sent.
     *
     * The share contract, and the same rule as [stageShareInChat] — a half-typed
     * draft outranks anything arriving into it, and nothing is sent on the
     * person's behalf.
     */
    fun stagePadInChat(id: String, text: String) = appendToDraft(chatDraftKey(id), text)

    fun stagePadInSession(name: String, text: String) = appendToDraft(sessionDraftKey(name), text)

    // ----------------------------------------------------- projects & apps

    private val _projects = MutableStateFlow<List<ProjectRow>>(emptyList())
    val projects: StateFlow<List<ProjectRow>> = _projects.asStateFlow()

    /**
     * Whether this daemon HAS projects. Null until the first probe answers.
     *
     * FEATURE DETECTION rather than version parsing — the scratchpads precedent,
     * and `HuginnClient.projects()` already turns the 404 into a null rather than
     * a throw. False hides EVERY way in at once: see [projectEntries].
     */
    private val _projectsAvailable = MutableStateFlow<Boolean?>(null)
    val projectsAvailable: StateFlow<Boolean?> = _projectsAvailable.asStateFlow()

    /**
     * Each project's live members, by project id.
     *
     * ⚠ THE LIST ROUTE DOES NOT CARRY MEMBERS, AND THAT IS NOT AN OMISSION. A row
     * carries the daemon's own rolled-up counts; who those members ARE comes from
     * the per-project GET, one request each. So this map fills in lazily — when a
     * disclosure opens, and for whichever project the sessions list is grouping —
     * and a missing entry means "not fetched", which the tree draws as
     * `PROJECT_MEMBERS_LOADING` rather than as an empty cluster.
     */
    private val _projectMembers = MutableStateFlow<Map<String, List<ProjectLive>>>(emptyMap())
    val projectMembers: StateFlow<Map<String, List<ProjectLive>>> = _projectMembers.asStateFlow()

    private val _projectDetail = MutableStateFlow<ProjectDetail?>(null)
    val projectDetail: StateFlow<ProjectDetail?> = _projectDetail.asStateFlow()

    private val _projectDashboard = MutableStateFlow<ProjectDashboard?>(null)
    val projectDashboard: StateFlow<ProjectDashboard?> = _projectDashboard.asStateFlow()

    /**
     * The daemon's last refusal about a project, VERBATIM.
     *
     * ⚠ NOT A TOAST. The three that matter — a working directory Claude Code has
     * not been trusted in, a slug already taken, and the headroom arbiter's STOP
     * sentinel — are each a sentence whose whole value is the fix it names, and a
     * toast takes it away after four seconds from a person who is mid-form. It
     * lands under the sheet's fields and on the manifest card instead, with
     * everything typed still in place.
     */
    private val _projectRefusal = MutableStateFlow<String?>(null)
    val projectRefusal: StateFlow<String?> = _projectRefusal.asStateFlow()
    fun clearProjectRefusal() { _projectRefusal.value = null }

    /** A create or a spawn is in the air, so the control cannot be pressed twice. */
    private val _projectBusy = MutableStateFlow(false)
    val projectBusy: StateFlow<Boolean> = _projectBusy.asStateFlow()

    private val _apps = MutableStateFlow(AppList())
    val apps: StateFlow<AppList> = _apps.asStateFlow()

    /**
     * Whether this daemon HAS apps. Same probe contract as projects — and the
     * client asks BOTH names before answering false, because a daemon older than
     * the 3.6 rename only serves `/v1/consoles`.
     */
    private val _appsAvailable = MutableStateFlow<Boolean?>(null)
    val appsAvailable: StateFlow<Boolean?> = _appsAvailable.asStateFlow()

    /**
     * The daemon's answer to the last add, or null.
     *
     * ⚠⚠ IT HOLDS A REFUSAL, NOT JUST A SUCCESS (decision 54). A 422 says the app
     * is not reachable from this phone's addresses yet and carries the lines that
     * would fix it; the screen keeps its form open on it. A toast would have
     * dropped both the lines and the typed address.
     */
    private val _appAdd = MutableStateFlow<AppCreate?>(null)
    val appAdd: StateFlow<AppCreate?> = _appAdd.asStateFlow()

    fun clearAppAdd() { _appAdd.value = null }

    /**
     * The project list and the apps registry, fetched once per connection.
     *
     * Both are the FEATURE PROBE, which is why they ride the app-wide refresh: it
     * is the one thing that runs once per connection, which is exactly the cadence
     * feature detection wants.
     */
    private suspend fun landProjects() {
        runCatching { client.projects() }
            .onSuccess { list ->
                if (list == null) { _projectsAvailable.value = false; return@onSuccess }
                _projectsAvailable.value = true
                _projects.value = ProjectRules.orderedProjects(list.projects)
                // Membership for projects that are GONE goes with them: a map that
                // kept them would group a session under a heading the tree no
                // longer draws.
                val live = list.projects.map { it.id }.toSet()
                _projectMembers.value = _projectMembers.value.filterKeys { it in live }
            }
    }

    private suspend fun landApps() {
        runCatching { client.apps() }
            .onSuccess { list ->
                if (list == null) { _appsAvailable.value = false; return@onSuccess }
                _appsAvailable.value = true
                _apps.value = list
            }
    }

    fun refreshProjects() {
        viewModelScope.launch { awaitReady(); landProjects() }
    }

    fun refreshApps() {
        viewModelScope.launch { awaitReady(); landApps() }
    }

    private var projectsPollJob: Job? = null

    /** Live while the Projects list is on screen. Ten seconds: a cluster changes at
     *  the speed somebody presses something, and the rollups are the daemon's. */
    fun startProjectsPolling() {
        projectsPollJob?.cancel()
        projectsPollJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                landProjects()
                delay(10_000)
            }
        }
    }

    fun stopProjectsPolling() {
        projectsPollJob?.cancel()
        projectsPollJob = null
    }

    /**
     * One project's record, row and live members.
     *
     * Feeds three surfaces from one request: the dashboard's header, the manifest
     * card, and the `live[]` the sessions list groups by. A second route for the
     * membership alone would be a second thing to keep in step.
     */
    fun openProject(id: String) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.project(id) }
                .onSuccess { landDetail(it) }
                .onFailure { if (it !is HuginnClient.HuginnException || it.code != 404) _toast.value = errText(it) }
        }
    }

    private fun landDetail(detail: ProjectDetail) {
        _projectDetail.value = detail
        _projectMembers.value = _projectMembers.value + (detail.project.id to detail.live)
        detail.row?.let { row ->
            _projects.value = ProjectRules.orderedProjects(
                _projects.value.map { if (it.id == row.id) row else it }
                    .let { if (it.none { p -> p.id == row.id }) it + row else it },
            )
        }
    }

    /** The members of one project, without disturbing whichever project is open. */
    fun fetchProjectMembers(id: String) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.project(id) }
                .onSuccess { d ->
                    _projectMembers.value = _projectMembers.value + (id to d.live)
                    if (_projectDetail.value?.project?.id == id) _projectDetail.value = d
                }
        }
    }

    private var dashboardJob: Job? = null

    /**
     * The dashboard, while it is on screen.
     *
     * ⚠ THE ANSWER IS ONLY PUBLISHED WHEN IT IS NEW. The daemon sums up to twelve
     * session overviews per tick and stamps the answer with `generatedAt`; an
     * answer carrying the stamp already on screen is the same answer, and pushing
     * it into the flow would redraw a table of twelve rows of identical numbers
     * every five seconds for as long as anybody reads it. See [DashboardCursor].
     */
    fun startDashboardPolling(id: String) {
        dashboardJob?.cancel()
        val cursor = DashboardCursor()
        _projectDashboard.value = null
        dashboardJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                runCatching { client.projectDashboard(id) }
                    .onSuccess { if (cursor.accept(it)) _projectDashboard.value = it }
                delay(5_000)
            }
        }
    }

    fun stopDashboardPolling() {
        dashboardJob?.cancel()
        dashboardJob = null
    }

    /**
     * Starts a project: the daemon launches its lead and types the brief into it.
     *
     * ⚠ THE 409 IS AN ANSWER AND IT IS KEPT WHOLE. An untrusted `cwd` comes back
     * as the daemon's own sentence naming the fix, and it belongs under the field
     * that caused it — not in a toast and not summarised.
     */
    fun createProject(name: String, kind: String, brief: String, cwd: String?, onCreated: (Project) -> Unit = {}) {
        viewModelScope.launch {
            awaitReady()
            _projectBusy.value = true
            _projectRefusal.value = null
            runCatching { client.createProject(name, kind, brief, cwd) }
                .onSuccess { made ->
                    val p = made.project
                    if (p == null) { _projectRefusal.value = made.refusal; return@onSuccess }
                    refreshProjects()
                    onCreated(p)
                }
                .onFailure { _toast.value = errText(it) }
            _projectBusy.value = false
        }
    }

    /**
     * Creates the members the owner approved.
     *
     * ⚠⚠ HTTP 200 IS NOT THE VERDICT. Spawning is a loop over tmux: the second of
     * three roles failing does not un-spawn the first, so the daemon carries on
     * and answers with both lists. `ok:false` here is the ORDINARY partial case
     * and it is reported per role, in the daemon's own words — a client that
     * collapsed it to "failed" would lose which role to retry.
     */
    fun spawnProject(id: String, manifestRev: Int) {
        viewModelScope.launch {
            awaitReady()
            _projectBusy.value = true
            _projectRefusal.value = null
            runCatching { client.spawnProject(id, manifestRev) }
                .onSuccess { outcome ->
                    if (outcome.refusal != null) {
                        // The STOP sentinel and the stale rev both arrive here,
                        // and both are states of the house rather than faults.
                        _projectRefusal.value = outcome.refusal
                    } else outcome.result?.let { r ->
                        _toast.value = (listOf(ProjectRules.spawnWords(r)) + ProjectRules.spawnFailures(r))
                            .joinToString(" · ")
                    }
                    openProject(id)
                    refreshProjects()
                }
                .onFailure { _toast.value = errText(it) }
            _projectBusy.value = false
        }
    }

    fun discardProposal(id: String) {
        viewModelScope.launch {
            awaitReady()
            _projectRefusal.value = null
            runCatching { client.discardProposal(id) }
                .onSuccess { openProject(id); refreshProjects() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Saves an edited proposal.
     *
     * Rev-guarded like every other edit on this daemon, and a conflict is ADOPTED
     * rather than raised: the other client having saved first is the ordinary
     * outcome of two devices on one host, and it arrives carrying the project as
     * it now stands.
     */
    fun saveProjectManifest(id: String, rev: Int, manifest: ProjectManifest) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.saveProject(id, rev, manifest = manifest) }
                .onSuccess { saved ->
                    if (saved.conflict) _toast.value = "That proposal changed on the host — showing the current one."
                    saved.refusal?.let { _projectRefusal.value = it }
                    openProject(id)
                    refreshProjects()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Forgets a project, and ends its sessions only when asked.
     *
     * [end] is `graceful`, `now`, or null for neither. Null is the default at the
     * wire too: a delete that silently killed twelve live sessions is not a delete
     * anybody meant.
     */
    fun deleteProject(id: String, end: String? = null) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.deleteProject(id, end) }
                .onSuccess { done ->
                    if (_projectDetail.value?.project?.id == id) _projectDetail.value = null
                    _projectMembers.value = _projectMembers.value - id
                    // ⚠ INCLUDING WHAT WAS NOT WOUND DOWN. A member sitting on a
                    // dialog cannot be typed at, so the daemon skips it and
                    // deletes the record anyway — those sessions are alive with
                    // no project behind them, and "Project removed" alone gives a
                    // reader no reason to go and find them.
                    _toast.value = ProjectRules.deletedWords(done)
                    refreshProjects()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    // ------------------------------------------ membership, edited by hand

    /**
     * ADOPT a session that is already running into this project.
     *
     * ⚠ IT LAUNCHES NOTHING — the record gains a row and the session carries on
     * exactly as it was. The 409 is an ANSWER and it is the interesting one: the
     * session already belongs to another cluster, and the daemon NAMES it.
     */
    fun adoptMember(id: String, role: String, name: String) {
        viewModelScope.launch {
            awaitReady()
            _projectBusy.value = true
            _projectRefusal.value = null
            runCatching { client.adoptMember(id, role, name) }
                .onSuccess { outcome ->
                    if (outcome.refusal != null) _projectRefusal.value = outcome.refusal
                    else _toast.value = "$name joined as $role"
                    openProject(id)
                    refreshProjects()
                }
                // 400 (a role that is taken, is "lead", or is not a name), 404
                // (no such session), 503 (tmux not answering) — every one of
                // them arrives as the daemon's own sentence, which is also the fix.
                .onFailure { _projectRefusal.value = errText(it) }
            _projectBusy.value = false
        }
    }

    /** DROP a member. ⚠ THE SESSION KEEPS RUNNING — see the client. */
    fun dropMember(id: String, role: String) {
        viewModelScope.launch {
            awaitReady()
            _projectBusy.value = true
            _projectRefusal.value = null
            runCatching { client.dropMember(id, role) }
                .onSuccess { outcome ->
                    if (outcome.refusal != null) _projectRefusal.value = outcome.refusal
                    // Said out loud, because "drop" and "end" are one keystroke
                    // apart and the reader has just pressed one of them.
                    else _toast.value = "Dropped $role — the session is still running"
                    openProject(id)
                    refreshProjects()
                }
                .onFailure { _projectRefusal.value = errText(it) }
            _projectBusy.value = false
        }
    }

    /** Types a line into one member, from another. NOT peer messaging — see the client. */
    fun messageProject(id: String, from: String, to: String, text: String) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.messageProject(id, from, to, text) }
                .onSuccess { r ->
                    _toast.value = when {
                        r.dropped != null -> "Not delivered: ${r.dropped}"
                        r.delivered -> "Sent to ${r.to}"
                        else -> "Queued for ${r.to}"
                    }
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    private var appsPollJob: Job? = null

    /** Live while an apps surface is on screen. The probe itself is the host's,
     *  memoised there, so this is only how often the verdict is collected. */
    fun startAppsPolling() {
        appsPollJob?.cancel()
        appsPollJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                landApps()
                delay(15_000)
            }
        }
    }

    fun stopAppsPolling() {
        appsPollJob?.cancel()
        appsPollJob = null
    }

    /** Probes one app now and lands the refreshed row. */
    fun probeApp(id: String) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.probeApp(id) }
                .onSuccess { row -> landAppRow(row) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    private fun landAppRow(row: App) {
        _apps.value = _apps.value.let { list ->
            list.copy(apps = list.apps.map { if (it.id == row.id) row else it })
        }
    }

    /**
     * Adds an app.
     *
     * ⚠⚠ THE 422 IS PUBLISHED, NOT TOASTED (decision 54). The daemon refused
     * because the app does not answer on the addresses this phone arrives from,
     * and it sent back the lines that would fix it; the screen keeps its form
     * open on that answer. Everything else — a bad address, a name already taken
     * — is a refusal of the request and says so once, in a toast.
     */
    fun addApp(form: AppForm) {
        viewModelScope.launch {
            awaitReady()
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
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Edits one app.
     *
     * A version conflict is adopted, like every other rev-guarded edit here; a
     * refused ADDRESS is a refusal of the request and says so.
     */
    fun saveApp(id: String, version: Int, form: AppForm) {
        viewModelScope.launch {
            awaitReady()
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
                    if (saved.conflict) {
                        _toast.value = saved.refusal ?: "That app changed on the host — showing the current one."
                    }
                    refreshApps()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun deleteApp(id: String) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.deleteApp(id) }
                .onSuccess {
                    _apps.value = _apps.value.let { l -> l.copy(apps = l.apps.filterNot { it.id == id }) }
                    refreshApps()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /** Read once when the new-chat dialog opens, so a machine enrolled since the
     *  last app-wide refresh is actually offerable. */
    fun refreshDevices() {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.devices() }.onSuccess { _devices.value = it }
        }
    }

    private var devicesPollJob: Job? = null

    /**
     * Live while the Devices screen is open, and only then.
     *
     * Slower than the Rounds tick because it is watching for a different kind of
     * change: a machine going quiet is a three-minute judgement at the daemon
     * anyway, so polling faster would only redraw the same answer. Whether a
     * device is RUNNING moves quickly, which is why it polls at all.
     */
    fun startDevicesPolling() {
        devicesPollJob?.cancel()
        devicesPollJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                runCatching { client.devices() }.onSuccess { _devices.value = it }
                delay(15_000)
            }
        }
    }

    fun stopDevicesPolling() {
        devicesPollJob?.cancel()
        devicesPollJob = null
    }

    /**
     * Stops offering a machine work.
     *
     * Deliberately not called "remove": nothing here reaches onto that machine and
     * nothing can. A runner still running on the far side will enrol again within
     * the minute, which is correct — the machine grants its own access — and the
     * confirmation on the way in says so.
     */
    fun forgetDevice(id: String) {
        val name = _devices.value.firstOrNull { it.id == id }?.name ?: "that device"
        _devices.value = _devices.value.filterNot { it.id == id }
        viewModelScope.launch {
            awaitReady()
            runCatching { client.deleteDevice(id) }
                .onSuccess { _toast.value = "Huginn will not send work to $name"; refreshDevices() }
                .onFailure { _toast.value = errText(it); refreshDevices() }
        }
    }

    /**
     * This phone's IANA zone, handed to the daemon when a Round is written here.
     *
     * The shared editor is multiplatform and has no calendar, so it never names a
     * zone itself; without this the daemon falls back to the HOST's, which is
     * usually the same and quietly is not when it isn't. Sent from the platform
     * layer, which is the only place that knows.
     */
    fun deviceZone(): String? =
        runCatching { java.util.TimeZone.getDefault().id?.takeIf { it.isNotBlank() } }.getOrNull()

    /**
     * @param onResult null on success, otherwise the reason — the daemon's words,
     *   not ours. It validates the same schedule this form does, and when the two
     *   disagree its answer is the real one.
     */
    fun createRound(draft: RoundDraft, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            awaitReady()
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
            }.onSuccess { refreshRounds(); onResult(null) }
                .onFailure { onResult(errText(it)) }
        }
    }

    fun saveRound(id: String, draft: RoundDraft, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            awaitReady()
            runCatching {
                client.updateRound(
                    id = id,
                    title = draft.title.trim(),
                    prompt = draft.prompt.trim(),
                    schedule = draft.toSchedule(deviceZone()),
                    // Sent even when blank: clearing a goal is a real edit, and
                    // omitting it would make "this no longer has a finish line"
                    // impossible to say.
                    goal = draft.goal.trim(),
                    mode = draft.mode,
                    notifyWhen = draft.notifyWhen,
                    host = draft.host,
                )
            }.onSuccess { refreshRounds(); onResult(null) }
                .onFailure { onResult(errText(it)) }
        }
    }

    /**
     * Asks the host to rewrite one field of a Round being drafted.
     *
     * Nothing is saved and nothing is refreshed: this is a PROPOSAL for the editor
     * to show, and the Round on the host — if it even exists yet — is untouched
     * until somebody presses Save.
     *
     * A thrown failure becomes a [PolishResult] with an error rather than a toast:
     * the editor already has a quiet line for it, and a toast for "the model was
     * busy" is a notification about nothing.
     */
    fun polishRound(draft: RoundDraft, field: String, onResult: (PolishResult) -> Unit) {
        viewModelScope.launch {
            awaitReady()
            runCatching {
                client.polishRound(
                    field = field,
                    title = draft.title.trim(),
                    prompt = draft.prompt.trim(),
                    goal = draft.goal.trim(),
                    mode = draft.mode,
                )
            }.onSuccess { onResult(it) }
                .onFailure { onResult(PolishResult(error = errText(it))) }
        }
    }

    fun deleteRound(id: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.deleteRound(id) }
                .onSuccess {
                    // The schedule goes; its past runs are ordinary chats and are
                    // left alone, so deleting a Round never destroys the reports it
                    // already produced.
                    _toast.value = "Round deleted"
                    refreshRounds()
                    onResult(null)
                }
                .onFailure { onResult(errText(it)) }
        }
    }

    /**
     * Fires a Round now. The report arrives exactly as a scheduled one does — as a
     * notification and a row on this screen — so there is nothing to navigate to
     * and nothing to wait on here.
     */
    fun runRound(id: String) {
        viewModelScope.launch {
            awaitReady()
            runCatching { client.runRound(id) }
                .onSuccess { _toast.value = "Running now"; refreshRounds() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * "I have read this and dealt with it", or Undo.
     *
     * Optimistic like the pause switch, for the same reason: the point of the
     * control is that the red goes away, and a control that waits for a round
     * trip to do the one thing it exists for feels broken.
     */
    fun acknowledgeRound(id: String, acknowledged: Boolean) {
        val stamp = if (acknowledged) System.currentTimeMillis() / 1000 else null
        _rounds.value = _rounds.value.map { r ->
            // ⚠ A local val, not `r.lastRun` twice: it is a public property of
            // another module, so Kotlin will not smart-cast it after the null
            // check — the compiler cannot prove :core did not change it in
            // between. The same shape fails identically in the desktop store.
            val run = r.lastRun
            if (r.id == id && run != null) r.copy(lastRun = run.copy(acknowledgedAt = stamp)) else r
        }
        viewModelScope.launch {
            awaitReady()
            runCatching { client.ackRound(id, acknowledged) }
                .onSuccess { updated -> _rounds.value = _rounds.value.map { if (it.id == id) updated else it } }
                .onFailure { _toast.value = errText(it); refreshRounds() }
        }
    }

    fun setRoundEnabled(id: String, enabled: Boolean) {
        // Optimistic, because a switch that waits for a round trip feels broken on
        // a phone. The refresh below is what makes it true; a failure puts the
        // server's answer back and says why.
        _rounds.value = _rounds.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        viewModelScope.launch {
            awaitReady()
            runCatching { client.updateRound(id, enabled = enabled) }
                .onSuccess { updated -> _rounds.value = _rounds.value.map { if (it.id == id) updated else it } }
                .onFailure { _toast.value = errText(it); refreshRounds() }
        }
    }

    // ---------------------------------------------------------- sessions

    fun createSession(name: String, onCreated: (String) -> Unit) {
        val canon = name.trim().lowercase()
        if (!canon.matches(SESSION_NAME)) {
            _toast.value = SESSION_NAME_HELP
            return
        }
        viewModelScope.launch {
            runCatching { client.createSession(canon) }
                // Open what tmux CALLED it, not what was asked for. The two can
                // differ and the host now reports which; opening the requested
                // name would 404 on everything done after it.
                .onSuccess { made -> refreshSessions(); onCreated(made.ifBlank { canon }) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun killSession(name: String) {
        viewModelScope.launch {
            runCatching { client.killSession(name) }
                .onSuccess {
                    clearDraft(sessionDraftKey(name))
                    clearAttachment(sessionDraftKey(name))
                    _toast.value = "Ended $name"; refreshSessions()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Soft end: ask Claude to wrap up (and, with auto-end on, let the host end the
     * session once it settles). The session lives on — a wrap-up question keeps it
     * open — so drafts are deliberately NOT cleared. Reports what was sent.
     */
    fun softEndSession(name: String) {
        viewModelScope.launch {
            runCatching { client.softEndSession(name) }
                .onSuccess { r ->
                    _toast.value = if (r.auto) "Winding down $name — ends when it goes idle"
                    else "Sent wrap-up to $name"
                    refreshSessions()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Archive: end the session for good and keep the way back into it.
     *
     * GRACEFUL, like the wind-down it is built on — Claude is asked to wrap up
     * and the host ends the session once it settles, so the row appears a little
     * later rather than at once. Drafts are cleared like a kill and unlike a
     * wind-down: this session is going, and the text typed at it is not.
     *
     * ⚠ THE 409 IS SHOWN VERBATIM. "answer the waiting question first, then
     * archive the session" tells somebody exactly what to do, and errText already
     * carries the daemon's own sentence through — replacing it with "Could not
     * archive" is how a refusal becomes a mystery.
     */
    fun archiveSession(name: String, now: Boolean = false) {
        viewModelScope.launch {
            runCatching { client.archiveSession(name, now = now) }
                .onSuccess { r ->
                    clearDraft(sessionDraftKey(name))
                    clearAttachment(sessionDraftKey(name))
                    _toast.value = when {
                        r.archived -> "Archived $name"
                        r.queued -> "$name will be archived after this turn"
                        else -> "$name is winding down — it will be archived when it settles"
                    }
                    refreshSessions()
                    landArchives()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Bring an archived session back and open it.
     *
     * The name is the HOST's answer, not the row's: the old name is taken when
     * free and numbered when not, so navigating to `row.tmuxName` would open a
     * session that does not exist (or, worse, a stranger's that reused the name).
     */
    fun reviveArchive(row: ArchivedSession, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { client.reviveArchive(row.id) }
                .onSuccess { r ->
                    // Said out loud when the conversation did NOT come back. That
                    // is the failure this feature exists to prevent, and it is
                    // invisible from the session that opens.
                    _toast.value = if (r.resumed) "Revived as ${r.name}"
                    else "Started ${r.name} fresh — nothing was left to resume"
                    refreshSessions()
                    landArchives()
                    onOpened(r.name)
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun deleteArchive(row: ArchivedSession) {
        viewModelScope.launch {
            runCatching { client.deleteArchive(row.id) }
                .onSuccess { _toast.value = "Forgotten"; landArchives() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    // ------------------------------------------- one archive, read only

    /**
     * The conversation of ONE archived session, as the read-only view draws it.
     *
     * ⚠ A SEPARATE PAGE FROM EVERY OTHER TRANSCRIPT IN THIS CLASS, on purpose.
     * The live ones are keyed on a tmux NAME, polled, followed, and sent to; this
     * one is keyed on the Claude session uuid, read once from a copy on disk, and
     * has nothing behind it to poll or type at.
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

    /**
     * Reads one archive's conversation. Idempotent for the id already shown, so
     * a recomposition of the destination does not re-fetch.
     */
    fun openArchiveTranscript(row: ArchivedSession) {
        if (_archiveRead.value?.id == row.id && _archiveRead.value?.loading == false) return
        val label = ArchiveRules.label(row)
        _archiveRead.value = ArchiveRead(id = row.id, title = label, loading = true)
        viewModelScope.launch {
            val outcome = runCatching { client.archiveTranscript(row.id) }
            val current = _archiveRead.value
            // The reader may have left while this was in flight; a late answer
            // must not paint itself over the archive they opened instead.
            if (current?.id != row.id) return@launch
            _archiveRead.value = outcome.fold(
                onSuccess = { page ->
                    // ⚠ NULL IS THE 404, WHICH IS BOTH "no such archive" AND "this
                    // daemon has no such route". Either way there is nothing to
                    // read, and the view says so rather than showing an error.
                    current.copy(
                        page = page,
                        loading = false,
                        note = if (page == null) ARCHIVE_TRANSCRIPT_GONE else null,
                    )
                },
                // The 409 carries the daemon's own sentence about which copies
                // went, and it is better than any summary of ours.
                onFailure = { current.copy(loading = false, note = errText(it)) },
            )
        }
    }

    /** Leaving the view. Keeps nothing: the next open is a fresh read. */
    fun closeArchiveTranscript() { _archiveRead.value = null }

    /** The one place the list and its feature flag are written. */
    private suspend fun landArchives() {
        runCatching { client.archives() }
            .onSuccess { _archives.value = ArchiveRules.ordered(it); _archiveAvailable.value = true }
            .onFailure {
                if (it is HuginnClient.HuginnException && it.code == 404) _archiveAvailable.value = false
            }
    }

    /**
     * Compact the session's context (the "context manager" action): types
     * "/compact" into the pane. 409s when a question is waiting or the pane has no
     * recorded Claude state (a plain shell would run "/compact" as a command).
     */
    fun compactSession(name: String) {
        viewModelScope.launch {
            runCatching { client.compactSession(name) }
                .onSuccess { r ->
                    _toast.value = if (r.queued) "Compacting $name after this turn" else "Compacting $name…"
                    refreshSessions()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun renameSession(from: String, to: String) {
        val canon = to.trim().lowercase()
        if (!canon.matches(SESSION_NAME)) {
            _toast.value = SESSION_NAME_HELP
            return
        }
        viewModelScope.launch {
            runCatching { client.renameSession(from, canon) }
                .onSuccess {
                    // MOVED, not dropped: half a typed message is worth keeping
                    // across a rename, and the old key would never be read again.
                    val carried = drafts.value[sessionDraftKey(from)].orEmpty()
                    clearDraft(sessionDraftKey(from))
                    if (carried.isNotBlank()) setDraft(sessionDraftKey(canon), carried)
                    _toast.value = "Renamed to $canon"; refreshSessions()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    // ------------------------------------------------------ session view

    private val _screen = MutableStateFlow<Screen?>(null)
    val screen: StateFlow<Screen?> = _screen.asStateFlow()

    /**
     * Pane history above the live screen. Fetched on request rather than with
     * every poll: it is tens of kilobytes and does not change while you read it,
     * so putting it in the poll would pay for it once a second for nothing.
     */
    private val _scrollback = MutableStateFlow<List<String>>(emptyList())
    val scrollback: StateFlow<List<String>> = _scrollback.asStateFlow()

    private val _loadingScrollback = MutableStateFlow(false)
    val loadingScrollback: StateFlow<Boolean> = _loadingScrollback.asStateFlow()

    fun loadScrollback(name: String, lines: Int = 400) {
        if (_loadingScrollback.value) return
        _loadingScrollback.value = true
        viewModelScope.launch {
            runCatching { client.screen(name, history = lines) }
                .onSuccess { _scrollback.value = it.scrollback }
                .onFailure { _toast.value = errText(it) }
            _loadingScrollback.value = false
        }
    }

    private val _transcript = MutableStateFlow<TranscriptPage?>(null)
    val transcript: StateFlow<TranscriptPage?> = _transcript.asStateFlow()

    private val _transcriptError = MutableStateFlow<String?>(null)
    val transcriptError: StateFlow<String?> = _transcriptError.asStateFlow()

    private var screenJob: Job? = null
    private var transcriptJob: Job? = null


    /** Geometry the phone can actually display, reported so tmux can match it. */
    private var wantCols: Int? = null
    private var wantRows: Int? = null
    private var forceResize = false

    /**
     * The Screen tab's LIVE TYPING mode: the soft keyboard is going straight into
     * the pane, keystroke by keystroke.
     *
     * ⚠ THE ONLY THING THAT MAY LEASE THE OWNER'S TMUX WINDOW (owner decision 52).
     * Reporting `?cols=&rows=` used to take that lease on every poll the Screen
     * tab made, so a phone with the tab merely on display and a desktop doing the
     * same thing walked the owner's real pane 152x44 <-> 107x44 three times in
     * ninety seconds with nobody typing. Watching is not a claim; typing is.
     */
    private var liveView = false

    /**
     * The session whose tmux window this phone has actually CLAIMED, or null.
     *
     * ⚠ NOT THE SAME AS "a session is open", and that gap was a real bug: the
     * phone sent `DELETE /v1/sessions/<name>/size` every time a session view
     * closed — including the overwhelming majority where the Screen tab was never
     * opened and this phone had leased nothing. Against the pre-52 daemon that
     * DELETE released whoever actually held the window, so simply leaving a
     * conversation could unpin somebody else's live pane. Releases are now driven
     * by [PaneLease.toRelease] off this field, so the phone hands back exactly
     * what it took and nothing else.
     */
    private var leasedName: String? = null

    fun setGeometry(cols: Int, rows: Int) {
        val changed = wantCols != cols || wantRows != rows
        wantCols = cols
        wantRows = rows
        // Re-poll immediately so the resize lands now rather than after the
        // current long poll times out.
        if (changed) screenJob?.let { restartScreenPolling() }
    }

    fun forceFit() {
        forceResize = true
        restartScreenPolling()
    }

    /**
     * Entering or leaving live typing on the Screen tab.
     *
     * Leaving RELEASES rather than waiting for the lease to lapse: the reader is
     * still on the tab, still polling, and a ninety-second wait is ninety seconds
     * of the owner's terminal held at phone width by somebody who has stopped
     * typing. The restart is how a parked long poll learns about either.
     */
    fun setLiveView(value: Boolean) {
        if (liveView == value) return
        liveView = value
        if (!value) releaseLease()
        if (screenJob != null) restartScreenPolling()
    }

    /**
     * Hand back whatever this phone claimed, if anything.
     *
     * The rule is [PaneLease.toRelease] — the same one the desktop's holder runs —
     * so "release what is held, never what is merely open" is written once.
     * Cleared BEFORE the call: a release that fails must still count as "we are no
     * longer asking", or a failed release becomes a permanent belief we hold it.
     */
    private fun releaseLease() {
        val owed = PaneLease.toRelease(leasedName, null) ?: return
        leasedName = null
        viewModelScope.launch { runCatching { client.releaseSize(owed) } }
    }

    private var currentSession: String? = null

    private fun restartScreenPolling() {
        val name = currentSession ?: return
        startScreenPolling(name)
    }

    /**
     * Long-polls the pane. The server holds the request until the screen actually
     * differs, so an idle session costs one parked connection instead of a capture
     * every second, and a busy one updates as fast as it changes.
     */
    fun startScreenPolling(name: String) {
        currentSession = name
        screenJob?.cancel()
        screenJob = viewModelScope.launch {
            var known: String? = _screen.value?.hash
            var backoff = 1000L
            while (isActive) {
                val useForce = forceResize
                // The geometry travels either way; `live` is the separate claim.
                val ask = PaneLease.poll(wantCols, wantRows, liveView)
                // Sending `live=1` IS the claim, so the record of owing a release
                // is made here rather than on the answer: a request that goes out
                // and whose reply is lost still moved the owner's window.
                if (ask.live) leasedName = name
                val r = runCatching {
                    client.screen(
                        name = name,
                        cols = ask.cols,
                        rows = ask.rows,
                        live = ask.live,
                        knownHash = known,
                        waitMs = if (known == null) 0 else 25_000,
                        force = useForce,
                    )
                }
                r.onSuccess { s ->
                    backoff = 1000L
                    // One shot: a forced resize must not keep renewing the lease
                    // on every subsequent poll, or its expiry can never fire.
                    if (useForce) forceResize = false
                    if (s.unchanged) {
                        known = s.hash
                        // Keep the size/attachment flags fresh even with no repaint.
                        _screen.value = _screen.value?.copy(
                            attachedClients = s.attachedClients,
                            sizeLeased = s.sizeLeased,
                            resizeBlocked = s.resizeBlocked,
                        )
                    } else {
                        _screen.value = s
                        known = s.hash
                    }
                }.onFailure { e ->
                    if (e is HuginnClient.HuginnException && e.code == 404) {
                        // The session died under the viewer — or the daemon cannot
                        // ADDRESS it, which is a different sentence and must not be
                        // reported as an ending. The UI collects this and navigates
                        // back either way; a screen it cannot fetch is no screen.
                        _toast.value = sessionGoneWords(name, _sessions.value.map { it.name })
                        _sessionGone.value = name
                        return@launch
                    }
                    // Network blips are expected on a phone; back off instead of
                    // spinning, and never drop the screen already on display.
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(15_000)
                }
            }
        }
    }

    fun stopScreenPolling() {
        screenJob?.cancel()
        screenJob = null
        transcriptJob?.cancel()
        transcriptJob = null
        currentSession = null
        forceResize = false
        // Live typing belongs to the visit, not to the app. A stale `true` here
        // would make the NEXT session's first poll claim its window unasked.
        liveView = false
        // Geometry belongs to the Screen tab of ONE session. Leaving it set
        // meant the next session opened was resized to the previous one's
        // grid even if its Screen tab was never opened.
        wantCols = null
        wantRows = null
        _screen.value = null
        _transcript.value = null
        _transcriptError.value = null
        // The history handles belong to the session being left. Kept, they made
        // the NEXT session's first "load earlier" ask for a byte offset into a
        // file it never wrote — and a spinner left true here can never be
        // cleared, since only the in-flight request clears it and that request
        // is now for somebody else.
        historyStart = null
        _loadingHistory.value = false
        // Hand the pane size back so an attached laptop re-fits immediately
        // instead of waiting out the server-side lease — but ONLY if this phone
        // took it. Closing a session it merely read must put nothing on the wire.
        releaseLease()
    }

    /**
     * The byte the OLDEST page on screen begins at, and the handle for reading
     * further back. Null until a page lands; 0 once the whole conversation is in
     * view.
     */
    private var historyStart: Long? = null

    private val _loadingHistory = MutableStateFlow(false)
    val loadingHistory: StateFlow<Boolean> = _loadingHistory.asStateFlow()

    /** True while there is still conversation above what is on screen. */
    val hasEarlier: StateFlow<Boolean> = _transcript
        .map { (it?.windowStart ?: 0L) > 0L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Reads the page before the oldest one on screen and puts it in front.
     *
     * A cold open only gets the tail, and on a long session that is a sliver of
     * it — 51 events out of 3452 on a real transcript here. Pages abut, because
     * a windowStart is a record boundary, so this neither repeats nor skips.
     */
    fun loadEarlierTranscript(name: String) {
        val until = historyStart ?: _transcript.value?.windowStart ?: return
        if (until <= 0L || _loadingHistory.value) return
        _loadingHistory.value = true
        viewModelScope.launch {
            val page = runCatching { client.sessionTranscript(name, until = until) }
                .onFailure { if (stillReading(name)) _toast.value = errText(it) }
                .getOrNull()
            // ⚠ THE ANSWER IS ONLY FOR THE SESSION THAT ASKED. This launch is
            // tracked by no field and the view model outlives the screen, so a
            // page that arrives after the reader has opened another session used
            // to be welded on top of THAT session's transcript — another
            // conversation, permanently, with no restart guard to undo it
            // (prependTranscriptPage keeps the current page's claudeSessionId).
            // The request can be in flight for tens of seconds: the daemon reads
            // backwards with a doubling window and this call has no timeout.
            if (page != null && stillReading(name)) {
                historyStart = page.windowStart
                _transcript.value = prependTranscriptPage(_transcript.value, page)
            }
            if (stillReading(name)) _loadingHistory.value = false
        }
    }

    /** Whether [name] is still the session on screen — see [loadEarlierTranscript]. */
    private fun stillReading(name: String): Boolean = pageStillWanted(name, currentSession)

    /** Tails the session's Claude transcript: the structured conversation view. */
    fun startTranscriptPolling(name: String) {
        transcriptJob?.cancel()
        _transcript.value = null
        _transcriptError.value = null
        historyStart = null
        transcriptJob = viewModelScope.launch {
            var offset: Long? = null
            while (isActive) {
                val r = runCatching { client.sessionTranscript(name, offset) }
                r.onSuccess { page ->
                    _transcriptError.value = null
                    // The tmux name now belongs to a different Claude session, so
                    // both handles into the old transcript are void: the offset is
                    // a byte position in a file this session never wrote, and the
                    // history handle points into its history. Start over; the next
                    // poll reads the new session's tail from scratch.
                    if (isTranscriptRestart(_transcript.value, page)) {
                        offset = null
                        historyStart = null
                    } else {
                        offset = page.nextOffset
                        // The first page decides where history begins; every tail
                        // read after it is BELOW that and must not move the handle.
                        if (historyStart == null) historyStart = page.windowStart
                    }
                    // :core's merge, not a copy of it. This was hand-rolled here
                    // and had quietly fallen behind the shared one in ways the
                    // screen could see: it dropped `state`, `modelDisplay`,
                    // `mode` and `claudeSessionId` on every tail read that did not
                    // happen to contain those records — so the model control fell
                    // back to a placeholder and the Send/Stop flag, which is
                    // derived from `state`, reverted seconds after the screen
                    // opened. It also never learned to clear the `queued` badge
                    // when the daemon reported a delivery, so a message that had
                    // landed went on claiming to be waiting.
                    _transcript.value = mergeTranscriptPage(_transcript.value, page, MAX_EVENTS)
                }.onFailure { e ->
                    if (_transcript.value == null) {
                        _transcriptError.value = when {
                            e is HuginnClient.HuginnException && e.code == 409 -> e.message
                            else -> errText(e)
                        }
                    }
                }
                delay(2500)
            }
        }
    }

    // --------------------------------------------------------- agent streams

    /**
     * The picked stream and its own cursors. See [AgentStream].
     *
     * The state machine is a plain object so it can be driven a step at a time by
     * a test; these flows are only what Compose reads off it. They are pushed
     * rather than derived because [AgentStream] is deliberately not observable —
     * it is a cursor pair, and making it a flow of itself would invite somebody to
     * treat a cursor as UI state.
     */
    private val stream = AgentStream()

    private val _selectedStream = MutableStateFlow<String?>(null)
    val selectedStream: StateFlow<String?> = _selectedStream.asStateFlow()

    /**
     * The PICKED agent's transcript, kept entirely apart from [_transcript].
     *
     * A separate page rather than a filter on the main one, and that is the whole
     * design: the two describe different files, so the parent's byte offsets say
     * nothing about the agent's, and merging them would hand `mergeTranscriptPage`
     * two sequences of `seq` numbers that both start at 1. The MAIN page goes on
     * ticking while this one is on screen — reading an agent is looking more
     * closely at a session that is still going, not leaving it.
     */
    private val _agentPage = MutableStateFlow<TranscriptPage?>(null)
    val agentPage: StateFlow<TranscriptPage?> = _agentPage.asStateFlow()

    private val _streamNote = MutableStateFlow<String?>(null)
    val streamNote: StateFlow<String?> = _streamNote.asStateFlow()

    private val _streamsSupported = MutableStateFlow(true)
    val streamsSupported: StateFlow<Boolean> = _streamsSupported.asStateFlow()

    /** Every agent this session has spawned, for the picker strip. */
    private val _streamAgents = MutableStateFlow<List<com.silencelen.huginn.data.AgentRun>>(emptyList())
    val streamAgents: StateFlow<List<com.silencelen.huginn.data.AgentRun>> = _streamAgents.asStateFlow()

    /** Whether the strip's `…` pill is unfolded. @see AgentStream.expanded */
    private val _streamsExpanded = MutableStateFlow(false)
    val streamsExpanded: StateFlow<Boolean> = _streamsExpanded.asStateFlow()

    private var agentStreamJob: Job? = null
    private var agentListJob: Job? = null

    private fun publishStream() {
        _selectedStream.value = stream.selected
        _agentPage.value = stream.page
        _streamNote.value = stream.note
        _streamsSupported.value = stream.supported
        _streamsExpanded.value = stream.expanded
    }

    /** @param agentId null (or "main") for the session's own transcript. */
    fun selectStream(name: String, agentId: String?) {
        if (!stream.select(agentId)) return
        publishStream()
        startAgentStreamPolling(name)
    }

    /**
     * Tails the picked agent, and only while one is picked.
     *
     * A SECOND loop rather than a branch inside [startTranscriptPolling], for the
     * reason [_agentPage] is a second page: the main tail must keep running
     * underneath it.
     */
    private fun startAgentStreamPolling(name: String) {
        agentStreamJob?.cancel()
        val picked = stream.selected ?: return
        agentStreamJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                runCatching { client.agentTranscript(name, picked, stream.offset) }
                    .onSuccess { stream.land(it) }
                    .onFailure { e ->
                        stream.fail((e as? HuginnClient.HuginnException)?.code, e.message)
                    }
                publishStream()
                delay(2500)
            }
        }
    }

    /**
     * The agents themselves, for the strip.
     *
     * See [fetchStreamAgents] for why this is the DEFAULT listing rather than
     * `all = true`.
     */
    fun startStreamAgentsPolling(name: String) {
        agentListJob?.cancel()
        agentListJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                runCatching { fetchStreamAgents(client, name) }
                    .onSuccess { _streamAgents.value = it }
                delay(AGENTS_POLL_MS)
            }
        }
    }

    /** The strip, from what has landed, what is picked, and whether the pill is open. */
    fun streamItems(nowSec: Long): List<StreamPicker.Item> =
        stream.items(_streamAgents.value, nowSec)

    fun toggleStreamsExpanded() {
        stream.toggleExpanded()
        publishStream()
    }

    /** Leaving the session: every stream handle goes with it. */
    fun stopStreamPolling() {
        agentStreamJob?.cancel(); agentStreamJob = null
        agentListJob?.cancel(); agentListJob = null
        stream.reset()
        _streamAgents.value = emptyList()
        publishStream()
    }

    private val _loadingAgentHistory = MutableStateFlow(false)
    val loadingAgentHistory: StateFlow<Boolean> = _loadingAgentHistory.asStateFlow()

    /** The picked agent's own history walk, the same shape as [loadEarlierTranscript]. */
    fun loadEarlierAgent(name: String) {
        val picked = stream.selected ?: return
        val until = stream.historyStart ?: stream.page?.windowStart ?: return
        if (until <= 0L || _loadingAgentHistory.value) return
        _loadingAgentHistory.value = true
        viewModelScope.launch {
            val page = runCatching { client.agentTranscript(name, picked, until = until) }
                .onFailure { if (stillReading(name)) _toast.value = errText(it) }
                .getOrNull()
            // The identical untracked-launch shape as [loadEarlierTranscript],
            // and the same rule: this page belongs to the session AND the agent
            // that asked for it.
            if (page != null && stillReading(name) && stream.selected == picked) {
                stream.prepend(page)
                publishStream()
            }
            if (stillReading(name)) _loadingAgentHistory.value = false
        }
    }

    fun sendText(name: String, text: String, thenEnter: Boolean) {
        viewModelScope.launch {
            slots.settle(sessionDraftKey(name))
            sendTextNow(name, text, thenEnter)
        }
    }

    private fun sendTextNow(name: String, text: String, thenEnter: Boolean) {
        // The staged attachments ride this message, same contract as a chat send:
        // consumed here (and only if staged for THIS session) so they cannot ride
        // twice or cross surfaces. Claude in the pane reads the paths like any file.
        //
        // composeMessage is :core's — the same join the desktop makes. It used to
        // be a hand-rolled "\n\n" here and again in sendNow, which is how the
        // marker join came to have three implementations and one separator rule.
        val taken = slots.take(sessionDraftKey(name))
        @Suppress("NAME_SHADOWING") val text = composeMessage(text, taken.markers)
        // Sent WITHOUT the ones that failed, and they are named: refusing to send
        // because one upload of five failed costs the typed message too.
        AttachBatch.failureLine(taken.failed, taken.markers.size + taken.failed.size)
            ?.let { _toast.value = it }
        if (text.isBlank()) return
        // The attached page, taken here so it rides ONE message. The daemon
        // composes the reference itself — the pane gets a path, not the page.
        val padKey = ScratchpadRules.sessionRefKey(name)
        val padId = padRefFor(padKey)
        clearDraft(sessionDraftKey(name))
        setPadRef(padKey, null)
        viewModelScope.launch {
            runCatching {
                client.sendKeys(
                    name,
                    text = text,
                    keys = if (thenEnter) listOf("Enter") else emptyList(),
                    scratchpadId = padId,
                )
            }.onSuccess { result ->
                // A send into a BUSY session is queued and delivered at the next
                // turn boundary. Seeded from the send's own answer rather than
                // waited for from the first poll: two seconds of a composer that
                // emptied with no explanation is the whole complaint.
                noteSend(name, result)
            }.onFailure {
                _toast.value = errText(it)
                // A refused send hands everything back: the composer was emptied
                // on press, so without this the text and the attached page existed
                // nowhere but a toast. Appended, never clobbered — newer typing
                // outranks the restore, same as the chat path.
                val key = sessionDraftKey(name)
                val cur = _drafts.value[key].orEmpty()
                setDraft(key, if (cur.isBlank()) text else cur + "\n" + text)
                if (padId != null && padRefFor(padKey) == null) setPadRef(padKey, padId)
            }
        }
    }

    // ------------------------------------------------------- the send queue
    //
    // The daemon holds a session send until the turn it would land in has ended.
    // Everything here is about SAYING SO: the facts were already on the wire and
    // no client read any of them, so a queued message read as a lost one.

    private val _typing = MutableStateFlow<Map<String, com.silencelen.huginn.data.TypingState>>(emptyMap())

    /** What the daemon is holding, per session. Absent means nothing is waiting. */
    val typing: StateFlow<Map<String, com.silencelen.huginn.data.TypingState>> = _typing.asStateFlow()

    /** The composer's one-line status for a session, or null. */
    fun queueNote(name: String): String? = SendQueue.note(_typing.value[name])

    private fun setTyping(name: String, state: com.silencelen.huginn.data.TypingState?) {
        _typing.value = _typing.value.toMutableMap().apply {
            if (state == null) remove(name) else put(name, state)
        }
    }

    private fun noteSend(name: String, result: com.silencelen.huginn.data.SendKeysResult) {
        setTyping(name, SendQueue.seed(result))
    }

    private var typingJob: Job? = null

    /**
     * Follows the queue until it drains. LIFECYCLE-SCOPED by its caller.
     *
     * The REQUEST is made only while something is actually waiting — an idle
     * session must not cost a round trip every two seconds for an answer that is
     * always zero — but the loop itself keeps ticking, because the next send can
     * queue at any moment and a poll that stopped for good would never notice.
     */
    fun startTypingPolling(name: String) {
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            awaitReady()
            while (isActive) {
                if ((_typing.value[name]?.queued ?: 0) > 0) {
                    runCatching { client.typingStatus(name) }
                        .onSuccess { st ->
                            // DRAINED CLEARS IT. A state with nothing queued and no
                            // error is the absence of a queue, not a queue of zero,
                            // and keeping the row would leave "(0 waiting)" under a
                            // composer that is working perfectly.
                            setTyping(name, st.takeIf { SendQueue.note(it) != null })
                        }
                }
                delay(2_000)
            }
        }
    }

    fun stopTypingPolling() {
        typingJob?.cancel()
        typingJob = null
    }

    // ------------------------------------------------ live typing (ordered)
    //
    // One queue, one drainer. viewModelScope runs on the main dispatcher, so
    // enqueue and drain never race; the drainer merges bursts into single
    // requests and sends them SEQUENTIALLY — the per-keystroke launch it
    // replaces could reorder characters in flight.
    /**
     * Queued keystrokes, each tagged with the session it was typed into.
     *
     * The tag is the whole point. The drainer used to capture `name` from
     * whichever call happened to start it, while later calls enqueued into this
     * same deque and returned early because a drainer was already running — so
     * typing in session A, switching to B, and typing again sent B's keystrokes
     * into A's pane. Arbitrary text into the wrong live Claude Code session,
     * which can answer a prompt or run something the reader never saw.
     */
    private val liveOps = ArrayDeque<Pair<String, LiveInput.Op>>()
    private var liveDrainer: Job? = null

    /**
     * Set when the session being viewed stops existing, so the UI can close its
     * view instead of leaving the reader on a dead screen.
     */
    private val _sessionGone = MutableStateFlow<String?>(null)
    val sessionGone: StateFlow<String?> = _sessionGone.asStateFlow()
    fun sessionGoneHandled() { _sessionGone.value = null }

    /**
     * Suggested next messages for the open session. Fetched when a turn ends —
     * detected as the transcript growing while the session is not running — and
     * cleared the moment a new turn starts, because suggestions for the previous
     * reply are stale the instant there is a newer one coming.
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()
    private var suggestedForOffset = -1L
    private var suggestJob: Job? = null

    fun maybeSuggest(name: String, page: TranscriptPage?, working: Boolean) {
        if (working) {
            if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
            return
        }
        val offset = page?.nextOffset ?: return
        if (offset == suggestedForOffset || suggestJob?.isActive == true) return
        suggestedForOffset = offset
        suggestJob = viewModelScope.launch {
            runCatching { client.sessionSuggestions(name) }
                .onSuccess { _suggestions.value = it.suggestions }
                .onFailure { /* suggestions are a nicety; silence is the right failure */ }
        }
    }

    /** The chat-side twin of [maybeSuggest]; same flow, same rules. */
    fun maybeSuggestChat(id: String, page: TranscriptPage?, busy: Boolean) {
        if (busy) {
            if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
            return
        }
        val offset = page?.nextOffset ?: return
        if (offset == suggestedForOffset || suggestJob?.isActive == true) return
        suggestedForOffset = offset
        suggestJob = viewModelScope.launch {
            runCatching { client.chatSuggestions(id) }
                .onSuccess { _suggestions.value = it.suggestions }
                .onFailure { /* suggestions are a nicety; silence is the right failure */ }
        }
    }

    fun renameChat(id: String, title: String) {
        viewModelScope.launch {
            runCatching { client.renameChat(id, title.trim()) }
                .onSuccess { refreshChats(); openChat(id) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        suggestedForOffset = -1L
    }

    /** Host-side automatic account rotation. */
    private val _autoswitch = MutableStateFlow<Autoswitch?>(null)
    val autoswitch: StateFlow<Autoswitch?> = _autoswitch.asStateFlow()

    fun refreshAutoswitch() {
        viewModelScope.launch {
            runCatching { client.autoswitch() }.onSuccess { _autoswitch.value = it }
        }
    }

    fun setAutoswitch(on: Boolean) {
        viewModelScope.launch {
            runCatching { client.setAutoswitch(on) }
                .onSuccess {
                    _autoswitch.value = (_autoswitch.value ?: Autoswitch()).copy(enabled = it.enabled)
                    _toast.value = if (it.enabled)
                        "huginn will rotate accounts when one runs out"
                    else "Automatic switching off"
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /** Agents for the open work sheet; polled only while the sheet is up. */
    private val _agents = MutableStateFlow<AgentsInfo?>(null)
    val agents: StateFlow<AgentsInfo?> = _agents.asStateFlow()
    private var agentsJob: Job? = null

    fun startAgentsPolling(name: String) {
        agentsJob?.cancel()
        agentsJob = viewModelScope.launch {
            while (isActive) {
                runCatching { client.sessionAgents(name) }.onSuccess { _agents.value = it }
                delay(3000)
            }
        }
    }

    fun stopAgentsPolling() {
        agentsJob?.cancel()
        agentsJob = null
        _agents.value = null
    }

    fun sendLive(name: String, op: LiveInput.Op) {
        liveOps.addLast(name to op)
        if (liveDrainer?.isActive == true) return
        liveDrainer = viewModelScope.launch {
            // A beat for the burst to accumulate: keystrokes arrive faster than
            // round trips complete, and merging them is the point.
            delay(15)
            while (liveOps.isNotEmpty()) {
                val batch = liveOps.toList()
                liveOps.clear()
                // Split into runs of consecutive ops for the SAME session, then
                // merge within each run. Merging across the boundary would fuse
                // two sessions' keystrokes into one string; sending the whole
                // batch to one name would deliver them to the wrong pane.
                var i = 0
                while (i < batch.size) {
                    val target = batch[i].first
                    var j = i
                    while (j < batch.size && batch[j].first == target) j++
                    val ops = LiveInput.merge(batch.subList(i, j).map { it.second })
                    for (m in ops) {
                        runCatching {
                            when (m) {
                                is LiveInput.Op.Text -> client.sendKeys(target, text = m.text)
                                is LiveInput.Op.Key -> client.sendKeys(target, keys = m.keys)
                            }
                        }.onFailure { _toast.value = errText(it) }
                    }
                    i = j
                }
            }
        }
    }

    fun sendKeys(name: String, keys: List<String>) {
        viewModelScope.launch {
            runCatching { client.sendKeys(name, keys = keys) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * ⚠ NO CALLER IN THIS APP SINCE 2026-09-15, AND DELIBERATELY KEPT.
     *
     * The owner's decision 23 replaced the session's answerable cards with a
     * one-line steer to the Screen tab, so nothing in the UI taps an option any
     * more — the reader answers in the pane, which handles every prompt type. The
     * lock-screen notification buttons still answer, through their OWN client in
     * `AnswerReceiver` (a broadcast receiver has no view model), so this and
     * [answerPrompt] are the in-app half of `POST /answer`: the fingerprint, the
     * 409 vocabulary and the toast wording. Deleting them and writing them again
     * later is how the fingerprint rule gets lost.
     */
    fun answerPromptMulti(name: String, options: List<Int>, fingerprint: String?) {
        viewModelScope.launch {
            runCatching { client.answerPromptMulti(name, options, fingerprint) }
                .onSuccess { r ->
                    _toast.value = if (r.ok) "Answered: ${r.labels?.joinToString(", ") ?: options.joinToString(", ")}"
                    else r.error ?: "Could not answer"
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Answers a detected choice prompt through the GUARDED endpoint.
     *
     * This used to type the bare digit with sendKeys — no fingerprint, no check —
     * while the lock-screen notification used the guarded path. Exactly backwards:
     * the host refuses a stale answer precisely because the pane can move on
     * between being read and being typed into, and the in-app card is the MOST
     * exposed to that, since it renders a polled screen that may be seconds old.
     * A digit landing in a prompt the reader never saw can accept something they
     * never agreed to, which is the whole reason the guard exists.
     */
    fun answerPrompt(name: String, number: Int, fingerprint: String? = null) {
        viewModelScope.launch {
            runCatching { client.answerPrompt(name, number, fingerprint) }
                .onSuccess { r ->
                    // 409 arrives as a failure; a false `ok` is the host declining
                    // for its own reason. Either way the reader is told, rather
                    // than left believing a tap landed.
                    if (!r.ok) _toast.value = r.error ?: "The question moved on — check the session"
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    // --------------------------------------------------------------- chat

    private val _chatPage = MutableStateFlow<TranscriptPage?>(null)
    val chatPage: StateFlow<TranscriptPage?> = _chatPage.asStateFlow()

    private val _chatMode = MutableStateFlow("ask")
    val chatMode: StateFlow<String> = _chatMode.asStateFlow()

    /**
     * Whether this chat HAS HISTORY — the daemon's pin condition, and what
     * decides which model rows its menu may offer. Optimistically true once a
     * send is in flight; staying true a moment too long only narrows a menu,
     * where the opposite offers rows the daemon will 409.
     */
    private val _chatStarted = MutableStateFlow(false)
    val chatStarted: StateFlow<Boolean> = _chatStarted.asStateFlow()

    /**
     * A LOCAL chat's pre-first-token cue: the silence is the model LOADING —
     * up to ~30s cold — not a hang. Set at send, cleared by the first token.
     */
    private val _chatWaking = MutableStateFlow(false)
    val chatWaking: StateFlow<Boolean> = _chatWaking.asStateFlow()

    /**
     * Whether the open chat is a finished Round run.
     *
     * Read from the chat's own meta, NOT from the chats list: a Round's runs are
     * deliberately absent from that list, so looking them up there would find
     * nothing and every sealed run would render as still open.
     */
    private val _chatSealed = MutableStateFlow(false)
    val chatSealed: StateFlow<Boolean> = _chatSealed.asStateFlow()

    private val _chatModel = MutableStateFlow<String?>(null)
    val chatModel: StateFlow<String?> = _chatModel.asStateFlow()

    private val _chatEffort = MutableStateFlow<String?>(null)
    val chatEffort: StateFlow<String?> = _chatEffort.asStateFlow()

    private val _chatTitle = MutableStateFlow<String?>(null)
    val chatTitle: StateFlow<String?> = _chatTitle.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _activeTool = MutableStateFlow<String?>(null)
    val activeTool: StateFlow<String?> = _activeTool.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /**
     * Why the chat transcript could not be loaded, when it could not.
     *
     * Every failure used to render the pristine "Ask mode / Act mode" empty state,
     * so a timeout on a chat with months of history looked exactly like a chat that
     * had never run — the worst possible confusion, because it reads as data loss.
     */
    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()

    /**
     * The daemon has no transcript for a chat that HAS run: Claude Code swept it.
     *
     * A separate fact from [chatError], because it is not a failure and there is
     * nothing to retry — and a separate fact from an empty page, because a chat
     * that never ran is empty too and means the opposite thing. See [chatEmptyCopy].
     */
    private val _chatGone = MutableStateFlow(false)
    val chatGone: StateFlow<Boolean> = _chatGone.asStateFlow()

    private var streamJob: Job? = null
    private var chatPollJob: Job? = null

    /**
     * The chat this view model is addressing. Set SYNCHRONOUSLY at the top of
     * [openChat], before its first suspension, so a teardown arriving from the
     * outgoing screen's recomposition can tell whether it is still the one on
     * screen — see [detachStream].
     */
    private var openChatId: String? = null

    fun openChat(id: String) {
        openChatId = id
        _chatPage.value = null
        _streamingText.value = null
        _activeTool.value = null
        _chatError.value = null
        _chatGone.value = false
        // The model menu must reflect which machines serve RIGHT NOW.
        refreshModels()
        viewModelScope.launch {
            val meta = runCatching { client.chat(id) }.getOrNull()
            _chatMode.value = meta?.mode ?: "ask"
            _chatModel.value = meta?.model
            _chatStarted.value = (meta?.turns ?: 0) > 0 || meta?.claudeSessionId != null
            _chatEffort.value = meta?.effort
            _chatTitle.value = meta?.title
            _chatSealed.value = meta?.closed == true
            // A chat that has never run has no transcript yet; that is not an error.
            loadChatTranscript(id)
            attachIfRunning(id, meta)
        }
    }

    /** Follows an in-flight run, seeding the bubble with what it has already said. */
    private fun attachIfRunning(id: String, meta: ChatDetail?) {
        val plan = reattachPlan(meta) ?: return
        _streamingText.value = plan.seed
        _sending.value = true
        collect(id, client.streamChat(id, since = plan.since))
    }

    private fun loadChatTranscript(id: String) {
        chatPollJob?.cancel()
        chatPollJob = viewModelScope.launch {
            runCatching { client.chatTranscript(id) }
                .onSuccess { _chatPage.value = it; _chatError.value = null }
                .onFailure { e ->
                    // A cancellation is this job being replaced or the screen
                    // going away, not a chat that would not load. Drawn as one,
                    // it opened a perfectly good chat under "Could not load this
                    // conversation / StandaloneCoroutine was cancelled".
                    if (e is CancellationException) return@onFailure
                    // 409 is the only failure that means the daemon has nothing to
                    // GIVE. Anything else is a failure to read history that exists,
                    // and must not be drawn as its absence.
                    //
                    // ⚠ AND 409 IS TWO FACTS. The route answers it both for
                    // "chat has not run yet" and for "transcript not found for this
                    // chat" — the second being Claude Code having swept its own
                    // JSONL, which is what a 53-day-old chat hits. Whether this is
                    // an absence or a loss is decided by whether the chat ever ran.
                    val refused = e is HuginnClient.HuginnException && e.code == 409
                    if (_chatPage.value == null) {
                        if (refused) {
                            _chatPage.value = TranscriptPage()
                            _chatGone.value = chatMessagesGone(_chatStarted.value, true)
                        } else _chatError.value = errText(e)
                    }
                }
        }
    }

    /** Retries the transcript load after a failure the user can see. */
    fun retryChatTranscript(id: String) {
        _chatError.value = null
        _chatGone.value = false
        loadChatTranscript(id)
    }

    fun setChatOptions(id: String, model: String? = null, effort: String? = null, mode: String? = null) {
        viewModelScope.launch {
            runCatching { client.updateChat(id, model = model, effort = effort, mode = mode) }
                .onSuccess {
                    _chatMode.value = it.mode
                    _chatModel.value = it.model
                    _chatEffort.value = it.effort
                    refreshChats()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * @param host a device id, or null for this host.
     *
     * The daemon refuses at CREATION if that machine is asleep or too narrowly
     * scoped, and its refusal names which — so it is surfaced as-is rather than
     * being rewritten into something vaguer here.
     */
    /**
     * Carries on from a finished Round, in a fresh chat.
     *
     * Same mode, same machine, same model as the Round, because acting on its
     * report means doing the thing it was watching — on the box it was watching.
     * The report lands as a DRAFT, never a sent message: a Round can be `act`, and
     * sending on a tap meant to read something would start unattended work.
     */
    fun continueRound(round: com.silencelen.huginn.data.Round, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            awaitReady()
            runCatching {
                client.createChat(
                    mode = round.mode,
                    model = round.model,
                    effort = round.effort,
                    host = round.host.takeIf { it != "local" },
                )
            }.onSuccess { c ->
                setDraft(chatDraftKey(c.id), followUpDraft(round))
                refreshChats()
                onCreated(c.id)
            }.onFailure { _toast.value = errText(it) }
        }
    }

    fun newChat(mode: String, host: String? = null, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { client.createChat(mode, host = host) }
                .onSuccess { refreshChats(); onCreated(it.id) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * A chat on a serving machine, in one tap: the model row IS the machine
     * choice, and the daemon forces ask. The refusal (machine just went
     * offline) surfaces as-is — it names the machine since appd 2.78.0.
     */
    /**
     * The user-driven half of the conduits: the local conversation lands as a
     * DRAFT in a NEW Claude chat, for the person to read, edit and send. The
     * local chat is untouched — its transcript lives on its machine.
     */
    fun escalateLocalChat(onOpened: (String) -> Unit) {
        val label = ModelLabels.model(_chatModel.value, _models.value)
        val turns = (_chatPage.value?.events ?: emptyList())
            .filter { (it.kind == "user" || it.kind == "assistant") && !it.sidechain }
            .mapNotNull { e -> e.text?.let { t -> (if (e.kind == "user") "User" else "Assistant") to t } }
        viewModelScope.launch {
            runCatching { client.createChat("ask") }
                .onSuccess {
                    setDraft(chatDraftKey(it.id), com.silencelen.huginn.ui.Escalation.draft(label, turns))
                    refreshChats()
                    onOpened(it.id)
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun newLocalChat(modelId: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { client.createChat("ask", model = modelId) }
                .onSuccess { refreshChats(); onCreated(it.id) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            runCatching { client.deleteChat(id) }
                .onSuccess {
                    // The draft outlives nothing: the persisted map is rewritten
                    // whole on every keystroke, so orphans are paid for forever.
                    clearDraft(chatDraftKey(id))
                    clearAttachment(chatDraftKey(id))
                    _toast.value = "Chat deleted"; refreshChats()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Sends, or queues if a run is already going: the server holds it and
     * delivers when that run ends, so the composer never dead-ends the way it
     * used to when a chat was busy.
     */
    fun send(id: String, text: String) {
        viewModelScope.launch {
            slots.settle(chatDraftKey(id))
            sendNow(id, text)
        }
    }

    private fun sendNow(id: String, text: String) {
        // The staged attachments ride this message — but only if they were staged
        // for THIS chat. Consumed here, whichever path the send takes (stream or
        // queue), so they cannot ride two messages. The join is :core's, shared
        // with the desktop.
        val taken = slots.take(chatDraftKey(id))
        @Suppress("NAME_SHADOWING") val text = composeMessage(text, taken.markers)
        AttachBatch.failureLine(taken.failed, taken.markers.size + taken.failed.size)
            ?.let { _toast.value = it }
        if (text.isBlank()) return
        // The attached page. Named, never pasted: the daemon composes the frame so
        // a queued message is a snapshot of what the page said when Send was
        // pressed rather than what it says when the queue drains.
        val padKey = ScratchpadRules.chatRefKey(id)
        val padId = padRefFor(padKey)
        if (_sending.value) {
            viewModelScope.launch {
                // Cleared only when the queue ACCEPTS: a refused send must not
                // cost the typed message — the audit caught a 409 destroying it
                // with nothing left but a transient snackbar. The reference goes
                // with it, for the same reason.
                runCatching { client.queueMessage(id, text, scratchpadId = padId) }
                    .onSuccess {
                        clearDraft(chatDraftKey(id)); setPadRef(padKey, null)
                        loadChatTranscript(id); refreshChats()
                    }
                    .onFailure { _toast.value = errText(it) }
            }
            return
        }
        clearDraft(chatDraftKey(id))
        setPadRef(padKey, null)
        _sending.value = true
        _chatStarted.value = true
        _chatWaking.value = ModelLabels.isLocal(_chatModel.value, _models.value)
        _streamingText.value = ""
        _activeTool.value = null
        // The page reference travels with the text on the way back too: a refused
        // send that restores the words but forgets the page is a message that
        // silently loses its attachment, and the second attempt sends without it.
        collect(
            id,
            client.sendMessage(id, text, scratchpadId = padId),
            sentText = text,
            sentPadId = padId,
        )
    }

    /** Interrupts a running session the way Esc does at the keyboard. */
    fun interruptSession(name: String) {
        viewModelScope.launch {
            runCatching { client.sendKeys(name, keys = listOf("Escape")) }
                .onSuccess { _toast.value = "Sent Esc to $name" }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch {
            runCatching { client.cancelChat(id) }.onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Detaches the stream WITHOUT cancelling the server-side run.
     *
     * ⚠ PASS THE CHAT BEING TORN DOWN. Every chat-to-chat hop calls openChat(new)
     * and then moves the destination in one callback, while the recomposition
     * that disposes the OUTGOING DisposableEffect(id) waits for the next vsync —
     * 8-16 ms, and a warm daemon GET measures 1-2 ms. So the new chat had already
     * loaded when the old screen's onDispose fired and wiped _chatPage, _sending
     * and _streamingText, leaving an indefinite spinner with no "Try again"; if
     * the target was mid-run its reattach went too. A teardown keyed to no chat
     * cannot tell that it is tearing down someone else's.
     *
     * Null [chat] means "no chat at all" — leaving the chat surface entirely.
     */
    fun detachStream(chat: String? = null) {
        if (!detachWanted(chat, openChatId)) return
        openChatId = null
        streamJob?.cancel()
        streamJob = null
        chatPollJob?.cancel()
        chatPollJob = null
        _sending.value = false
        _streamingText.value = null
        _activeTool.value = null
        _chatPage.value = null
    }

    private fun collect(
        id: String,
        flow: kotlinx.coroutines.flow.Flow<ChatEvent>,
        sentText: String? = null,
        sentPadId: String? = null,
    ) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            // A Failure as the VERY FIRST event is an HTTP refusal — the daemon
            // said no before any run existed — and gets its own honest handling.
            var sawStream = false
            flow.collect { ev ->
                if (ev !is ChatEvent.Failure) sawStream = true
                when (ev) {
                    is ChatEvent.Started -> Unit
                    is ChatEvent.Delta -> {
                        _chatWaking.value = false
                        _streamingText.value = (_streamingText.value ?: "") + ev.text
                    }
                    is ChatEvent.Assistant -> {
                        // The block is complete and now in the transcript, which is
                        // the richer source: reload rather than keeping a second copy.
                        _streamingText.value = ""
                        _activeTool.value = null
                        loadChatTranscript(id)
                    }
                    is ChatEvent.ToolStart -> _activeTool.value = ev.name
                    is ChatEvent.Tool -> {
                        _activeTool.value = null
                        loadChatTranscript(id)
                    }
                    is ChatEvent.Result -> {
                        _streamingText.value = null
                        loadChatTranscript(id)
                    }
                    is ChatEvent.Failure -> {
                        _chatWaking.value = false
                        _toast.value = ev.text
                        _streamingText.value = null
                        // The tool is not running for US any more, whatever it is
                        // doing on huginn. Left set, `streaming` stayed true and the
                        // view showed a spinner for a tool that had long finished.
                        _activeTool.value = null
                        if (!sawStream) {
                            // Refused at the door: no run exists, nothing will
                            // land. The typed message goes back to the composer
                            // it was cleared from a moment earlier — and so does
                            // the page it was carrying, which is part of the
                            // message as far as the person who attached it is
                            // concerned.
                            _sending.value = false
                            sentText?.let { setDraft(chatDraftKey(id), it) }
                            sentPadId?.let { setPadRef(ScratchpadRules.chatRefKey(id), it) }
                        }
                    }
                    ChatEvent.Done -> {
                        _chatWaking.value = false
                        _sending.value = false
                        _streamingText.value = null
                        _activeTool.value = null
                        loadChatTranscript(id)
                        refreshChats()
                    }
                }
            }
            // The flow ended. `done` is the ordinary reason; a dropped socket is the
            // other, and nothing else polls a chat, so the answer would finish
            // server-side while the screen sat frozen until the user navigated out
            // and back in. Ask the server whether the run is still going and pick it
            // back up if it is — the same path a cold open takes, so there is one
            // reattach to keep correct.
            resumeIfStillRunning(id)
        }
    }

    /**
     * Reattaches after a stream ends with the run unfinished, backing off between
     * attempts.
     *
     * Bounded, because a chat whose server-side run is wedged must not turn the
     * phone into a reconnect loop; after the last try `sending` is released so the
     * composer works again, which is the state the user can act from.
     */
    private suspend fun resumeIfStillRunning(id: String) {
        var wait = 1_000L
        repeat(CHAT_REATTACH_TRIES) {
            val meta = runCatching { client.chat(id) }.getOrNull()
            if (meta == null) {
                delay(wait); wait = (wait * 2).coerceAtMost(8_000L)
                return@repeat
            }
            if (meta.running != true) {
                _sending.value = false
                loadChatTranscript(id)
                return
            }
            attachIfRunning(id, meta)      // replaces streamJob; this coroutine ends
            return
        }
        _sending.value = false
    }

    companion object {
        /** Newest events kept in memory for one session view. Shared with the desktop. */
        private const val MAX_EVENTS = MAX_TRANSCRIPT_EVENTS

        /** Reattach attempts after a chat stream drops with the run still going. */
        private const val CHAT_REATTACH_TRIES = 4

        /**
         * How often headroom is re-read while the app is in front of somebody.
         *
         * Thirty seconds, the rate the design costed: the daemon serves it from a
         * file it already keeps, and a usage window does not move faster than
         * this. The sessions list ticks at five because a session appearing is a
         * thing you watch for; a percentage is not.
         */
        const val HEADROOM_POLL_MS: Long = 30_000

        /**
         * How often the agent LIST behind the stream picker is re-read.
         *
         * Slower than the transcript tail: a new subagent is a rarer event than a
         * new line from one, and `?all=1` lifts the recency filter, so the answer
         * grows rather than churning.
         */
        const val AGENTS_POLL_MS: Long = 10_000

        /**
         * The one expected reason the stream strip is disabled — the daemon is
         * older than 3.0.0 and has no agent-transcript route at all. A literal,
         * because the test asserts the sentence a reader will actually see.
         */
        const val STREAMS_UNSUPPORTED: String = "needs appd 3.0"

        fun sessionDraftKey(name: String) = "sess:$name"
        fun chatDraftKey(id: String) = "chat:$id"

        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return HuginnViewModel(app) as T
            }
        }
    }
}

// --------------------------------------------------------------- attachments

/**
 * One thing staged for a composer's next message.
 *
 * @param owner WHOSE it is — a chat's draft key or a session's. The slots are one
 *   global list, and before ownership existed a photo staged on one screen could
 *   ride a send from another: stage in chat A, hop to chat B before A's dispose
 *   ran, send — B's message carried A's photo. Every read names the surface
 *   asking, and a mismatch reads as absent.
 * @param path where the daemon put it; non-null only once it is [AttachChipState.READY],
 *   because a marker for bytes that did not land is worse than no attachment.
 */
data class PendingAttachment(
    val id: String,
    val owner: String,
    val label: String,
    val image: Boolean,
    val state: AttachChipState,
    val bytes: Long? = null,
    val path: String? = null,
    /** Original filename, for the chip and the marker; null for photos. */
    val name: String? = null,
    /** Host's verdict on whether Read can open it; drives the marker. */
    val readable: Boolean = true,
    val detail: String? = null,
)

/** The marker line this attachment contributes, or null while it has no path. */
fun PendingAttachment.marker(): String? = path?.let {
    if (image) AttachmentText.marker(it) else AttachmentText.fileMarker(it, name, readable)
}

/** What one composer draws, in attach order. */
fun chipsFor(items: List<PendingAttachment>, owner: String): List<AttachChipItem> =
    items.filter { it.owner == owner }.map {
        AttachChipItem(it.id, it.label, it.image, it.state, it.bytes, it.detail)
    }

/**
 * The phone's staged attachments: ordered, owned, capped — and testable.
 *
 * Deliberately a plain class rather than view-model methods. `HuginnViewModel` is
 * an `AndroidViewModel` and this host has no device and no `/dev/kvm`, so
 * anything that needs an `Application` cannot be asserted at all; the slot logic
 * that shipped before this (`takeAttachment`, `whenAttachmentSettled`, the owner
 * guard) had ZERO tests for exactly that reason.
 */
class AttachmentSlots {

    private val _items = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val items: StateFlow<List<PendingAttachment>> = _items.asStateFlow()

    /**
     * Every mutation here is read-modify-write on one list, and the writers are
     * a composer on the main thread and N uploads finishing on IO. Without this
     * two uploads settling at once can each publish a copy of the list taken
     * before the other's edit, and one chip silently reverts to UPLOADING.
     */
    private val lock = Any()

    fun countFor(owner: String): Int = _items.value.count { it.owner == owner }

    /**
     * Stages a new item and returns its id — or null when this composer is full.
     * The cap is [AttachBatch.MAX_ITEMS], the same number on both shells.
     */
    fun stage(owner: String, label: String, image: Boolean, bytes: Long? = null): String? = synchronized(lock) {
        if (AttachBatch.room(countFor(owner)) <= 0) return null
        val id = "a-" + (nextId++).toString(16)
        _items.value = _items.value + PendingAttachment(
            id = id, owner = owner, label = label, image = image,
            state = AttachChipState.UPLOADING, bytes = bytes,
        )
        return id
    }

    /** The real name and size, once a provider query has answered for them. */
    fun describe(id: String, label: String, bytes: Long?) = update(id) {
        it.copy(label = label, bytes = bytes ?: it.bytes)
    }

    fun ready(id: String, path: String, name: String?, image: Boolean, readable: Boolean, bytes: Long?) =
        update(id) {
            it.copy(
                state = AttachChipState.READY,
                path = path,
                name = name ?: it.name,
                image = image,
                readable = readable,
                bytes = bytes?.takeIf { b -> b > 0 } ?: it.bytes,
                detail = if (readable) null else "binary — Claude will need act mode to inspect it",
            )
        }

    fun fail(id: String, why: String) = update(id) {
        it.copy(state = AttachChipState.FAILED, detail = why)
    }

    fun remove(id: String) = synchronized(lock) {
        _items.value = _items.value.filterNot { it.id == id }
    }

    /** Clears one composer's, or everyone's when [owner] is null. */
    fun clear(owner: String?) = synchronized(lock) {
        _items.value = if (owner == null) emptyList() else _items.value.filterNot { it.owner == owner }
    }

    /**
     * Everything [owner] staged, consumed atomically.
     *
     * READY markers come back IN ATTACH ORDER and everything else comes back as a
     * label, so the composer can send what landed and name what did not. The slot
     * version could only return the one Ready item and left a failed one staged,
     * which is how a failed photo used to ride the NEXT message.
     */
    fun take(owner: String): TakeResult = synchronized(lock) {
        val mine = _items.value.filter { it.owner == owner }
        _items.value = _items.value.filterNot { it.owner == owner }
        return TakeResult(
            markers = mine.filter { it.state == AttachChipState.READY }.mapNotNull { it.marker() },
            failed = mine.filter { it.state != AttachChipState.READY }.map { it.label },
        )
    }

    /**
     * Suspends until nothing of [owner]'s is still in flight.
     *
     * Sending while a chip still said "Uploading…" used to drop the photo
     * silently. Attaching something is a statement of intent about THIS message,
     * so the send waits for it — under ONE budget for the whole batch, not
     * [budgetMs] per item: ten files settled one at a time could hold the composer
     * for three minutes, which is the wedged-socket case the timeout exists to
     * prevent. Past it the message goes as text, which is at least visible and
     * recoverable.
     */
    suspend fun settle(owner: String, budgetMs: Long = SETTLE_TIMEOUT_MS) {
        if (!inFlight(owner)) return
        kotlinx.coroutines.withTimeoutOrNull(budgetMs) {
            items.first { !inFlight(owner) }
        }
    }

    private fun inFlight(owner: String): Boolean = _items.value.any {
        it.owner == owner &&
            (it.state == AttachChipState.UPLOADING || it.state == AttachChipState.QUEUED)
    }

    private fun update(id: String, edit: (PendingAttachment) -> PendingAttachment) = synchronized(lock) {
        _items.value = _items.value.map { if (it.id == id) edit(it) else it }
    }

    private var nextId: Long = 1

    companion object {
        /** The whole-batch wait a send will do for uploads still in flight. */
        const val SETTLE_TIMEOUT_MS: Long = 20_000
    }
}
