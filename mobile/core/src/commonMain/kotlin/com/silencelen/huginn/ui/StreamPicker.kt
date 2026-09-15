package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AgentRun

/**
 * The rows of the stream picker: the chip strip that switches the Conversation
 * body from the session's own transcript to one subagent's.
 *
 * Pure and shared, because the ORDER is the feature. A picker that lists agents
 * in whatever order the daemon read the directory makes the one that is running
 * right now the hardest thing on the strip to find, and both clients would have
 * had to discover that separately.
 *
 * Four rules, in order of weight:
 *
 * 1. **Only live agents get a chip; the finished FOLD, they do not vanish.** The
 *    strip mirrors the TUI's own agent footer, which drops an agent within
 *    seconds of it going green: a session that has fanned out thirty times over
 *    an afternoon must not answer "which stream am I reading" with thirty chips,
 *    twenty-nine of which are over. But a finished agent's transcript is still
 *    worth reading back through, so everything settled goes behind ONE trailing
 *    `…` pill carrying the count, and expanding it lists them newest first. The
 *    stream being READ is shown either way — yanking a transcript out from under
 *    a reader is worse than one stale chip.
 * 2. **Main is always first.** The session's own transcript is the thing the
 *    reader came for and the thing they need to get back to; it never moves.
 * 3. **Running before settled, newest first inside each.** What is happening now
 *    outranks what happened, and among equals recency is the only ordering that
 *    does not need explaining.
 * 4. **A workflow run is one unit.** Its members sit under a header and travel
 *    with it, so a six-agent run cannot interleave itself through the strip and
 *    bury a direct agent between two of its own members. Collapsed, a header
 *    needs a LIVE member to be worth the line; expanded, a finished-only run
 *    gets one too, under its own key.
 *
 * Keys must be unique or LazyColumn throws — that is not a style rule, it is a
 * crash — so a duplicate agent id is DROPPED rather than rendered twice.
 */
object StreamPicker {

    /**
     * How long after its last write an agent still counts as alive.
     *
     * The daemon's own `ACTIVE_S` (`server/appd/lib/agents.js`), which is what
     * its `active` flag means: the transcript file grew inside this window. We
     * carry the number rather than only trusting the flag because the flag is
     * computed against the HOST's clock at the moment the response was built,
     * and the response carries that clock — so the same rule can be re-applied
     * to a list that has been sitting in a `StateFlow` since.
     */
    const val ACTIVE_S: Long = 90

    /**
     * Outcome words that mean the agent is over whatever else the row says.
     *
     * Only workflow members ever carry one; `orphan`, `running` and null are all
     * "not settled" and are judged on [ACTIVE_S] alone.
     */
    private val SETTLED: Set<String> = setOf("done", "failed")

    /** The main session's row. Constant so both clients key on the same string. */
    const val MAIN_KEY: String = "main"

    /** The row that folds every finished agent away. Not a stream. */
    const val OVERFLOW_KEY: String = "more"

    /**
     * The most finished agents the pill will ever unfold.
     *
     * A day-long session settles hundreds; past a screenful the list stops being
     * a way back into a transcript and becomes a wall. [Item.count] on the pill
     * still carries the TRUE total, so the strip never quietly under-reports what
     * it is holding.
     */
    const val FOLDED_MAX: Int = 40

    /** The longest a chip label may be; past this it is clipped with an ellipsis. */
    const val LABEL_MAX: Int = 40

    /** One row of the strip. */
    data class Item(
        /** Stable and unique across the whole list. */
        val key: String,
        /**
         * Null on the Main row and on a workflow header — neither is an agent.
         *
         * ⚠ EXACTLY WHAT THE DAEMON SENT, trimmed and no more. `/agents` emits
         * the BARE hex and the transcript route accepts bare or `agent-`
         * prefixed, so there is nothing to normalise and normalising would make
         * this a second opinion about an id it did not mint. [shortId] shortens
         * a LABEL; it never touches this.
         */
        val agentId: String? = null,
        val label: String,
        /** Draw the live dot. */
        val running: Boolean = false,
        /**
         * This agent is over: it is here because it is being read, or because
         * the reader unfolded the pill.
         *
         * Drawn dimmed with a "finished" hint rather than silently: a chip that
         * looks like every other chip while its agent is gone is a strip that
         * claims work is still arriving.
         */
        val finished: Boolean = false,
        /**
         * The `…` pill. Not an agent and not a header — it is the control that
         * unfolds the settled ones, so it is never SELECTABLE as a stream.
         */
        val overflow: Boolean = false,
        /** How many finished agents the pill stands for. The true total. */
        val count: Int = 0,
        /** The run this row belongs to, or names if [header] is true. */
        val workflowId: String? = null,
        /** This row labels a group rather than being pickable itself. */
        val header: Boolean = false,
        /** Epoch SECONDS of the agent's last write; 0 on Main. */
        val updatedAt: Long = 0,
        val agentType: String? = null,
        val status: String? = null,
    )

