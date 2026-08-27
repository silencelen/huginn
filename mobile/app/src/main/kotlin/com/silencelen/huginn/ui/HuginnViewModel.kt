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
import com.silencelen.huginn.data.PolishResult
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
import com.silencelen.huginn.data.LoginState
import com.silencelen.huginn.data.RouteResolver
import com.silencelen.huginn.data.UriByteStream
import com.silencelen.huginn.data.Watchers
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.Heartbeat
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

    /**
     * Thumbnails for photo attachments in chat history. Built here (the client is
     * private, so nothing outside can) and provided to the shared transcript
     * renderer; app-scoped so decoded bitmaps survive scrolling and recomposition.
     */
    val attachmentImages: com.silencelen.huginn.ui.AttachmentImageLoader =
        com.silencelen.huginn.ui.AttachmentImageLoader(
            fetch = { client.uploadBytes(it) },
            decoder = com.silencelen.huginn.ui.AndroidImageBytesDecoder(),
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
    fun stagePadInChat(id: String, text: String) {
        val key = chatDraftKey(id)
        val cur = _drafts.value[key].orEmpty()
        setDraft(key, if (cur.isBlank()) text else cur + "\n" + text)
    }

    fun stagePadInSession(name: String, text: String) {
        val key = sessionDraftKey(name)
        val cur = _drafts.value[key].orEmpty()
        setDraft(key, if (cur.isBlank()) text else cur + "\n" + text)
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
            runCatching { client.sessionTranscript(name, until = until) }
                .onSuccess { older ->
                    historyStart = older.windowStart
                    _transcript.value = prependTranscriptPage(_transcript.value, older)
                }
                .onFailure { _toast.value = errText(it) }
            _loadingHistory.value = false
        }
    }

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

    private var streamJob: Job? = null
    private var chatPollJob: Job? = null

    fun openChat(id: String) {
        _chatPage.value = null
        _streamingText.value = null
        _activeTool.value = null
        _chatError.value = null
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
