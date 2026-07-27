package com.silencelen.huginn.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silencelen.huginn.MainActivity
import com.silencelen.huginn.R
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Polls the sessions list in the background and notifies when a session starts
 * waiting on you.
 *
 * Polling rather than push on purpose: the daemon is tailnet-only and there is no
 * cloud component to hold a push token, so FCM would mean standing up an internet
 * -facing relay for one notification. A 15-minute WorkManager poll costs nothing
 * and cannot work when the phone is off the tailnet, which is exactly when the
 * notification would be useless anyway.
 */
class SessionWatchWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(context)
        val token = settings.token.first()
        if (token.isBlank()) return Result.success()
        if (!settings.notifyEnabled.first()) return Result.success()
        val base = settings.baseUrl.first()

        val client = HuginnClient({ base }, { token })
        val sessions = try {
            client.sessions()
        } catch (e: Exception) {
            // Off the tailnet, asleep, daemon restarting: all ordinary. Retrying
            // would just burn battery for a notification that is not urgent.
            return Result.success()
        }

        val needing = sessions.filter { it.state == "attention" }.map { it.name }.toSet()
        val alreadyNotified = settings.notifiedSessions.first()

        // Only notify on the TRANSITION into needing-you. Re-notifying every poll
        // for a session that has been waiting an hour trains you to ignore it.
        val fresh = needing - alreadyNotified
        if (fresh.isNotEmpty()) notify(fresh.toList(), sessions.size)
        if (needing != alreadyNotified) settings.setNotifiedSessions(needing)

        return Result.success()
    }

    private fun notify(names: List<String>, total: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sessions needing you", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A Claude Code session on huginn is waiting for an answer"
            }
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION, names.first())
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (names.size == 1) "${names.first()} needs you" else "${names.size} sessions need you"
        val text = if (names.size == 1) "Waiting for your answer on huginn"
        else names.joinToString(", ")

        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_huginn)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFY_ID, n)
    }

    companion object {
        const val CHANNEL = "sessions_attention"
        const val EXTRA_SESSION = "session"
        private const val NOTIFY_ID = 4711
        private const val WORK = "session-watch"

        /** 15 minutes is WorkManager's floor for periodic work. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SessionWatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
