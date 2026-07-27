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
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.Usage
import com.silencelen.huginn.notify.SessionWatchWorker
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
            _drafts.value = settings.drafts.first()
            if (tokenNow.isNotBlank()) {
                refreshAll()
                if (_notifyEnabled.value) SessionWatchWorker.schedule(getApplication())
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
        viewModelScope.launch {
            settings.setNotifyEnabled(on)
            if (on) SessionWatchWorker.schedule(getApplication())
            else SessionWatchWorker.cancel(getApplication())
        }
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

    private var usagePollJob: Job? = null

    fun refreshAccount() {
        viewModelScope.launch {
            runCatching { client.account() }
                .onSuccess { _account.value = it }
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

    /**
     * Opens an interactive `claude auth login` in a session and hands back both
     * the session (so the Screen tab can take the pasted code) and the sign-in
     * URL, which the pane hard-wraps and a phone cannot copy.
     */
    fun startLogin(onSession: (String) -> Unit) {
        _toast.value = "Starting sign-in…"
        viewModelScope.launch {
            runCatching { client.startLogin() }
                .onSuccess {
                    refreshSessions()
                    _loginUrl.value = it.url
                    onSession(it.session)
                }
                .onFailure { _toast.value = errText(it) }
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
                        _toast.value = "Session ended"
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
                        title = page.title ?: cur.title,
                        model = page.model ?: cur.model,
                        gitBranch = page.gitBranch ?: cur.gitBranch,
                        permissionMode = page.permissionMode ?: cur.permissionMode,
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

    fun send(id: String, text: String) {
        if (_sending.value) return
        clearDraft(chatDraftKey(id))
        _sending.value = true
        _streamingText.value = ""
        _activeTool.value = null
        collect(id, client.sendMessage(id, text))
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
