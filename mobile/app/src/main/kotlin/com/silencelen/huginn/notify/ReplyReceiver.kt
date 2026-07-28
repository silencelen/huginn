package com.silencelen.huginn.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.silencelen.huginn.R
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Replies to a finished chat from the notification shade.
 *
 * The counterpart to [AnswerReceiver]: that one answers a session's fixed question
 * with a digit, this one continues a conversation with a sentence. Together they
 * cover both things huginn asks for without the phone ever being unlocked.
 *
 * The notification is UPDATED rather than dismissed, which is the one behaviour
 * worth being deliberate about. Every messaging app does the same thing and for the
 * same reason: the reply was typed blind into a shade, and a notification that
 * simply vanishes leaves no evidence of whether the send worked. So it stays,
 * showing what was sent and then what became of it.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra(EXTRA_CHAT) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "huginn" }
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        // An empty send is a mis-tap, not an instruction. Restoring the notification
        // rather than leaving the shade half-collapsed keeps the reply box reachable.
        if (text.isEmpty()) return

        val app = context.applicationContext
        val pending = goAsync()

        update(app, notificationId, title, "Sending: $text", chat)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = withTimeoutOrNull(25_000) { send(app, chat, text) }
                when (ok) {
                    // The chat is now running, and its finish will arrive as a fresh
                    // notification through the ordinary path — so this one says only
                    // that the message left, and offers the box again for a follow-up.
                    true -> update(app, notificationId, title, "Sent: $text", chat)
                    // The text is repeated back on every failure. It was typed into a
                    // box the system has already torn down; losing it would mean the
                    // sentence has to be written again from memory.
                    false -> update(app, notificationId, title,
                        "Not sent — huginn is unreachable. Your message: $text", chat)
                    null -> update(app, notificationId, title,
                        "Not sent — huginn did not respond. Your message: $text", chat)
                }
            } catch (e: Exception) {
                update(app, notificationId, title,
                    "Not sent (${e.message ?: "error"}). Your message: $text", chat)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun send(app: Context, chat: String, text: String): Boolean {
        val settings = SettingsStore(app)
        val bearer = settings.token.first()
        if (bearer.isBlank()) return false
        val base = settings.baseUrl.first()
        val client = HuginnClient({ base }, { bearer })
        // Always the queueing endpoint, never the streaming one: a broadcast
        // receiver has seconds to live and nothing to show a stream to. The host
        // starts the run either way — queued if the chat is busy, immediately if
        // it is not — and the answer comes back as the next finish notification.
        return runCatching { client.queueMessage(chat, text) }.isSuccess
    }

    /**
     * Rebuilds the notification in place, keeping its reply box.
     *
     * Built fresh rather than recovered from the active notification: reading one
     * back gives a [Notification] whose actions cannot be edited, so preserving the
     * box would mean posting the old object and losing the new text.
     */
    private fun update(context: Context, id: Int, title: String, body: String, chat: String) {
        if (id == 0 || !SessionWatchWorker.canNotify(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                SessionWatchWorker.CHANNEL,
                "Sessions needing you",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val open = Intent(context, com.silencelen.huginn.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(SessionWatchWorker.EXTRA_CHAT, chat)
        }
        val remote = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to huginn").build()
        val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_CHAT, chat)
            putExtra(EXTRA_NOTIFICATION_ID, id)
            putExtra(EXTRA_TITLE, title)
        }
        val builder = NotificationCompat.Builder(context, SessionWatchWorker.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_huginn)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            // Silent: this is the echo of something the reader just did, and buzzing
            // a phone to confirm its own keystroke is how a useful channel becomes
            // one that gets muted.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context, chat.hashCode(), open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                NotificationCompat.Action.Builder(0, "Reply",
                    android.app.PendingIntent.getBroadcast(
                        context, chat.hashCode(), replyIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_MUTABLE,
                    )).addRemoteInput(remote).build()
            )
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    companion object {
        const val ACTION = "com.silencelen.huginn.REPLY"
        const val KEY_REPLY = "reply_text"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_TITLE = "title"
    }
}
