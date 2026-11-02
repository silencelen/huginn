package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ProjectDashboard

/**
 * The last rollup each project answered with, by project id.
 *
 * Both shells used to hold ONE dashboard and clear it to null on every walk
 * into a project, then wait for the next poll tick: the pane read "Nothing to
 * show yet." for up to five seconds on a cluster it had drawn a moment ago. The
 * book keeps what each project last said, so a reopen draws it at once and the
 * refetch replaces it when it lands.
 *
 * [adopt] is the one gate on what replaces a held rollup, and it never goes
 * backwards — see [moved]. Not thread-safe by itself: both shells call it from
 * their one UI-side scope.
 */
class DashboardBook {
    private val held = mutableMapOf<String, ProjectDashboard>()

    /** What [id] last answered with, or null when it never has (or was forgotten). */
    fun held(id: String?): ProjectDashboard? = id?.let { held[it] }

    /**
     * Take a freshly-fetched rollup for [id] when it has actually moved.
     *
     * @return whether the book took it — which is also whether a screen drawn
     *   from the held one needs to redraw.
     */
    fun adopt(id: String, fresh: ProjectDashboard): Boolean {
        if (!moved(held[id], fresh)) return false
        held[id] = fresh
        return true
    }

    /** Drop the rollups of projects that are gone, keeping those in [alive]. */
    fun retain(alive: Collection<String>) {
        val keep = alive.toSet()
        held.keys.retainAll(keep)
    }

    fun forget(id: String) { held.remove(id) }

    companion object {
        /**
         * Whether a freshly-fetched project dashboard is worth putting on screen.
         *
         * ⚠ THE CLOCK IS `generatedAt`, AND IT IS THE ONLY HONEST ONE. It is when
         * the daemon answered this poll — not when the project changed — so two
         * answers carrying the same stamp are the same rollup, re-sent. Adopting
         * one anyway costs a recomposition of a twelve-row table every five
         * seconds for nothing.
         *
         * Three cases are NOT skips and each has cost somebody a frozen screen:
         *
         *  * NOTHING HELD — the first answer always lands, or the pane never fills.
         *  * A DIFFERENT PROJECT — stamps are per-answer, not per-cluster, so
         *    comparing them across two projects is comparing nothing.
         *  * NO STAMP AT ALL (`0`) — a daemon that does not send one cannot be
         *    skipped on it, and a client that skipped anyway would draw the first
         *    answer forever.
         *
         * And it never goes BACKWARDS: an answer older than the one on screen is
         * a poll that overtook its predecessor, not news.
         */
        fun moved(held: ProjectDashboard?, fresh: ProjectDashboard): Boolean {
            if (held == null) return true
            if (held.project?.id != fresh.project?.id) return true
            if (fresh.generatedAt <= 0L || held.generatedAt <= 0L) return true
            return fresh.generatedAt > held.generatedAt
        }
    }
}
