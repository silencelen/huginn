package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ArchivedSession

/**
 * Which archived sessions a list shows, in what order, and what may be done with
 * each one.
 *
 * In `:core` rather than in the section that draws it, for the reason
 * `docs/ADDING-A-FEATURE.md` gives: both shells list these rows, and a bucketing
 * rule written twice is a rule the two apps will eventually disagree about. The
 * nearest precedent is [ScratchpadRules.ordered] — and the interesting thing
 * about this one is that it takes the OPPOSITE decision, deliberately.
 */
object ArchiveRules {

    /**
     * Newest archive first, id breaking the tie so the order is total.
     *
     * ⚠ THE OPPOSITE RULE TO THE PAGE PICKER, and on purpose. A scratchpad list
     * is a PLACE — it must hold still under the finger, so it sorts by name and
     * recency lives in the row. Nobody points at an archived session from
     * memory: the question this list answers is "what did I just put away", and
     * the row most likely to be wanted back is the one archived last.
     *
     * Applied client-side even though the daemon already sends this order,
     * because a list that is only correct when the server happens to be right is
     * a list with no rule at all — and this one is asserted against the same
     * literal order lib/archive.js sorts by.
     */
    fun ordered(rows: List<ArchivedSession>): List<ArchivedSession> =
        rows.sortedWith(compareByDescending<ArchivedSession> { it.archivedAt }.thenBy { it.id })

    /**
     * Whether this row may be brought back.
     *
     * False for a row that is ALREADY BACK — a revive of one of those would put a
     * second Claude on one transcript, and two processes appending to one jsonl
     * is how a conversation becomes unreadable to both of them. The host refuses
     * it too; this is what stops the button being offered in the first place,
     * which is the difference between a control and a trap.
     */
    fun canRevive(row: ArchivedSession): Boolean = !row.live

    /**
     * Whether a revive would come back with no memory.
     *
     * ⚠ THE ONE THING A ROW MUST NOT BE QUIET ABOUT. Claude Code deletes its own
     * transcripts after `cleanupPeriodDays`, the host keeps a copy against
     * exactly that, and when neither survives `claude --resume <uuid>` opens a
     * BLANK conversation in the right directory and reports success. A row that
     * looked identical to every other one right up to the moment it disappointed
     * is the worst outcome this feature has.
     */
    fun startsFresh(row: ArchivedSession): Boolean = !row.transcriptPresent

    /** The name this row is running under now, for a row that is back. */
    fun liveName(row: ArchivedSession): String? =
        if (!row.live) null else (row.revivedAs ?: row.tmuxName)

    /**
     * What the row leads with: Claude Code's own title, falling back to the tmux
     * name and then to a short form of the id.
     *
     * The id is a last resort rather than a blank: a row with no title and no
     * name still has to be distinguishable from the row above it, and "" is not.
     */
    fun label(row: ArchivedSession): String =
        row.title?.takeIf { it.isNotBlank() }
            ?: row.tmuxName?.takeIf { it.isNotBlank() }
            ?: row.id.take(8)
}
