package com.silencelen.huginn

import com.silencelen.huginn.data.AskData
import com.silencelen.huginn.data.AskQuestion
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.TranscriptGroups
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The folding rule for subagent runs. A wrong boundary here swallows events into
 * the wrong card, which nobody reports because nobody can see it.
 */
class TranscriptGroupsTest {

    private fun ev(seq: Int, kind: String, side: Boolean = false, text: String? = null) =
        TranscriptEvent(seq = seq, kind = kind, sidechain = side, text = text)

    @Test
    fun `a plain conversation stays row for row`() {
        val rows = TranscriptGroups.group(
            listOf(ev(1, "user"), ev(2, "assistant"), ev(3, "tool")),
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { it is TranscriptGroups.Row.Single })
    }

    @Test
    fun `consecutive sidechain events fold into one group`() {
        val rows = TranscriptGroups.group(
            listOf(
                ev(1, "assistant"),
                ev(2, "user", side = true, text = "Search the tree for FIXME"),
                ev(3, "thinking", side = true),
                ev(4, "tool", side = true),
                ev(5, "assistant", side = true),
                ev(6, "assistant"),
            ),
        )
        assertEquals(3, rows.size)
        val group = rows[1] as TranscriptGroups.Row.Subagents
        assertEquals(4, group.events.size)
        assertEquals("Search the tree for FIXME", group.task)
        assertEquals(3, group.steps)   // the prompt is the task, not a step
    }

