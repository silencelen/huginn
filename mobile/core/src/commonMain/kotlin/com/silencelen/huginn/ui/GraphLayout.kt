package com.silencelen.huginn.ui

import com.silencelen.huginn.data.GraphAgent
import com.silencelen.huginn.data.GraphNode
import com.silencelen.huginn.data.SessionGraph
import kotlin.math.max
import kotlin.math.min

/**
 * Where every branch line goes: the geometry of the session map, decided once,
 * in the module both clients read.
 *
 * The map is a spine of blocks with agent lifelines beside it — a line leaves the
 * block that spawned an agent, runs down past whatever else happened while it
 * worked, and curves back into the block its result arrived in. The only real
 * question is which COLUMN each lifeline gets, and it is the only part of this
 * surface that can be wrong in a way a screenshot would not show: two agents
 * sharing a column while both are running draws one line where there are two.
 *
 * So it lives here, as arithmetic over a list, and the drawing code downstream
 * does no thinking at all.
 *
 * The rule is greedy first-free-lane in spawn order: an agent takes the leftmost
 * column that nothing else is occupying at the row it starts on, and gives it
 * back at the row it ends on. That reuses columns aggressively — a session that
 * spawned forty agents one after another draws one lane, not forty — while never
 * overlapping two live agents, which is the property that matters.
 */
object GraphLayout {

    /** How a lifeline meets one row. */
    enum class Phase {
        /** The branch leaves the spine here. */
        START,

        /** The agent was working while this block happened; the line just passes. */
        PASS,

        /** The branch rejoins the spine here. */
        END,

        /** Spawned and merged inside the same block — a stub, not a line. */
        POINT,
    }

    /**
     * One lifeline crossing one row.
     *
     * [hue] is an INDEX, not a colour: `:core` cannot import Compose, and the two
     * clients must pick the same colour for the same agent. Members of one
     * workflow run share a hue so a fan-out reads as one thing that happened
     * rather than as six unrelated ones.
     *
     * [indent] is nesting, drawn as an offset INSIDE the lane rather than as a
     * lane of its own: an agent that spawned another agent is one branch with a
     * step in it, and giving the child a column of its own says they were
     * independent.
     */
    data class Lane(
        val agentId: String,
        val lane: Int,
        val phase: Phase,
        val hue: Int,
        val indent: Int,
        val status: String,
        val workflowId: String? = null,
    )

    /** One row of the map: a block, and everything running beside it. */
    data class Row(
        val index: Int,
        val node: GraphNode,
        /** Ordered by lane, so a renderer can walk them left to right. */
        val lanes: List<Lane> = emptyList(),
        val spawned: List<String> = emptyList(),
        val merged: List<String> = emptyList(),
    )

    /**
     * @param laneCount the widest the map ever gets. The gutter is reserved at
     *   this width for every row, so the spine does not shuffle sideways as
     *   branches open and close.
     * @param unplaced agents whose branch point is not on the map — a join that
     *   did not survive a compaction, or an agent directory with no matching
     *   tool_use. Listed rather than dropped: those tokens were spent, and a map
     *   that silently omits work is worse than one with a loose end.
     */
    data class Result(
        val rows: List<Row> = emptyList(),
        val laneCount: Int = 0,
        val unplaced: List<GraphAgent> = emptyList(),
    )

    /** How many distinct hues a renderer must provide. */
    const val HUES: Int = 6

    /**
     * A branch may not run wider than this. Past it the map is a barcode, and the
     * lanes beyond are dropped into [Result.unplaced] where they are at least
     * counted — the alternative is a gutter wider than the blocks it annotates.
     */
    const val MAX_LANES: Int = 8

    fun layout(graph: SessionGraph): Result = layout(graph.nodes, graph.agents)

    fun layout(nodes: List<GraphNode>, agents: List<GraphAgent>): Result {
        if (nodes.isEmpty()) return Result()
        val indexOf = HashMap<String, Int>(nodes.size)
        nodes.forEachIndexed { i, n -> if (n.id.isNotEmpty()) indexOf.putIfAbsent(n.id, i) }
        val last = nodes.lastIndex

        // Spawn order, and `id` behind it so two agents spawned in the same second
        // land in the same columns on every client and on every poll.
        val ordered = agents.sortedWith(compareBy({ it.spawnTs ?: Long.MAX_VALUE }, { it.id }))

        val hues = LinkedHashMap<String, Int>()
        fun hueFor(a: GraphAgent): Int {
            val key = a.workflowId ?: "agent:${a.id}"
            return hues.getOrPut(key) { hues.size % HUES }
        }

        // lane index -> the row this lane is occupied THROUGH. A lane is free for
        // an agent starting at `s` when its occupant ended strictly before `s`.
        val busyUntil = ArrayList<Int>()
        val placed = ArrayList<Triple<GraphAgent, IntRange, Int>>()
        val unplaced = ArrayList<GraphAgent>()

        for (a in ordered) {
            val start = a.spawnNodeId?.let { indexOf[it] }
            if (start == null) { unplaced += a; continue }
            val end = endRow(a, nodes, indexOf, start, last)
            var lane = busyUntil.indexOfFirst { it < start }
            if (lane < 0) {
                if (busyUntil.size >= MAX_LANES) { unplaced += a; continue }
                busyUntil.add(end)
                lane = busyUntil.lastIndex
            } else {
                busyUntil[lane] = end
            }
            placed += Triple(a, start..end, lane)
        }

        val rows = nodes.mapIndexed { i, node ->
            val lanes = ArrayList<Lane>()
            val spawned = ArrayList<String>()
            val merged = ArrayList<String>()
            for ((agent, span, lane) in placed) {
                if (i !in span) continue
                val phase = when {
                    span.first == span.last -> Phase.POINT
                    i == span.first -> Phase.START
                    i == span.last -> Phase.END
                    else -> Phase.PASS
                }
                if (phase == Phase.START || phase == Phase.POINT) spawned += agent.id
                if (phase == Phase.END || phase == Phase.POINT) merged += agent.id
                lanes += Lane(
                    agentId = agent.id,
                    lane = lane,
                    phase = phase,
                    hue = hueFor(agent),
                    indent = min(2, max(0, agent.depth - 1)),
                    status = agent.status,
                    workflowId = agent.workflowId,
                )
            }
            lanes.sortBy { it.lane }
            Row(index = i, node = node, lanes = lanes, spawned = spawned, merged = merged)
        }
        return Result(rows = rows, laneCount = busyUntil.size, unplaced = unplaced)
    }

    /**
     * The row a branch curves back into.
     *
     * A merge point is the parent's tool_result and is the only exact answer, but
     * most agents never get one: a workflow member's parent is told the run
     * LAUNCHED, not that it finished, so the run's own journal is the epitaph and
     * the block it lines up with has to be found by time. A running agent ends at
     * the bottom of the map, because that is where it still is.
     */
    private fun endRow(
        a: GraphAgent,
        nodes: List<GraphNode>,
        indexOf: Map<String, Int>,
        start: Int,
        last: Int,
    ): Int {
        a.mergeNodeId?.let { id -> indexOf[id]?.let { return max(start, it) } }
        if (a.status == "running") return last
        val until = a.mergeTs ?: a.updatedAt.takeIf { it > 0 } ?: return start
        var best = start
        for (i in start + 1..last) {
            val ts = nodes[i].ts ?: continue
            if (ts <= until) best = i else break
        }
        return best
    }
}
