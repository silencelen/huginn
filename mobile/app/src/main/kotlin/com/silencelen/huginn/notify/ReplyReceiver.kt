package com.silencelen.huginn.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.silencelen.huginn.R
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.ui.QuickActionRules
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
 *
 * ⚠ AND IT HONOURS THE APP LOCK, which it did not. The reply box's only gate was
 * `setAuthenticationRequired(true)`, which asks for the DEVICE to be unlocked, not
 * Huginn, so with "Lock the app" on, whoever held the unlocked phone could type an
 * instruction into a finished chat's notification and this sent it to Claude under
 * the owner's bearer, Act mode included, with the lock never appearing. AskActivity,
 * the other free-text doorway, always checked. Now nothing is sent while the app
 * is locked ([replyGate]): the text is kept as the chat's draft and the
 * notification comes back as "Open Huginn to reply". While the lock is on, a chat
 * notification gets no reply box in the first place ([chatReplyAction]), so this is
 * the guard for one that was posted before the lock was turned on.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val chat = intent.getStringExtra(EXTRA_CHAT) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "huginn" }
        val app = context.applicationContext
        val raw = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)
        val text = raw?.toString()?.trim().orEmpty()

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // Locked until the setting says otherwise, and read from the store,
            // not just the cache: a process started to deliver this broadcast has
            // never run MainActivity, so the cache says "off" whatever the setting
            // is. Unreadable counts as on.
            var lockOn = true
            try {
                lockOn = AppLock.enabledFromStore(app)
                // An empty send is a mis-tap, not an instruction. Restoring the
                // notification rather than leaving the shade half-collapsed keeps the
                // reply box reachable — and it is the ONLY thing that clears
                // SystemUI's spinner, which is started on the send press and cleared
                // by nothing but an update to this id. Returning without re-posting
                // left it spinning for good, with the shade thread recoverable only
                // by swiping it away.
                if (replyStep(raw) == ReplyStep.RESTORE) {
                    update(app, notificationId, title, chat, lockOn = lockOn)
                    return@launch
                }

                val gate = replyGate(
                    lockEnabled = lockOn,
                    lockedNow = AppLock.lockedNow,
                    onScreen = Foreground.resumed,
                    awayAt = AppLock.lastAwayAt,
                    now = SystemClock.elapsedRealtime(),
                )
                if (gate == ReplyGate.HOLD) {
                    // Not sent, and not lost either: it was typed into a box the
                    // system has already torn down. If the draft cannot be written
                    // the words are repeated back, like a failed send's.
                    val kept = runCatching { keepAsDraft(app, chat, text) }.isSuccess
                    update(
                        app, notificationId, title, chat,
                        mine = text,
                        theirs = if (kept) HELD_AS_DRAFT else "$HELD_NOT_SAVED $text",
                        lockOn = true,
                    )
                    return@launch
                }

                // Echoed into the thread immediately, before the network is
                // touched. The box the words were typed into is already gone by
                // now, so anything slower leaves a moment where the reply exists
                // nowhere the reader can see.
                update(app, notificationId, title, chat, mine = text, lockOn = lockOn)

                val outcome = withTimeoutOrNull(25_000) { send(app, chat, text) }
                // Nothing more to say on success: the reply is already in the thread,
                // and huginn's answer will arrive as the next message in it. Only
                // failure needs words — the DAEMON'S OWN words when it refused (the
                // audit caught every refusal collapsed into "unreachable", a network
                // failure that did not happen) — and it repeats the text back,
                // because it was typed into a box the system has torn down.
                when {
                    outcome == null -> update(app, notificationId, title, chat,
                        theirs = "Not sent — huginn did not respond. Your message: $text", lockOn = lockOn)
                    outcome.isEmpty() -> Unit
                    else -> update(app, notificationId, title, chat,
                        theirs = "Not sent — $outcome. Your message: $text", lockOn = lockOn)
                }
            } catch (e: Exception) {
                runCatching {
                    update(app, notificationId, title, chat,
                        theirs = "Not sent (${e.message ?: "error"}). Your message: $text", lockOn = lockOn)
                }
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
     * The held reply, appended to the chat's draft — the same store and the same
     * key the composer reads, so opening the chat after unlocking finds it there.
     */
    private suspend fun keepAsDraft(app: Context, chat: String, text: String) {
        val settings = SettingsStore(app)
        val key = DraftBook.chatKey(chat)
        val drafts = heldDraft(settings.drafts.first(), chat, text)
        settings.setDrafts(drafts)
        // A view model that is already alive read the store once, when it
        // started, and writes its own copy back over it: hand it the text too.
        HeldReplies.sink?.invoke(key, drafts.getValue(key))
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
     * @param theirs a message from huginn (only ever a failure report or a held
     *               reply; a successful send says nothing, since huginn's real
     *               answer follows on its own)
     * @param lockOn the app lock is on: "Open Huginn to reply" instead of the box
     */
    private fun update(
        context: Context,
        id: Int,
        title: String,
        chat: String,
        mine: String? = null,
        theirs: String? = null,
        lockOn: Boolean,
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
        val openChat = PendingIntent.getActivity(
            context, chat.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, SessionWatchWorker.CHANNEL_CHATS)
            .setSmallIcon(R.drawable.ic_stat_huginn)
            .setColor(SessionWatchWorker.ACCENT)
            .setContentTitle(title)
            .setStyle(style)
            .setAutoCancel(true)
            // Silent: this is the echo of something the reader just did, and buzzing
            // a phone to confirm its own keystroke is how a useful channel becomes
            // one that gets muted.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openChat)
        when (chatReplyAction(lockOn)) {
            // The app opens to its lock and then to this chat, draft and all.
            ChatReplyAction.OPEN_TO_REPLY -> builder.addAction(0, OPEN_TO_REPLY, openChat)
            ChatReplyAction.REPLY_BOX -> {
                val remote = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to huginn").build()
                val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
                    action = ACTION
                    putExtra(EXTRA_CHAT, chat)
                    putExtra(EXTRA_NOTIFICATION_ID, id)
                    putExtra(EXTRA_TITLE, title)
                }
                builder.addAction(
                    NotificationCompat.Action.Builder(0, "Reply",
                        PendingIntent.getBroadcast(
                            context, chat.hashCode(), replyIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )).addRemoteInput(remote)
                        // Re-applied on EVERY rebuild, matching SessionWatchWorker.post().
                        // Free text is arbitrary instruction to Claude on the host, so the
                        // reply box must demand a device unlock before it sends — and a
                        // rebuilt notification (echoing "mine", or a failed-send "Not
                        // sent…") takes the platform default of false unless this is set
                        // again, which left a lock-screen free-text reply reachable after
                        // one prior authenticated reply. The APP lock is [replyGate]'s.
                        .setAuthenticationRequired(true)
                        .build()
                )
            }
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    companion object {
        const val ACTION = "com.silencelen.huginn.REPLY"
        const val KEY_REPLY = "reply_text"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_TITLE = "title"

        /** The action a chat notification carries instead of the reply box while the app lock is on. */
        const val OPEN_TO_REPLY = "Open Huginn to reply"

        internal const val HELD_AS_DRAFT = "Not sent — Huginn is locked. Your message is kept as this chat's draft."
        internal const val HELD_NOT_SAVED = "Not sent — Huginn is locked. Your message:"
    }
}

/**
 * Where a reply held back by the app lock goes besides the draft store.
 *
 * The store is what a FRESH view model reads. One that is already alive read it
 * once, when it started, and writes its own copy of the drafts back over it — so a
 * held reply written only to disk would not appear in the composer, and the next
 * draft written anywhere would erase it. The live view model registers here and
 * takes the text into its own drafts; the value is the whole merged draft, so
 * delivering it twice changes nothing.
 */
object HeldReplies {
    @Volatile
    var sink: ((key: String, draft: String) -> Unit)? = null
}

/**
 * What arrived from the shade's reply box: something to send, or nothing —
 * in which case the notification still has to be PUT BACK.
 *
 * SystemUI enables the send button on RAW length, so a reply of spaces, a tab,
 * or a non-breaking space is submittable and arrives here as blank. Pure and
 * top-level so the branch can be asserted; the bug was never in the sending
 * half.
 */
internal enum class ReplyStep { SEND, RESTORE }

internal fun replyStep(raw: CharSequence?): ReplyStep =
    if (raw?.toString()?.trim().isNullOrEmpty()) ReplyStep.RESTORE else ReplyStep.SEND

/** Whether a reply typed into the shade may go to Claude now. */
internal enum class ReplyGate { SEND, HOLD }

/**
 * HOLD exactly when the app is locked: when opening it now would ask for the
 * unlock. That is [AppLock.lockedNow] (locked, or "Lock now"), or the same grace
 * rule MainActivity and AskActivity apply at their doors — which also locks a
 * process with no memory of an unlock (`awayAt == 0`) and a clock that went back.
 * The one case that sends with the lock on, besides the grace, is the app open and
 * unlocked on screen: whoever holds the phone can already type into it directly.
 *
 * Pure, so the decision is asserted without a device; [ReplyReceiver] feeds it.
 */
internal fun replyGate(
    lockEnabled: Boolean,
    lockedNow: Boolean,
    onScreen: Boolean,
    awayAt: Long,
    now: Long,
): ReplyGate = when {
    !lockEnabled -> ReplyGate.SEND
    lockedNow -> ReplyGate.HOLD
    onScreen -> ReplyGate.SEND
    AppLock.shouldLock(enabled = true, awayAt = awayAt, now = now) -> ReplyGate.HOLD
    else -> ReplyGate.SEND
}

/**
 * [drafts] with a held reply APPENDED to [chat]'s draft — appended, never
 * clobbered, under the key both clients use for a chat.
 */
internal fun heldDraft(drafts: Map<String, String>, chat: String, text: String): Map<String, String> {
    val key = DraftBook.chatKey(chat)
    return drafts + (key to QuickActionRules.appendToDraft(drafts[key].orEmpty(), text))
}

/** What a finished chat's notification offers for answering it. */
internal enum class ChatReplyAction { REPLY_BOX, OPEN_TO_REPLY }

/**
 * NO REPLY BOX WHILE THE APP LOCK IS ON. A box that sends for a minute after the
 * app was last open and holds the text after that is a box that sometimes does
 * nothing, which is worse than no box; with the lock on, the notification says
 * where the reply goes instead, and the app's own lock decides.
 */
internal fun chatReplyAction(lockOn: Boolean): ChatReplyAction =
    if (lockOn) ChatReplyAction.OPEN_TO_REPLY else ChatReplyAction.REPLY_BOX
