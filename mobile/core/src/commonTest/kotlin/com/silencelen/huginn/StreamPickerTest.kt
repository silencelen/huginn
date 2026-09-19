package com.silencelen.huginn

import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.ui.StreamPicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WHICH chips the stream picker offers, and in what order. NOTE kotlin.test's
 * argument order is (expected, actual, message).
 *
 * NOW is a host clock in epoch SECONDS, the unit every `updatedAt` on an
 * `AgentRun` uses.
 *
 * The strip answers "which stream am I reading" first and "what did this session
 * do" second, so the default agent below is a LIVE one — that is the only kind
 * that earns a chip of its own. Everything here that is settled is settled on
 * purpose, and lives behind the `…` pill.
 */
class StreamPickerTest {

    private companion object {
        const val NOW = 1_789_460_000L

        /** One more settled agent than the fold will ever unfold. */
        const val FOLDED_OVERFLOW = StreamPicker.FOLDED_MAX + 1
    }

    private fun agent(
        id: String,
        task: String? = null,
        active: Boolean = true,
        updatedAt: Long = NOW - 30,
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
            listOf(agent("agent-aaa", task = "one")),
            NOW,
        )
        assertEquals(StreamPicker.MAIN_KEY, items.first().key)
        assertEquals("Main", items.first().label)
        assertNull(items.first().agentId, "Main is the session, not an agent")
        assertFalse(items.first().header, "Main is pickable, not a group label")
    }

    @Test
    fun `Main is first even when every agent is gone`() {
        // The strip drains down to Main plus the pill, never past it: the row
        // that gets a reader back to their own transcript must never move.
        val items = StreamPicker.items(
            listOf(
                agent("agent-done", task = "over", status = "done"),
                agent("agent-old", task = "silent", updatedAt = NOW - 4000),
            ),
            NOW,
        )
        assertEquals(listOf("main", "more"), items.map { it.key })
    }

    @Test
    fun `an empty fan-out still offers Main`() {
        val items = StreamPicker.items(emptyList(), NOW)
        assertEquals(1, items.size)
        assertEquals(StreamPicker.MAIN_KEY, items.single().key)
    }

    // ------------------------------------------------------------- liveness

    @Test
    fun `a running agent is kept and marked live`() {
        val items = StreamPicker.items(listOf(agent("agent-run", task = "working", status = "running")), NOW)
        assertEquals(listOf("main", "agent:agent-run"), items.map { it.key })
        assertTrue(items[1].running, "an agent that wrote thirty seconds ago is running")
        assertFalse(items[1].finished, "and is not being held open as a courtesy")
    }

    @Test
    fun `a done workflow member is not offered`() {
        // The owner's report in one line: the strip listed every subagent the
        // session had ever used. A member that went green is off Claude Code's
        // own footer within seconds and must be off this strip too.
        val items = StreamPicker.items(
            listOf(
                agent("agent-done", task = "finished", status = "done", workflowId = "wf_01H9ZKQT"),
                agent("agent-live", task = "still going", status = "running", workflowId = "wf_01H9ZKQT"),
            ),
            NOW,
        )
        assertTrue(items.none { it.agentId == "agent-done" }, "done is done: ${items.map { it.key }}")
        assertTrue(items.any { it.agentId == "agent-live" })
        assertEquals(1, items.single { it.overflow }.count, "but it is behind the pill, not gone")
    }

    @Test
    fun `a failed agent is not offered either`() {
        val items = StreamPicker.items(
            listOf(agent("agent-bad", task = "blew up", status = "failed", workflowId = "wf_01H9ZKQT")),
            NOW,
        )
        assertEquals(listOf("main", "more"), items.map { it.key }, "failed is settled, and its header folds with it")
    }

    @Test
    fun `an active flag older than the active window by the host clock is disbelieved`() {
        // `active` is computed when the response is built; the response also
        // carries the clock it was computed against. A list that has sat in a
        // StateFlow past the window cannot keep claiming a live fan-out.
        val items = StreamPicker.items(
            listOf(
                agent("agent-stale", task = "claims to run", updatedAt = NOW - StreamPicker.ACTIVE_S - 1),
                agent("agent-edge", task = "wrote just in time", updatedAt = NOW - StreamPicker.ACTIVE_S),
            ),
            NOW,
        )
        assertTrue(items.none { it.agentId == "agent-stale" }, "ninety-one seconds of silence is not running")
        assertTrue(items.any { it.agentId == "agent-edge" }, "the window is inclusive at its edge")
    }

    @Test
    fun `an agent the daemon never called active is not offered`() {
        val items = StreamPicker.items(listOf(agent("agent-cold", task = "cold", active = false)), NOW)
        assertEquals(listOf("main", "more"), items.map { it.key })
    }

    @Test
    fun `with no clock the daemon's own flag is taken at face value`() {
        val items = StreamPicker.items(
            listOf(agent("agent-stale", task = "claims to run", updatedAt = NOW - 99_999)),
            0L,
        )
        assertTrue(items[1].running, "nowSec 0 means there is no clock to disbelieve it with")
    }

    // --------------------------------------------------- the stream being read

    @Test
    fun `the stream being read is kept after it finishes, and only that one`() {
        val agents = listOf(
            agent("agent-read", task = "the one on screen", status = "done"),
            agent("agent-other", task = "also over", status = "done"),
            agent("agent-live", task = "still going"),
        )
        val items = StreamPicker.items(agents, NOW, selectedKey = "agent-read")
        assertTrue(items.any { it.agentId == "agent-read" }, "a reader's transcript is never yanked out from under them")
        assertTrue(items.none { it.agentId == "agent-other" }, "the courtesy is for ONE chip, not for history")

        val kept = items.first { it.agentId == "agent-read" }
        assertFalse(kept.running, "it is not running and must not draw the live dot")
        assertTrue(kept.finished, "it says so instead")
        assertFalse(items.first { it.agentId == "agent-live" }.finished)
    }

    @Test
    fun `the row key is accepted as the selection too`() {
        // The clients hold the pick as the id `onPick` handed them; the rows are
        // keyed `agent:<id>`. Both spellings mean the same stream.
        val agents = listOf(agent("agent-read", task = "on screen", status = "done"))
        assertTrue(StreamPicker.items(agents, NOW, "agent:agent-read").any { it.agentId == "agent-read" })
        assertEquals(
            listOf("main", "more"),
            StreamPicker.items(agents, NOW, StreamPicker.MAIN_KEY).map { it.key },
            "Main is not an agent, so nothing is held open for it",
        )
    }

    @Test
    fun `switching back to Main folds the finished chip away on the next refresh`() {
        val agents = listOf(agent("agent-read", task = "the one on screen", status = "done"))
        assertEquals(
            listOf("main", "agent:agent-read", "more"),
            StreamPicker.items(agents, NOW, "agent-read").map { it.key },
        )
        assertEquals(
            listOf("main", "more"),
            StreamPicker.items(agents, NOW, selectedKey = null).map { it.key },
            "nothing is selected, so nothing is owed a chip of its own",
        )
    }

    // ------------------------------------------------------------- the pill

    @Test
    fun `no settled agent means no pill at all`() {
        val items = StreamPicker.items(listOf(agent("agent-live", task = "working")), NOW)
        assertTrue(items.none { it.overflow }, "a strip with nothing folded must not offer to unfold it")
        assertEquals(listOf("main", "agent:agent-live"), items.map { it.key })
    }

    @Test
    fun `the pill counts every settled agent and is never a stream`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-live", task = "working"),
                agent("agent-d1", task = "over", status = "done"),
                agent("agent-d2", task = "over too", status = "failed"),
                agent("agent-d3", task = "silent", updatedAt = NOW - 5000),
            ),
            NOW,
        )
        assertEquals(listOf("main", "agent:agent-live", "more"), items.map { it.key })
        val pill = items.single { it.overflow }
        assertEquals("…", pill.label)
        assertEquals(3, pill.count)
        assertNull(pill.agentId, "the pill opens no transcript")
        assertFalse(pill.header)
    }

    @Test
    fun `unfolding the pill lists the settled agents, dimmed and newest first`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-live", task = "working"),
                agent("agent-old", task = "oldest", status = "done", updatedAt = NOW - 5000),
                agent("agent-mid", task = "middle", status = "done", updatedAt = NOW - 900),
            ),
            NOW,
            expanded = true,
        )
        assertEquals(
            listOf("main", "agent:agent-live", "more", "agent:agent-mid", "agent:agent-old"),
            items.map { it.key },
            "the pill stays put as the control that closes it again",
        )
        items.filter { it.agentId?.startsWith("agent-") == true && it.agentId != "agent-live" }.forEach {
            assertTrue(it.finished, "${it.agentId} is unfolded from the pill, not live work")
            assertFalse(it.running, "${it.agentId} must not draw the live dot")
        }
        assertEquals(2, items.single { it.overflow }.count)
    }

    @Test
    fun `the unfolded list is capped and the pill still says how many there are`() {
        val many = (1..FOLDED_OVERFLOW).map {
            agent("agent-d$it", task = "settled $it", status = "done", updatedAt = NOW - 1000 - it)
        }
        val items = StreamPicker.items(many, NOW, expanded = true)
        assertEquals(StreamPicker.FOLDED_MAX, items.count { it.agentId != null }, "past a screenful it is a wall")
        assertEquals(FOLDED_OVERFLOW, items.single { it.overflow }.count, "the count is the TRUE total")
        // Newest first, so the cap cuts the oldest.
        assertEquals("agent:agent-d1", items.first { it.agentId != null }.key)
        assertTrue(items.none { it.agentId == "agent-d$FOLDED_OVERFLOW" })
    }

    @Test
    fun `the cap never cuts the stream being read`() {
        val many = (1..FOLDED_OVERFLOW).map {
            agent("agent-d$it", task = "settled $it", status = "done", updatedAt = NOW - 1000 - it)
        }
        val oldest = "agent-d$FOLDED_OVERFLOW"
        val items = StreamPicker.items(many, NOW, selectedKey = oldest, expanded = true)
        assertEquals(StreamPicker.FOLDED_MAX, items.count { it.agentId != null }, "the cap still holds")
        assertTrue(items.any { it.agentId == oldest }, "it takes the oldest kept row's place, it is not cut")
    }

    @Test
    fun `the agent being read is on the strip whether the pill is open or shut`() {
        val agents = listOf(
            agent("agent-live", task = "working"),
            agent("agent-read", task = "on screen, over", status = "done"),
            agent("agent-other", task = "over", status = "done"),
        )
        val shut = StreamPicker.items(agents, NOW, selectedKey = "agent-read")
        assertEquals(listOf("main", "agent:agent-live", "agent:agent-read", "more"), shut.map { it.key })

        val open = StreamPicker.items(agents, NOW, selectedKey = "agent-read", expanded = true)
        assertEquals(
            1,
            open.count { it.agentId == "agent-read" },
            "once, not twice: a duplicate key is a LazyColumn crash, not a style note",
        )
        assertTrue(open.any { it.agentId == "agent-other" })
    }

    // -------------------------------------------------------------- ordering

    @Test
    fun `live agents keep the order they were first seen in`() {
        // ⚠⚠ THIS USED TO BE "newest first", AND THE CHIPS MOVED UNDER THE
        // POINTER. A running agent writes every few seconds, so ordering the live
        // half by `updatedAt` meant two live chips swapped places on the next
        // five-second poll while somebody was reaching for one. Recency is the
        // right order for a list being read top-down and the wrong one for a row
        // of targets. First-seen never changes; see StreamPickerSheetTest.
        //
        // These rows carry no `startedAt`, which is the pre-3.x daemon case: the
        // last write stands in for it, and the order is then stable for exactly as
        // long as the list is.
        val items = StreamPicker.items(
            listOf(
                agent("agent-run1", task = "running, older", updatedAt = NOW - 80),
                agent("agent-run2", task = "running, newest", updatedAt = NOW - 5),
                agent("agent-run3", task = "running, middle", updatedAt = NOW - 40),
            ),
            NOW,
        )
        assertEquals(
            listOf("main", "agent:agent-run1", "agent:agent-run3", "agent:agent-run2"),
            items.map { it.key },
        )
    }

    @Test
    fun `the held-open chip sorts below everything still running`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-read", task = "on screen, over", status = "done", updatedAt = NOW - 1),
                agent("agent-live", task = "still going", updatedAt = NOW - 60),
            ),
            NOW,
            selectedKey = "agent-read",
        )
        assertEquals(
            listOf("main", "agent:agent-live", "agent:agent-read", "more"),
            items.map { it.key },
            "the newest updatedAt does not outrank actually running",
        )
    }

    // -------------------------------------------------------------- workflows

    @Test
    fun `a workflow run travels as one block under its own header`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-solo", task = "direct", updatedAt = NOW - 80),
                agent("agent-m1", task = "member one", updatedAt = NOW - 70, workflowId = "wf_01H9ZKQT"),
                agent("agent-m2", task = "member two", updatedAt = NOW - 5, workflowId = "wf_01H9ZKQT"),
            ),
            NOW,
        )
        assertEquals(
            // First-seen among the units, and among a run's members — the same
            // rule as the loose chips, for the same reason. What matters here is
            // that the run's rows stay CONTIGUOUS under their header, which is
            // what stops a six-agent run interleaving itself through the strip.
            listOf("main", "agent:agent-solo", "workflow:wf_01H9ZKQT", "agent:agent-m1", "agent:agent-m2"),
            items.map { it.key },
            "the run sorts by its oldest member and its members stay contiguous",
        )
        val header = items[2]
        assertTrue(header.header, "the run row labels a group")
        assertNull(header.agentId, "a header is not pickable as a stream")
        assertTrue(header.running, "a run with a live member is live")
        assertEquals("Run 01H9ZKQT", header.label)
    }

    @Test
    fun `a run whose members are all done folds away, header and all`() {
        val members = listOf(
            agent("agent-m1", task = "member one", status = "done", updatedAt = NOW - 900, workflowId = "wf_01H9ZKQT"),
            agent("agent-m2", task = "member two", status = "done", updatedAt = NOW - 100, workflowId = "wf_01H9ZKQT"),
            agent("agent-solo", task = "direct and live"),
        )
        assertEquals(
            listOf("main", "agent:agent-solo", "more"),
            StreamPicker.items(members, NOW).map { it.key },
            "collapsed, a header over nothing live is a group of zero",
        )
        // Unfolded it comes back — the run is how those two transcripts are found.
        assertEquals(
            listOf("main", "agent:agent-solo", "more", "workflow:wf_01H9ZKQT:finished", "agent:agent-m2", "agent:agent-m1"),
            StreamPicker.items(members, NOW, expanded = true).map { it.key },
        )
    }

    @Test
    fun `a run split across the fold gets a header on each side, under its own key`() {
        // Two rows under one key is a LazyColumn crash, so the folded header is
        // keyed apart from the live one rather than either being dropped.
        val items = StreamPicker.items(
            listOf(
                agent("agent-m1", task = "still going", workflowId = "wf_01H9ZKQT"),
                agent("agent-m2", task = "finished", status = "done", workflowId = "wf_01H9ZKQT"),
            ),
            NOW,
            expanded = true,
        )
        assertEquals(
            listOf("main", "workflow:wf_01H9ZKQT", "agent:agent-m1", "more", "workflow:wf_01H9ZKQT:finished", "agent:agent-m2"),
            items.map { it.key },
        )
        assertEquals(items.size, items.map { it.key }.toSet().size, "keys stay unique across the fold")
        assertTrue(items[1].running, "the live half of the run is live")
        assertFalse(items.first { it.key.endsWith(":finished") }.running, "the folded half is not")
    }

    @Test
    fun `a held-open member is a chip, not a run of one`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-m1", task = "member one", status = "done", workflowId = "wf_01H9ZKQT"),
                agent("agent-m2", task = "member two", status = "done", workflowId = "wf_01H9ZKQT"),
            ),
            NOW,
            selectedKey = "agent-m2",
        )
        assertEquals(listOf("main", "agent:agent-m2", "more"), items.map { it.key })
        assertTrue(items.none { it.header }, "collapsed, a header needs a live member to earn its line")
    }

    @Test
    fun `an older daemon's rows have no run id and stay flat`() {
        // Compat: 2.85.0 answers with no workflowId at all.
        val items = StreamPicker.items(
            listOf(agent("agent-a", task = "a"), agent("agent-b", task = "b")),
            NOW,
        )
        assertTrue(items.none { it.header }, "no run ids means no group headers")
        assertEquals(3, items.size)
    }

    // ----------------------------------------------------------------- labels

    @Test
    fun `labels are clipped to the chip width including the ellipsis`() {
        // 28, not 40: at 40 a chip was wider than half a 411dp phone and the
        // unfolded list laid out one per row — nine settled agents, nine
        // full-width lines. See StreamPicker.LABEL_MAX.
        assertEquals(28, StreamPicker.LABEL_MAX, "the width two chips a row is measured against")
        val long = "audit every nginx vhost on hermod and report what changed"
        val items = StreamPicker.items(listOf(agent("agent-x", task = long)), NOW)
        val label = items[1].label
        // AT MOST, not exactly: clip trims the trailing space before the ellipsis,
        // so a cut that lands on a word boundary comes out one short. Never over
        // is the rule the FlowRow's measurement depends on.
        assertTrue(label.length <= StreamPicker.LABEL_MAX, "'$label' is ${label.length}")
        assertEquals(StreamPicker.LABEL_MAX, StreamPicker.clip("x".repeat(90)).length, "the ellipsis is inside the budget")
        assertTrue(label.endsWith("…"), "a clipped label must say it was clipped")
        assertTrue(long.startsWith(label.dropLast(1).trimEnd()))
        assertEquals("exactly twenty-eight chars ok", StreamPicker.clip("exactly twenty-eight chars ok", max = 29))
    }

    @Test
    fun `a prompt body is never what a chip says`() {
        // THE DEFECT, from the owner's phone: nine settled recon agents, nine
        // chips reading "You are a READ-ONLY reconnaiss…". The daemon's `task` is
        // literally the first line of the agent's first user record, and a
        // fan-out hands every sibling the same opening paragraph — so the one
        // field the strip was labelling with is the one field that cannot tell
        // them apart.
        val prompt = "You are a READ-ONLY reconnaissance agent on huginn (LXC 117). Audit the tree."
        val agents = (1..9).map { agent("agent-recon$it", task = prompt, status = "done") }
        val items = StreamPicker.items(agents, NOW, expanded = true)
        val chips = items.filter { it.agentId != null }
        assertEquals(9, chips.size)
        assertTrue(
            chips.none { it.label.lowercase().startsWith("you are") },
            "the prompt body is boilerplate, not a name: ${chips.map { it.label }}",
        )
        assertEquals(
            9, chips.map { it.label }.toSet().size,
            "nine chips that read the same are one chip drawn nine times: ${chips.map { it.label }}",
        )
    }

    @Test
    fun `the agent's own summary outranks its task`() {
        // The summary is the only string on an AgentRun written as an account of
        // the work rather than as instructions for it.
        val items = StreamPicker.items(
            listOf(
                AgentRun(
                    id = "agent-sum",
                    task = "You are a READ-ONLY reconnaissance agent. Go and look.",
                    summary = "nginx vhosts audited\nsecond line ignored",
                    active = true,
                    updatedAt = NOW - 30,
                ),
            ),
            NOW,
        )
        assertEquals("nginx vhosts audited", items[1].label)
    }

    @Test
    fun `an ordinary task still labels its own chip`() {
        // The fallback is for PROMPTS, not for every task. A short written task —
        // which is what the Agent tool's own `description` produces — is the best
        // label there is and must survive.
        val items = StreamPicker.items(listOf(agent("agent-x", task = "audit the nginx vhosts")), NOW)
        assertEquals("audit the nginx vhosts", items[1].label)
    }

    @Test
    fun `identical labels are separated by the row's own short id`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-aaaaaaaa11", task = "sweep the tree"),
                agent("agent-bbbbbbbb22", task = "sweep the tree"),
            ),
            NOW,
        )
        val labels = items.filter { it.agentId != null }.map { it.label }
        assertEquals(2, labels.toSet().size, "two identical chips: $labels")
        assertTrue(labels.all { it.length <= StreamPicker.LABEL_MAX }, "still one chip wide: $labels")
        assertTrue(labels.any { it.endsWith("aaaaaaaa") }, labels.toString())
        assertTrue(labels.any { it.endsWith("bbbbbbbb") }, labels.toString())
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
    fun `a dead first copy does not let a live duplicate back in`() {
        val items = StreamPicker.items(
            listOf(
                agent("agent-dup", task = "settled", status = "done"),
                agent("agent-dup", task = "the same agent, listed twice"),
            ),
            NOW,
        )
        assertEquals(listOf("main", "more"), items.map { it.key }, "one id is one row, alive or not")
        assertEquals(1, items.single { it.overflow }.count, "and is counted once")
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
        // Type AND id: the type alone is what every member of a workflow run
        // shares, so on its own it is the same wall the prompt body was.
        assertEquals("workflow-subagent · notask", items.first { it.agentId == "agent-notask" }.label)
        assertEquals("abcdef12", items.first { it.agentId == "agent-abcdef123456" }.label)
    }

    @Test
    fun `the daemon's own agent facts ride through untouched`() {
        val items = StreamPicker.items(
            listOf(agent("agent-a", task = "t", agentType = "workflow-subagent", status = "running", updatedAt = NOW - 7)),
            NOW,
        )
        val row = items[1]
        assertEquals("workflow-subagent", row.agentType)
        assertEquals("running", row.status)
        assertEquals(NOW - 7, row.updatedAt)
    }
}