    /**
     * The strip, from the rows `/v1/sessions/:name/agents?all=1` returned.
     *
     * Main, then a chip per LIVE agent — see [isAlive] — then, when anything has
     * settled, one `…` pill standing for all of it. `?all=1` is deliberate and
     * stays: the route's own 45-minute window would hide exactly the older runs
     * the pill exists to let somebody drill back into, and the pill's count is
     * only honest if the list behind it is whole.
     *
     * @param nowSec the daemon's clock, not the device's — the rows' timestamps
     *   are the host's, and a device clock a few minutes out would fold a live
     *   fan-out away. Pass 0 for an offline render with no clock, which takes
     *   each row's `active` flag at face value.
     * @param selectedKey the stream being read, as the row key (`agent:<id>`) or
     *   as the bare agent id the client passed to `onPick`; null for Main. That
     *   one agent is ALWAYS on the strip, folded or not, marked [Item.finished]
     *   once it settles — a reader whose transcript vanished mid-scroll has no
     *   way to tell a finished agent from a broken client. It folds away on the
     *   next refresh after they switch back.
     * @param expanded the pill is open: every finished agent follows it, newest
     *   first and capped at [FOLDED_MAX], workflow members under their run.
     */
    fun items(
        agents: List<AgentRun>,
        nowSec: Long,
        selectedKey: String? = null,
        expanded: Boolean = false,
    ): List<Item> {
        val out = ArrayList<Item>(agents.size + 3)
        out += Item(key = MAIN_KEY, label = "Main")

        // Either spelling, normalised once here: the clients hold the selection
        // as the id they were handed by `onPick` while the rows are keyed
        // `agent:<id>`, and reconciling that at two call sites is how the two
        // clients drift.
        val sel = selectedKey?.trim()?.removePrefix("agent:")
            ?.takeIf { it.isNotEmpty() && it != MAIN_KEY }

        val seen = HashSet<String>()
        val live = ArrayList<Item>(agents.size)
        val settled = ArrayList<Item>(agents.size)
        for (a in agents) {
            val id = a.id.trim()
            if (id.isEmpty()) continue
            // Claimed before the liveness test, so a duplicate of a settled agent
            // cannot come back as a second row behind its own first one.
            if (!seen.add(id)) continue
            val alive = isAlive(a, nowSec)
            val row = Item(
                key = "agent:$id",
                agentId = id,
                label = labelFor(a),
                running = alive,
                finished = !alive,
                workflowId = a.workflowId?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = a.updatedAt,
                agentType = a.agentType,
                status = a.status,
            )
            if (alive) live += row else settled += row
        }

        // Collapsed, the one settled agent being READ rides with the live ones —
        // it is the only chip on the strip whose body is already on screen.
        // Expanded it is down in the folded list instead, because appearing in
        // both places is a duplicate key, which is a crash and not a style note.
        val held = if (expanded) null else settled.firstOrNull { it.agentId == sel }
        out += lay(live + listOfNotNull(held), headerSuffix = "", headerNeedsLive = true)

        if (settled.isEmpty()) return out
        out += Item(key = OVERFLOW_KEY, label = "…", overflow = true, count = settled.size)
        if (expanded) out += lay(folded(settled, sel), headerSuffix = ":finished", headerNeedsLive = false)
        return out
    }

    /**
     * The finished agents worth unfolding: newest first, capped.
     *
     * The stream being read is never the one the cap cuts — it is the single row
     * whose absence the reader would feel immediately, so it takes the place of
     * the oldest row that made it in rather than the cap taking it.
     */
    private fun folded(settled: List<Item>, sel: String?): List<Item> {
        val byRecency = settled.sortedByDescending { it.updatedAt }
        if (byRecency.size <= FOLDED_MAX) return byRecency
        val capped = byRecency.take(FOLDED_MAX)
        if (sel == null || capped.any { it.agentId == sel }) return capped
        val read = byRecency.firstOrNull { it.agentId == sel } ?: return capped
        return capped.dropLast(1) + read
    }

