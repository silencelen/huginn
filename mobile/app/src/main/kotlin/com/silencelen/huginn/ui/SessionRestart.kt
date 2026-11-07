package com.silencelen.huginn.ui

import com.silencelen.huginn.data.RestartResult
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SessionRuntime
import com.silencelen.huginn.ui.theme.stateWord

/**
 * What the phone says about a session's RUNTIME: whether its Claude Code still
 * runs what a launch would load now, and what asking for a restart answered.
 *
 * A Claude Code process reads its MCP servers, hooks, settings and binary at
 * START and never again, so a session launched before a deploy keeps the old
 * wiring until it is restarted — one launched on 10-27 still had no
 * `start_session` tool on 11-07, four days after it was registered. The daemon
 * (appd 3.19) stamps each launch with a generation and says on the row whether
 * it is behind ([SessionRuntime.stale]); the row says so in the state language,
 * and the session's menu offers the restart.
 *
 * Pure, and out of the view model and the screens, for the reason every rule in
 * this package is: `HuginnViewModel` is an `AndroidViewModel`, this host has no
 * device, and a word that lives inline in a composable is a word no test reads.
 */
object SessionRestart {

    /** The menu verb. Says what it is FOR, not what it does: a bare "Restart" reads as a kill. */
    const val MENU_LABEL: String = "Restart to pick up updates"

    /**
     * The row's mark for a stale session, and its short form where a row has no
     * width (the two-pane list column). In-vernacular — a fact on the row's meta
     * line beside "2 attached" — not a verb: the verb is in the menu.
     */
    const val MARK: String = "restart to update"
    const val MARK_SHORT: String = "update"

    /**
     * The state word while the daemon cycles the session. Drawn where the row's
     * state word goes, in the busy colour with the breathing dot: the same
     * language the row already speaks for a session that is starting up.
     */
    const val RESTARTING: String = "restarting"

    /** The one question, asked once, before a restart is forced past an attached terminal. */
    const val ATTACHED_QUESTION: String = "A terminal is attached — restart anyway?"

    /** The daemon is cycling this session right now. */
    fun restarting(runtime: SessionRuntime?): Boolean = runtime?.restarting == true

    /**
     * The row's mark, or null when there is nothing to say.
     *
     * Null while [restarting]: the restart IS the answer to "stale", and a row
     * saying "restart to update" over a session being restarted is a row asking
     * for something already under way. Null on an absent cell too — a daemon
     * that records no generations has not said anything, and ABSENT IS NOT
     * STALE (see [SessionRuntime]).
     */
    fun rowMark(runtime: SessionRuntime?, short: Boolean = false): String? {
        if (runtime == null || !runtime.stale || runtime.restarting) return null
        return if (short) MARK_SHORT else MARK
    }

    /**
     * The row's state word: [RESTARTING] while the daemon cycles the session,
     * otherwise the word the row has always shown — "background work" over a
     * session that is not running but has shells or agents going (the list is
     * where "looks stalled" started), else the brand's word for the wire state.
     */
    fun titleWord(state: String?, backgroundWork: Boolean, runtime: SessionRuntime?): String = when {
        restarting(runtime) -> RESTARTING
        state != "running" && backgroundWork -> "background work"
        else -> stateWord(state)
    }

    /**
     * Whether the menu offers the restart at all: only on a row the daemon has
     * stamped. A daemon older than the route never sends the cell, and a verb
     * whose only outcome there is a 404 is worse than no verb (the archive
     * feature's rule).
     */
    fun offered(session: Session?): Boolean = session?.runtime != null

    /**
     * Whether to ask [ATTACHED_QUESTION] before sending. The daemon holds an
     * unforced restart behind `attached` forever — a terminal is a person who
     * would watch their session vanish mid-thought — so an attached session is
     * asked about once and then forced; nothing else is ever forced.
     */
    fun needsConfirm(session: Session?): Boolean = (session?.attachedClients ?: 0) > 0

    /**
     * What the toast says about the daemon's answer, in the shape the app uses
     * for every other queued verb ("Compacting x after this turn"): due now, held
     * and why, or already under way.
     *
     * The hold is NAMED. `blockedBy` is a wire word; the reader gets what it
     * means and what clears it, the way the composer's queue note does for a
     * held send ([SendQueue.note]).
     */
    fun words(name: String, result: RestartResult): String = when {
        result.alreadyQueued -> "$name is already restarting"
        result.blockedBy != null -> "Restarting $name when it is free · waiting: ${holdWords(result.blockedBy)}"
        else -> "Restarting $name…"
    }

    /** The daemon's hold word, in the reader's words; an unknown word is passed through. */
    fun holdWords(blockedBy: String?): String = when (blockedBy) {
        "running" -> "its turn to end"
        "dialog" -> "a dialog on the screen"
        "draft" -> "unsent text in the live view"
        "background" -> "background work to finish"
        "attached" -> "a terminal is attached"
        "load" -> "the host to be less busy"
        else -> blockedBy.orEmpty()
    }
}
