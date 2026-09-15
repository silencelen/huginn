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
 * The buttons on a headroom notification.
 *
 * A sibling of [AnswerReceiver] rather than a branch inside it, because the two
 * are answering different things: an answer selects a ROW of a dialog Claude Code
 * is showing and is refused without the fingerprint of that dialog, while these
 * reach one named session's model and there is no pane, no rows and no question.
 * Folding them together would mean carrying a fingerprint that means nothing, or
 * teaching the fingerprint guard an exception — and that guard is the reason a
 * notification button is allowed to work on a locked phone at all.
 *
 * Every verb here is a BOUNDED CHOICE with a fixed label. Nothing typed by anyone
 * travels through this receiver, so — exactly as with the answer buttons — it
 * carries no authentication requirement. The chat reply box remains the only
 * free-text path off the shade and keeps its own AppLock gate.
 */
class UndoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val verb = intent.getStringExtra(EXTRA_VERB) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        val app = context.applicationContext

        // Taken down at once rather than when the request comes back. The tap has
        // been registered; leaving the buttons up invites a second, and two undos
        // are two model changes typed into one pane.
        if (notificationId != 0) {
            runCatching { NotificationManagerCompat.from(app).cancel(notificationId) }
        }

        // "OK" and "Stay" mean exactly "I have seen this". Dismissing IS the
        // whole action — there is nothing to tell the host, and a round trip that
        // said "the owner acknowledged" would be a fact nothing reads.
        if (verb == HeadroomNotices.VERB_ACK) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(20_000) { send(app, session, verb) }
                    ?: SessionWatchWorker.post(
                        app,
                        "Could not change $session",
                        "huginn did not respond in time.",
                        session,
                    )
            } catch (e: Exception) {
                SessionWatchWorker.post(
                    app,
                    "Could not change $session",
                    e.message ?: "Something went wrong.",
                    session,
                )
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Both moving verbs go to `POST /v1/sessions/:name/headroom/undo`.
     *
     * ⚠ KNOWN GAP, and it is the daemon's to close. That route refuses with 409
     * "huginn has not moved this session" unless appd itself laddered the session
     * — which is never true for the `headroom_ladder_up` OFFER, since the arbiter
     * only offers when Claude Code did the switching natively
     * (`headroom.js`: `native && !(ladder && ladder.to)`). So "Back to Fable"
     * reaches the only route there is and, on appd 3.0.0, is told no.
     *
     * The refusal is SHOWN rather than swallowed, which is [AnswerReceiver]'s
     * rule and the reason it exists: an action button that sometimes does nothing
     * is worse than no button. When appd grows a route that applies the offer,
     * only [VERB_BACK]'s call changes.
     */
    private suspend fun send(app: Context, session: String, verb: String) {
        val settings = SettingsStore(app)
        val bearer = settings.token.first()
        if (bearer.isBlank()) return
        val base = settings.baseUrl.first()
        val client = HuginnClient({ base }, { bearer })

        // The return value is deliberately NOT bound. The daemon answers
        // `applied` or `queued` — a model change must never open the picker
        // inside a running turn, so an undo sent mid-turn waits for the boundary
        // like every other automated send — and the route's answer shape is
        // still settling in a sibling worktree. Ignoring it keeps this compiling
        // either way, and the wording below promises only what is certain.
        runCatching { client.undoLadder(session) }
            .onSuccess {
                // Brief and self-cancelling: confirmation that the request
                // landed, not a new thing to deal with. "Sent" rather than
                // "done": a queued undo arrives at the next turn boundary, and a
                // notification claiming the model had already changed would be
                // wrong for as long as the turn runs.
                SessionWatchWorker.post(
                    app,
                    if (verb == HeadroomNotices.VERB_BACK) "Back to Fable sent"
                    else "Undo sent for $session",
                    "huginn will apply it at the next turn boundary and will not " +
                        "move this session again for a while.",
                    session,
                    isResult = true,
                )
            }
            .onFailure { e ->
                // The daemon's own sentence, verbatim — a 409 here names the
                // reason, and replacing it with "could not undo" would throw away
                // the one thing the reader pressed the button to find out.
                val why = (e as? HuginnClient.HuginnException)?.message
                    ?: e.message ?: "Could not change the model"
                SessionWatchWorker.post(app, "Not changed: $session", why, session, isResult = true)
            }
    }

    companion object {
        const val ACTION = "com.silencelen.huginn.HEADROOM"
        const val EXTRA_SESSION = "session"
        const val EXTRA_VERB = "verb"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        /**
         * A DISTINCT request code per button.
         *
         * Sharing one would make FLAG_UPDATE_CURRENT hand every button the same
         * intent, so both would do whichever was built last — which here means
         * "OK" silently undoing a ladder move. Offset well clear of
         * [AnswerReceiver]'s `session.hashCode() * 31 + option` codes.
         */
        fun requestCodeFor(session: String, verb: String): Int =
            session.hashCode() * 31 + 900 + verb.hashCode().and(0x3f)
    }
}