    /**
     * Rows into units, units into order.
     *
     * A unit is either a whole workflow run or a single direct agent. Runs keep
     * the order of first appearance among themselves before sorting, so the
     * result is stable when every timestamp is equal.
     *
     * @param headerSuffix keeps a run's two headers apart when the SAME run has a
     *   live member up top and settled members down in the folded list. Two rows
     *   under one key is a LazyColumn crash.
     * @param headerNeedsLive collapsed, a header earns its line only by having
     *   something running under it; a lone held-open member is just a chip.
     */
    private fun lay(rows: List<Item>, headerSuffix: String, headerNeedsLive: Boolean): List<Item> {
        val groups = LinkedHashMap<String, MutableList<Item>>()
        val direct = ArrayList<Item>()
        for (r in rows) {
            val wf = r.workflowId
            if (wf == null) direct += r else groups.getOrPut(wf) { ArrayList() } += r
        }

        data class Unit(val running: Boolean, val updatedAt: Long, val rows: List<Item>)

        val units = ArrayList<Unit>(groups.size + direct.size)
        for ((wf, members) in groups) {
            val sorted = members.sortedWith(compareByDescending<Item> { it.running }.thenByDescending { it.updatedAt })
            val running = sorted.any { it.running }
            val updatedAt = sorted.maxOf { it.updatedAt }
            if (headerNeedsLive && !running) {
                for (m in sorted) units += Unit(m.running, m.updatedAt, listOf(m))
                continue
            }
            val header = Item(
                key = "workflow:$wf$headerSuffix",
                label = clip(runLabel(wf)),
                running = running,
                workflowId = wf,
                header = true,
                updatedAt = updatedAt,
            )
            units += Unit(running, updatedAt, listOf(header) + sorted)
        }
        for (d in direct) units += Unit(d.running, d.updatedAt, listOf(d))

        units.sortWith(compareByDescending<Unit> { it.running }.thenByDescending { it.updatedAt })
        return units.flatMap { it.rows }
    }

    /**
     * Whether this agent is still working, and therefore whether it belongs on
     * the strip at all.
     *
     * Three independent facts, all of which must agree, because each one alone
     * has been wrong:
     *
     * - the daemon's `active` flag — its reading, but computed when the response
     *   was built and stale by however long the list has been held since;
     * - that flag re-derived against the clock the response CARRIED, so a list
     *   that stopped refreshing cannot keep claiming a live fan-out;
     * - the settled outcome word, which is the only one of the three that can
     *   say an agent finished the same second it last wrote — the case where
     *   [ACTIVE_S] alone would still call it alive.
     */
    private fun isAlive(a: AgentRun, nowSec: Long): Boolean {
        if (!a.active) return false
        if (a.status?.trim()?.lowercase() in SETTLED) return false
        if (nowSec <= 0 || a.updatedAt <= 0) return true
        return nowSec - a.updatedAt <= ACTIVE_S
    }

    /**
     * What a chip says.
     *
     * The task is the only thing that identifies an agent to a person; an id
     * does not. When there is no task the type is the next most useful thing,
     * and the short id is the last resort — never nothing, because a blank chip
     * cannot be picked on purpose.
     */
    private fun labelFor(a: AgentRun): String {
        val task = a.task?.trim().orEmpty()
        if (task.isNotEmpty()) return clip(task)
        val type = a.agentType?.trim().orEmpty()
        if (type.isNotEmpty()) return clip(type)
        return clip(shortId(a.id))
    }

    /** `wf_01H9…` → `Run 01H9…`, so a header reads as one. */
    private fun runLabel(workflowId: String): String {
        val bare = workflowId.removePrefix("wf_").trim().ifEmpty { workflowId }
        return "Run " + bare.takeLast(8)
    }

    private fun shortId(id: String): String {
        val bare = id.trim().removePrefix("agent-")
        return if (bare.length <= 8) bare else bare.take(8)
    }

    /**
     * Clipped to [LABEL_MAX] INCLUDING the ellipsis, so the longest label is
     * exactly the stated width rather than one character over it. A strip whose
     * chips each run a character past the budget is how a FlowRow ends up one
     * row taller than the layout was measured for.
     */
    fun clip(s: String, max: Int = LABEL_MAX): String {
        val t = s.trim()
        if (t.length <= max) return t
        if (max <= 1) return "…"
        return t.take(max - 1).trimEnd() + "…"
    }
}
