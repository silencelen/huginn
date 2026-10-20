package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.desktop.DesktopSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What this machine reports about being a device, for the Settings panel.
 *
 * `note` is one line of plain language rather than a code: every state this can
 * be in is one the owner may need to act on, and "the core is not running"
 * versus "enrolled, waiting for work" versus "claude was not found" are three
 * different actions. (Moved here from the deleted DeviceRunner.kt; the fields
 * are the same, and the core's `runner.Status` uses the same names.)
 */
data class DeviceStatus(
    val enabled: Boolean = false,
    val deviceId: String? = null,
    val enrolled: Boolean = false,
    val busy: Boolean = false,
    val locked: Boolean = false,
    /** Whether [locked] is something the probe SAID or the fence's default. */
    val lockKnown: Boolean = false,
    val note: String = "Off",
    /** False while no `huginn core` answers — Settings then offers to start one. */
    val coreReachable: Boolean = false,
)

/**
 * Whether the app may run at all (owner rule 2026-10-15): the desktop is a
 * layer on top of huginn core, which includes the CLI, and does not work
 * without it. Anything but [READY] is a whole-window screen with one action.
 */
enum class CoreGate { CHECKING, READY, NOT_SET_UP, NOT_RUNNING }

/**
 * "Available to huginn", driven by `huginn core` (docs/design/core.md P1).
 *
 * ⚠ THE APP NO LONGER RUNS WORK. Its old runner lived only while this window's
 * JVM did, and the JVM lives only while somebody is signed in — which is how
 * RIG kept vanishing as a device (audit 2026-10-03). The core is a per-user
 * service that starts at boot; this class is a DOOR to it: it shows the core's
 * state and sends the owner's changes, and a crash or quit here can neither
 * orphan nor misreport a run.
 */
class CoreDevice internal constructor(
    private val scope: CoroutineScope,
    private val settings: DesktopSettings,
    private val clientFor: () -> CoreClient? = { CorePaths.socket()?.let { CoreClient(it) } },
) {
    private val _status = MutableStateFlow(DeviceStatus(note = "Looking for huginn core…"))
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private val _coreTray = MutableStateFlow(false)

    /**
     * True while `huginn core tray` is attached: the core then owns the tray and
     * the notifications (docs/design/core.md D5), and this window stands
     * its own down — no second icon, no second copy of every toast, no claim
     * against appd, and closing the window quits (D6).
     */
    val coreTray: StateFlow<Boolean> = _coreTray.asStateFlow()

    private val _guiCommands = kotlinx.coroutines.flow.MutableSharedFlow<GuiCommand>(extraBufferCapacity = 8)

    /**
     * What the core tray's menu asks of this app (owner rule 2026-10-17: one
     * icon, one menu, carrying the app's items). Sent once by the core, never
     * replayed, so a collector that arrives late misses nothing it should act on.
     */
    val guiCommands: kotlinx.coroutines.flow.SharedFlow<GuiCommand> = _guiCommands

    /** One command from the core tray. */
    sealed interface GuiCommand {
        /** Bring the window up; [url], when set, is a huginn:// target. */
        data class Open(val url: String?) : GuiCommand
        data object Quit : GuiCommand
        data class CloseToTray(val on: Boolean) : GuiCommand
    }

    @kotlinx.serialization.Serializable
    private data class GuiCmdWire(val cmd: String = "", val value: Boolean? = null, val url: String? = null)

    /** The core's "gui.cmd" event, as a [GuiCommand]; null for anything else. */
    internal fun guiCommand(data: String): GuiCommand? {
        val w = runCatching { CoreClient.json.decodeFromString(GuiCmdWire.serializer(), data) }.getOrNull() ?: return null
        return when (w.cmd) {
            "open" -> GuiCommand.Open(w.url?.takeIf { it.startsWith("huginn://") })
            "quit" -> GuiCommand.Quit
            "closeToTray" -> w.value?.let { GuiCommand.CloseToTray(it) }
            else -> null
        }
    }

    @Volatile private var client: CoreClient? = null
    private var job: Job? = null

    private val _gate = MutableStateFlow(CoreGate.CHECKING)
    /** See [CoreGate]. A core that drops for a restart (an update) keeps READY for [GATE_GRACE_MS]. */
    val gate: StateFlow<CoreGate> = _gate.asStateFlow()

    @Volatile private var readyAt = System.currentTimeMillis() // launch counts: the core may be restarting onto the bundled one

    private fun ready() {
        readyAt = System.currentTimeMillis()
        _gate.value = CoreGate.READY
    }

    private fun lost() {
        // No service: nothing is restarting, so there is nothing to wait out.
        if (!CoreSetup.serviceInstalled()) {
            _gate.value = CoreGate.NOT_SET_UP
            return
        }
        if (System.currentTimeMillis() - readyAt < GATE_GRACE_MS) return
        _gate.value = CoreGate.NOT_RUNNING
    }

    /** Idempotent: follows the core's event stream for the life of the app. */
    fun start() {
        if (job?.isActive == true) return
        // The tray's checkbox follows this app's setting wherever it changes.
        scope.launch {
            settings.closeToTray.collect { on -> client?.let { c -> runCatching { c.closeToTray(on) } } }
        }
        job = scope.launch {
            // D9: the core that ships inside this app is put in place at once,
            // silently — copying a binary into the per-user layout needs no
            // decision. Starting it at BOOT is the person's choice ([setUp]).
            CoreSetup.ensureInstalled()
            while (isActive) {
                val c = clientFor()
                client = c
                if (c == null) {
                    _status.value = DeviceStatus(note = NOT_AVAILABLE)
                    lost()
                } else {
                    runCatching {
                        var said = false
                        // As the GUI (role=gui): the core tray then carries this
                        // app's items, and is told its Close to tray at once.
                        c.events(role = "gui").collect { (event, data) ->
                            ready()
                            if (!said) {
                                said = true
                                launch { runCatching { c.closeToTray(settings.closeToTrayNow()) } }
                            }
                            when (event) {
                                "device" -> apply(data)
                                "coretray" -> _coreTray.value = data.contains("\"attached\":true")
                                "gui.cmd" -> guiCommand(data)?.let { _guiCommands.tryEmit(it) }
                            }
                        }
                    }
                    _status.value = DeviceStatus(note = if (c.socketPresent()) NOT_ANSWERING else NOT_RUNNING)
                    _coreTray.value = false // no core, no core tray: this window takes the job back
                    lost()
                }
                delay(RECONNECT_MS)
            }
        }
    }

    private fun apply(json: String) {
        val v = runCatching { CoreClient.json.decodeFromString(CoreDeviceView.serializer(), json) }.getOrNull() ?: return
        apply(v)
    }

    private fun apply(v: CoreDeviceView) {
        val s = v.settings
        val st = v.status
        _status.value = DeviceStatus(
            enabled = s.enabled ?: false, deviceId = st.deviceId, enrolled = st.enrolled, busy = st.busy,
            locked = st.locked, lockKnown = st.lockKnown, note = st.note.ifBlank { "Off" }, coreReachable = true,
        )
        settings.mirrorDeviceFromCore(
            enabled = s.enabled ?: false, id = st.deviceId.orEmpty(), scope = s.scope ?: "look",
            root = s.root.orEmpty(), actWhileLocked = s.actWhileLocked ?: false, claude = s.claude.orEmpty(),
        )
    }

    /** Sends one change; the core's answer (or its refusal) is what the UI shows. */
    private fun patch(change: CoreDeviceSettings) {
        val c = client ?: clientFor()
        scope.launch {
            if (c == null) {
                _status.value = _status.value.copy(note = NOT_AVAILABLE)
                return@launch
            }
            runCatching { c.patchDevice(change) }
                .onSuccess { apply(it) }
                .onFailure { e -> _status.value = _status.value.copy(note = "Not saved: ${e.message ?: NOT_ANSWERING}") }
        }
    }

    fun setEnabled(v: Boolean) = patch(CoreDeviceSettings(enabled = v))
    fun setScope(v: String) = patch(CoreDeviceSettings(scope = v))
    fun setRoot(v: String) = patch(CoreDeviceSettings(root = v))
    fun setActWhileLocked(v: Boolean) = patch(CoreDeviceSettings(actWhileLocked = v))
    fun setClaudePath(v: String) = patch(CoreDeviceSettings(claude = v))

    private val _setup = MutableStateFlow<String?>(null)
    /** The last "Set up huginn core" outcome, for Settings to show. */
    val setup: StateFlow<String?> = _setup.asStateFlow()

    /** "Set up huginn core": start the core at boot, then reconnect to it. */
    fun setUp() {
        _setup.value = "Setting up huginn core…"
        scope.launch {
            CoreSetup.installService()
                .onSuccess { _setup.value = "huginn core is set up to start at boot." }
                .onFailure { _setup.value = "Not set up: ${it.message}" }
        }
    }

    /** "Start huginn core": an installed core that is not running. */
    fun startCore() {
        _setup.value = "Starting huginn core…"
        scope.launch {
            CoreSetup.startService()
                .onSuccess { _setup.value = "Started — connecting…" }
                .onFailure { _setup.value = "Not started: ${it.message}. `huginn core status` says why." }
        }
    }

    /**
     * Keeps the core told what this window shows (its rules take the focused key
     * and toast everything else): at once on every change of [focusedKey], and
     * again every [PRESENCE_EVERY_MS] while it holds, since the core forgets a
     * report older than 45 s and a window that died must not mute it for good.
     *
     * ⚠ ON EVERY CHANGE, NOT ONLY ON THE TIMER. The core does not defer an
     * alert for the focused key, it CONSUMES it. Reported every 20 s alone, the
     * key lagged the window by up to that long, and a session that asked
     * something seconds after the owner switched away was never announced.
     *
     * One report at a time, in this coroutine: two in flight can land in either
     * order, and the older one landing last is the same stale key again.
     */
    suspend fun followPresence(focusedKey: Flow<String?>, send: suspend (String?) -> Unit = ::sendPresence) {
        focusedKey.distinctUntilChanged().collectLatest { key ->
            while (true) {
                send(key)
                delay(PRESENCE_EVERY_MS)
            }
        }
    }

    /** One report. Best effort: a core that is down simply does not suppress. */
    private suspend fun sendPresence(focusedKey: String?) {
        val c = client ?: return
        runCatching { c.presence(focusedKey, settings.closeToTrayNow()) }
    }

    /**
     * Settings → Remove this computer: the core forgets its pairing too.
     * Success when there is no core on this machine at all (nothing holds a
     * token); a core that is INSTALLED but does not confirm is a failure the
     * caller must not paper over (see AppStore.removeThisComputer).
     */
    suspend fun unpair(): Result<Unit> = runCatching {
        val c = client ?: clientFor()
        val present = CoreSetup.installedBinary() != null || c?.socketPresent() == true
        if (!present) return@runCatching
        (c ?: error(NOT_AVAILABLE)).runCatching { unpair() }.getOrElse {
            throw IllegalStateException(
                "huginn core is installed here but did not confirm it forgot this computer's token — start it " +
                    "(`huginn core status`) or remove it (`huginn core uninstall`), then press Remove again", it,
            )
        }
    }

    /**
     * Settings' "Tray icon" switch (owner, 2026-10-17: one icon, and a clear way
     * to bring it back after "Hide this icon"). On starts the core's icon; off
     * takes it away until the next sign-in.
     */
    suspend fun setTrayIcon(on: Boolean): Result<Unit> =
        if (on) CoreSetup.showTray()
        else runCatching { (client ?: clientFor() ?: error(NOT_AVAILABLE)).hideTray() }

    companion object {
        const val RECONNECT_MS = 3_000L
        /** The heartbeat of [followPresence]: well inside the core's 45 s focus TTL. */
        const val PRESENCE_EVERY_MS = 20_000L
        /** How long a core may be gone before the window is handed to the gate: an update restarts it. */
        const val GATE_GRACE_MS = 20_000L
        const val NOT_RUNNING = "huginn core is not running on this computer — run `huginn core install` to start it at boot"
        const val NOT_ANSWERING = "huginn core is not answering — `huginn core status` says why"
        const val NOT_AVAILABLE = "huginn core is not available here (no runtime directory)"

        /**
         * The name that appears in the device list: the computer's own name,
         * because that is what the owner calls it.
         */
        fun defaultName(): String {
            val env = System.getenv("COMPUTERNAME")
                ?: System.getenv("HOSTNAME")
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            return (env ?: "this machine").take(40)
        }

        /** The daemon's machine key (same normalisation as appd and the core). */
        fun machineKey(host: String): String? =
            host.lowercase().replace(Regex("[^a-z0-9-]+"), "-")
                .replace(Regex("-+"), "-").trim('-').take(40).ifEmpty { null }
    }
}
