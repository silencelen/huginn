package com.silencelen.huginn.desktop.tray

import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.desktop.notify.NotifyRules

/** What the tray icon is saying at a glance. */
enum class TrayState { IDLE, WORKING, ATTENTION }

/**
 * Everything the tray shows, worked out from one digest.
 *
 * A value rather than a widget so the rules — which colour, which tooltip, which
 * rows in the menu — are testable without a system tray, which is the one thing
 * this dev box does not have by default and the owner's machine always does.
 */
data class TraySummary(
    val state: TrayState,
    val tooltip: String,
    /** Sessions waiting on an answer, in digest order, capped for the menu. */
    val attention: List<String>,
    val workingSessions: Int,
    val workingChats: Int,
) {
    val working: Int get() = workingSessions + workingChats
}

object TrayModel {

    /** How many "needs you" rows the menu will carry before it stops being a menu. */
    const val MENU_ATTENTION_CAP: Int = 6

    val EMPTY: TraySummary = TraySummary(TrayState.IDLE, "Huginn — idle", emptyList(), 0, 0)

    fun summarize(watch: Watch?): TraySummary {
        if (watch == null) return TraySummary(TrayState.IDLE, "Huginn — not connected", emptyList(), 0, 0)

        val attention = watch.sessions.filterValues { it == NotifyRules.ATTENTION }.keys.toList()
        val workingSessions = watch.sessions.values.count { it == RUNNING }
        val workingChats = watch.chats.values.count { it.running }
        val working = workingSessions + workingChats

        val state = when {
            attention.isNotEmpty() -> TrayState.ATTENTION
            working > 0 -> TrayState.WORKING
            else -> TrayState.IDLE
        }

        // The tooltip is the desktop's version of the phone's ongoing summary, and
        // it is read at a glance from a 16px icon — so it says the two things that
        // change what the reader does next and nothing else.
        val parts = buildList {
            if (attention.isNotEmpty()) {
                add("${attention.size} need${if (attention.size == 1) "s" else ""} you")
            }
            if (working > 0) add("$working working")
        }
        val tooltip = if (parts.isEmpty()) "Huginn — idle" else "Huginn — " + parts.joinToString(" · ")

        return TraySummary(state, tooltip, attention.take(MENU_ATTENTION_CAP), workingSessions, workingChats)
    }

    /** The daemon's word for "Claude is working in this session". */
    private const val RUNNING = "running"
}
