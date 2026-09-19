package com.silencelen.huginn.desktop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ArchivedSession
import com.silencelen.huginn.ui.ArchiveRules

/**
 * Empty, loading and nothing-selected — told apart.
 *
 * THE BUG THIS FIXES IS A SENTENCE. The list pane said "No chats yet." during the
 * first fetch and "No chats yet." when the daemon really had none, and on a cold
 * start those are five seconds of a client claiming an answer it does not have.
 * The store already knows the difference (`listsLoaded`); only the copy did not.
 *
 * A spinner is not the whole fix either. Two words centred in 300px of black reads
 * as a failure whichever word it is, so each of these carries ONE orienting
 * sentence: what this pane is for, or what to do next. Plain and calm — no
 * exclamation, no illustration, no call to action dressed as a button.
 */

/** A fetch that has not settled. Distinguishable from empty at a glance. */
@Composable
fun LoadingBlock(what: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.unit),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Loading $what…",
                style = DeskType.rowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.unit),
            )
        }
    }
}

/** A fetch that settled on nothing. */
@Composable
fun EmptyBlock(headline: String, sentence: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.tight),
    ) {
        Text(headline, style = DeskType.rowTitle, color = MaterialTheme.colorScheme.onSurface)
        Text(
            sentence,
            style = DeskType.rowMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------- what an empty pane may say
//
// ⚠ THE COPY DEPENDS ON A PANE THAT IS NOT THIS ONE. Every sentence below used
// to point at the list — "pick one on the left", "every tmux session on the host
// is on the left", "walk the list", "right-click to rename" — and since the notch
// arrived the list can be shut. Directions to a pane that is not there are worse
// than no directions at all: the reader looks left, finds a nav rail, and
// concludes the client is broken rather than that they closed something.
//
// So the choice is a function the render sites consult rather than a literal at
// each call. That is also the only way to assert it, and asserting it is the
// point: nothing crashes, nothing draws wrong, and a screenshot of the stale
// version looks entirely correct unless you already know the pane is shut.

/** The two halves of an empty detail pane that can go stale. */
data class EmptyPaneCopy(val sentence: String, val routes: List<Pair<String, String>>)

/** Chats, with none open. */
fun noChatOpenCopy(listCollapsed: Boolean): EmptyPaneCopy = if (listCollapsed) {
    EmptyPaneCopy(
        "The list is hidden — the notch on the seam brings it back. " +
            "Or start a new one from here: Ask reads and reasons; Act can change things on the host.",
        listOf(
            "Ctrl B" to "show the list",
            "Ctrl N" to "new Ask chat",
            "Ctrl Shift N" to "new Act chat",
            "Ctrl K" to "find one by name",
        ),
    )
} else {
    EmptyPaneCopy(
        "Pick one on the left, or start a new one. Ask reads and reasons; Act can change things on the host.",
        listOf(
            "Ctrl N" to "new Ask chat",
            "Ctrl Shift N" to "new Act chat",
            "Ctrl K" to "find one by name",
        ),
    )
}

/**
 * Sessions, with none open.
 *
 * The collapsed half drops right-click rather than rewording it: the menu hangs
 * off a LIST ROW, so with the list shut there is nothing on screen to open it on.
 * Alt+arrow survives because it really does still work — it walks the sessions
 * whether or not they are drawn — so it is renamed to say what it does rather
 * than where it does it.
 */
fun noSessionOpenCopy(listCollapsed: Boolean): EmptyPaneCopy = if (listCollapsed) {
    EmptyPaneCopy(
        "The list is hidden. Every tmux session on the host is still in it, " +
            "and the notch on the seam brings it back.",
        listOf(
            "Ctrl B" to "show the list",
            "Ctrl K" to "find one by name",
            "Alt ↑ / ↓" to "previous / next session",
        ),
    )
} else {
    EmptyPaneCopy(
        "Every tmux session on the host is on the left. Opening one shows its conversation and its live screen.",
        listOf(
            "Ctrl K" to "find one by name",
            "Alt ↑ / ↓" to "walk the list",
            "Right-click" to "rename, interrupt, end",
        ),
    )
}

// ------------------------------------------------- the session that just ended
//
// ⚠⚠ THE CONVERSATION SIMPLY VANISHED. A session that wraps up while somebody is
// reading it took the pane down to the generic first-run empty state — "No
// session open / Every tmux session on the host is on the left…" plus the
// keyboard hints — over a transcript that was being read a second earlier.
// Nothing said the session had ended. And a wrapped-up session is NOT archived
// (only `Archive…` writes a row), so afterwards the conversation was not
// reachable from the UI at all: it did not appear under Archived, and the
// session it belonged to no longer existed.
//
// A pane that was showing a conversation owes an account of where it went. What
// it can honestly offer depends on what the host kept, which is the whole of the
// rule below.

/**
 * What the pane says after the conversation in it ended.
 *
 * A headline of its own rather than an [EmptyPaneCopy], because this pane is not
 * empty — something happened in it, and the difference between "nothing is open"
 * and "the thing you were reading finished" is the entire point.
 *
 * @param resume the exact command that brings the conversation back, as the HOST
 *   built it. ⚠ NEVER assembled here: `ArchivedSession.resumeCommand` is the
 *   daemon's string, and a second implementation of it would eventually disagree
 *   about a directory with a space in it.
 * @param archiveId the archive row to open in this pane, when the transcript is
 *   still readable. Null when there is nothing to read.
 */
data class EndedSessionCopy(
    val headline: String,
    val sentence: String,
    val resume: String? = null,
    val archiveId: String? = null,
)

/**
 * How long after a session ends an archive row may still be ITS archive row.
 *
 * ⚠ A tmux NAME IS REUSED WITHIN HOURS on this host — the archive list is keyed
 * on the Claude session uuid for exactly that reason. So a row is only this
 * session's if it was archived around the time this session ended; without the
 * window, wrapping up `sql` would offer the resume command of last week's `sql`.
 * Generous in the other direction because a graceful archive returns while the
 * session is still winding down, so the row can be stamped slightly BEFORE the
 * client notices it is gone.
 */
const val ARCHIVE_MATCH_SLACK_MS: Long = 5 * 60 * 1000

/** This session's archive row, if the host wrote one. See [ARCHIVE_MATCH_SLACK_MS]. */
fun archiveFor(rows: List<ArchivedSession>, name: String, endedAtMs: Long): ArchivedSession? =
    rows.filter { it.tmuxName == name && it.archivedAt >= endedAtMs - ARCHIVE_MATCH_SLACK_MS }
        .maxByOrNull { it.archivedAt }

/**
 * The card, as a pure function of what the host kept.
 *
 * Three states, and each one is a different thing to offer:
 *
 *  * **Archived, transcript present.** The best case: the conversation is
 *    readable without bringing anything back, and the resume command is on file.
 *  * **Archived, transcript swept.** `ArchiveRules.startsFresh` — Claude Code
 *    deletes its own after `cleanupPeriodDays` and the host's copy can be gone
 *    too. Said out loud, because a resume that opens a BLANK conversation in the
 *    right directory looks exactly like a success.
 *  * **Not archived.** A wrap-up ends a session without writing a row, so there
 *    is no stored copy and no resume command. Offering either would be inventing
 *    one; what it CAN say is that archiving is the verb that would have kept it.
 */
fun sessionEndedCopy(name: String, title: String?, archived: ArchivedSession?): EndedSessionCopy {
    val label = title?.takeIf { it.isNotBlank() } ?: name
    val headline = "“$label” ended"
    if (archived == null) {
        return EndedSessionCopy(
            headline = headline,
            sentence = "The session finished and was not archived, so this host kept no copy of " +
                "the conversation. Archive… on a session is what stores one, along with the " +
                "command that brings it back.",
        )
    }
    val readable = ArchiveRules.canView(archived)
    return EndedSessionCopy(
        headline = headline,
        sentence = if (readable) {
            "It was archived, so the whole conversation is still here to read — " +
                "and the command that brings it back is below."
        } else {
            "It was archived, but neither this host nor Claude Code still has the " +
                "transcript, so resuming it would open an empty conversation in the " +
                "right folder rather than this one."
        },
        resume = archived.resumeCommand?.takeIf { it.isNotBlank() },
        archiveId = archived.id.takeIf { readable },
    )
}

/**
 * The detail pane after the session in it ended: a headline, a sentence, and the
 * one or two things there are to do about it.
 *
 * Laid out like [NothingOpen] because it occupies the same pane, and a card that
 * arrived in a different shape would read as a different screen rather than as
 * the same one answering a question.
 */
@Composable
fun SessionEndedCard(
    copy: EndedSessionCopy,
    onOpenArchive: (String) -> Unit,
    onCopyResume: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.tight),
        ) {
            Text(copy.headline, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Text(
                copy.sentence,
                style = DeskType.rowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.padding(top = Space.unit),
                horizontalArrangement = Arrangement.spacedBy(Space.tight),
            ) {
                copy.archiveId?.let { id ->
                    TextButton(onClick = { onOpenArchive(id) }) { Text("Read the transcript") }
                }
                copy.resume?.let { command ->
                    TextButton(onClick = { onCopyResume(command) }) { Text("Copy resume command") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * The detail pane with nothing open. Centred, because unlike the list pane there
 * is no content above it for the text to belong to.
 *
 * @param routes the keyboard ways in. On a desktop this is the honest answer to
 *   "what do I do here", and it is also where the shortcuts get learned — a
 *   cheatsheet behind F1 is a cheatsheet nobody opens.
 */
@Composable
fun NothingOpen(headline: String, sentence: String, routes: List<Pair<String, String>> = emptyList()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.tight),
        ) {
            Text(headline, style = MaterialTheme.typography.titleSmall)
            Text(
                sentence,
                style = DeskType.rowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (routes.isNotEmpty()) {
                Column(
                    Modifier.padding(top = Space.wide),
                    verticalArrangement = Arrangement.spacedBy(Space.hair),
                ) {
                    routes.forEach { (keys, what) ->
                        Row {
                            Text(
                                keys,
                                style = DeskType.rowMeta,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(104.dp),
                            )
                            Text(
                                what,
                                style = DeskType.rowMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