    @Test
    fun `two separated fan-outs are two groups, not one`() {
        val rows = TranscriptGroups.group(
            listOf(
                ev(1, "tool", side = true),
                ev(2, "assistant"),
                ev(3, "tool", side = true),
            ),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0] is TranscriptGroups.Row.Subagents)
        assertTrue(rows[1] is TranscriptGroups.Row.Single)
        assertTrue(rows[2] is TranscriptGroups.Row.Subagents)
    }

    @Test
    fun `a transcript ending mid-fan-out still shows the group`() {
        // The tail case matters most: it is what a live session looks like while
        // subagents are running right now.
        val rows = TranscriptGroups.group(
            listOf(ev(1, "assistant"), ev(2, "tool", side = true), ev(3, "tool", side = true)),
        )
        assertEquals(2, rows.size)
        assertEquals(2, (rows[1] as TranscriptGroups.Row.Subagents).events.size)
    }

    @Test
    fun `a group with no user event has no task line`() {
        val rows = TranscriptGroups.group(listOf(ev(1, "tool", side = true)))
        assertNull((rows[0] as TranscriptGroups.Row.Subagents).task)
    }

    @Test
    fun `the group key is stable while the group grows`() {
        // Expansion state is remembered by key; a key that changed as events
        // streamed in would collapse the card the reader just opened.
        val first = TranscriptGroups.group(listOf(ev(7, "tool", side = true)))
        val grown = TranscriptGroups.group(
            listOf(ev(7, "tool", side = true), ev(8, "assistant", side = true)),
        )
        assertEquals(
            (first[0] as TranscriptGroups.Row.Subagents).key,
            (grown[0] as TranscriptGroups.Row.Subagents).key,
        )
    }

    @Test
    fun `an empty transcript groups to nothing`() {
        assertTrue(TranscriptGroups.group(emptyList()).isEmpty())
    }

    @Test
    fun `a blank task is treated as absent`() {
        val rows = TranscriptGroups.group(listOf(ev(1, "user", side = true, text = "   ")))
        assertNull((rows[0] as TranscriptGroups.Row.Subagents).task)
    }

    @Test
    fun `row keys are distinct, and a Single never collides with a Subagents run`() {
        // LazyColumn throws on a duplicate key, so this is a crash guard as much as
        // an identity check.
        val rows = TranscriptGroups.group(
            listOf(ev(1, "user"), ev(2, "tool", side = true), ev(3, "assistant")),
        )
        val keys = TranscriptGroups.keys(rows)
        assertEquals(rows.size, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a repeated seq costs one row its anchoring, not the screen`() {
        // Uniqueness holds by construction upstream; if that ever breaks, the list
        // must still render.
        val rows = TranscriptGroups.group(listOf(ev(4, "assistant"), ev(4, "assistant")))
        val keys = TranscriptGroups.keys(rows)
        assertEquals(2, keys.size)
        assertEquals(2, keys.toSet().size)
        assertEquals("s4", keys[0])
    }

    // ------------------------------------------------ an answer in a later read

    private val sizeShape = AskData(
        listOf(
            AskQuestion(question = "Which size?", header = "Size", options = listOf("Small", "Medium", "Large")),
            AskQuestion(question = "Which shape?", header = "Shape", options = listOf("Circle", "Square")),
        ),
    )

    private fun ask(seq: Int, result: String? = null) = TranscriptEvent(
        seq = seq, kind = "tool", name = "AskUserQuestion", detail = "Which size?", input = "", ask = sizeShape,
        result = result, ok = if (result == null) null else true,
    )

    private fun orphan(seq: Int, result: String, ok: Boolean = true) =
        TranscriptEvent(seq = seq, kind = "tool_result", result = result, ok = ok)

    /** Verbatim, Claude Code 2.1.316 (2026-11-03): a free-text answer to each question. */
    private val answered = "The user answered: \"Which size?\"=\"extra wide\", \"Which shape?\"=\"hexagon\". " +
        "Read the answers carefully \u2014 they may request clarification, changes, or that you not proceed \u2014 " +
        "and follow what they actually say."

    /**
     * ⚠ THE PHONE DREW THE ANSWER AS A ONE-LINE MONO ROW (2026-11-03). A view
     * that is following the session reads the ask in one tail page and the
     * answer in the next; the daemon can only pair a result with a call it read
     * in the SAME page, so the answer arrives as an orphan `tool_result` — and
     * the card above it said "Waiting for your answer" forever, with Claude
     * Code's sentence to the model printed under it in monospace.
     */
    @Test
    fun `an answer read after its question completes the question's card`() {
        val rows = TranscriptGroups.group(
            listOf(ev(1, "user", text = "pick a widget"), ask(2), ev(3, "thinking"), orphan(4, answered), ev(5, "assistant")),
        )
        assertEquals(listOf("user", "tool", "thinking", "assistant"), rows.map { (it as TranscriptGroups.Row.Single).event.kind })
        val card = (rows[1] as TranscriptGroups.Row.Single).event
        assertEquals(answered, card.result, "the card carries the answer")
        assertEquals(true, card.ok)
        assertEquals(2, card.seq, "and keeps its own identity")
    }

    @Test
    fun `an answer goes to the question still open, never to one already answered`() {
        val first = ask(1, result = answered)
        val rows = TranscriptGroups.group(listOf(first, ev(2, "assistant"), ask(3), orphan(4, answered)))
        assertEquals(3, rows.size, "the orphan is folded into the open card")
        assertEquals(first, (rows[0] as TranscriptGroups.Row.Single).event, "the answered card is untouched")
        assertEquals(answered, (rows[2] as TranscriptGroups.Row.Single).event.result)
        // A subagent's result belongs to the subagent's own calls.
        val side = TranscriptGroups.group(listOf(ask(1), orphan(2, answered).copy(sidechain = true)))
        assertEquals(2, side.size)
        assertEquals(null, (side[0] as TranscriptGroups.Row.Single).event.result)
    }

    @Test
    fun `another tool's output is never taken for the answer`() {
        // Ask and Read called together: both results can arrive in a later page.
        val read = TranscriptEvent(seq = 2, kind = "tool", name = "Read", input = "/srv/notes.md")
        val readOut = orphan(3, "1\tnotes for the size question")
        val rows = TranscriptGroups.group(listOf(ask(1), read, readOut, orphan(4, answered)))
        assertEquals(3, rows.size)
        assertEquals(answered, (rows[0] as TranscriptGroups.Row.Single).event.result, "the answer finds its card")
        assertEquals(readOut, (rows[2] as TranscriptGroups.Row.Single).event, "the Read's output stays its own row")
        // ...and a FAILED result is the question's only when nothing else is waiting.
        val failed = orphan(3, "File does not exist.", ok = false)
        val busy = TranscriptGroups.group(listOf(ask(1), read, failed))
        assertEquals(3, busy.size)
        assertEquals(null, (busy[0] as TranscriptGroups.Row.Single).event.result)
    }

    @Test
    fun `a refused question is completed by its refusal`() {
        // Esc on the dialog, verbatim (is_error true on the wire, so ok = false).
        val refusal = "The user doesn't want to proceed with this tool use. The tool use was rejected " +
            "(eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing " +
            "and wait for the user to tell you how to proceed."
        val rows = TranscriptGroups.group(listOf(ask(1), orphan(2, refusal, ok = false)))
        assertEquals(1, rows.size)
        val card = (rows[0] as TranscriptGroups.Row.Single).event
        assertEquals(refusal, card.result)
        assertEquals(false, card.ok)
    }
}
