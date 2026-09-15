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
 * Three rules, in order of weight:
 *
 * 1. **Main is always first.** The session's own transcript is the thing the
 *    reader came for and the thing they need to get back to; it never moves.
 * 2. **Running before settled, newest first inside each.** What is happening now
 *    outranks what happened, and among equals recency is the only ordering that
 *    does not need explaining.
 * 3. **A workflow run is one unit.** Its members sit under a header and travel
 *    with it, so a six-agent run cannot interleave itself through the strip and
 *    bury a direct agent between two of its own members.
 *
 * Keys must be unique or LazyColumn throws — that is not a style rule, it is a
 * crash — so a duplicate agent id is DROPPED rather than rendered twice.
 */
object StreamPicker {

    /** How long after its last write an agent is still believed to be running. */
    const val STALE_S: Long = 15 * 60

    /** The main session's row. Constant so both clients key on the same string. */
    const val MAIN_KEY: String = "main"

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
     * @param nowSec the daemon's clock, not the device's — the rows' timestamps
     *   are the host's. Used to disbelieve a stale `active` flag: an agent whose
     *   process died leaves its last state behind, and the TUI's own footer has
     *   the same bug, which is why [WorkSummary.paneRows] exists. A row that has
     *   written nothing for [STALE_S] is shown settled whatever it claims. Pass
     *   0 to skip the check entirely (an offline render with no clock).
     */
    fun items(agents: List<AgentRun>, nowSec: Long): List<Item> {
        val out = ArrayList<Item>(agents.size + 2)
        out += Item(key = MAIN_KEY, label = "Main")

        val seen = HashSet<String>()
        val rows = ArrayList<Item>(agents.size)
        for (a in agents) {
            val id = a.id.trim()
            if (id.isEmpty()) continue
            if (!seen.add(id)) continue
            rows += Item(
                key = "agent:$id",
                agentId = id,
                label = labelFor(a),
                running = isRunning(a, nowSec),
                workflowId = a.workflowId?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = a.updatedAt,
                agentType = a.agentType,
                status = a.status,
            )
        }

        // A unit is either a whole workflow run or a single direct agent. Runs
        // keep the order of first appearance among themselves before sorting, so
        // the result is stable when every timestamp is equal.
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
            val header = Item(
                key = "workflow:$wf",
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
        for (u in units) out += u.rows
        return out
    }

    /**
     * Whether a row is still believed to be working.
     *
     * `active` is the daemon's reading and is trusted — except when the row has
     * been silent longer than a live agent ever is, which is the one case where
     * "still running" is a leftover rather than an observation.
     */
    private fun isRunning(a: AgentRun, nowSec: Long): Boolean {
        if (!a.active) return false
        if (nowSec <= 0 || a.updatedAt <= 0) return true
        return nowSec - a.updatedAt < STALE_S
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
