package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.desktop.DesktopSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * "Available to huginn", driven by `huginn core` (docs/CORE-ARCHITECTURE.md P1).
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

    @Volatile private var client: CoreClient? = null
    private var job: Job? = null

    /** Idempotent: follows the core's event stream for the life of the app. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val c = clientFor()
                client = c
                if (c == null) {
                    _status.value = DeviceStatus(note = NOT_AVAILABLE)
                } else {
                    runCatching {
                        c.events().collect { (event, data) -> if (event == "device") apply(data) }
                    }
                    _status.value = DeviceStatus(note = if (c.socketPresent()) NOT_ANSWERING else NOT_RUNNING)
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

    /** Settings → Remove this computer: the core forgets its pairing too. */
    suspend fun unpair(): Result<Unit> = runCatching { (client ?: clientFor() ?: error(NOT_AVAILABLE)).unpair() }

    companion object {
        const val RECONNECT_MS = 3_000L
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
