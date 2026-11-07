package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ProjectDashboard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a [DashboardBook] keeps its rollups BETWEEN RUNS.
 *
 * The book alone fixed the walk-back ("Nothing to show yet." for a tick over a
 * cluster drawn a moment ago) and left the relaunch: every start of either app
 * began with an empty book, and the first open of each project read the empty
 * text until the daemon's first rollup landed — seconds, when the daemon has to
 * walk a cold member's transcript. A shelf is a few small files, one per
 * project, like the transcript caches; `FileDashboardShelf` (jvmShared) is the
 * one both shells use. Every call is best-effort: a shelf that cannot be read
 * is an empty book, never a crash.
 */
interface DashboardShelf {
    /** Everything on the shelf, by project id. What cannot be read is skipped. */
    fun load(): Map<String, ProjectDashboard>

    fun save(id: String, dashboard: ProjectDashboard)

    fun remove(id: String)
}

/**
 * The last rollup each project answered with, by project id.
 *
 * Both shells used to hold ONE dashboard and clear it to null on every walk
 * into a project, then wait for the next poll tick: the pane read "Nothing to
 * show yet." for up to five seconds on a cluster it had drawn a moment ago. The
 * book keeps what each project last said, so a reopen draws it at once and the
 * refetch replaces it when it lands — and, with a [shelf], so does the first
 * open after a relaunch (R5, 2026-11-07: "seems to have no cache").
 *
 * [adopt] is the one gate on what replaces a held rollup, and it never goes
 * backwards — see [moved]. Synchronised on itself: the desktop adopts from its
 * poll coroutine and reads from the composition, and a shelf read on first use
 * must not race either.
 */
class DashboardBook(private val shelf: DashboardShelf? = null) {
    private val held = mutableMapOf<String, ProjectDashboard>()

    /** Whether the shelf has been read. Read once, on first use, never on construction. */
    private var shelved = shelf == null

    private fun shelved() {
        if (shelved) return
        shelved = true
        runCatching { shelf?.load() }.getOrNull()?.let { held.putAll(it) }
    }

    /** What [id] last answered with, or null when it never has (or was forgotten). */
    fun held(id: String?): ProjectDashboard? = synchronized(this) {
        shelved()
        id?.let { held[it] }
    }

    /**
     * Take a freshly-fetched rollup for [id] when it has actually moved.
     *
     * @return whether the book took it — which is also whether a screen drawn
     *   from the held one needs to redraw.
     */
    fun adopt(id: String, fresh: ProjectDashboard): Boolean {
        synchronized(this) {
            shelved()
            if (!moved(held[id], fresh)) return false
            held[id] = fresh
        }
        // Outside the lock: a file write must not hold the composition's read.
        runCatching { shelf?.save(id, fresh) }
        return true
    }

    /** Drop the rollups of projects that are gone, keeping those in [alive]. */
    fun retain(alive: Collection<String>) {
        val gone: List<String>
        synchronized(this) {
            shelved()
            val keep = alive.toSet()
            gone = held.keys.filterNot { it in keep }
            held.keys.retainAll(keep)
        }
        gone.forEach { runCatching { shelf?.remove(it) } }
    }

    fun forget(id: String) {
        synchronized(this) {
            shelved()
            held.remove(id)
        }
        runCatching { shelf?.remove(id) }
    }

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

        /**
         * One shelved rollup as it is written: the id BESIDE the dashboard, so a
         * shelf keyed on file names never has to trust them (a sanitised name is
         * not the id), and a dashboard whose `project` is null still knows
         * whose it is.
         */
        @Serializable
        private class Shelved(val id: String, val dashboard: ProjectDashboard)

        // The client's own settings: unknown fields skipped (a newer daemon's
        // rollup shelved by a newer app), nulls not written, defaults written so
        // a file reads back as what was held.
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }

        /** The text a shelf stores for [id]. PURE; `decode` reads it back. */
        fun encode(id: String, dashboard: ProjectDashboard): String =
            json.encodeToString(Shelved.serializer(), Shelved(id, dashboard))

        /** [encode]'s inverse, or null for anything it cannot read — a shelf skips those. */
        fun decode(text: String): Pair<String, ProjectDashboard>? =
            runCatching { json.decodeFromString(Shelved.serializer(), text) }.getOrNull()
                ?.takeIf { it.id.isNotEmpty() }
                ?.let { it.id to it.dashboard }
    }
}
