package com.silencelen.huginn.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.ui.ProjectRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Spawn and Discard buttons on a proposal notification.
 *
 * A sibling of [UndoReceiver] rather than a branch inside it: that one names a
 * SESSION and changes its model, this one names a PROJECT and a manifest rev.
 * Folding them together would mean a session field that is never a session and a
 * rev that is usually meaningless.
 *
 * Both verbs are bounded choices with fixed labels — see [ProjectNotices] for why
 * Edit is not among them — so nothing typed by anyone passes through here and no
 * authentication gate is required in front of it.
 *
 * ⚠ THE OUTCOME IS ALWAYS SAID. A spawn is a loop over tmux that answers HTTP 200
 * with `ok:false` whenever a role could not be created, and the 409s (the
 * headroom STOP sentinel, a manifest that moved under the card) are states of the
 * house rather than faults. All three are posted back in the daemon's own words:
 * an action button that sometimes does nothing is worse than no button.
 */
class ProjectActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val projectId = intent.getStringExtra(EXTRA_PROJECT) ?: return
        val verb = intent.getStringExtra(EXTRA_VERB) ?: return
        if (verb !in ProjectNotices.VERBS) return
        val rev = intent.getIntExtra(EXTRA_MANIFEST_REV, 0)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        val app = context.applicationContext

        // Taken down at once rather than when the request returns. The tap has
        // been registered; leaving the buttons up invites a second, and two
        // spawns are two clusters.
        if (notificationId != 0) {
            runCatching { NotificationManagerCompat.from(app).cancel(notificationId) }
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(30_000) { send(app, projectId, verb, rev) }
                    ?: post(app, projectId, "huginn did not respond in time. The proposal is still waiting.")
            } catch (e: Exception) {
                post(app, projectId, e.message ?: "Something went wrong.")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun send(app: Context, projectId: String, verb: String, rev: Int) {
        val settings = SettingsStore(app)
        val bearer = settings.token.first()
        if (bearer.isBlank()) return
        val base = settings.baseUrl.first()
        val client = HuginnClient({ base }, { bearer })

        when (verb) {
            ProjectNotices.VERB_DISCARD -> runCatching { client.discardProposal(projectId) }
                .onSuccess {
                    post(
                        app,
                        projectId,
                        "The proposal was turned down. The lead has been told and may send a revised one.",
                        title = "Discarded",
                    )
                }
                .onFailure { post(app, projectId, refusalOf(it)) }

            ProjectNotices.VERB_SPAWN -> runCatching { client.spawnProject(projectId, rev) }
                .onSuccess { outcome ->
                    // A refusal stopped everything before a session was made, and
                    // the daemon's sentence about it IS the news — the STOP
                    // sentinel and the stale rev both read as states rather than
                    // errors, and a client summary of either helps nobody.
                    val refusal = outcome.refusal
                    if (refusal != null) {
                        post(app, projectId, refusal, title = "Not spawned")
                        return@onSuccess
                    }
                    val result = outcome.result ?: return@onSuccess
                    val lines = listOf(ProjectRules.spawnWords(result)) + ProjectRules.spawnFailures(result)
                    post(
                        app,
                        projectId,
                        lines.joinToString("\n"),
                        title = if (result.failed.isEmpty()) "Spawned" else "Partly spawned",
                    )
                }
                .onFailure { post(app, projectId, refusalOf(it)) }
        }
    }

    /** The daemon's own sentence when it wrote one; ours only when it did not. */
    private fun refusalOf(e: Throwable): String =
        (e as? HuginnClient.HuginnException)?.message ?: e.message ?: "The request did not go through"

    private fun post(app: Context, projectId: String, text: String, title: String = "Project") {
        SessionWatchWorker.post(
            app,
            title,
            text,
            session = null,
            project = projectId,
            key = ProjectNotices.keyFor(projectId),
            isResult = true,
        )
    }

    companion object {
        const val ACTION = "com.silencelen.huginn.PROJECT"
        const val EXTRA_PROJECT = "project"
        const val EXTRA_VERB = "verb"
        const val EXTRA_MANIFEST_REV = "manifestRev"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        /**
         * A DISTINCT request code per button.
         *
         * Sharing one would make FLAG_UPDATE_CURRENT hand both buttons the same
         * intent, so both would do whichever was built last — which here means
         * Discard spawning a cluster, or the reverse.
         */
        fun requestCodeFor(projectId: String, verb: String): Int =
            projectId.hashCode() * 31 + verb.hashCode()
    }
}
