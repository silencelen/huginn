package com.silencelen.huginn

import com.silencelen.huginn.data.Activity
import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.BgTask
import com.silencelen.huginn.ui.WorkSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The work strip's judgment, which is all of it. NOTE kotlin.test's argument
 * order is (expected, actual, message).
 */
class WorkSummaryTest {

    // ------------------------------------------------------------- headline

    @Test
    fun `the pane's own spinner outranks everything`() {
        val s = WorkSummary.strip(
            spinner = "Pondering…",
            statusLines = emptyList(),
            transient = null,
            activity = Activity(tool = "Bash"),
            tasks = emptyList(),
            bgAgents = 0,
        )
        assertEquals("Pondering…", s.headline)
    }

    @Test
    fun `the transcript's unresolved tool is the fallback`() {
        val s = WorkSummary.strip(
            spinner = null,
            statusLines = emptyList(),
            transient = null,
            activity = Activity(tool = "Bash", detail = "gradle test", subagents = 2),
            tasks = emptyList(),
            bgAgents = 0,
        )
        assertEquals("Bash  gradle test  ·  2 subagents", s.headline)
    }

    @Test
    fun `one subagent is singular`() {
        val s = WorkSummary.strip(null, emptyList(), null, Activity(tool = "Task", subagents = 1), emptyList(), 0)
        assertEquals("Task  ·  1 subagent", s.headline)
    }

    @Test
    fun `background-only work says so rather than claiming a turn is running`() {
        val s = WorkSummary.strip(null, emptyList(), null, null, listOf(BgTask(command = "npm run build")), 0)
        assertEquals("background work", s.headline)
    }

    @Test
    fun `working is the floor`() {
        assertEquals("working", WorkSummary.strip(null, emptyList(), null, null, emptyList(), 0).headline)
    }

    @Test
    fun `a lingering strip does not claim to be working`() {
        // Kept on screen for a few minutes after the work ended so an agent's
        // conclusion is still reachable. Saying "working" there is the strip
        // lying about a session that has stopped.
        val s = WorkSummary.strip(null, emptyList(), null, null, emptyList(), 0, live = false)
        assertEquals("just finished", s.headline)
    }

    @Test
    fun `a lingering strip still reports background work it can see`() {
        val s = WorkSummary.strip(null, emptyList(), null, null, emptyList(), 2, live = false)
        assertEquals("background work", s.headline)
    }

    // -------------------------------------------------------------- details

    @Test
    fun `durable rows come before the transient one and the whole lot is capped`() {
        val s = WorkSummary.strip(
            spinner = "Working…",
            statusLines = listOf("◯ wave3  1/4 agents done"),
            transient = "Read src/Main.kt",
            activity = null,
            tasks = listOf(BgTask(command = "gradle build", forSeconds = 95)),
            bgAgents = 3,
        )
        assertEquals(
            listOf("◯ wave3  1/4 agents done", "⚙ gradle build · 1m", "Read src/Main.kt"),
            s.details,
        )
    }

    @Test
    fun `the agent count is not repeated when a pane row already says it`() {
        val s = WorkSummary.strip(null, listOf("2/6 agents done"), null, null, emptyList(), 4)
        assertEquals(listOf("2/6 agents done"), s.details)
    }

    @Test
    fun `background agents are counted when no pane row mentions them`() {
        val s = WorkSummary.strip(null, emptyList(), null, null, emptyList(), 1)
        assertEquals(listOf("1 background agent"), s.details)
    }

    @Test
    fun `a task with no elapsed time carries no dangling separator`() {
        val s = WorkSummary.strip(null, emptyList(), null, null, listOf(BgTask(command = "tail -f log")), 0)
        assertEquals(listOf("⚙ tail -f log"), s.details)
    }

    // ---------------------------------------------------------- trust rules

    @Test
    fun `pane rows are dropped once the turn ends`() {
        val rows = listOf("◯ wave3  4/4 agents done")
        assertEquals(rows, WorkSummary.paneRows(rows, working = true))
        assertEquals(emptyList(), WorkSummary.paneRows(rows, working = false))
    }

    // ------------------------------------------------------------- lingering

    @Test
    fun `the strip outlives the work so a conclusion can be read`() {
        assertTrue(WorkSummary.visible(working = true, bgWork = false, lastWorkAtMs = null, nowMs = 0))
        assertTrue(WorkSummary.visible(false, bgWork = true, lastWorkAtMs = null, nowMs = 0))
        // Just settled: the agents' summaries arrive about now.
        assertTrue(WorkSummary.visible(false, false, lastWorkAtMs = 1_000, nowMs = 1_000 + 60_000))
        assertFalse(
            WorkSummary.visible(false, false, lastWorkAtMs = 1_000, nowMs = 1_000 + WorkSummary.LINGER_MS + 1),
        )
    }

    @Test
    fun `a session that never worked shows nothing`() {
        assertFalse(WorkSummary.visible(false, false, lastWorkAtMs = null, nowMs = 9_999))
    }

    // ---------------------------------------------------------- agent count

    @Test
    fun `the count uses the TUI's denominator, not the file count`() {
        val agents = AgentsInfo(agents = listOf(AgentRun(id = "a", active = false), AgentRun(id = "b", active = true)))
        assertEquals("1 of 6 agents done", WorkSummary.agentCount(agents, listOf("1/6 agents done")))
    }

    @Test
    fun `more files than the TUI planned still counts every file`() {
        val agents = AgentsInfo(agents = List(5) { AgentRun(id = "$it", active = false) })
        assertEquals("5 of 5 agents done", WorkSummary.agentCount(agents, listOf("0/2 agents done")))
    }

    @Test
    fun `nothing known yet is not an assertion of zero`() {
        assertNull(WorkSummary.agentCount(null, listOf("1/6 agents done")))
        assertEquals("No agents in this session recently", WorkSummary.agentCount(AgentsInfo(), emptyList()))
    }

    // ------------------------------------------------------------- formatting

    @Test
    fun `elapsed reads short`() {
        assertEquals("12s", WorkSummary.agoShort(12))
        assertEquals("4m", WorkSummary.agoShort(4 * 60 + 9))
        assertEquals("1h 12m", WorkSummary.agoShort(3600 + 12 * 60))
    }

    @Test
    fun `agent freshness is measured against the server's clock`() {
        assertEquals("now", WorkSummary.sinceShort(atSec = 1_000, nowSec = 1_030))
        assertEquals("3m", WorkSummary.sinceShort(1_000, 1_000 + 200))
        assertEquals("2h", WorkSummary.sinceShort(1_000, 1_000 + 7_500))
        assertEquals("", WorkSummary.sinceShort(0, 5_000))
    }

    @Test
    fun `the context header is stripped off a task line`() {
        assertEquals("audit the release script", WorkSummary.taskLine("CONTEXT: audit the release script"))
        assertNull(WorkSummary.taskLine("   "))
        assertNull(WorkSummary.taskLine(null))
    }
}
