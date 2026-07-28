package com.silencelen.huginn.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
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
 * The opportunistic check: whenever the system happens to give the app a moment,
 * look and see.
 *
 * Kept, but demoted, and it is worth recording why it cannot be the main mechanism.
 * WorkManager work is DEFERRED BY DOZE — a periodic job does not run while the
 * device is idle, it waits for a maintenance window or for the screen to come on.
 * So this delivers promptly all day and then nothing at all overnight, which is
 * exactly the symptom that prompted the rewrite. [Heartbeat] is the path that fires
 * while the phone sleeps; this one still earns its place by costing nothing and by
 * covering the case where the alarm has been dropped (an app update cancels pending
 * alarms) but WorkManager's own persistence has not.
 *
 * Also home to [post] and [canNotify], which everything else notifies through.
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
        val id = settings.clientId()
        val canNotify = canNotify(context)

        val client = HuginnClient({ base }, { token }, { id }, { canNotify })
        val watch = try {
            client.watch(knownHash = null, waitMs = 0)
        } catch (e: Exception) {
            // Off the tailnet, asleep, daemon restarting: all ordinary. Retrying
            // would just burn battery for a notification that is not urgent.
            settings.noteWatchError(e.message ?: "unreachable", System.currentTimeMillis())
            return Result.success()
        }
        settings.noteContact(System.currentTimeMillis())

        // The same cycle the stream and the alarm run. Sharing it is what makes three
        // overlapping mechanisms safe: the comparison baseline is persisted, so
        // whichever of them notices a transition first consumes it, and the others
        // find nothing left to announce.
        WatchCycle.apply(context, settings, watch, client)
        return Result.success()
    }

    companion object {
        const val CHANNEL = "sessions_attention"

        /**
         * A separate channel for chat results, because they are a different kind of
         * interruption and were sharing the sessions one. A session waiting on you
         * is blocking — work has stopped until you answer — whereas a chat that
         * finished is news you asked for. Sharing a channel meant silencing the
         * chatty one silenced the blocking one too, and Android gives that choice to
         * the person holding the phone only if the app bothers to split them.
         *
         * A new id rather than reworking the old channel, necessarily: Android
         * ignores importance changes to a channel that already exists, since the
         * user's own setting owns it from creation onward.
         */
        const val CHANNEL_CHATS = "chat_results"
        const val EXTRA_SESSION = "session"
        const val EXTRA_CHAT = "chat"

        /** Both channels, created together so either can be posted to at any time. */
        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sessions needing you", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A Claude Code session on huginn is waiting for an answer"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CHATS, "Chat results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A chat you started on huginn has finished"
                }
            )
        }
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

        /** True when the system will actually deliver what we post. */
        fun canNotify(context: Context): Boolean {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        /** One tappable answer to a session's question. */
        data class AnswerOption(val number: Int, val label: String)

        /**
         * Android renders at most three action buttons, so a longer question keeps its
         * first two and is finished in the app. Two rather than three, so there is
         * always room for the tap that opens the session — a four-option prompt whose
         * buttons were all answers would offer no way to see the rest.
         */
        private const val MAX_ACTIONS = 3

        /**
         * A stable slot per session, so two sessions waiting at once do not overwrite
         * each other's question — which, with a single shared id, meant the first was
         * silently replaced and its answer buttons vanished.
         *
         * Nudged off the two fixed ids rather than assumed distinct from them: a
         * collision with the ongoing foreground notification would replace the thing
         * Android requires the watch service to keep showing.
         */
        fun notificationIdFor(session: String?): Int {
            if (session == null) return NOTIFY_ID
            val id = session.hashCode()
            return if (id == NOTIFY_ID || id == 4712) id + 7 else id
        }

        /**
         * Posts a notification, creating the channel first. Shared by the poller
         * and by the test button in Settings, so what you verify is the same path
         * that fires for real.
         */
        /**
         * @param replyChat when set, the notification carries an inline reply box
         *        whose text is posted to that chat — so answering a finished chat
         *        costs a swipe-down and a sentence rather than unlocking, finding
         *        the app, finding the chat and scrolling to the bottom of it.
         */
        fun post(
            context: Context,
            title: String,
            text: String,
            session: String?,
            answers: List<AnswerOption> = emptyList(),
            fingerprint: String? = null,
            replyChat: String? = null,
        ) {
            if (!canNotify(context)) return
            val channel = if (replyChat != null) CHANNEL_CHATS else CHANNEL
            ensureChannels(context)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (session != null) putExtra(EXTRA_SESSION, session)
                if (replyChat != null) putExtra(EXTRA_CHAT, replyChat)
            }
            val pending = PendingIntent.getActivity(
                context,
                // Distinct per target, for the same reason the answer buttons are:
                // one shared request code plus FLAG_UPDATE_CURRENT means the newest
                // notification's extras are handed to every earlier one, so tapping
                // an older alert opens whatever arrived last.
                (session ?: replyChat)?.hashCode() ?: 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notificationId = notificationIdFor(session ?: replyChat?.let { "chat:$it" })
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_huginn)
                .setContentTitle(title)
                .setContentText(text)
                // The question is often longer than one line, and truncating it to
                // "Do you want to create the fi…" defeats the purpose of sending it.
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            if (session != null) {
                for (a in answers.take(MAX_ACTIONS)) {
                    val answerIntent = Intent(context, AnswerReceiver::class.java).apply {
                        action = AnswerReceiver.ACTION
                        putExtra(AnswerReceiver.EXTRA_SESSION, session)
                        putExtra(AnswerReceiver.EXTRA_OPTION, a.number)
                        putExtra(AnswerReceiver.EXTRA_LABEL, a.label)
                        putExtra(AnswerReceiver.EXTRA_FINGERPRINT, fingerprint.orEmpty())
                        putExtra(AnswerReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                    }
                    builder.addAction(
                        0,
                        // Numbered as the pane numbers them, so what you tap on the lock
                        // screen matches what you would have picked looking at the session.
                        "${a.number}. ${a.label.take(28)}",
                        PendingIntent.getBroadcast(
                            context,
                            // A DISTINCT request code per option. Sharing one would make
                            // FLAG_UPDATE_CURRENT hand every button the same intent, so
                            // all three would answer whichever was built last — a bug
                            // that looks like the wrong option being chosen at random.
                            requestCodeFor(session, a.number),
                            answerIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
            // A reply box for CHATS ONLY, and deliberately never for a session.
            //
            // A session's notification offers the options huginn itself put on the
            // screen, and nothing else. That bound is what lets the answer buttons
            // work on a locked phone with no authentication: whoever taps one can
            // only choose among answers the session was already waiting for, so the
            // worst case is a wrong choice among expected ones. Free text into a
            // pane is a different power entirely — it is arbitrary instruction to
            // Claude Code on the host — and offering that from a lock screen would
            // demand a login in front of it. Owner's rule, 2026-07-28.
            //
            // A session that is asking something the buttons cannot express is
            // answered by opening the app, which is one tap away on the same
            // notification.
            if (replyChat != null) {
                val remote = RemoteInput.Builder(ReplyReceiver.KEY_REPLY)
                    .setLabel("Reply to huginn")
                    .build()
                val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
                    action = ReplyReceiver.ACTION
                    putExtra(ReplyReceiver.EXTRA_CHAT, replyChat)
                    putExtra(ReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                    putExtra(ReplyReceiver.EXTRA_TITLE, title)
                }
                builder.addAction(
                    NotificationCompat.Action.Builder(0, "Reply", PendingIntent.getBroadcast(
                        context,
                        // Offset from the answer buttons' codes, which are
                        // session.hashCode()*31 + option: a bare hashCode could
                        // collide with one of those and hand the reply box an
                        // answer intent.
                        replyChat.hashCode() * 31 + 99,
                        replyIntent,
                        // MUTABLE, and it has to be: RemoteInput delivers what was
                        // typed by writing it into this very intent, which an
                        // immutable one forbids — the reply arrives empty.
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ))
                        .addRemoteInput(remote)
                        // Free text is arbitrary instruction to Claude on the host, so
                        // the device must be unlocked before it is sent — the same bar
                        // the owner set for sessions, met here rather than by dropping
                        // the feature. The ANSWER buttons deliberately carry no such
                        // requirement: a choice among offered options is bounded, which
                        // is exactly why it needs no login.
                        .setAuthenticationRequired(true)
                        .build()
                )
            }
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }

        private fun requestCodeFor(session: String, option: Int): Int =
            session.hashCode() * 31 + option
    }
}
