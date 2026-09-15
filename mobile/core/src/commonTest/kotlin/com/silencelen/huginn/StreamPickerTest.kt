package com.silencelen.huginn

import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.ui.StreamPicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The order of the stream picker's chips. NOTE kotlin.test's argument order is
 * (expected, actual, message).
 *
 * NOW is a host clock in epoch SECONDS, the unit every `updatedAt` on an
 * `AgentRun` uses.
 */
class StreamPickerTest {

    private companion object {
        const val NOW = 1_789_460_000L
    }

    private fun agent(
        id: String,
        task: String? = null,
        active: Boolean = false,
        updatedAt: Long = NOW - 60,
        workflowId: String? = null,
        agentType: String? = null,
        status: String? = null,
    ) = AgentRun(
        id = id,
        task = task,
        active = active,
        updatedAt = updatedAt,
        workflowId = workflowId,
        agentType = agentType,
        status = status,
    )

    @Test
    fun `Main is always the first row and always pickable`() {
        val items = StreamPicker.items(
            listOf(agent("agent-aaa", task = "one", active = true)),
            NOW,
        )
        assertEquals(StreamPicker.MAIN_KEY, items.first().key)
        assertEquals("Main", items.first().label)
        assertNull(items.first().agentId, "Main is the session, not an agent")
        assertFalse(items.first().header, "Main is pickable, not a group label")
    }

    @Test
    fun `an empty fan-out still offers Main`() {
        val items = StreamPicker.items(emptyList(), NOW)
        assertEquals(1, items.size)
        assertEquals(StreamPicker.MAIN_KEY, items.single().key)
    }

    @Test
    fun `running agents come before settled ones, each newest first`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-old", task = "settled long ago", updatedAt = NOW - 4000),
                agent("agent-run1", task = "running, older", active = true, updatedAt = NOW - 300),
                agent("agent-new", task = "settled recently", updatedAt = NOW - 100),
                agent("agent-run2", task = "running, newest", active = true, updatedAt = NOW - 30),
            ),
            NOW,
        )
        assertEquals(
            listOf("main", "agent:agent-run2", "agent:agent-run1", "agent:agent-new", "agent:agent-old"),
            items.map { it.key },
        )
    }

    @Test
    fun `a workflow run travels as one block under its own header`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-solo", task = "direct", updatedAt = NOW - 200),
                agent("agent-m1", task = "member one", updatedAt = NOW - 900, workflowId = "wf_01H9ZKQT"),
                agent("agent-m2", task = "member two", active = true, updatedAt = NOW - 50, workflowId = "wf_01H9ZKQT"),
            ),
            NOW,
        )
        assertEquals(
            listOf("main", "workflow:wf_01H9ZKQT", "agent:agent-m2", "agent:agent-m1", "agent:agent-solo"),
            items.map { it.key },
            "the run sorts by its liveliest member and its members stay contiguous",
        )
        val header = items[1]
        assertTrue(header.header, "the run row labels a group")
        assertNull(header.agentId, "a header is not pickable as a stream")
        assertTrue(header.running, "a run with a live member is live")
        assertEquals("Run 01H9ZKQT", header.label)
    }

    @Test
    fun `an older daemon's rows have no run id and stay flat`() {
        // Compat: 2.85.0 answers `?all=1` with no workflowId at all.
        val items = StreamPicker.items(
            listOf(agent("agent-a", task = "a"), agent("agent-b", task = "b")),
            NOW,
        )
        assertTrue(items.none { it.header }, "no run ids means no group headers")
        assertEquals(3, items.size)
    }

    @Test
    fun `labels are clipped to forty characters including the ellipsis`() {
        val long = "audit every nginx vhost on hermod and report what changed"
        val items = StreamPicker.items(listOf(agent("agent-x", task = long)), NOW)
        val label = items[1].label
        assertEquals(StreamPicker.LABEL_MAX, label.length)
        assertTrue(label.endsWith("…"), "a clipped label must say it was clipped")
        assertTrue(long.startsWith(label.dropLast(1).trimEnd()))
        assertEquals("exactly forty characters long, honest", StreamPicker.clip("exactly forty characters long, honest"))
    }

    @Test
    fun `keys are unique even when the daemon repeats an agent`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-dup", task = "first"),
                agent("agent-dup", task = "second copy"),
                agent("   ", task = "no id at all"),
            ),
            NOW,
        )
        assertEquals(items.size, items.map { it.key }.toSet().size, "LazyColumn throws on a duplicate key")
        assertEquals(listOf("main", "agent:agent-dup"), items.map { it.key })
        assertEquals("first", items[1].label, "the first row wins; the copy is dropped")
    }

    @Test
    fun `an agent that has written nothing for a quarter hour is not running`() {
        // The daemon's `active` is a leftover once the process is gone — the same
        // bug the TUI's own footer has, which WorkSummary.paneRows exists for.
        val items = StreamPicker.items(
            listOf(
                agent("agent-stale", task = "claims to run", active = true, updatedAt = NOW - StreamPicker.STALE_S - 1),
                agent("agent-live", task = "really runs", active = true, updatedAt = NOW - 30),
            ),
            NOW,
        )
        assertFalse(items.first { it.agentId == "agent-stale" }.running)
        assertTrue(items.first { it.agentId == "agent-live" }.running)
        assertEquals("agent:agent-live", items[1].key, "the live one outranks the leftover")
    }

    @Test
    fun `with no clock the daemon's own flag is taken at face value`() {
        val items = StreamPicker.items(
            listOf(agent("agent-stale", task = "claims to run", active = true, updatedAt = NOW - 99_999)),
            0L,
        )
        assertTrue(items[1].running, "nowSec 0 means there is no clock to disbelieve it with")
    }

    @Test
    fun `a chip never renders blank`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-notask", agentType = "workflow-subagent"),
                agent("agent-abcdef123456"),
            ),
            NOW,
        )
        assertEquals("workflow-subagent", items.first { it.agentId == "agent-notask" }.label)
        assertEquals("abcdef12", items.first { it.agentId == "agent-abcdef123456" }.label)
    }

    @Test
    fun `the daemon's own agent facts ride through untouched`() {
        val items = StreamPicker.items(
            listOf(agent("agent-a", task = "t", agentType = "workflow-subagent", status = "failed", updatedAt = NOW - 7)),
            NOW,
        )
        val row = items[1]
        assertEquals("workflow-subagent", row.agentType)
        assertEquals("failed", row.status)
        assertEquals(NOW - 7, row.updatedAt)
    }
}
