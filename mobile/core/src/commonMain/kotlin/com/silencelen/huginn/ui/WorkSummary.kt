package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Activity
import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.BgTask

/**
 * What a session is doing right now, compressed into the few lines a strip can
 * hold — and the rules about when that strip may be believed at all.
 *
 * Pure and shared, because the judgment is the feature and none of it is
 * platform-shaped: which source outranks which, which pane rows are trustworthy
 * once the turn ends, how long the strip outlives the work, and whose number the
 * agent count is. The phone learned each of these from a real wrong answer on
 * screen; a second client re-deriving them by eye would get a different one.
 */
object WorkSummary {

    /**
     * How long the strip stays after work stops.
     *
     * Bought with a real miss: an agent's conclusion — the whole reason to open
     * the detail — lands at almost exactly the moment the fan-out settles, which
     * is when a strip keyed strictly on "is it working" disappears. So the strip
     * outlives the work by a few minutes and the reader can still get at what the
     * agents concluded.
     */
    const val LINGER_MS: Long = 3 * 60 * 1000L

    /** Everything the strip needs, already decided. */
    data class Strip(
        val headline: String,
        val details: List<String>,
    )

    /**
     * Whether the strip should be on screen.
     *
     * @param lastWorkAtMs when work was last observed, or null if it never was.
     */
    fun visible(working: Boolean, bgWork: Boolean, lastWorkAtMs: Long?, nowMs: Long): Boolean {
        if (working || bgWork) return true
        val last = lastWorkAtMs ?: return false
        return nowMs - last < LINGER_MS
    }

    /**
     * Pane-derived rows are trusted ONLY while the session is actually working.
     *
     * The TUI keeps workflow and board rows in its persistent footer after the run
     * ends — truthful on a terminal, where they read as history, but mirrored into
     * a strip they claimed a workflow was still running long after it finished.
     * The hook state knows better. Background shells and agents are exempt because
     * their liveness is measured rather than read off a screen.
     */
    fun paneRows(statusLines: List<String>, working: Boolean): List<String> =
        if (working) statusLines else emptyList()

    /**
     * The strip's contents.
     *
     * Headline priority: the pane's own spinner (what Claude Code itself is
     * saying), then the transcript's unresolved tool, then the bare fact that
     * something is running. Detail rows put DURABLE things first — workflow
     * phases, background shells — and the transient per-tool line last, because it
     * turns over constantly and a row that appears and vanishes mid-turn made the
     * whole strip pump up and down.
     */
    fun strip(
        spinner: String?,
        statusLines: List<String>,
        transient: String?,
        activity: Activity?,
        tasks: List<BgTask>,
        bgAgents: Int,
        maxDetails: Int = 3,
        live: Boolean = true,
    ): Strip {
        val headline = spinner
            ?: activity?.let { a ->
                buildString {
                    append(a.tool ?: "working")
                    a.detail?.takeIf { it.isNotBlank() }?.let { append("  ").append(it) }
                    if (a.subagents > 0) {
                        append("  ·  ${a.subagents} subagent${if (a.subagents == 1) "" else "s"}")
                    }
                }
            }
            ?: when {
                tasks.isNotEmpty() || bgAgents > 0 -> "background work"
                // The LINGERING strip, still on screen only so the agents'
                // conclusions can be read. Saying "working" here is the one
                // thing it must not do — it would be the strip lying about a
                // session that has stopped.
                !live -> "just finished"
                else -> "working"
            }

        val details = buildList {
            addAll(statusLines)
            tasks.take(2).forEach { t ->
                add("⚙ ${t.command}" + if (t.forSeconds > 0) " · ${agoShort(t.forSeconds)}" else "")
            }
            // Only when no pane row already says it, or the same fan-out is
            // counted twice in three lines.
            if (bgAgents > 0 && statusLines.none { it.contains("agent") }) {
                add("$bgAgents background agent${if (bgAgents == 1) "" else "s"}")
            }
            transient?.let { add(it) }
        }.take(maxDetails)

        return Strip(headline, details)
    }

    /**
     * "2 of 6 agents done", in the TUI's own phrasing and with the TUI's own
     * denominator — see [TranscriptGroups.plannedAgents] for why the file count
     * cannot be it. Null while nothing is known yet, so a caller can say "Agents…"
     * rather than assert a count it does not have.
     */
    fun agentCount(agents: AgentsInfo?, statusLines: List<String>): String? {
        val list = agents?.agents ?: return null
        val planned = TranscriptGroups.plannedAgents(statusLines)
        if (list.isEmpty() && planned == null) return "No agents in this session recently"
        val done = list.count { !it.active }
        return "$done of ${maxOf(planned ?: list.size, list.size)} agents done"
    }

    /** "12s", "4m", "1h 12m" — elapsed for a background task, kept short. */
    fun agoShort(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    /**
     * "now", "3m", "2h" — how long ago an agent last wrote.
     *
     * Measured against the SERVER's clock, which [AgentsInfo] carries for exactly
     * this reason: the agents are files on huginn, and a client whose clock is a
     * minute out would otherwise report a live agent as stale, or a settled one as
     * writing right now.
     */
    fun sinceShort(atSec: Long, nowSec: Long): String {
        if (atSec <= 0 || nowSec <= 0) return ""
        val secs = (nowSec - atSec).coerceAtLeast(0)
        return when {
            secs < 60 -> "now"
            secs < 3600 -> "${secs / 60}m"
            secs < 86_400 -> "${secs / 3600}h"
            else -> "${secs / 86_400}d"
        }
    }

    /**
     * The boilerplate context header a fan-out prepends buries the actual ask, and
     * a two-line row has no space to spend on it.
     */
    fun taskLine(task: String?): String? =
        task?.removePrefix("CONTEXT:")?.trim()?.takeIf { it.isNotEmpty() }
}
