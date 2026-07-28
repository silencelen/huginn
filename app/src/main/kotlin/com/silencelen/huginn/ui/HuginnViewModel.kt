package com.silencelen.huginn.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.data.Usage
import com.silencelen.huginn.data.Alerts
import com.silencelen.huginn.data.ClientsInfo
import com.silencelen.huginn.data.PushStatus
import com.silencelen.huginn.data.LoginState
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.Heartbeat
import com.silencelen.huginn.notify.HuginnMessagingService
import com.silencelen.huginn.notify.WatchService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HuginnViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    private var baseUrlNow = SettingsStore.DEFAULT_BASE_URL
    private var tokenNow = ""

    private val client = HuginnClient(
        baseUrlProvider = { baseUrlNow },
        tokenProvider = { tokenNow },
    )

    // ---- shared UI state

    private val _baseUrl = MutableStateFlow(SettingsStore.DEFAULT_BASE_URL)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _statusError = MutableStateFlow<String?>(null)
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

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

    fun refreshAlerts() {
        viewModelScope.launch { runCatching { client.alerts() }.onSuccess { _alerts.value = it } }
    }

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
            runCatching { client.alerts() }.onSuccess { _alerts.value = it }
            runCatching { client.clients() }.onSuccess { _clients.value = it }
            runCatching { client.push() }.onSuccess { _push.value = it }
            _health.value = readHealth()
        }
    }

    /**
     * Sends a real push to this device.
     *
     * Deliberately the same path a genuine alert takes, right through Google, so a
     * success here means the whole chain works rather than that one link does.
     */
    fun sendTestPush() {
        viewModelScope.launch {
            runCatching { client.testPush() }
                .onSuccess {
                    _toast.value = if (it.ok) "Pushed to ${it.sent} device${if (it.sent == 1) "" else "s"}"
                        else (it.error ?: "No device accepted the push")
                    refreshDelivery()
                }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /** The app's own side of the story: when it last reached huginn, and how. */
    data class DeliveryHealth(
        val lastContactAt: Long = 0,
        val lastAlarmAt: Long = 0,
        val lastError: String = "",
        val lastErrorAt: Long = 0,
        val dozeExempt: Boolean = false,
    )

    private val _health = MutableStateFlow(DeliveryHealth())
    val health: StateFlow<DeliveryHealth> = _health.asStateFlow()

    private suspend fun readHealth() = DeliveryHealth(
        lastContactAt = settings.lastContactAt.first(),
        lastAlarmAt = settings.lastAlarmAt.first(),
        lastError = settings.lastWatchError.first(),
        lastErrorAt = settings.lastWatchErrorAt.first(),
        dozeExempt = Heartbeat.isExemptFromDoze(getApplication()),
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

    fun sendTestAlert() {
        viewModelScope.launch {
            runCatching { client.testAlert() }
                .onSuccess { _toast.value = "Test sent from huginn"; refreshAlerts() }
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

    fun refreshModels() {
        if (_models.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { client.models() }.onSuccess { _models.value = it }
        }
    }

    /**
     * Unsent composer text per target. Held here rather than in the composable so
     * it survives navigating away, and written through to storage (debounced) so
     * it survives the process being killed.
     */
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

    init {
        viewModelScope.launch {
            baseUrlNow = settings.baseUrl.first()
            tokenNow = settings.token.first()
            _baseUrl.value = baseUrlNow
            _token.value = tokenNow
            _fontScale.value = settings.fontScale.first()
            _notifyEnabled.value = settings.notifyEnabled.first()
            _watchEnabled.value = settings.watchEnabled.first()
            _drafts.value = settings.drafts.first()
            _health.value = readHealth()
            _appLock.value = settings.appLock.first()
            AppLock.enabledCache = _appLock.value
            if (tokenNow.isNotBlank()) {
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

    private fun errText(e: Throwable): String = when (e) {
        is HuginnClient.HuginnException ->
            if (e.code == 401) "Rejected by huginn: check the token in Settings" else e.message
        else -> e.message ?: e::class.java.simpleName
    }

    fun copy(text: String, label: String = "huginn") {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        _toast.value = "Copied"
    }

    // ---------------------------------------------------------- settings

    fun saveSettings(url: String, tok: String) {
        viewModelScope.launch {
            settings.setBaseUrl(url)
            settings.setToken(tok)
            // A token registered against the previous host means nothing to the new one.
            HuginnMessagingService.syncToken(getApplication())
            baseUrlNow = url.trim()
            tokenNow = tok.trim()
            _baseUrl.value = baseUrlNow
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
        Heartbeat.arm(app)
        SessionWatchWorker.schedule(app)
    }

    fun testConnection() {
        viewModelScope.launch {
            runCatching { client.ping() }
                .onSuccess {
                    _connected.value = it.ok
                    _toast.value = "Connected to ${it.host ?: "huginn"} (appd ${it.version ?: "?"})"
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
            _loading.value = true
            runCatching { client.status() }
                .onSuccess { _status.value = it; _statusError.value = null; _connected.value = true }
                .onFailure {
                    _statusError.value = errText(it)
                    if (it is HuginnClient.HuginnException && it.code == 401) _connected.value = false
                }
            runCatching { client.sessions(preview = true) }.onSuccess { _sessions.value = it }
            runCatching { client.chats() }.onSuccess { _chats.value = it }
            _loading.value = false
        }
    }

    private var sessionsPollJob: Job? = null

    /** Keeps the sessions list live while it is on screen. */
    fun startSessionsPolling() {
        sessionsPollJob?.cancel()
        sessionsPollJob = viewModelScope.launch {
            while (isActive) {
                runCatching { client.sessions(preview = true) }.onSuccess { _sessions.value = it }
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
            runCatching { client.sessions(preview = true) }
                .onSuccess { _sessions.value = it }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun refreshChats() {
        viewModelScope.launch {
            runCatching { client.chats() }
                .onSuccess { _chats.value = it }
                .onFailure { _toast.value = errText(it) }
        }
    }

    // ---------------------------------------------------------- sessions

    fun createSession(name: String, onCreated: (String) -> Unit) {
        val canon = name.trim().lowercase()
        if (!canon.matches(Regex("^[a-z0-9_]{1,50}$"))) {
            _toast.value = "Name can use letters, digits and underscore only"
            return
        }
        viewModelScope.launch {
            runCatching { client.createSession(canon) }
                .onSuccess { refreshSessions(); onCreated(canon) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun killSession(name: String) {
        viewModelScope.launch {
            runCatching { client.killSession(name) }
                .onSuccess { _toast.value = "Ended $name"; refreshSessions() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun renameSession(from: String, to: String) {
        val canon = to.trim().lowercase()
        if (!canon.matches(Regex("^[a-z0-9_]{1,50}$"))) {
            _toast.value = "Name can use letters, digits and underscore only"
            return
        }
        viewModelScope.launch {
            runCatching { client.renameSession(from, canon) }
                .onSuccess { _toast.value = "Renamed to $canon"; refreshSessions() }
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
                val r = runCatching {
                    client.screen(
                        name = name,
                        cols = wantCols,
                        rows = wantRows,
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
                        // The session died under the viewer. Saying so in a toast
                        // while leaving them staring at a dead screen is not
                        // enough — the UI collects this and navigates back.
                        _toast.value = "Session $name ended"
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
        val name = currentSession
        currentSession = null
        forceResize = false
        // Geometry belongs to the Screen tab of ONE session. Leaving it set
        // meant the next session opened was resized to the previous one's
        // grid even if its Screen tab was never opened.
        wantCols = null
        wantRows = null
        _screen.value = null
        _transcript.value = null
        _transcriptError.value = null
        // Hand the pane size back so an attached laptop re-fits immediately
        // instead of waiting out the server-side lease.
        if (name != null) {
            viewModelScope.launch { runCatching { client.releaseSize(name) } }
        }
    }

    /** Tails the session's Claude transcript: the structured conversation view. */
    fun startTranscriptPolling(name: String) {
        transcriptJob?.cancel()
        _transcript.value = null
        _transcriptError.value = null
        transcriptJob = viewModelScope.launch {
            var offset: Long? = null
            while (isActive) {
                val r = runCatching { client.sessionTranscript(name, offset) }
                r.onSuccess { page ->
                    _transcriptError.value = null
                    offset = page.nextOffset
                    val cur = _transcript.value
                    _transcript.value = if (cur == null) page
                    else page.copy(
                        // Keep what the tail read no longer carries. Bounded:
                        // a session left open on a busy day would otherwise grow
                        // this list without limit, copying it whole every poll.
                        events = (cur.events + page.events).takeLast(MAX_EVENTS),
                        // A tail read only reports fields whose records happen to
                        // fall inside it, so EVERY session-level field has to be
                        // carried forward or it reverts to null seconds after the
                        // screen opens. Missing `effort` here is exactly why the
                        // effort control kept falling back to a placeholder.
                        title = page.title ?: cur.title,
                        model = page.model ?: cur.model,
                        effort = page.effort ?: cur.effort,
                        gitBranch = page.gitBranch ?: cur.gitBranch,
                        permissionMode = page.permissionMode ?: cur.permissionMode,
                        cwd = page.cwd ?: cur.cwd,
                        lastActivityTs = page.lastActivityTs ?: cur.lastActivityTs,
                        truncated = cur.truncated,
                    )
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

    fun sendText(name: String, text: String, thenEnter: Boolean) {
        clearDraft(sessionDraftKey(name))
        viewModelScope.launch {
            runCatching {
                client.sendKeys(name, text = text, keys = if (thenEnter) listOf("Enter") else emptyList())
            }.onFailure { _toast.value = errText(it) }
        }
    }

    // ------------------------------------------------ live typing (ordered)
    //
    // One queue, one drainer. viewModelScope runs on the main dispatcher, so
    // enqueue and drain never race; the drainer merges bursts into single
    // requests and sends them SEQUENTIALLY — the per-keystroke launch it
    // replaces could reorder characters in flight.
    private val liveOps = ArrayDeque<LiveInput.Op>()
    private var liveDrainer: Job? = null

    /**
     * Set when the session being viewed stops existing, so the UI can close its
     * view instead of leaving the reader on a dead screen.
     */
    private val _sessionGone = MutableStateFlow<String?>(null)
    val sessionGone: StateFlow<String?> = _sessionGone.asStateFlow()
    fun sessionGoneHandled() { _sessionGone.value = null }

    fun sendLive(name: String, op: LiveInput.Op) {
        liveOps.addLast(op)
        if (liveDrainer?.isActive == true) return
        liveDrainer = viewModelScope.launch {
            // A beat for the burst to accumulate: keystrokes arrive faster than
            // round trips complete, and merging them is the point.
            delay(15)
            while (liveOps.isNotEmpty()) {
                val batch = LiveInput.merge(liveOps.toList())
                liveOps.clear()
                for (m in batch) {
                    runCatching {
                        when (m) {
                            is LiveInput.Op.Text -> client.sendKeys(name, text = m.text)
                            is LiveInput.Op.Key -> client.sendKeys(name, keys = m.keys)
                        }
                    }.onFailure { _toast.value = errText(it) }
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

    /** Answers a detected choice prompt by sending its number. */
    fun answerPrompt(name: String, number: Int) {
        viewModelScope.launch {
            runCatching { client.sendKeys(name, text = number.toString()) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    // --------------------------------------------------------------- chat

    private val _chatPage = MutableStateFlow<TranscriptPage?>(null)
    val chatPage: StateFlow<TranscriptPage?> = _chatPage.asStateFlow()

    private val _chatMode = MutableStateFlow("ask")
    val chatMode: StateFlow<String> = _chatMode.asStateFlow()

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

    private var streamJob: Job? = null
    private var chatPollJob: Job? = null

    fun openChat(id: String) {
        _chatPage.value = null
        _streamingText.value = null
        _activeTool.value = null
        viewModelScope.launch {
            val meta = runCatching { client.chat(id) }.getOrNull()
            _chatMode.value = meta?.mode ?: "ask"
            _chatModel.value = meta?.model
            _chatEffort.value = meta?.effort
            _chatTitle.value = meta?.title
            // A chat that has never run has no transcript yet; that is not an error.
            loadChatTranscript(id)
            if (meta?.running == true) {
                _streamingText.value = meta.partialText ?: ""
                _sending.value = true
                collect(id, client.streamChat(id, since = 0))
            }
        }
    }

    private fun loadChatTranscript(id: String) {
        chatPollJob?.cancel()
        chatPollJob = viewModelScope.launch {
            runCatching { client.chatTranscript(id) }
                .onSuccess { _chatPage.value = it }
                .onFailure {
                    // 409 = has not run yet. Show the empty state, not an error.
                    if (_chatPage.value == null) _chatPage.value = TranscriptPage()
                }
        }
    }

    fun setChatOptions(id: String, model: String? = null, effort: String? = null) {
        viewModelScope.launch {
            runCatching { client.updateChat(id, model = model, effort = effort) }
                .onSuccess { _chatModel.value = it.model; _chatEffort.value = it.effort; refreshChats() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun newChat(mode: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { client.createChat(mode) }
                .onSuccess { refreshChats(); onCreated(it.id) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            runCatching { client.deleteChat(id) }
                .onSuccess { _toast.value = "Chat deleted"; refreshChats() }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Sends, or queues if a run is already going: the server holds it and
     * delivers when that run ends, so the composer never dead-ends the way it
     * used to when a chat was busy.
     */
    fun send(id: String, text: String) {
        clearDraft(chatDraftKey(id))
        if (_sending.value) {
            viewModelScope.launch {
                runCatching { client.queueMessage(id, text) }
                    .onSuccess { loadChatTranscript(id); refreshChats() }
                    .onFailure { _toast.value = errText(it) }
            }
            return
        }
        _sending.value = true
        _streamingText.value = ""
        _activeTool.value = null
        collect(id, client.sendMessage(id, text))
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

    /** Detaches the stream WITHOUT cancelling the server-side run. */
    fun detachStream() {
        streamJob?.cancel()
        streamJob = null
        chatPollJob?.cancel()
        chatPollJob = null
        _sending.value = false
        _streamingText.value = null
        _activeTool.value = null
        _chatPage.value = null
    }

    private fun collect(id: String, flow: kotlinx.coroutines.flow.Flow<ChatEvent>) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            flow.collect { ev ->
                when (ev) {
                    is ChatEvent.Started -> Unit
                    is ChatEvent.Delta -> _streamingText.value = (_streamingText.value ?: "") + ev.text
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
                        _toast.value = ev.text
                        _streamingText.value = null
                    }
                    ChatEvent.Done -> {
                        _sending.value = false
                        _streamingText.value = null
                        _activeTool.value = null
                        loadChatTranscript(id)
                        refreshChats()
                    }
                }
            }
            _sending.value = false
        }
    }

    companion object {
        /** Newest events kept in memory for one session view. */
        private const val MAX_EVENTS = 600

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
