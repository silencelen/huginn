package com.silencelen.huginn.ui

import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage

/**
 * How many events a live view keeps. A session left open on a busy day would
 * otherwise grow the list without limit and copy it whole on every poll.
 */
const val MAX_TRANSCRIPT_EVENTS: Int = 600

/**
 * Appends an incremental page to the window already on screen, renumbering it so
 * `seq` is unique across the result.
 *
 * The daemon numbers each tail read from 0, so concatenated pages arrive with
 * REPEATED seqs — and seq is the identity the UI keys row state on. Two rows could
 * claim `seq = 3`, so opening one tool card opened the wrong one, and a row's
 * expansion followed whichever event later inherited its number. Renumbering makes
 * the identity mean what the callers assume it means; nothing client-side reads seq
 * as the server's own numbering.
 *
 * Renumbering is relative to the last KEPT seq rather than to the array's length,
 * so seqs keep climbing across trims and stay unique for as long as the view lives.
 *
 * Moved here from the Android view model in phase 3c: the desktop needs the same
 * identity rule, and two implementations of "which row is this" is exactly the
 * divergence the migration exists to stop.
 */
fun mergeTranscript(
    kept: List<TranscriptEvent>,
    incoming: List<TranscriptEvent>,
    cap: Int = MAX_TRANSCRIPT_EVENTS,
): List<TranscriptEvent> {
    if (kept.isEmpty()) return incoming.takeLast(cap)
    var next = (kept.lastOrNull()?.seq ?: -1) + 1
    val renumbered = incoming.map { it.copy(seq = next++) }
    return (kept + renumbered).takeLast(cap)
}

/**
 * Clears the `queued` badge from messages the daemon has since delivered.
 *
 * A message sent while Claude is mid-turn is written to the transcript only as
 * queue-operation records: an `enqueue` when it is typed, a `remove` when it is
 * delivered. Those two land in different tail windows on nearly every
 * send-while-busy, so by the time the delivery is read the bubble is already on
 * screen wearing a badge that is no longer true.
 *
 * Matching on text is what the daemon's own queue does — it is the only identity
 * these records carry — so a message sent twice while busy is un-badged
 * oldest-first, one per delivery, exactly as the daemon dequeues them.
 */
internal fun clearDelivered(
    events: List<TranscriptEvent>,
    delivered: List<String>,
): List<TranscriptEvent> {
    if (delivered.isEmpty()) return events
    val remaining = delivered.toMutableList()
    return events.map { ev ->
        if (ev.queued && remaining.remove(ev.text)) ev.copy(queued = false) else ev
    }
}

/**
 * Folds a tail page into the page already on screen.
 *
 * A tail read only reports session-level fields whose records happen to fall
 * inside it, so every nullable one has to be CARRIED FORWARD or it reverts to null
 * seconds after the view opens — a dropped `effort` here is exactly why the phone's
 * effort control kept falling back to a placeholder.
 *
 * Three classes of field, and the exceptions are the interesting part:
 *
 *  * carried forward, fresher-non-null wins: the identity and setup fields below;
 *  * taken fresh every time (via `page.copy`): `running`, `pending`, `activity`,
 *    `tasks`, `bgAgents`, `nextOffset`. `activity` is computed by the server on
 *    every response, so null means "nothing in flight" — carrying it forward would
 *    freeze a finished tool row on screen forever;
 *  * sticky from the FIRST page: [TranscriptPage.truncated], because a tail read
 *    says nothing about the head that was dropped.
 */
fun mergeTranscriptPage(
    current: TranscriptPage?,
    page: TranscriptPage,
    cap: Int = MAX_TRANSCRIPT_EVENTS,
): TranscriptPage {
    if (current == null) return page
    return page.copy(
        events = mergeTranscript(clearDelivered(current.events, page.deliveredQueued), page.events, cap),
        title = page.title ?: current.title,
        model = page.model ?: current.model,
        modelDisplay = page.modelDisplay ?: current.modelDisplay,
        effort = page.effort ?: current.effort,
        gitBranch = page.gitBranch ?: current.gitBranch,
        permissionMode = page.permissionMode ?: current.permissionMode,
        cwd = page.cwd ?: current.cwd,
        state = page.state ?: current.state,
        mode = page.mode ?: current.mode,
        claudeSessionId = page.claudeSessionId ?: current.claudeSessionId,
        lastActivityTs = page.lastActivityTs ?: current.lastActivityTs,
        truncated = current.truncated,
    )
}
