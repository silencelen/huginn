package com.silencelen.huginn.ui

import com.silencelen.huginn.data.TranscriptEvent

/**
 * Folds a transcript's subagent activity into visible, openable groups.
 *
 * Subagent events used to render inline with a small indent, which was truthful
 * and useless in practice: during a fan-out the sidechain is most of the
 * transcript, so the main thread — the thing being followed — drowned in its own
 * helpers, and there was no way to see "a subagent ran, here is what it did" as a
 * unit. A run of consecutive sidechain events is one unit of delegated work, so it
 * becomes one row: closed, a single line saying what was delegated and how much
 * happened; open, the full play-by-play.
 *
 * Pure and separate from the rendering so the folding rule is testable: getting a
 * boundary wrong here silently swallows events into the wrong card, which is the
 * kind of bug nobody reports because nobody can see it.
 */
object TranscriptGroups {

    sealed interface Row {
        /** An ordinary main-thread event. */
        data class Single(val event: TranscriptEvent) : Row

        /** A consecutive run of subagent events, shown as one openable unit. */
        data class Subagents(val events: List<TranscriptEvent>) : Row {
            /**
             * The task the subagent was given: its first user event, which is the
             * prompt the parent wrote for it. The best possible one-line summary,
             * because it is the parent's own description of the work.
             */
            val task: String? get() = events.firstOrNull { it.kind == "user" }?.text
                ?.trim()?.takeIf { it.isNotEmpty() }

            /** Steps worth counting: what it said and did, not its own prompts. */
            val steps: Int get() = events.count { it.kind != "user" }

            /** A stable identity for expansion state: where the run starts. */
            val key: Int get() = events.first().seq
        }
    }

    /**
     * A row's identity for list keys and saved state.
     *
     * Both conversation lists were POSITIONAL (`items(rows.size)`), so once the
     * retained window hit its cap every poll dropped events off the front and shifted
     * every index — LazyColumn anchors scroll by position, so the content slid under a
     * reader who was scrolled up looking at something. Keyed on the row's first event
     * instead, the viewport stays on the row it was on.
     *
     * The kind is part of the key so a Single and a Subagents run starting at the same
     * seq can never collide.
     */
    private fun keyOf(row: Row): String = when (row) {
        is Row.Single -> "s${row.event.seq}"
        is Row.Subagents -> "a${row.key}"
    }

    /**
     * Keys for a whole row list, guaranteed distinct.
     *
     * LazyColumn THROWS on a duplicate key, which would take out the entire
     * conversation view — a far worse outcome than the drifting scroll position keys
     * were added to fix. Uniqueness is supposed to hold by construction (the merge
     * renumbers, and one page's seqs are unique), so a collision here means an
     * assumption broke somewhere upstream; that costs one row its anchoring instead
     * of costing the reader the screen.
     */
    fun keys(rows: List<Row>): List<String> {
        val seen = HashSet<String>(rows.size * 2)
        return rows.map { row ->
            val base = keyOf(row)
            if (seen.add(base)) return@map base
            var n = 2
            while (!seen.add("$base#$n")) n++
            "$base#$n"
        }
    }

    /**
     * How many agents the fan-out PLANNED, read off the TUI's own progress row
     * ("0/4 agents done"), or null when no row says so.
     *
     * It has to come from here rather than from the agent files: a planned agent
     * has no file until it starts, so counting files undercounts the total for
     * exactly as long as the fan-out is interesting to look at — and the client's
     * "1 of 2 agents" then sat beside the pane's own "1/6 agents done" and read as
     * a different fan-out. Takes the largest total on screen, since several
     * workflow rows can be present and the widest is the job.
     *
     * Null rather than 0 when nothing reports agents: the caller falls back to the
     * file count, and a 0 would render "3 of 0 agents done".
     */
    fun plannedAgents(statusLines: List<String>): Int? =
        statusLines
            .mapNotNull { AGENTS_DONE.find(it)?.groupValues?.get(2)?.toIntOrNull() }
            .maxOrNull()

    private val AGENTS_DONE = Regex("(\\d+)\\s*/\\s*(\\d+)\\s+agents?\\s+done")

    fun group(events: List<TranscriptEvent>): List<Row> {
        val out = ArrayList<Row>(events.size)
        var run: MutableList<TranscriptEvent>? = null
        for (ev in withLateAnswers(events)) {
            if (ev.sidechain) {
                (run ?: ArrayList<TranscriptEvent>().also { run = it }).add(ev)
            } else {
                run?.let { out.add(Row.Subagents(it)) }
                run = null
                out.add(Row.Single(ev))
            }
        }
        run?.let { out.add(Row.Subagents(it)) }
        return out
    }

    /**
     * An AskUserQuestion's answer, read in a LATER page than the question,
     * put back on the question's card.
     *
     * ⚠⚠ THE PHONE'S CARD SAID "Waiting for your answer" AFTER IT WAS ANSWERED
     * (2026-11-03), with Claude Code's sentence to the model printed under it as
     * a one-line monospace row. A view following a session reads the question in
     * one tail page and the answer in the next, and the daemon pairs a result
     * with its call only inside one page — so the answer arrives as an orphan
     * `tool_result`, carrying no tool-use id to pair it by.
     *
     * The pairing is therefore by order, and narrow on purpose:
     *  - the orphan goes to the NEAREST question on its own thread, and only if
     *    that question has no result yet (questions are answered in order, so
     *    an answered one means this result is not for any question above it);
     *  - an answer-shaped result ([AskAnswers.isAnswer]) may skip past other
     *    tools still waiting — a question asked alongside a Read;
     *  - a FAILED result (Esc, a timeout) has no shape to know it by, so it is
     *    the question's only when nothing else is waiting between them.
     * Anything else stays the orphan row it always was. Pure and allocation-free
     * for a window with no orphans, which is nearly every one.
     */
    internal fun withLateAnswers(events: List<TranscriptEvent>): List<TranscriptEvent> {
        if (events.none { it.kind == "tool_result" }) return events
        val out = ArrayList<TranscriptEvent>(events.size)
        for (ev in events) {
            if (ev.kind == "tool_result") {
                val at = openQuestion(out, ev)
                if (at >= 0) {
                    out[at] = out[at].copy(result = ev.result, ok = ev.ok)
                    continue
                }
            }
            out.add(ev)
        }
        return out
    }

    /** Index in [seen] of the question [orphan] answers, or -1. See [withLateAnswers]. */
    private fun openQuestion(seen: List<TranscriptEvent>, orphan: TranscriptEvent): Int {
        val shaped = AskAnswers.isAnswer(orphan.result)
        if (!shaped && orphan.ok != false) return -1
        var waiting = false
        for (i in seen.indices.reversed()) {
            val e = seen[i]
            if (e.sidechain != orphan.sidechain || e.kind != "tool") continue
            if (e.ask != null) {
                if (!e.result.isNullOrBlank()) return -1
                return if (shaped || !waiting) i else -1
            }
            if (e.result.isNullOrBlank()) waiting = true
        }
        return -1
    }
}
