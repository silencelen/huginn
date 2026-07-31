package com.silencelen.huginn.desktop.diag

import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.update.BuildInfo
import com.silencelen.huginn.desktop.update.UpdateState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.lang.management.ManagementFactory

/**
 * The app's one log, and the wiring that fills it.
 *
 * A singleton rather than something passed around: the call sites that most need
 * to log — a coroutine failing inside a flow, an uncaught exception on a random
 * thread — are precisely the ones with nothing to pass. [RingLog] is the testable
 * part; this is the part that knows about [AppStore].
 */
object AppLog : RingLog(defaultLogFile()) {

    // The watch stream's last complaint, mirrored here so the diagnostics
    // gatherer does not have to collect a Flow to read one string. Written by the
    // observer below, which is already collecting it.
    @Volatile private var lastWatchError: String = ""
    @Volatile private var lastWatchErrorAt: Long = 0

    /**
     * Starts the observers. ONE call, from [AppStore.start].
     *
     * Everything it records is derived from state the store already publishes, so
     * this adds no polling and no second source of truth: it turns edges that were
     * previously visible only on screen (the stream dropped, the updater failed)
     * into lines that survive long enough to be pasted into a chat.
     */
    fun attach(store: AppStore) {
        AppLog.info("app", "started ${BuildInfo.VERSION} on ${platform()} (${if (DesktopSettings.isPackaged()) "packaged" else "unpackaged"})")
        installUncaughtHandler()

        store.scope.launch {
            var seen = false
            store.watchConnected.collect { connected ->
                // The FIRST value is the initial `false`, not a drop. Logging it as
                // one would put "stream dropped" at the top of every report from a
                // perfectly healthy launch.
                if (!seen) { seen = true; if (!connected) return@collect }
                if (connected) info("watch", "stream connected")
                else warn("watch", "stream dropped: ${lastWatchError.ifBlank { "no reason given" }}")
            }
        }

        store.scope.launch {
            store.settings.lastWatchError.collect { lastWatchError = it }
        }
        store.scope.launch {
            store.settings.lastWatchErrorAt.collect { lastWatchErrorAt = it }
        }

        store.scope.launch {
            var last: String? = null
            store.updater.state.collect { s ->
                val line = describe(s)
                // Downloading ticks per chunk; only transitions are worth a line.
                if (line == last) return@collect
                last = line
                when (s) {
                    is UpdateState.Error -> warn("update", line)
                    is UpdateState.Ready -> info("update", line)
                    is UpdateState.UpToDate -> info("update", line)
                    else -> Unit
                }
            }
        }

        store.scope.launch {
            var last: String? = null
            store.error.collectLatest { e ->
                if (e == null || e == last) return@collectLatest
                last = e
                warn("net", e)
            }
        }
    }

    /**
     * The last-chance record. Chained rather than replacing whatever is already
     * installed: Compose Desktop sets its own handler, and swallowing it would
     * turn a crash into silence on the owner's daily driver.
     */
    private fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Chained) return
        Thread.setDefaultUncaughtExceptionHandler(Chained(previous))
    }

    private class Chained(private val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            // Qualified: `error` unqualified would resolve to kotlin.error(), which
            // throws IllegalStateException — from inside the uncaught handler.
            runCatching {
                AppLog.error("uncaught", "${t.name}: ${e::class.simpleName}: ${e.message ?: "no message"}")
            }
            previous?.uncaughtException(t, e)
        }
    }

    // -------------------------------------------------------- the report

    /** Gathers the live facts and renders the shareable report. */
    fun diagnostics(store: AppStore): String {
        val runtime = Runtime.getRuntime()
        val update = store.updater.state.value
        return Diagnostics.build(
            Diagnostics.Input(
                generatedAt = RingLog.stamp(System.currentTimeMillis()),
                appVersion = BuildInfo.VERSION,
                packaged = DesktopSettings.isPackaged(),
                platform = platform(),
                jvm = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
                uptimeSec = runCatching { ManagementFactory.getRuntimeMXBean().uptime / 1000 }.getOrDefault(0L),
                heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
                heapMaxMb = runtime.maxMemory() / 1024 / 1024,
                baseUrl = store.settings.baseUrlNow(),
                routePinned = store.settings.routePinnedNow(),
                // The ONLY thing said about the token, and the reason the type has
                // no field for the token itself.
                hasToken = store.settings.tokenNow().isNotBlank(),
                clientId = store.settings.clientIdNow(),
                watchConnected = store.watchConnected.value,
                lastWatchError = lastWatchError.ifBlank { null },
                lastWatchErrorAt = if (lastWatchErrorAt > 0) RingLog.stamp(lastWatchErrorAt) else null,
                appdVersion = store.status.value?.appdVersion,
                notifyEnabled = store.settings.notifyEnabledNow(),
                present = store.presence.present.value,
                visible = store.presence.visible.value,
                claiming = store.settings.notifyEnabledNow() && store.presence.present.value,
                notifier = if (NotifierSeam.available) (NotifierSeam.name ?: "wired") else null,
                updateStatus = describe(update),
                updateVersion = updateVersion(update),
                updateError = (update as? UpdateState.Error)?.message,
                lastError = store.error.value,
                logPath = path,
                log = text(),
            )
        )
    }

    private fun describe(s: UpdateState): String = when (s) {
        UpdateState.Idle -> "idle"
        UpdateState.Checking -> "checking"
        is UpdateState.UpToDate -> "up to date (${s.version})"
        is UpdateState.Downloading -> "downloading ${s.version}"
        is UpdateState.Ready -> "ready to install ${s.version}"
        is UpdateState.Error -> "error: ${s.message}"
    }

    private fun updateVersion(s: UpdateState): String? = when (s) {
        is UpdateState.UpToDate -> s.version
        is UpdateState.Downloading -> s.version
        is UpdateState.Ready -> s.version
        else -> null
    }

    private fun platform(): String = buildString {
        append(System.getProperty("os.name"))
        append(' ')
        append(System.getProperty("os.version"))
        append(" (")
        append(System.getProperty("os.arch"))
        append(')')
    }
}

/**
 * Beside the settings file, not beside the binary: a packaged install lives
 * somewhere the user cannot write, and a log that silently fails to open is the
 * one thing this whole file exists to prevent.
 */
private fun defaultLogFile(): File? = runCatching {
    File(DesktopSettings.defaultFile().parentFile, "huginn-desktop-kt.log")
}.getOrNull()
