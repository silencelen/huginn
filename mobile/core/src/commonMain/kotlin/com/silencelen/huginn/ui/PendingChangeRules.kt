package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ProjectMemberState
import com.silencelen.huginn.data.ProjectPendingChange

/**
 * What the pending-change card SAYS, decided here so both shells say it the same
 * way and a test can read it without a window (appd 3.17: `pendingChange` on a
 * project row).
 *
 * The card is a diff a person approves, so every line names a role and nothing
 * paraphrases the lead: an added role shows the FIRST LINE of its first prompt
 * (the whole prompt is a paragraph or more, and twelve of them would stop the
 * card being a card), a removed role shows its name, a re-roled one its name.
 */
object PendingChangeRules {
    const val TITLE: String = "Roster change"
    const val APPROVE: String = "Approve"
    const val DECLINE: String = "Decline"

    /** What Approve does, said before it is pressed: the removed roles are asked, never killed. */
    const val APPROVE_NOTE: String =
        "Approve spawns the added roles, asks the removed ones to wrap up and tells the re-roled ones their new task."

    /** The headline when the lead wrote no summary. */
    fun headline(change: ProjectPendingChange): String =
        change.summary?.trim()?.takeIf { it.isNotEmpty() } ?: "The lead proposes a change to the roster."

    /** "2 added · 1 removed · 1 re-roled", the parts that are non-zero. */
    fun countWords(change: ProjectPendingChange): String {
        val bits = mutableListOf<String>()
        change.added.size.takeIf { it > 0 }?.let { bits += "$it added" }
        change.removed.size.takeIf { it > 0 }?.let { bits += "$it removed" }
        change.changed.size.takeIf { it > 0 }?.let { bits += "$it re-roled" }
        return if (bits.isEmpty()) "nothing changes" else bits.joinToString(" · ")
    }

    /** One section of the card: its heading and its rows. Empty sections are left out. */
    data class Section(val heading: String, val rows: List<String>)

    fun sections(change: ProjectPendingChange): List<Section> {
        val out = mutableListOf<Section>()
        if (change.added.isNotEmpty()) {
            out += Section(
                "Added",
                change.added.map { a ->
                    val first = firstLine(a.firstPrompt)
                    val extras = listOfNotNull(a.model, a.effort, a.mode, a.cwd).joinToString(" · ")
                    buildString {
                        append(a.role)
                        if (first.isNotEmpty()) append("  —  ").append(first)
                        if (extras.isNotEmpty()) append("  (").append(extras).append(")")
                    }
                },
            )
        }
        if (change.removed.isNotEmpty()) out += Section("Removed (asked to wrap up)", change.removed.map { it.role })
        if (change.changed.isNotEmpty()) out += Section("Re-roled", change.changed.map { it.role })
        return out
    }

    /** Every row of the card as flat text, headings included, for the shells' tests and copy. */
    fun lines(change: ProjectPendingChange): List<String> =
        sections(change).flatMap { s -> listOf(s.heading) + s.rows.map { "  $it" } }

    /** The first non-empty line of a prompt, clipped so it stays one row. */
    fun firstLine(prompt: String?, max: Int = 120): String {
        val line = prompt.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return if (line.length <= max) line else line.take(max - 1) + "…"
    }

    /** A member asked to wrap up (`endedAt` set, session still present) is "winding down". */
    fun windingDown(member: ProjectMemberState): Boolean = member.endedAt != null && (member.present || member.alive)
}
