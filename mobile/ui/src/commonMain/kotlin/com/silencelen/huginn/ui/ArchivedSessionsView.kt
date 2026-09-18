package com.silencelen.huginn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ArchivedSession

/**
 * The sessions that were ended on purpose, and the way back into each of them.
 *
 * ONE composable for both shells, which is new for a session row: the live row
 * is implemented three times (phone, desktop, widget) with three StateDots and
 * three relTimes, and none of that was ever worth copying. This one is written
 * once because it is the same object under a thumb and under a mouse — a
 * finished conversation, a directory, and a command.
 *
 * The shells own only WHERE it goes: the phone puts it at the bottom of the
 * Sessions list, collapsed, and the desktop puts it under the session rows in
 * the list pane. Neither is a destination — an archive is a footnote to the
 * sessions list, not a place to go.
 */
@Composable
fun ArchivedSessionsSection(
    rows: List<ArchivedSession>,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRevive: (ArchivedSession) -> Unit,
    onCopyResume: (ArchivedSession) -> Unit,
    onDelete: (ArchivedSession) -> Unit,
    /** Open the live session a revived row is now running as. */
    onOpenLive: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ordered = remember(rows) { ArchiveRules.ordered(rows) }
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A triangle rather than an icon import: the section is a disclosure,
            // and both shells already read this glyph the same way.
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Archived",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${ordered.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!expanded) return@Column

        if (ordered.isEmpty()) {
            // Said, not left blank. "Archived (0)" that opens onto nothing reads
            // as a feature that is broken rather than one nobody has used.
            Text(
                ARCHIVE_EMPTY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
            return@Column
        }
        ordered.forEach { row ->
            ArchivedSessionRow(
                row = row,
                nowMs = nowMs,
                onRevive = { onRevive(row) },
                onCopyResume = { onCopyResume(row) },
                onDelete = { onDelete(row) },
                onOpenLive = onOpenLive,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ArchivedSessionRow(
    row: ArchivedSession,
    nowMs: Long,
    onRevive: () -> Unit,
    onCopyResume: () -> Unit,
    onDelete: () -> Unit,
    onOpenLive: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val live = ArchiveRules.liveName(row)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ArchiveRules.label(row),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                archivedSubtitle(row, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // ⚠ THE WARNING, ABOVE THE BUTTON THAT WOULD DISAPPOINT. Claude Code
            // deletes its own transcripts after cleanupPeriodDays; past that a
            // revive opens a blank conversation in the right directory and looks
            // exactly like a success.
            archivedWarning(row)?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    // Three, not two: at the desktop's 325dp starting width the
                    // sentence that matters most here was cut mid-clause —
                    // "…so a revive would". A warning that stops before its own
                    // consequence is a warning that has not been given.
                    maxLines = 3,
                )
            }
            row.lastMessage?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            // ⚠ COMPACT AND SINGLE-LINE, BOTH OF THEM. A TextButton's default
            // padding is 24dp a side, and the desktop's list pane starts at
            // 325dp — "Copy resume command" wrapped to FOUR lines beside
            // "Open opensession2" and made one row taller than the three above
            // it. Seen in a screenshot, which is the only place a layout like
            // this is ever wrong.
            Row(horizontalArrangement = Arrangement.Start) {
                if (live != null) {
                    // Already back. A Revive here would start a SECOND Claude on
                    // one transcript, so the row offers the session instead.
                    RowAction("Open $live", onOpenLive.let { open -> { open(live) } })
                } else {
                    RowAction("Revive", onRevive)
                }
                if (row.resumeCommand != null) RowAction("Copy resume command", onCopyResume)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Archived session actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/**
 * One of a row's inline verbs: small type, tight padding, and never more than
 * one line. See the call site for why all three matter.
 */
@Composable
private fun RowAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.heightIn(min = 30.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What a section with nothing in it says, so an empty list is not a broken one. */
const val ARCHIVE_EMPTY: String =
    "Nothing archived yet. Archiving a session ends it for good and keeps the way " +
        "back — the directory it ran in, a copy of the conversation, and the exact " +
        "claude --resume command."

/**
 * The one line under an archived row: when, where, and on what.
 *
 * Deliberately not the resume command — that is behind a button, because it is
 * 70 characters of uuid nobody reads and it would push the row to three lines
 * for text whose only use is being copied.
 */
fun archivedSubtitle(row: ArchivedSession, nowMs: Long): String {
    val bits = mutableListOf<String>()
    agoWords(row.archivedAt, nowMs).takeIf { it.isNotBlank() }?.let { bits += "archived $it" }
    // The tmux name, only when the title is something else — two identical
    // strings on two lines is the row wasting half its height, the rule the
    // desktop session row already follows.
    row.tmuxName?.takeIf { it.isNotBlank() && it != row.title }?.let { bits += it }
    row.cwd?.takeIf { it.isNotBlank() }?.let { bits += it }
    return bits.joinToString(" · ")
}

/**
 * What is wrong with this row, or null.
 *
 * Only two things ever are, and they are opposites: there is nothing left to
 * bring back, or it is already back. Both have to be said BEFORE somebody
 * presses something — the first because the failure is silent, the second
 * because the consequence is two Claudes on one transcript.
 */
fun archivedWarning(row: ArchivedSession): String? = when {
    row.live -> null    // said by the button itself ("Open <name>"), not twice
    ArchiveRules.startsFresh(row) ->
        "The conversation is gone — Claude Code clears old transcripts, so a revive would start fresh here."
    row.transcriptTruncated ->
        "Only the end of this conversation was kept; a revive resumes from there."
    else -> null
}
