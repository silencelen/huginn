package com.silencelen.huginn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Message
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.data.Status
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single ViewModel for the whole app: the surfaces share a client, a connection
 * banner, and a snackbar channel, and there are few enough of them that splitting
 * would add wiring without removing coupling.
 */
class HuginnViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    // Settings are read once into memory and kept current, so client calls need
    // no suspension to learn the URL/token.
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

    /** Set once the token is known to be accepted, so Settings can say so plainly. */
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected.asStateFlow()

    init {
        viewModelScope.launch {
            baseUrlNow = settings.baseUrl.first()
            tokenNow = settings.token.first()
            _baseUrl.value = baseUrlNow
            _token.value = tokenNow
            if (tokenNow.isNotBlank()) {
                refreshAll()
            }
        }
    }

    private fun errText(e: Throwable): String = when (e) {
        is HuginnClient.HuginnException ->
            if (e.code == 401) "Rejected by huginn: check the token in Settings" else e.message
        else -> e.message ?: e::class.java.simpleName
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

    // ------------------------------------------------------------- data

    fun refreshAll() {
        viewModelScope.launch {
            _loading.value = true
            runCatching { client.status() }
                .onSuccess { _status.value = it; _statusError.value = null; _connected.value = true }
                .onFailure { _statusError.value = errText(it); if (it is HuginnClient.HuginnException && it.code == 401) _connected.value = false }
            runCatching { client.sessions() }.onSuccess { _sessions.value = it }
            runCatching { client.chats() }.onSuccess { _chats.value = it }
            _loading.value = false
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            runCatching { client.sessions() }
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

    // ------------------------------------------------------ terminal view

    private val _screen = MutableStateFlow<Screen?>(null)
    val screen: StateFlow<Screen?> = _screen.asStateFlow()

    private var screenJob: Job? = null

    /** Polls capture-pane. tmux has no push channel, so this is the honest option. */
    fun startScreenPolling(name: String, intervalMs: Long = 1200) {
        screenJob?.cancel()
        screenJob = viewModelScope.launch {
            while (isActive) {
                runCatching { client.screen(name) }
                    .onSuccess { _screen.value = it }
                    .onFailure {
                        _toast.value = errText(it)
                        return@launch          // session gone or auth broke: stop hammering
                    }
                kotlinx.coroutines.delay(intervalMs)
            }
        }
    }

    fun stopScreenPolling() {
        screenJob?.cancel()
        screenJob = null
        _screen.value = null
    }

    fun sendText(name: String, text: String, thenEnter: Boolean) {
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

    // --------------------------------------------------------------- chat

    private val _chatDetail = MutableStateFlow<ChatDetail?>(null)
    val chatDetail: StateFlow<ChatDetail?> = _chatDetail.asStateFlow()

    /** Text of the turn currently streaming in, or null when nothing is streaming. */
    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    /** Tool the model is running right now, for the "working" line. */
    private val _activeTool = MutableStateFlow<String?>(null)
    val activeTool: StateFlow<String?> = _activeTool.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var streamJob: Job? = null

    fun openChat(id: String) {
        _chatDetail.value = null
        _streamingText.value = null
        _activeTool.value = null
        viewModelScope.launch {
            runCatching { client.chat(id) }
                .onSuccess { detail ->
                    _chatDetail.value = detail
                    // A run that started before this screen opened (or survived a
                    // phone lock) is still streaming server-side: reattach.
                    if (detail.running) {
                        _streamingText.value = detail.partialText ?: ""
                        _sending.value = true
                        collect(id, client.streamChat(id, since = 0))
                    }
                }
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

    fun send(id: String, text: String) {
        if (_sending.value) return
        _sending.value = true
        _streamingText.value = ""
        _activeTool.value = null
        // Show the user's line immediately; the server has it persisted either way.
        appendLocal(Message(type = "user", text = text, ts = System.currentTimeMillis() / 1000))
        collect(id, client.sendMessage(id, text))
    }

    fun cancel(id: String) {
        viewModelScope.launch {
            runCatching { client.cancelChat(id) }
                .onFailure { _toast.value = errText(it) }
        }
    }

    /**
     * Detaches the stream without cancelling the server-side run. Called when the
     * chat screen leaves composition: locking the phone must never kill a turn.
     */
    fun detachStream() {
        streamJob?.cancel()
        streamJob = null
        _sending.value = false
        _streamingText.value = null
        _activeTool.value = null
    }

    private fun collect(id: String, flow: kotlinx.coroutines.flow.Flow<ChatEvent>) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            flow.collect { ev ->
                when (ev) {
                    is ChatEvent.Started -> Unit
                    is ChatEvent.Delta -> _streamingText.value = (_streamingText.value ?: "") + ev.text
                    is ChatEvent.Assistant -> {
                        // The final block for this turn: promote streamed text to a
                        // real message so it survives a later reload.
                        appendLocal(Message(type = "assistant", text = ev.text, ts = now()))
                        _streamingText.value = ""
                        _activeTool.value = null
                    }
                    is ChatEvent.ToolStart -> _activeTool.value = ev.name
                    is ChatEvent.Tool -> {
                        appendLocal(Message(type = "tool", name = ev.name, input = ev.input, ts = now()))
                        _activeTool.value = null
                    }
                    is ChatEvent.Result -> {
                        appendLocal(
                            Message(
                                type = "result", ok = ev.ok, durationMs = ev.durationMs,
                                costUsd = ev.costUsd, ts = now(),
                            )
                        )
                        _streamingText.value = null
                    }
                    is ChatEvent.Failure -> {
                        appendLocal(Message(type = "error", text = ev.text, ts = now()))
                        _streamingText.value = null
                    }
                    ChatEvent.Done -> {
                        _sending.value = false
                        _streamingText.value = null
                        _activeTool.value = null
                        refreshChats()
                    }
                }
            }
            _sending.value = false
        }
    }

    private fun now() = System.currentTimeMillis() / 1000

    private fun appendLocal(msg: Message) {
        val cur = _chatDetail.value ?: return
        _chatDetail.value = cur.copy(messages = cur.messages + msg)
    }

    companion object {
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
