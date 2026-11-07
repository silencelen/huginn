package com.silencelen.huginn.desktop.ui.common

import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SessionRuntime

/**
 * The words for a session that runs an older launch than a launch would get
 * now, and for the restart that fixes it (R5: `Session.runtime`,
 * `POST /v1/sessions/:name/restart`).
 *
 * A Claude Code process loads its MCP servers, hooks, settings and binary at
 * START; a session launched before a deploy keeps the old wiring until it is
 * restarted. The daemon says which rows are stale and cycles them itself at a
 * safe point; these are the row's mark, the menu's verb and the notice for a
 * restart the daemon is holding — in the vocabulary the row already has (the
 * compaction marker, the queued-send count), never a colour of its own.
 */
object RestartWords {

    /** The session menu's verb. */
    const val MENU: String = "Restart to pick up updates"

    /** The row's small state mark for `runtime.stale`. */
    const val STALE: String = "restart to update"

    /** The row's mark while the daemon cycles it: the starting-style state, said. */
    const val RESTARTING: String = "restarting…"

    /** The row's mark for a restart the daemon is holding — the queued-send shape. */
    const val HOLD: String = "restart queued"

    const val RESTARTING_TIP: String =
        "Restarting · waiting for Claude to start — the conversation resumes in place"

    /** The confirm body when somebody is attached to the session's tmux window. */
    const val ATTACHED_BODY: String =
        "Somebody is attached to this session's tmux window. Restarting closes Claude Code there and " +
            "relaunches it in place, resuming the same conversation; a turn in progress is waited out first."

    const val ATTACHED_VERB: String = "Restart anyway"

    /**
     * Whether the menu offers the verb for [session]: the daemon speaks runtime
     * (an older one has no restart route either, and a verb whose only outcome
     * is a 404 is worse than none) and it is not already cycling the session.
     */
    fun offered(session: Session): Boolean {
        val rt = session.runtime ?: return false
        return !rt.restarting
    }

    /** The stale mark's tip: what moved, when it is known, and where the verb is. */
    fun staleTip(rt: SessionRuntime): String {
        val claude = rt.claude?.takeIf { it.isNotBlank() }
        val now = rt.currentClaude?.takeIf { it.isNotBlank() }
        val versions = if (claude != null && now != null && claude != now) " · claude $claude → $now" else ""
        return "Launched before the last update$versions — \"$MENU\" in the session menu restarts it in place"
    }

    /**
     * The hold's tip: what the daemon is waiting on, in the queued-send words,
     * or that a restart was already under way. The reasons are the route's
     * (`running` · `dialog` · `draft` · `background` · `attached` · `load`).
     */
    fun holdTip(blockedBy: String?, alreadyQueued: Boolean): String {
        if (alreadyQueued) return "Restart already under way"
        return when (blockedBy) {
            null, "" -> "Restart queued · due now"
            "running" -> "Restart queued · will run when Claude finishes its turn"
            "dialog" -> "Restart queued · a dialog is open on the screen"
            "draft" -> "Restart queued · unsent text in the live view"
            "background" -> "Restart queued · background work is still running"
            "attached" -> "Restart queued · somebody is attached to the session — \"$MENU\" again goes ahead anyway"
            "load" -> "Restart queued · waiting for the host to quieten"
            else -> "Restart queued · waiting on $blockedBy"
        }
    }
}
