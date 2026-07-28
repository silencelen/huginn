package com.silencelen.huginn.notify

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
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

        // Posted from the payload first, and without touching the network: the phone
        // may have been woken from Doze with a few seconds of grace, and an alert that
        // depends on a round trip to arrive is an alert that sometimes does not.
        SessionWatchWorker.post(
            applicationContext,
            title,
            text,
            if (kind == "session_attention") subject else null,
        )

        // Then bring the app's own record up to date, so the ten-minute alarm does not
        // later rediscover this same transition and repeat it. Best effort by design —
        // if it fails the worst case is one duplicate, which is much better than a
        // missed alert.
        CoroutineScope(Dispatchers.IO).launch {
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
                WatchCycle.apply(context, settings, watch)
            }
        }
    }
}
