package com.silencelen.huginn.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.silencelen.huginn.MainActivity
import com.silencelen.huginn.R
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches huginn continuously so an alert arrives in seconds.
 *
 * The alternative, and what this replaces, is WorkManager's periodic floor of 15
 * minutes — which is fine for "your disk is filling" and useless for "Claude is
 * waiting on an answer". A live connection is the only way to do better, and a
 * live connection on Android means a foreground service with an ongoing
 * notification. That notification is the honest price, so it is made to earn its
 * place: it states what huginn is doing right now.
 *
 * The connection is a long poll against `/v1/watch`, which parks until something
 * an alert depends on actually changes. Idle costs one held request; it does not
 * poll in a loop from the phone.
 */
class WatchService : Service() {

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
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
                val client = HuginnClient({ base }, { token })
                val watch = runCatching { client.watch(knownHash, waitMs = 120_000) }.getOrNull()

                if (watch == null) {
                    // Off the tailnet, asleep, daemon restarting: all ordinary.
                    update(ongoing("Watching huginn", "Not connected, retrying"))
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(120_000)
                    continue
                }
                backoff = 5_000L
                val firstLook = knownHash == null
                knownHash = watch.hash

                val needing = watch.sessions.filterValues { it == "attention" }.keys
                val runningChats = watch.chats.filterValues { it.running }.keys

                // On the FIRST look there is no previous observation, so anything
                // already waiting would arrive as a fresh alert for something that
                // happened before the service started. Record and stay quiet.
                if (firstLook) {
                    settings.setNotifiedSessions(needing)
                    settings.setRunningChats(runningChats)
                } else {
                    val notified = settings.notifiedSessions.first()
                    val fresh = needing - notified
                    if (fresh.isNotEmpty()) {
                        SessionWatchWorker.post(
                            applicationContext,
                            if (fresh.size == 1) "${fresh.first()} needs you" else "${fresh.size} sessions need you",
                            if (fresh.size == 1) "Waiting for your answer" else fresh.joinToString(", "),
                            fresh.first(),
                        )
                    }
                    if (needing != notified) settings.setNotifiedSessions(needing)

                    val wasRunning = settings.runningChats.first()
                    val finished = wasRunning - runningChats
                    if (finished.isNotEmpty()) {
                        val title = watch.chats[finished.first()]?.title
                        SessionWatchWorker.post(
                            applicationContext,
                            if (finished.size == 1) "Chat finished" else "${finished.size} chats finished",
                            title?.take(80) ?: "huginn has answered",
                            null,
                        )
                    }
                    if (runningChats != wasRunning) settings.setRunningChats(runningChats)
                }

                update(ongoing("Watching huginn", summary(watch.sessions, runningChats.size)))
            }
        }
    }

    /** The ongoing notification says what is happening, not merely that we exist. */
    private fun summary(sessions: Map<String, String?>, chatsRunning: Int): String {
        val needing = sessions.count { it.value == "attention" }
        val working = sessions.count { it.value == "running" }
        return buildList {
            if (needing > 0) add(if (needing == 1) "1 session needs you" else "$needing sessions need you")
            if (working > 0) add("$working working")
            if (chatsRunning > 0) add("$chatsRunning chat${if (chatsRunning == 1) "" else "s"} running")
            if (isEmpty()) add("${sessions.size} session${if (sessions.size == 1) "" else "s"}, all idle")
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
