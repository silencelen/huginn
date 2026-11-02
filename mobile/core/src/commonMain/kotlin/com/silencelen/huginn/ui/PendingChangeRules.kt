package com.silencelen.huginn.ui

import com.silencelen.huginn.data.PendingAdded
import com.silencelen.huginn.data.ProjectMemberState
import com.silencelen.huginn.data.ProjectPendingChange

/**
 * What the pending-change card SAYS, decided here so both shells say it the same
 * way and a test can read it without a window (appd 3.17: `pendingChange` on a
 * project row).
 *
 * The card is a diff a person approves, so every line names a role and nothing
 * paraphrases the lead — and NOTHING IS CLIPPED (security scan 2026-11-02, F1).
 * Approve makes the daemon type each re-roled member's new task, and each added
 * member's whole first prompt, into that member's composer. The lead wrote those
 * words, and a prompt-injected lead writes anything; the first line can look
 * routine while the rest tells a member to edit CLAUDE.md or run commands. So an
 * added role shows its FULL first prompt with its mode, working directory and
 * model, and a re-roled role its FULL new task, through [PromptDisplay.visible]
 * — the same display copy the session-request card uses, with control and bidi
 * characters spelled out and whitespace runs collapsed — plus the size of the
 * text as sent. A removed role shows its name: nothing is typed into it.
 *
 * [approvable] is the other half of the rule: a change whose text the card cannot
 * show (a daemon that sent none) cannot be approved from it.
 */
object PendingChangeRules {
    const val TITLE: String = "Roster change"
    const val APPROVE: String = "Approve"
    const val DECLINE: String = "Decline"

    /** What Approve does, said before it is pressed: the removed roles are asked, never killed. */
    const val APPROVE_NOTE: String =
        "Approve spawns the added roles with the prompts shown, asks the removed ones to wrap up and types the re-roled ones the task shown. The text is the lead's."

    /** Why Approve is disabled when the card has no text to show for a role. */
    const val UNSHOWN_TEXT: String = "This change carries a task the card cannot show, so it cannot be approved here."

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

    /**
     * One role on the card.
     *
     * @param role  the role name, the row's label
     * @param meta  "mode act · in /srv/x · model sonnet · effort high" for an added role, else null
     * @param text  the words the daemon will type into this role on Approve, AS SENT
     *              (the shells draw [PromptDisplay.visible] of it); null for a removed role
     * @param textLabel  what [text] is: "First prompt" or "New task"
     */
    data class Row(val role: String, val meta: String? = null, val text: String? = null, val textLabel: String? = null) {
        /** The display copy of [text]: hidden characters spelled, whitespace runs collapsed. Never clipped. */
        val visibleText: String? get() = text?.let(PromptDisplay::visible)

        /** `3 lines · 212 characters`, of [text] as sent. */
        val countsLine: String? get() = text?.let(PromptDisplay::countsLine)
    }

    /** One section of the card: its heading and its rows. Empty sections are left out. */
    data class Section(val heading: String, val rows: List<Row>)

    const val ADDED: String = "Added"
    const val REMOVED: String = "Removed (asked to wrap up)"
    const val REROLED: String = "Re-roled"
    const val FIRST_PROMPT: String = "First prompt"
    const val NEW_TASK: String = "New task"

    fun sections(change: ProjectPendingChange): List<Section> {
        val out = mutableListOf<Section>()
        if (change.added.isNotEmpty()) {
            out += Section(ADDED, change.added.map { a -> Row(a.role, meta = metaLine(a), text = a.firstPrompt, textLabel = FIRST_PROMPT) })
        }
        if (change.removed.isNotEmpty()) out += Section(REMOVED, change.removed.map { Row(it.role) })
        if (change.changed.isNotEmpty()) {
            out += Section(REROLED, change.changed.map { c -> Row(c.role, text = c.firstPrompt, textLabel = NEW_TASK) })
        }
        return out
    }

    /**
     * An added role's mode, working directory and model — every one said, with
     * the server's default named when the lead left it to the server, so a
     * member that will run unattended in `auto` mode in a directory of the lead's
     * choosing is approved knowingly.
     */
    fun metaLine(a: PendingAdded): String {
        val parts = mutableListOf<String>()
        parts += "mode " + (a.mode?.trim()?.takeIf { it.isNotEmpty() } ?: "default")
        parts += "in " + (a.cwd?.trim()?.takeIf { it.isNotEmpty() } ?: "the project's directory")
        parts += "model " + (a.model?.trim()?.takeIf { it.isNotEmpty() } ?: "default")
        a.effort?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "effort $it" }
        return parts.joinToString(" · ")
    }

    /**
     * Every row of the card as flat text, headings included, for the shells' tests
     * and copy: the role, its meta line, then the FULL display copy of its text,
     * every line of it, and the counts. Nothing here is clipped.
     */
    fun lines(change: ProjectPendingChange): List<String> =
        sections(change).flatMap { s ->
            listOf(s.heading) + s.rows.flatMap { r ->
                buildList {
                    add("  ${r.role}")
                    r.meta?.let { add("  ($it)") }
                    r.visibleText?.let { v ->
                        add("  ${r.textLabel ?: FIRST_PROMPT} · ${r.countsLine}")
                        v.lines().forEach { add("    $it") }
                    }
                }
            }
        }

    /**
     * Why the change cannot be approved from a card, or null when it can.
     *
     * Approve types text into every added and re-roled member; a card that has no
     * text to show for one of them is a surface that cannot show what it would
     * approve, and the brief's rule is that no such surface offers Approve.
     */
    fun approvable(change: ProjectPendingChange): String? {
        val blank = { s: String? -> s == null || s.isBlank() }
        if (change.added.any { blank(it.firstPrompt) } || change.changed.any { blank(it.firstPrompt) }) return UNSHOWN_TEXT
        return null
    }

    /** A member asked to wrap up (`endedAt` set, session still present) is "winding down". */
    fun windingDown(member: ProjectMemberState): Boolean = member.endedAt != null && (member.present || member.alive)

    /** Said on the card, over the text: whose words these are. */
    const val WHOSE_WORDS: String = "Written by the lead, not by you. Approve types it into the member as the lead's task."
}
