package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Plan
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which of the four destinations the window is showing. */
enum class View { CHATS, SESSIONS, STATUS, SETTINGS }

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
     * The self-updater. Deliberately NOT handed [settings]' base URL: its feed is
     * pinned in UpdateFeed, because these builds are unsigned and whoever
     * controls the feed controls what runs on this machine. It downloads and
     * verifies; INSTALLING is a button, never a background decision.
     */
    val updater = DesktopUpdater(tokenProvider = { settings.tokenNow() })

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

    init {
        current = this
    }

    // ------------------------------------------------------------ navigation

    private val _view = MutableStateFlow(View.CHATS)
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

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    /** Null until the first fetch settles, so a cold start never claims "no chats". */
    private val _listsLoaded = MutableStateFlow(false)
    val listsLoaded: StateFlow<Boolean> = _listsLoaded.asStateFlow()

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _plan = MutableStateFlow<Plan?>(null)
    val plan: StateFlow<Plan?> = _plan.asStateFlow()

    private val _usage = MutableStateFlow<Usage?>(null)
    val usage: StateFlow<Usage?> = _usage.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

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
            .onSuccess { _chats.value = it; _listsLoaded.value = true }
            .onFailure { note(it) }
    }

    suspend fun refreshSessions() {
        // preview=1: the list rows show what each session is doing, which is the
        // only thing that makes the list worth reading at a glance.
        runCatching { client.sessions(preview = true) }
            .onSuccess { _sessions.value = it }
            .onFailure { note(it) }
    }

    suspend fun refreshStatus() {
        runCatching { client.status() }.onSuccess { _status.value = it }.onFailure { note(it) }
        runCatching { client.plan() }.onSuccess { _plan.value = it }
        runCatching { client.usage() }.onSuccess { _usage.value = it }
    }

    private fun note(t: Throwable) {
        // A CANCELLATION IS NOT A FAULT. `pollLoop` and `watchLoop` both hang off
        // `collectLatest`, which cancels the in-flight refresh every time presence
        // flips — so walking away from the desk and back reliably put
        // "Child of the scoped flow was cancelled" on screen, where it said nothing
        // to the reader and sat on top of any real error underneath it. Caught here
        // rather than at each call site because every one of them uses
        // `runCatching`, which does not spare CancellationException either.
        if (t is kotlinx.coroutines.CancellationException) return
        _error.value = when (t) {
            is HuginnClient.HuginnException -> t.message
            else -> t.message ?: "network error"
        }
    }

    /**
     * The same reporting path, for calls the SHELL makes rather than the poll loop:
     * rename, delete, interrupt, end a session. Those go straight to the client
     * from a context-menu item, and without this a failed one does nothing at all —
     * the row stays, the reason is swallowed, and the reader is left to guess
     * whether the click even registered.
     */
    fun noteError(t: Throwable) = note(t)

    // ------------------------------------------------------------- lifecycle

    /**
     * Starts the three long-lived loops. Called once, from the window's
     * composition; each loop lives as long as [scope].
     */
    fun start() {
        scope.launch { drafts.load() }
        scope.launch { resolveRoute() }
        scope.launch { pollLoop() }
        scope.launch { watchLoop() }
        scope.launch { presenceTicker() }
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
                refreshChats()
                refreshSessions()
                if (_view.value == View.STATUS) refreshStatus()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun presenceTicker() {
        while (scope.isActive) {
            presence.tick()
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
