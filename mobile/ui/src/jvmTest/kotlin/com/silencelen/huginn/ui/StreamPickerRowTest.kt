package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AgentRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which chip on the stream strip is drawn as the one being read.
 *
 * The ORDER of the strip is [StreamPicker]'s and tested in `:core`; what is left
 * here is the one judgment the row itself makes, and it is the one with two
 * spellings to reconcile: the controller says `null` for the session's own
 * transcript and the picker's Main row says [StreamPicker.MAIN_KEY]. If those two
 * ever stop agreeing, nothing is marked and every chip looks unpicked.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class StreamPickerRowTest {

    private val nowSec = 1_800_000_000L

    private fun agent(
        id: String,
        task: String = "audit the thing",
        active: Boolean = false,
        workflow: String? = null,
    ) = AgentRun(
        id = id,
        task = task,
        active = active,
        updatedAt = nowSec - 30,
        workflowId = workflow,
    )

    @Test
    fun `main is marked when nothing is picked, and by its own key too`() {
        val items = StreamPicker.items(listOf(agent("agent-aaa")), nowSec)
        val main = items.first()
        assertTrue(streamChipSelected(main, null), "null is how the controller spells Main")
        assertTrue(streamChipSelected(main, StreamPicker.MAIN_KEY), "so is the picker's own key")
        assertFalse(streamChipSelected(main, "agent-aaa"))
    }

    @Test
    fun `exactly one chip is marked for a picked agent`() {
        val items = StreamPicker.items(listOf(agent("agent-aaa"), agent("agent-bbb")), nowSec)
        val marked = items.filter { streamChipSelected(it, "agent-bbb") }
        assertEquals(1, marked.size, "two marked chips is a strip that cannot say what is on screen")
        assertEquals("agent-bbb", marked.single().agentId)
    }

    @Test
    fun `a workflow header is never the marked chip`() {
        // It labels a group. Marking it would claim a transcript that does not
        // exist is on screen, and picking it could only ever do nothing.
        val items = StreamPicker.items(
            listOf(agent("agent-aaa", workflow = "wf_01H9ABCDEF"), agent("agent-bbb", workflow = "wf_01H9ABCDEF")),
            nowSec,
        )
        val header = items.single { it.header }
        assertFalse(streamChipSelected(header, null))
        assertFalse(streamChipSelected(header, header.workflowId))
        assertFalse(streamChipSelected(header, header.key))
    }

    @Test
    fun `an agent that has gone away leaves nothing marked rather than falling back to main`() {
        // The controller is still pointed at a stream the host no longer lists.
        // Silently marking Main would say the main transcript is on screen while
        // the body still shows the agent's — the strip lying about its own body.
        val items = StreamPicker.items(listOf(agent("agent-aaa")), nowSec)
        assertTrue(items.none { streamChipSelected(it, "agent-zzz") })
    }
}
