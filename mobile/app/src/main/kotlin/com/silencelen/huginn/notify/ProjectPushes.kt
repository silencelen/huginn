package com.silencelen.huginn.notify

import com.silencelen.huginn.data.ProjectDetail
import com.silencelen.huginn.data.ProjectRow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A project push, handed to the page that has the project open.
 *
 * ⚠⚠ AN OPEN PROJECT PAGE NEVER DREW A ROSTER CHANGE THAT ARRIVED WHILE IT WAS
 * OPEN (Fold, 2026-11-03). The lead proposed one (`pendingChange` rev 2) with
 * the page on screen: the shade said so, the page did not — not on its own poll
 * (which fetched only the dashboard), not on Refresh (which refreshed the list
 * while the card was read from the detail fetched on open). Leaving and coming
 * back showed it. So the poll and Refresh now re-read the detail too, and a push
 * about this project says "look again" at once — through here, because the
 * push is received by the messaging service and the page is drawn from the
 * view model, and the two share nothing but the process.
 *
 * In-memory only. A push that starts the process has no page to tell, and
 * nothing is lost: the page reads the project when it opens.
 */
object ProjectPushes {

    /**
     * The project a push is about, when it is one the open page must re-read:
     * a roster change or a proposal. The id is read the way the notice reads it
     * ([ProjectNotices.fromPush]), so the shade and the page agree on which
     * project a push names.
     */
    fun projectOf(kind: String, subject: String?, payload: String?): String? =
        ProjectNotices.fromPush(kind = kind, title = "", text = "", subject = subject, payload = payload)?.projectId

    private val changes = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Project ids a push said changed; the view model re-reads the open one. */
    val changed: SharedFlow<String> = changes.asSharedFlow()

    /** Never suspends: called from the messaging service's own thread. */
    fun announce(projectId: String) {
        changes.tryEmit(projectId)
    }

    /**
     * The row the open page draws the roster change from: the detail's or the
     * list's, WHICHEVER IS NEWER by the record's `rev` (the daemon bumps it on
     * every write, a pending change included). The detail's used to win
     * whenever it existed, so a list refreshed after the change landed was
     * read for nothing — the Refresh half of the bug above. A tie keeps the
     * detail's.
     */
    fun openRow(detail: ProjectDetail?, list: List<ProjectRow>, id: String): ProjectRow? {
        val fromDetail = detail?.takeIf { it.project.id == id }?.row
        val fromList = list.firstOrNull { it.id == id }
        return when {
            fromDetail == null -> fromList
            fromList == null -> fromDetail
            fromList.rev > fromDetail.rev -> fromList
            else -> fromDetail
        }
    }
}
