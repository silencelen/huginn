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
import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.ModelChoice
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
import com.silencelen.huginn.data.PushStatus
import com.silencelen.huginn.data.LoginState
import com.silencelen.huginn.data.RouteResolver
import com.silencelen.huginn.data.UriByteStream
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

class HuginnViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)

    private var baseUrlNow = SettingsStore.DEFAULT_BASE_URL
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
    fun showToast(text: String) { _toast.value = text }

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
            awaitReady()
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
        /**
         * The two counts the wake-up cadence is decided from. Surfaced because the
         * decision was otherwise invisible: the alarm quietly chose hourly or
         * ten-minutely and nothing said which, or why — so the one bug it has
         * already had could only be found by reading a night of server logs.
         */
        val pushesSent: Long = 0,
        val pushesReceived: Long = 0,
    ) {
        /** What the alarm will do next, in the same terms the rule is written in. */
        val relaxed: Boolean get() = Heartbeat.intervalFor(pushesSent, pushesReceived) ==
            Heartbeat.RELAXED_INTERVAL_MS
        val pushesMissing: Long get() = (pushesSent - pushesReceived).coerceAtLeast(0)
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
            awaitReady()
            runCatching { client.models() }.onSuccess { _models.value = it }
        }
    }

    /**
     * Unsent composer text per target. Held here rather than in the composable so
     * it survives navigating away, and written through to storage (debounced) so
     * it survives the process being killed.
     */
    /**
     * The one photo staged for the open chat's next message.
     *
     * A single slot, not a list, and deliberately so: the message marker carries
     * one path, the composer shows one chip, and "which of my three photos did it
     * answer about" is not a question this UI should ever pose. Cleared on send
     * and on leaving the chat.
     */
    sealed interface Attachment {
        data object Uploading : Attachment
        data class Ready(
            val path: String,
            /** Original filename, for the chip and the marker; null for photos. */
            val name: String? = null,
            val image: Boolean = true,
            /** Host's verdict on whether Read can open it; drives the marker. */
            val readable: Boolean = true,
        ) : Attachment
        data class Failed(val why: String) : Attachment
    }

    private val _attachment = MutableStateFlow<Attachment?>(null)
    val attachment: StateFlow<Attachment?> = _attachment.asStateFlow()

    /**
     * WHOSE photo the slot holds — a chat's draft key or a session's. The slot is
     * a single global, and before it had an owner a photo staged on one screen
     * could ride a send from another: stage in chat A, hop to chat B before A's
     * dispose ran, send — B's message carried A's photo. Every read of the slot
     * now names the surface asking, and a mismatch reads as empty.
     */
    private val _attachmentOwner = MutableStateFlow<String?>(null)
    val attachmentOwner: StateFlow<String?> = _attachmentOwner.asStateFlow()

    /** Clears the slot — everyone's, or only if [owner] still holds it. */
    fun clearAttachment(owner: String? = null) {
        if (owner == null || _attachmentOwner.value == owner) {
            _attachment.value = null
            _attachmentOwner.value = null
        }
    }

    /** The Ready attachment for [owner], consumed atomically; null if not theirs. */
    private fun takeAttachment(owner: String): Attachment.Ready? {
        val att = _attachment.value
        if (att !is Attachment.Ready || _attachmentOwner.value != owner) return null
        _attachment.value = null
        _attachmentOwner.value = null
        return att
    }

    /**
     * Runs [go] once [owner]'s attachment is no longer mid-upload.
     *
     * Sending while the chip still said "Uploading…" used to drop the photo
     * silently — takeAttachment only accepts a Ready slot — and because the slot
     * was left staged, the photo then rode the NEXT message instead. Attaching
     * something is a statement of intent about THIS message, so the send waits
     * for it. Bounded, because a stuck upload must not strand the message: past
     * the timeout it sends as text, which is at least visible and recoverable.
     */
    private fun whenAttachmentSettled(owner: String, go: () -> Unit) {
        val mine = _attachmentOwner.value == owner
        if (!mine || _attachment.value !is Attachment.Uploading) { go(); return }
        viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                attachment.first { it !is Attachment.Uploading || _attachmentOwner.value != owner }
            }
            go()
        }
    }

    /** The marker line an attachment contributes to an outgoing message. */
    private fun markerFor(att: Attachment.Ready): String =
        if (att.image) Attachments.marker(att.path)
        else Attachments.fileMarker(att.path, att.name, att.readable)

    /**
     * A non-image document from the file picker. Images that arrive this way are
     * routed through the photo pipeline (transcode, EXIF); everything else is
     * uploaded as-is and stands or falls on the server's type allowlist — a
     * refused docx fails HERE with the server's own words, not later as a chat
     * shrugging at unreadable bytes.
     */
    fun attachFile(uri: android.net.Uri, owner: String) {
        _attachmentOwner.value = owner
        _attachment.value = Attachment.Uploading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // The WHOLE body is caught: an uncaught throw in viewModelScope kills
            // the process, and a picker that sometimes crashes the app is worse
            // than one that says why it failed. Anything thrown becomes a chip.
            val result = runCatching {
                val cr = getApplication<Application>().contentResolver
                val mime = runCatching { cr.getType(uri) }.getOrNull() ?: "application/octet-stream"
                if (mime.startsWith("image/")) { attachImage(uri, owner); return@launch }
                val name = runCatching {
                    cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull()
                // Size read from the provider rather than by loading the file:
                // a backup is tens of megabytes and reading it into a ByteArray
                // to hand to the uploader would hold it twice on a phone.
                val size = runCatching {
                    cr.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: -1L
                runCatching {
                    client.uploadStream(mime, name, UriByteStream(cr, uri, size))
                }.fold(
                    // The HOST owns the size limit now, and says so in its own
                    // words — one place to change it, and no stale number here
                    // quietly refusing what the daemon would have accepted.
                    { Attachment.Ready(it.path, name = name, image = false, readable = it.readable) },
                    { Attachment.Failed(errText(it)) },
                )
            }.getOrElse { Attachment.Failed(it.message ?: "Could not attach that file") }
            if (_attachmentOwner.value == owner) _attachment.value = result
        }
    }

    /** Transcodes to JPEG off the main thread, uploads, and stages the path. */
    fun attachImage(uri: android.net.Uri, owner: String) {
        _attachmentOwner.value = owner
        _attachment.value = Attachment.Uploading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = Attachments.toJpeg(getApplication(), uri)
            val result = if (bytes == null) Attachment.Failed("Could not read that image")
                else runCatching { client.upload(bytes, Attachments.MIME) }
                    .fold({ Attachment.Ready(it.path) }, { Attachment.Failed(errText(it)) })
            // The slot may have been re-staged for someone else while this
            // uploaded; a stale upload must not overwrite the newer claim.
            if (_attachmentOwner.value == owner) _attachment.value = result
        }
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
        if (!text.isNullOrBlank()) {
            val cur = _drafts.value[key].orEmpty()
            // Appended, never clobbered: a half-typed draft outranks a share.
            setDraft(key, if (cur.isBlank()) text else cur + "\n" + text)
        }
        if (image != null) attachImage(image, key)
    }

    /** The same, into a session's conversation composer. */
    fun stageShareInSession(name: String, text: String?, image: android.net.Uri?) {
        val key = sessionDraftKey(name)
        if (!text.isNullOrBlank()) {
            val cur = _drafts.value[key].orEmpty()
            setDraft(key, if (cur.isBlank()) text else cur + "\n" + text)
        }
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
            _routePinned.value = settings.routePinned.first()
            AppLock.enabledCache = _appLock.value
            // Opened even when no token is configured: a caller must unblock and
            // get a real "not configured" failure rather than hang forever.
            ready.value = true
            if (tokenNow.isNotBlank()) {
                // Only one VPN can hold the tunnel slot, so the reachable route
                // changes when the owner switches between Tailscale and the
                // yggdrasil mesh. Re-pick before the first fan-out of calls.
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

    // ------------------------------------------------------- appd routes

    private val _routePinned = MutableStateFlow(false)
    val routePinned: StateFlow<Boolean> = _routePinned.asStateFlow()
    private val _resolvingRoute = MutableStateFlow(false)
    val resolvingRoute: StateFlow<Boolean> = _resolvingRoute.asStateFlow()

    /**
     * Moves to the first reachable route. Leaves the current setting alone when
     * nothing answers — blanking it would turn "the network is down" into "the
     * app is misconfigured".
     */
    fun resolveRoute(silent: Boolean = false) {
        viewModelScope.launch {
            if (settings.routePinned.first()) {
                if (!silent) _toast.value = "Route is pinned — unpin to switch automatically"
                return@launch
            }
            _resolvingRoute.value = true
            val found = RouteResolver.resolve(AppdRoutes.candidates(baseUrlNow)) { client.probe(it) }
            _resolvingRoute.value = false
            when {
                found == null ->
                    if (!silent) _toast.value = "No route to huginn — is a VPN connected?"
                AppdRoutes.normalize(found) == AppdRoutes.normalize(baseUrlNow) ->
                    if (!silent) _toast.value = "Still on ${AppdRoutes.labelFor(found)}"
                else -> {
                    applyRoute(found, pinned = false)
                    _toast.value = "Switched to ${AppdRoutes.labelFor(found)}"
                }
            }
        }
    }

    /** Manual switch from the Settings picker; pins so auto-resolve won't move it. */
    fun selectRoute(url: String) {
        viewModelScope.launch { applyRoute(url, pinned = true) }
    }

    fun unpinRoute() {
        viewModelScope.launch {
            settings.selectRoute(baseUrlNow, pinned = false)
            _routePinned.value = false
            resolveRoute()
        }
    }

    private suspend fun applyRoute(url: String, pinned: Boolean) {
        settings.selectRoute(url, pinned)
        baseUrlNow = AppdRoutes.normalize(url)
        _baseUrl.value = baseUrlNow
        _routePinned.value = pinned
        _connected.value = null
        refreshAll()
    }

    // ---------------------------------------------------------- settings

    fun saveSettings(url: String, tok: String) {
        viewModelScope.launch {
            // A hand-typed URL is a deliberate choice; don't let auto-resolve
            // silently move off it.
            settings.selectRoute(url, pinned = true)
            _routePinned.value = true
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

    fun testConnection() {
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
            awaitReady()
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
            // The poller starts the instant the sessions screen composes, which on
            // a cold start is before credentials have loaded — its first tick was
            // the one remaining 401.
            awaitReady()
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

    // ---------------------------------------------------------- sessions

    fun createSession(name: String, onCreated: (String) -> Unit) {
        val canon = name.trim().lowercase()
        if (!canon.matches(Regex("^[a-z0-9_]{1,50}$"))) {
            _toast.value = "Name can use letters, digits and underscore only"
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

    fun renameSession(from: String, to: String) {
        val canon = to.trim().lowercase()
        // Matches what the daemon will route to (NAME_RE): a leading alphanumeric
        // or underscore keeps the name usable as a filename under /run, and dashes
        // and dots are ordinary in sessions made at the keyboard.
        if (!canon.matches(Regex("^[a-z0-9_][a-z0-9_.-]{0,49}$"))) {
            _toast.value = "Start with a letter or digit; letters, digits, _ . - after that"
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
                        events = mergeTranscript(cur.events, page.events, MAX_EVENTS),
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
        whenAttachmentSettled(sessionDraftKey(name)) { sendTextNow(name, text, thenEnter) }
    }

    private fun sendTextNow(name: String, text: String, thenEnter: Boolean) {
        // The staged photo rides this message, same contract as a chat send:
        // consumed here (and only if staged for THIS session) so it cannot ride
        // twice or cross surfaces. Claude in the pane reads the path like any file.
        val att = takeAttachment(sessionDraftKey(name))
        @Suppress("NAME_SHADOWING") var text = text
        if (att != null) {
            text = if (text.isBlank()) markerFor(att) else text + "\n\n" + markerFor(att)
        }
        if (text.isBlank()) return
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

    private var streamJob: Job? = null
    private var chatPollJob: Job? = null

    fun openChat(id: String) {
        _chatPage.value = null
        _streamingText.value = null
        _activeTool.value = null
        _chatError.value = null
        viewModelScope.launch {
            val meta = runCatching { client.chat(id) }.getOrNull()
            _chatMode.value = meta?.mode ?: "ask"
            _chatModel.value = meta?.model
            _chatEffort.value = meta?.effort
            _chatTitle.value = meta?.title
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
                    // 409 is the only failure that MEANS "nothing here yet" — the
                    // chat exists but has never run. Anything else is a failure to
                    // read history that exists, and must not be drawn as its absence.
                    val neverRan = e is HuginnClient.HuginnException && e.code == 409
                    if (_chatPage.value == null) {
                        if (neverRan) _chatPage.value = TranscriptPage()
                        else _chatError.value = errText(e)
                    }
                }
        }
    }

    /** Retries the transcript load after a failure the user can see. */
    fun retryChatTranscript(id: String) {
        _chatError.value = null
        loadChatTranscript(id)
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
        whenAttachmentSettled(chatDraftKey(id)) { sendNow(id, text) }
    }

    private fun sendNow(id: String, text: String) {
        // The staged photo rides this message — but only if it was staged for
        // THIS chat. Consumed here, whichever path the send takes (stream or
        // queue), so it cannot ride two messages.
        val att = takeAttachment(chatDraftKey(id))
        @Suppress("NAME_SHADOWING") var text = text
        if (att != null) {
            text = if (text.isBlank()) markerFor(att) else text + "\n\n" + markerFor(att)
        }
        if (text.isBlank()) return
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
                        // The tool is not running for US any more, whatever it is
                        // doing on huginn. Left set, `streaming` stayed true and the
                        // view showed a spinner for a tool that had long finished.
                        _activeTool.value = null
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
