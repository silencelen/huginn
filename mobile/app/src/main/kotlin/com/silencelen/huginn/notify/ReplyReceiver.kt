package com.silencelen.huginn.notify

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

        // Echoed into the thread immediately, before the network is touched. The box
        // the words were typed into is already gone by now, so anything slower leaves
        // a moment where the reply exists nowhere the reader can see.
        update(app, notificationId, title, chat, mine = text)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outcome = withTimeoutOrNull(25_000) { send(app, chat, text) }
                // Nothing more to say on success: the reply is already in the thread,
                // and huginn's answer will arrive as the next message in it. Only
                // failure needs words — the DAEMON'S OWN words when it refused (the
                // audit caught every refusal collapsed into "unreachable", a network
                // failure that did not happen) — and it repeats the text back,
                // because it was typed into a box the system has torn down.
                when {
                    outcome == null -> update(app, notificationId, title, chat,
                        theirs = "Not sent — huginn did not respond. Your message: $text")
                    outcome.isEmpty() -> Unit
                    else -> update(app, notificationId, title, chat,
                        theirs = "Not sent — $outcome. Your message: $text")
                }
            } catch (e: Exception) {
                update(app, notificationId, title, chat,
                    theirs = "Not sent (${e.message ?: "error"}). Your message: $text")
            } finally {
                pending.finish()
            }
        }
    }

    /** Empty on success; the failure's own words otherwise (never a guess). */
    private suspend fun send(app: Context, chat: String, text: String): String {
        val settings = SettingsStore(app)
        val bearer = settings.token.first()
        if (bearer.isBlank()) return "no token is set"
        val base = settings.baseUrl.first()
        val client = HuginnClient({ base }, { bearer })
        // Always the queueing endpoint, never the streaming one: a broadcast
        // receiver has seconds to live and nothing to show a stream to. The host
        // starts the run either way — queued if the chat is busy, immediately if
        // it is not — and the answer comes back as the next finish notification.
        return runCatching { client.queueMessage(chat, text) }
            .fold({ "" }, { it.message ?: "huginn is unreachable" })
    }

    /**
     * Appends a message to the chat's thread, keeping its reply box.
     *
     * The STYLE is recovered from the notification already on screen and the rest is
     * rebuilt. Both halves of that matter: recovering the style is what preserves the
     * conversation — huginn's answer, then your reply, then whatever came next — and
     * rebuilding the rest is necessary because a notification read back from the
     * system has actions that cannot be edited, so reposting it wholesale would drop
     * the reply box.
     *
     * @param mine   a message from the owner (the reply just typed)
     * @param theirs a message from huginn (only ever a failure report; a successful
     *               send says nothing, since huginn's real answer follows on its own)
     */
    private fun update(
        context: Context,
        id: Int,
        title: String,
        chat: String,
        mine: String? = null,
        theirs: String? = null,
    ) {
        if (id == 0 || !SessionWatchWorker.canNotify(context)) return
        SessionWatchWorker.ensureChannels(context)

        // The thread as it stands, read back off the shade. Absent — swiped away, or
        // posted before this app rendered chats as conversations — a fresh one starts
        // with just this message, which is better than dropping the reply entirely.
        val style = SessionWatchWorker.threadFor(context, id, title)
        val now = System.currentTimeMillis()
        if (mine != null) style.addMessage(mine, now, SessionWatchWorker.you(context))
        if (theirs != null) style.addMessage(theirs, now, SessionWatchWorker.huginn())

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
        val builder = NotificationCompat.Builder(context, SessionWatchWorker.CHANNEL_CHATS)
            .setSmallIcon(R.drawable.ic_stat_huginn)
            .setContentTitle(title)
            .setStyle(style)
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
                    )).addRemoteInput(remote)
                    // Re-applied on EVERY rebuild, matching SessionWatchWorker.post().
                    // Free text is arbitrary instruction to Claude on the host, so the
                    // reply box must demand an unlock before it sends — and a rebuilt
                    // notification (echoing "mine", or a failed-send "Not sent…") takes
                    // the platform default of false unless this is set again, which
                    // left a lock-screen free-text reply reachable after one prior
                    // authenticated reply.
                    .setAuthenticationRequired(true)
                    .build()
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
