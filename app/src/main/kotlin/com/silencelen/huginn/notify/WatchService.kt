package com.silencelen.huginn.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.silencelen.huginn.MainActivity
import com.silencelen.huginn.R
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.data.WatchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds a connection to huginn so an alert arrives in seconds.
 *
 * Now a keepalive'd stream rather than a long poll, for one reason: a long poll that
 * has silently died is indistinguishable from one that is patiently waiting. The
 * service went on reporting that it was watching while its socket had in fact been
 * black-holed by a network change hours earlier — and it recovered only when
 * something happened to make it look, which is precisely the moment the alert was
 * needed. The server now sends a keepalive every 25 seconds and the reader times out
 * after 60, so silence has become a fact the app can act on.
 *
 * This is still the FAST path, not the reliable one. Doze powers down the radio and
 * the system may kill the service outright, so [Heartbeat] runs underneath as the
 * floor that survives both, and the host itself alerts over Telegram when no phone
 * has checked in at all. Three mechanisms because each fails differently.
 */
class WatchService : Service() {

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Bumped whenever the network returns, to cut short a backoff that is now stale. */
    @Volatile private var networkGeneration = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android requires the notification promptly after start, so it goes up
        // before anything is known.
        startForegroundCompat(ongoing("Watching huginn", "Connecting…"))
        if (job == null) start()
        // Restart if the system kills us; the watch is the point of the service.
        return START_STICKY
    }

    /**
     * Reconnects the moment the network comes back, instead of waiting out a
     * backoff. Without this, coming home to wifi could leave the phone unwatched for
     * two minutes for no reason other than arithmetic.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkGeneration++ }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun start() {
        val cs = CoroutineScope(SupervisorJob())
        scope = cs
        job = cs.launch {
            val settings = SettingsStore(applicationContext)
            var backoff = 5_000L
            var knownHash: String? = null

            while (isActive) {
                val token = settings.token.first()
                val base = settings.baseUrl.first()
                if (token.isBlank()) {
                    update(ongoing("Watching huginn", "No token set"))
                    delay(30_000)
                    continue
                }
                val id = settings.clientId()
                val canNotify = SessionWatchWorker.canNotify(applicationContext)
                val client = HuginnClient({ base }, { token }, { id }, { canNotify })

                var sawAnything = false
                var rotated = false
                var failure: String? = null
                var latest: Watch? = null

                try {
                    client.watchStream(knownHash).collect { ev ->
                        when (ev) {
                            is WatchEvent.State -> {
                                sawAnything = true
                                knownHash = ev.watch.hash
                                latest = ev.watch
                                settings.noteContact(System.currentTimeMillis())
                                WatchCycle.apply(applicationContext, settings, ev.watch)
                                update(ongoing("Watching huginn", summary(ev.watch)))
                            }
                            // Nothing changed, but the path is proven open — which is
                            // itself the thing worth recording, since the host uses it
                            // to decide whether Telegram needs to step in.
                            WatchEvent.Alive -> {
                                sawAnything = true
                                settings.noteContact(System.currentTimeMillis())
                                latest?.let { update(ongoing("Watching huginn", summary(it))) }
                            }
                            WatchEvent.Rotated -> rotated = true
                            is WatchEvent.Failure -> failure = ev.message
                        }
                    }
                } catch (e: Exception) {
                    failure = e.message ?: "stream ended"
                }

                if (!isActive) break

                when {
                    // A clean rotation is not a fault and must not be backed off, or
                    // the phone would go unwatched every half hour by design.
                    rotated -> backoff = 5_000L
                    // Something got through before the drop, so the setup is sound and
                    // the connection merely aged out. Reconnect at once.
                    sawAnything -> backoff = 5_000L
                    else -> {
                        failure?.let { settings.noteWatchError(it, System.currentTimeMillis()) }
                        update(ongoing("Watching huginn", offlineText(failure)))
                        waitOrNetwork(backoff)
                        backoff = (backoff * 2).coerceAtMost(120_000)
                    }
                }
            }
        }
    }

    /** Sleeps, but wakes early if the network returns in the meantime. */
    private suspend fun waitOrNetwork(ms: Long) {
        val generation = networkGeneration
        var waited = 0L
        while (waited < ms && networkGeneration == generation) {
            delay(1_000)
            waited += 1_000
        }
    }

    private fun offlineText(failure: String?): String =
        if (failure.isNullOrBlank()) "Not connected, retrying"
        else "Not connected: ${failure.take(60)}"

    /** The ongoing notification says what is happening, not merely that we exist. */
    private fun summary(watch: Watch): String {
        val needing = watch.sessions.count { it.value == "attention" }
        val working = watch.sessions.count { it.value == "running" }
        val chatsRunning = watch.chats.count { it.value.running }
        return buildList {
            if (needing > 0) add(if (needing == 1) "1 session needs you" else "$needing sessions need you")
            if (working > 0) add("$working working")
            if (chatsRunning > 0) add("$chatsRunning chat${if (chatsRunning == 1) "" else "s"} running")
            if (isEmpty()) add("${watch.sessions.size} session${if (watch.sessions.size == 1) "" else "s"}, all idle")
        }.joinToString(" · ")
    }

    private fun ongoing(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_huginn)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0, "Stop watching",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, WatchService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    private fun startForegroundCompat(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_ID, n, foregroundTypeCompat())
        } else {
            @Suppress("DEPRECATION")
            startForeground(ONGOING_ID, n)
        }
    }

    private fun foregroundTypeCompat(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            @Suppress("DEPRECATION")
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    private fun update(n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(ONGOING_ID, n) }
    }

    override fun onDestroy() {
        netCallback?.let { cb ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        job?.cancel()
        scope?.cancel()
        job = null
        scope = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ONGOING = "watch_ongoing"
        const val ACTION_STOP = "com.silencelen.huginn.STOP_WATCH"
        private const val ONGOING_ID = 4712

        /**
         * The ongoing channel is deliberately the lowest importance the system
         * allows: it must be present, it should not be noticed.
         */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Watching huginn", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "The quiet notification Android requires while huginn is watched continuously"
                    setShowBadge(false)
                }
            )
        }

        fun start(context: Context) {
            val i = Intent(context, WatchService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WatchService::class.java)) }
        }
    }
}
