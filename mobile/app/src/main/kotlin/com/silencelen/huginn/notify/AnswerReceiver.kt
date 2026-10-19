package com.silencelen.huginn.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers a session's question straight from the notification.
 *
 * The point of the whole feature: a permission prompt reaches the lock screen in
 * seconds and can be answered there, instead of unlocking, finding the app, finding
 * the session and tapping the same option.
 *
 * Every answer carries the FINGERPRINT of the question it was offered for, and the
 * host refuses it if the pane has moved on. That check has to happen on the host, in
 * the same request, because this receiver cannot hold the pane still: between reading
 * it and sending a digit, the session may have been answered in tmux or moved to a
 * different question — and a stray digit in a Claude Code pane is not a harmless
 * keystroke, it could accept a prompt that was never seen.
 *
 * A tap therefore has exactly three outcomes, and all three say something. Silence
 * would be the worst of them: an action button that sometimes does nothing is worse
 * than no button.
 *
 * ⚠ AND NO FINGERPRINT, NO ANSWER. A blank one used to be sent as none at all,
 * which is the one request the host cannot check: the digit lands on whatever
 * dialog is on the pane by then. Buttons are only drawn with a fingerprint, so a
 * blank one means a notification from before that rule; it is refused here and
 * the owner is sent to the session to answer there ([decide]).
 */
class AnswerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val option = intent.getIntExtra(EXTRA_OPTION, 0)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val fingerprint = intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val step = decide(option, fingerprint)
        if (step == Step.IGNORE) return

        val app = context.applicationContext

        // Taken down at once rather than when the request comes back. The tap has been
        // registered and leaving the buttons on screen invites a second, and a double
        // answer is two keystrokes into a pane that expected one.
        if (notificationId != 0) {
            runCatching { NotificationManagerCompat.from(app).cancel(notificationId) }
        }

        if (step == Step.OPEN_SESSION) {
            // A receiver cannot open an activity from a notification button, so the
            // way to the session is a notification whose tap opens it.
            SessionWatchWorker.post(
                app,
                "Not answered: $session",
                "This button did not say which question it was for. Open the session to answer there.",
                session,
            )
            return
        }

        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(20_000) { answer(app, session, option, label, fingerprint) }
                    ?: SessionWatchWorker.post(app, "Could not answer $session",
                        "huginn did not respond in time. The question is still waiting.", session)
            } catch (e: Exception) {
                SessionWatchWorker.post(app, "Could not answer $session",
                    e.message ?: "Something went wrong sending the answer.", session)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun answer(
        app: Context,
        session: String,
        option: Int,
        label: String,
        fingerprint: String,
    ) {
        val settings = SettingsStore(app)
        val bearer = settings.token.first()
        if (bearer.isBlank()) return
        val base = settings.baseUrl.first()
        val client = HuginnClient({ base }, { bearer })

        val result = runCatching {
            client.answerPrompt(session, option, fingerprint)
        }
        result.onSuccess { r ->
            if (r.ok) {
                // Brief and self-cancelling: confirmation that the keystroke landed,
                // not a new thing to deal with.
                SessionWatchWorker.post(
                    app,
                    "Answered $session",
                    "${r.option}) ${r.label ?: label}",
                    session,
                )
            } else {
                // The client now decodes the host's 409 into a result (so the UI can
                // steer on `reason`); an ok=false here is that refusal — the tap was
                // correct when it was offered and the world changed underneath it.
                SessionWatchWorker.post(
                    app,
                    "Not answered: $session",
                    r.error ?: "The question moved on",
                    session,
                )
            }
        }.onFailure { e ->
            val why = when {
                e is HuginnClient.HuginnException && e.code == 409 ->
                    // Expected, and worth saying plainly: the tap was correct when it
                    // was offered and the world changed underneath it.
                    e.message
                else -> e.message ?: "Could not send the answer"
            }
            SessionWatchWorker.post(app, "Not answered: $session", why, session)
        }
    }

    /** What one tap does, decided before anything is sent. */
    enum class Step {
        /** Not an answer at all (no option): nothing happens. */
        IGNORE,

        /** No fingerprint to bind it to a question: nothing is sent; the session is offered instead. */
        OPEN_SESSION,

        /** Sent, with the fingerprint, for the host to check against the pane. */
        SEND,
    }

    companion object {
        /**
         * The whole decision, pure so it can be tested off a device. A blank
         * [fingerprint] is never sent as "none": that would ask the host to press
         * [option] on whatever is on the pane now.
         */
        fun decide(option: Int, fingerprint: String?): Step = when {
            option < 1 -> Step.IGNORE
            fingerprint.isNullOrBlank() -> Step.OPEN_SESSION
            else -> Step.SEND
        }

        const val ACTION = "com.silencelen.huginn.ANSWER"
        const val EXTRA_SESSION = "session"
        const val EXTRA_OPTION = "option"
        const val EXTRA_LABEL = "label"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
