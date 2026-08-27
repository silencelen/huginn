package com.silencelen.huginn

import com.silencelen.huginn.data.GraphAgent
import com.silencelen.huginn.data.GraphNode
import com.silencelen.huginn.ui.GraphLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session map's geometry — the one part of that screen that can be wrong in a
 * way a screenshot would not show. Two agents sharing a column while both are
 * running draws ONE line where there are two, and it looks perfectly fine.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class GraphLayoutTest {

    private fun nodes(n: Int, startTs: Long = 1_000) =
        (0 until n).map { GraphNode(id = "n$it", kind = "action", ts = startTs + it * 60) }

    private fun agent(
        id: String,
        spawn: String?,
        merge: String? = null,
        status: String = "done",
        depth: Int = 1,
        workflowId: String? = null,
        spawnTs: Long? = null,
        updatedAt: Long = 0,
    ) = GraphAgent(
        id = id,
        spawnNodeId = spawn,
        mergeNodeId = merge,
        status = status,
        depth = depth,
        workflowId = workflowId,
        spawnTs = spawnTs,
        updatedAt = updatedAt,
    )

    private fun lanesOf(r: GraphLayout.Result, row: Int) = r.rows[row].lanes.map { it.lane }

    @Test
    fun `an empty map has no rows and no gutter`() {
        val r = GraphLayout.layout(emptyList(), emptyList())
        assertEquals(0, r.rows.size)
        assertEquals(0, r.laneCount)
        assertTrue(r.unplaced.isEmpty())
    }

    @Test
    fun `a map with no agents is a plain spine`() {
        val r = GraphLayout.layout(nodes(4), emptyList())
        assertEquals(4, r.rows.size)
        assertEquals(0, r.laneCount, "no branches means no gutter to reserve")
        assertTrue(r.rows.all { it.lanes.isEmpty() })
    }

    @Test
    fun `one branch leaves, passes and rejoins`() {
        val r = GraphLayout.layout(nodes(4), listOf(agent("a", "n0", "n2", spawnTs = 1)))
        assertEquals(1, r.laneCount)
        assertEquals(GraphLayout.Phase.START, r.rows[0].lanes.single().phase)
        assertEquals(GraphLayout.Phase.PASS, r.rows[1].lanes.single().phase)
        assertEquals(GraphLayout.Phase.END, r.rows[2].lanes.single().phase)
        assertTrue(r.rows[3].lanes.isEmpty(), "the line stops where the agent came back")
        assertEquals(listOf("a"), r.rows[0].spawned)
        assertEquals(listOf("a"), r.rows[2].merged)
    }

    @Test
    fun `an agent that returns inside its own block is a stub, not a line`() {
        val r = GraphLayout.layout(nodes(3), listOf(agent("a", "n1", "n1", spawnTs = 1)))
        val lane = r.rows[1].lanes.single()
        assertEquals(GraphLayout.Phase.POINT, lane.phase)
        assertEquals(listOf("a"), r.rows[1].spawned)
        assertEquals(listOf("a"), r.rows[1].merged)
        assertTrue(r.rows[0].lanes.isEmpty())
        assertTrue(r.rows[2].lanes.isEmpty())
    }

    @Test
    fun `overlapping agents never share a column`() {
        // The property the whole file exists for: three agents alive at once must
        // be three lines. A greedy allocator that forgot to mark a lane busy would
        // put all three in lane 0 and the map would look tidy and lie.
        val r = GraphLayout.layout(
            nodes(6),
            listOf(
                agent("a", "n0", "n4", spawnTs = 1),
                agent("b", "n1", "n3", spawnTs = 2),
                agent("c", "n1", "n5", spawnTs = 3),
            ),
        )
        assertEquals(3, r.laneCount)
        assertEquals(listOf(0, 1, 2), lanesOf(r, 1))
        assertEquals(3, r.rows[1].lanes.map { it.agentId }.toSet().size)
        assertEquals(listOf(0, 2), lanesOf(r, 4), "b has come back; a and c have not")
    }

    @Test
    fun `a column is reused once its occupant has come back`() {
        // Forty agents one after another is one lane, not forty. Without reuse a
        // long auto-mode session's gutter is wider than the blocks it annotates.
        val r = GraphLayout.layout(
            nodes(8),
            listOf(
                agent("a", "n0", "n1", spawnTs = 1),
                agent("b", "n2", "n3", spawnTs = 2),
                agent("c", "n4", "n5", spawnTs = 3),
            ),
        )
        assertEquals(1, r.laneCount)
        assertEquals(0, r.rows[0].lanes.single().lane)
        assertEquals(0, r.rows[2].lanes.single().lane)
        assertEquals(0, r.rows[4].lanes.single().lane)
    }

    @Test
    fun `a column is NOT reused on the row its occupant leaves`() {
        // Off-by-one: an agent ending at row 3 is still drawn at row 3, so a new
        // branch starting there needs its own column or the two curves cross.
        val r = GraphLayout.layout(
            nodes(6),
            listOf(agent("a", "n0", "n3", spawnTs = 1), agent("b", "n3", "n5", spawnTs = 2)),
        )
        assertEquals(2, r.laneCount)
        assertEquals(listOf(0, 1), lanesOf(r, 3))
    }

    @Test
    fun `a gap between branches leaves rows with no lanes at all`() {
        val r = GraphLayout.layout(
            nodes(6),
            listOf(agent("a", "n0", "n1", spawnTs = 1), agent("b", "n4", "n5", spawnTs = 2)),
        )
        assertTrue(r.rows.subList(2, 4).all { it.lanes.isEmpty() }, "nothing was running here")
        assertEquals(1, r.laneCount, "the gutter is still one wide, so the spine does not shuffle")
    }

    @Test
    fun `a running agent runs to the bottom of the map`() {
        // It has not come back, and drawing its line as if it had is the map
        // saying the fan-out is over while it is still going.
        val r = GraphLayout.layout(nodes(4), listOf(agent("a", "n1", null, status = "running", spawnTs = 1)))
        assertEquals(GraphLayout.Phase.START, r.rows[1].lanes.single().phase)
        assertEquals(GraphLayout.Phase.PASS, r.rows[2].lanes.single().phase)
        assertEquals(GraphLayout.Phase.END, r.rows[3].lanes.single().phase)
        assertEquals("running", r.rows[3].lanes.single().status)
    }

    @Test
    fun `an agent with no merge point ends at the last block it could have touched`() {
        // A workflow member's parent is told the run LAUNCHED, never that it
        // finished, so there is no tool_result to merge into — the line has to be
        // closed by when the agent last wrote.
        val ns = nodes(5, startTs = 1_000)   // ts 1000, 1060, 1120, 1180, 1240
        val r = GraphLayout.layout(
            ns,
            listOf(agent("w", "n0", null, status = "done", workflowId = "wf_1", spawnTs = 1_000, updatedAt = 1_130)),
        )
        assertEquals(GraphLayout.Phase.END, r.rows[2].lanes.single().phase, "n2 at 1120 is inside; n3 at 1180 is not")
        assertTrue(r.rows[3].lanes.isEmpty())
    }

    @Test
    fun `agents of one workflow share a hue and separate agents do not`() {
        val r = GraphLayout.layout(
            nodes(4),
            listOf(
                agent("w1", "n0", "n2", workflowId = "wf_a", spawnTs = 1),
                agent("w2", "n0", "n2", workflowId = "wf_a", spawnTs = 2),
                agent("d", "n0", "n2", spawnTs = 3),
            ),
        )
        val byId = r.rows[0].lanes.associateBy { it.agentId }
        assertEquals(byId["w1"]!!.hue, byId["w2"]!!.hue, "one fan-out reads as one thing")
        assertTrue(byId["d"]!!.hue != byId["w1"]!!.hue, "an unrelated agent is not part of it")
    }

    @Test
    fun `a hue index never runs off the end of the palette`() {
        val many = (0 until GraphLayout.HUES * 3).map { agent("a$it", "n0", "n1", spawnTs = it.toLong()) }
        val r = GraphLayout.layout(nodes(3), many)
        assertTrue(r.rows[0].lanes.all { it.hue in 0 until GraphLayout.HUES })
    }

    @Test
    fun `a nested agent indents inside its lane rather than taking one of its own`() {
        val r = GraphLayout.layout(
            nodes(3),
            listOf(agent("p", "n0", "n2", depth = 1, spawnTs = 1), agent("c", "n1", "n2", depth = 2, spawnTs = 2)),
        )
        val child = r.rows[1].lanes.first { it.agentId == "c" }
        assertEquals(1, child.indent, "one step in, not one column over")
        assertEquals(0, r.rows[1].lanes.first { it.agentId == "p" }.indent)
    }

    @Test
    fun `indent is clamped, because depth is somebody else's number`() {
        val r = GraphLayout.layout(nodes(2), listOf(agent("d", "n0", "n1", depth = 9, spawnTs = 1)))
        assertEquals(2, r.rows[0].lanes.single().indent)
        val zero = GraphLayout.layout(nodes(2), listOf(agent("z", "n0", "n1", depth = 0, spawnTs = 1)))
        assertEquals(0, zero.rows[0].lanes.single().indent)
    }

    @Test
    fun `an agent whose branch point is not on the map is listed, never dropped`() {
        // The join does not always survive a compaction. Those tokens were still
        // spent, and a map that silently omits work is worse than a loose end.
        val r = GraphLayout.layout(
            nodes(3),
            listOf(agent("ghost", null, spawnTs = 1), agent("stale", "n99", spawnTs = 2), agent("ok", "n0", "n1", spawnTs = 3)),
        )
        assertEquals(listOf("ghost", "stale"), r.unplaced.map { it.id })
        assertEquals(1, r.laneCount)
        assertEquals("ok", r.rows[0].lanes.single().agentId)
    }

    @Test
    fun `the gutter has a ceiling, and what does not fit is counted`() {
        val wide = (0 until GraphLayout.MAX_LANES + 3).map { agent("a$it", "n0", "n5", spawnTs = it.toLong()) }
        val r = GraphLayout.layout(nodes(6), wide)
        assertEquals(GraphLayout.MAX_LANES, r.laneCount)
        assertEquals(3, r.unplaced.size, "past the ceiling the map is a barcode; the count still tells the truth")
    }

    @Test
    fun `the same input lays out the same way every time`() {
        // A poll every five seconds re-lays the map. Columns that shuffled between
        // polls would make a running fan-out flicker.
        val ns = nodes(6)
        val sameSecond = listOf(
            agent("b", "n0", "n4", spawnTs = 100),
            agent("a", "n0", "n3", spawnTs = 100),
            agent("c", "n1", "n5", spawnTs = 100),
        )
        val first = GraphLayout.layout(ns, sameSecond)
        val second = GraphLayout.layout(ns, sameSecond.reversed())
        assertEquals(
            first.rows.map { row -> row.lanes.map { it.agentId to it.lane } },
            second.rows.map { row -> row.lanes.map { it.agentId to it.lane } },
            "ties break on the id, so the order agents arrive in does not move them",
        )
    }

    @Test
    fun `lanes come back ordered left to right`() {
        val r = GraphLayout.layout(
            nodes(4),
            listOf(agent("a", "n0", "n3", spawnTs = 1), agent("b", "n0", "n3", spawnTs = 2), agent("c", "n0", "n3", spawnTs = 3)),
        )
        assertEquals(listOf(0, 1, 2), r.rows[2].lanes.map { it.lane }, "a renderer walks them without sorting")
    }
}
