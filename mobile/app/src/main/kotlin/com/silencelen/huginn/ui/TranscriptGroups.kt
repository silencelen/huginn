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
