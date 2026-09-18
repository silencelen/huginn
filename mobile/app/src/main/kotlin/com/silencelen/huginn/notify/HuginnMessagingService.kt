package com.silencelen.huginn.notify

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import androidx.core.app.NotificationManagerCompat
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Push arriving from huginn by way of Google.
 *
 * This is the fast path that actually reaches a sleeping phone. The alarm in
 * [Heartbeat] gets there within ten minutes and survives anything; a high-priority
 * FCM message gets there in seconds. Both remain, because they fail differently — FCM
 * needs Play Services, a network, and an app that has not been force-stopped, none of
 * which the alarm cares about.
 *
 * Messages are DATA-ONLY, which is what makes [onMessageReceived] run even while the
 * app is backgrounded. Had the host sent a `notification` block instead, the system
 * would draw it without consulting this app at all — and the app would have no idea it
 * had already told you, so the next alarm would announce the same thing again.
 */
class HuginnMessagingService : FirebaseMessagingService() {

    /**
     * A new or rotated registration token. Firebase reissues these after a reinstall,
     * a restore, or at its own discretion, and a token the host does not know about
     * delivers nothing — so this is the one callback that must not be dropped.
     */
    override fun onNewToken(token: String) {
        // runBlocking, deliberately: the process may have been started solely to
        // deliver this callback, and returning first would let it be torn down with the
        // registration half-done. Bounded so it cannot hang the service.
        runBlocking {
            withTimeoutOrNull(20_000) { register(applicationContext, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"].orEmpty().ifBlank { "huginn" }
        val text = data["text"].orEmpty()
        val subject = data["subject"]?.takeIf { it.isNotBlank() }
        val kind = data["kind"].orEmpty()

        // The question's options, when it has any, become the notification's buttons —
        // so a permission prompt can be answered from the lock screen rather than by
        // unlocking, finding the app and finding the session.
        val answers = parseAnswers(data["options"])

        // Withheld when it would describe the very screen the reader has open — the
        // answer is already streaming into the UI in front of them, and a buzz that
        // carries nothing teaches that buzzes carry nothing. Everything AFTER the
        // post still runs: the arrival is recorded, the session claimed, the state
        // reconciled, so the suppressed notification cannot come back later through
        // the alarm rediscovering the same transition.
        // A resolution is not a notification — it is an instruction to take one
        // down. The question was answered in tmux or from another device, and the
        // "needs you" still in the shade now invites a tap whose fingerprint the
        // host will refuse. Everything below the post still runs: the arrival is
        // counted (the host counted the send, and an uncounted arrival would read
        // as a dropped push and tighten the heartbeat), and the reconcile advances
        // the shared baseline.
        if (kind == "session_resolved" && subject != null) {
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .cancel(SessionWatchWorker.notificationIdFor(subject))
            }
        }

        // Headroom, which the daemon pushes for the two kinds a reader may want
        // to act on — a ladder move and the one-shot offer to undo a native one.
        // The other two (`headroom_limit`, `headroom_resumed`) ride the watch
        // digest instead and are decided in WatchNotifier; both are handled here
        // as well so that a daemon which later pushes them renders the same
        // notice rather than an unknown-kind fallback. See HeadroomNotices.
        val headroom = if (HeadroomNotices.isHeadroomKind(kind)) {
            // The RAW title, not the "huginn" fallback above: a headroom notice
            // has its own sentence to fall back to, and it names the session.
            //
            // `options` is a plain array of LABELS here, not the numbered answer
            // rows `parseAnswers` reads — a headroom button selects nothing on a
            // pane, so it has no number and needs no fingerprint. `payload`
            // carries the session and the rungs.
            HeadroomNotices.fromPush(
                kind = kind,
                title = data["title"].orEmpty(),
                text = text,
                subject = subject,
                payload = data["payload"],
                options = data["options"],
            )
        } else null
        if (headroom != null) {
            // The focused-target rule, same as everywhere else: a limit notice
            // about the session on screen says nothing the screen does not. The
            // ladder kinds are NOT suppressed — they carry buttons, and a button
            // withheld is a choice the reader never gets offered.
            val hidden = headroom.kind == HeadroomNotices.Kind.LIMIT &&
                Foreground.showsSession(subject)
            if (!hidden) {
                val a = HeadroomNotices.postArgs(headroom)
                SessionWatchWorker.post(
                    applicationContext,
                    a.title,
                    a.text,
                    a.session,
                    replyChat = a.replyChat,
                    fingerprint = a.fingerprint,
                    actions = a.actions,
                    key = a.key,
                    isResult = a.isResult,
                )
            }
        }

        // A lead has proposed a cluster. Its two bounded buttons are built here
        // and NOT from the wire's `options` — see [ProjectNotices] for why a
        // payload may not name a button that spawns twelve sessions.
        val proposal = ProjectNotices.fromPush(
            kind = kind,
            title = data["title"].orEmpty(),
            text = text,
            subject = subject,
            payload = data["payload"],
        )
        if (proposal != null) {
            val a = ProjectNotices.postArgs(proposal)
            SessionWatchWorker.post(
                applicationContext,
                a.title,
                a.text,
                a.session,
                answers = a.answers,
                fingerprint = a.fingerprint,
                replyChat = a.replyChat,
                isResult = a.isResult,
                key = a.key,
                project = a.project,
                projectActions = a.projectActions,
            )
        }

        val redundant = proposal != null || headroom != null || kind == "session_resolved" || when (kind) {
            "chat_finished" -> Foreground.showsChat(subject)
            "session_attention", "session_finished" -> Foreground.showsSession(subject)
            else -> false
        }

        // Posted from the payload first, and without touching the network: the phone
        // may have been woken from Doze with a few seconds of grace, and an alert that
        // depends on a round trip to arrive is an alert that sometimes does not.
        if (!redundant) SessionWatchWorker.post(
            applicationContext,
            title,
            text,
            // Carried for BOTH session kinds, so tapping either opens that session.
            // It does not imply buttons: those come from `answers`, which the host
            // only ever attaches to a question. A finished session therefore gets
            // the deep link and no actions, which is exactly right — there is
            // nothing to answer.
            if (kind == "session_attention" || kind == "session_finished") subject else null,
            answers,
            data["fingerprint"],
            // A finished chat can be continued from the shade. Sessions cannot: a
            // tmux pane takes keystrokes, not messages, and free text typed at one
            // lands wherever the cursor happens to be — and the owner's rule is that
            // a notification may only offer choices huginn itself put on the screen.
            replyChat = if (kind == "chat_finished") subject else null,
            isResult = kind == "session_finished",
        )

        // Then bring the app's own record up to date, so the ten-minute alarm does not
        // later rediscover this same transition and repeat it. Best effort by design —
        // if it fails the worst case is one duplicate, which is much better than a
        // missed alert.
        CoroutineScope(Dispatchers.IO).launch {
            // Proof that push works — and the moment to act on it. Recording the
            // arrival is not enough on its own: the pending alarm was armed with
            // whatever cadence was true when it was set, so without re-arming here
            // a healthy setup still wakes the device on the tight schedule until
            // the next beat happens to notice. Feeding the watchdog on every push
            // means a phone receiving pushes may never wake for the alarm at all.
            runCatching {
                val settings = SettingsStore(applicationContext)
                val now = System.currentTimeMillis()
                settings.notePushArrived(now)
                if (settings.notifyEnabled.first()) {
                    // Read back AFTER recording the arrival, so this push counts
                    // toward the tally that decides the cadence.
                    Heartbeat.arm(applicationContext, Heartbeat.intervalFor(
                        settings.pushesSent.first(),
                        settings.pushesReceived.first(),
                    ))
                }
            }
            // Claim this session BEFORE reconciling. Otherwise the reconcile's own
            // WatchNotifier sees the same transition as fresh and posts a SECOND
            // notification under the same per-session id — and its text is the
            // generic "Waiting for your answer" whenever its prompt fetch comes
            // back empty, silently replacing the question and its answer buttons.
            // Observed on-device: the first attention push rendered as the generic
            // line, the second as the question. The push already told the user;
            // nothing downstream should re-announce it.
            if (kind == "session_attention" && subject != null) {
                runCatching {
                    val settings = SettingsStore(applicationContext)
                    settings.setNotifiedSessions(settings.notifiedSessions.first() + subject)
                }
            }
            // The same claim for a ladder move, and for the same reason: the
            // reconcile below sees `laddered` gain this name and would post the
            // identical notice a second time. The push already told the reader;
            // claiming the name consumes the transition. Left deliberately blank
            // as to WHICH family — any value ends the edge, and the digest writes
            // the real one on the next pass.
            val laddered = headroom?.takeIf { it.kind == HeadroomNotices.Kind.DOWNGRADED }?.session
            if (laddered != null) {
                runCatching {
                    val settings = SettingsStore(applicationContext)
                    val known = settings.ladderedSessions.first()
                    if (!known.containsKey(laddered)) {
                        settings.setLadderedSessions(known + (laddered to HeadroomNotices.PUSHED))
                    }
                }
            }
            withTimeoutOrNull(15_000) { reconcile(applicationContext) }
        }
    }

    companion object {

        /**
         * Hands the current token to huginn. Safe to call on every app start: the host
         * only persists a token that actually changed.
         */
        suspend fun register(context: Context, token: String): Boolean {
            val settings = SettingsStore(context)
            val bearer = settings.token.first()
            if (bearer.isBlank()) return false        // not configured yet; a later start will
            val base = settings.baseUrl.first()
            val installId = settings.clientId()
            val client = HuginnClient({ base }, { bearer })
            return runCatching {
                client.registerPush(installId, token, android.os.Build.MODEL)
                settings.notePushToken(token, System.currentTimeMillis())
                true
            }.getOrDefault(false)
        }

        /**
         * Asks Firebase for the current token and registers it.
         *
         * Called at app start as well as from [onNewToken], because a token issued while
         * the app had no server configured — or while huginn was unreachable — would
         * otherwise never be handed over, and push would appear to be set up while
         * nothing could actually be delivered.
         */
        fun syncToken(context: Context) {
            runCatching {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    val token = task.result
                    if (!task.isSuccessful || token.isNullOrBlank()) return@addOnCompleteListener
                    CoroutineScope(Dispatchers.IO).launch { register(context, token) }
                }
            }
        }

        /**
         * Re-registers the push token when the host does not have the current one.
         *
         * The heartbeat's job, and a real gap it was documented as covering: onNewToken
         * registers ONCE with no retry, and [register] records the token only after the
         * host accepts it — so a rotation while the tailnet was down (a nightly event,
         * not an exceptional one) left this phone registered under a token FCM would no
         * longer deliver to. Nothing noticed, because the app looked configured. The
         * only repair was opening the app by hand, which is exactly what push exists to
         * make unnecessary.
         *
         * Silent when nothing has changed: the comparison is against what was last
         * ACCEPTED, so an ordinary beat costs one local Firebase lookup and no request.
         */
        suspend fun ensureTokenRegistered(context: Context): Boolean {
            val current = currentToken() ?: return false
            val known = runCatching { SettingsStore(context).pushToken.first() }.getOrDefault("")
            if (current == known) return false
            return register(context, current)
        }

        /** The device's current FCM token, or null if Firebase cannot say. */
        private suspend fun currentToken(): String? = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        val t = if (task.isSuccessful) task.result else null
                        if (cont.isActive) cont.resume(t?.takeIf { it.isNotBlank() }) { }
                    }
                }
            }
        }.getOrNull()

        /**
         * The options list, which travels as a JSON string because an FCM data payload
         * is string-to-string. A malformed or absent value yields no buttons rather
         * than dropping the alert: being told a session needs you without buttons is
         * far better than not being told.
         */
        private fun parseAnswers(raw: String?): List<SessionWatchWorker.Companion.AnswerOption> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<WireOption>>(raw)
                    .filter { it.number >= 1 && it.label.isNotBlank() }
                    .map { SessionWatchWorker.Companion.AnswerOption(it.number, it.label) }
            }.getOrDefault(emptyList())
        }

        @Serializable
        private data class WireOption(val number: Int = 0, val label: String = "")

        /** Re-reads huginn's state so the shared baseline consumes this transition. */
        private suspend fun reconcile(context: Context) {
            val settings = SettingsStore(context)
            val bearer = settings.token.first()
            if (bearer.isBlank()) return
            val base = settings.baseUrl.first()
            val installId = settings.clientId()
            val canNotify = SessionWatchWorker.canNotify(context)
            val client = HuginnClient({ base }, { bearer }, { installId }, { canNotify })
            runCatching {
                val watch = client.watch(knownHash = null, waitMs = 0)
                settings.noteContact(System.currentTimeMillis())
                WatchNotifier.apply(context, settings, watch)
            }
        }
    }
}
