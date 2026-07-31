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

    fun group(events: List<TranscriptEvent>): List<Row> {
        val out = ArrayList<Row>(events.size)
        var run: MutableList<TranscriptEvent>? = null
        for (ev in events) {
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
}
