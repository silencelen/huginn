package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.TranscriptEvent

/**
 * AN ARCHIVED CONVERSATION, READ ONLY — one composable, both shells.
 *
 * ⚠⚠ THERE IS NOTHING TO SEND TO, AND THAT IS THE WHOLE DESIGN. An archive is a
 * conversation whose tmux session is GONE: the daemon reads it from a copy on
 * disk (`GET /v1/archive/:id/transcript`) with no session gate at all, because
 * there is no session to gate on. So this draws the rows and nothing else — no
 * composer, no send, no queue line, no keys, no Screen tab, no menu of verbs. A
 * control here would either do nothing or type at whatever tmux has since given
 * that name to, and the second is much worse than the first.
 *
 * Reviving is the way back in, and it lives on the ARCHIVED ROW where the rest
 * of that decision already is ([ArchivedSessionsSection]) — beside the warning
 * about a transcript Claude Code may have swept. A Revive button in here as well
 * would be a second opinion about a thing with real consequences (a second
 * Claude appending to one jsonl) inside a view whose whole job is to be inert.
 *
 * WHY IN `:ui` RATHER THAN TWICE. `docs/ADDING-A-FEATURE.md`'s rule: the rows are
 * already one composable ([TranscriptRowItem]) and all either shell adds is a
 * frame. The phone opens it as a child destination, the desktop in its detail
 * pane, and neither owns a pixel of what is inside.
 */
@Composable
fun ArchivedTranscriptView(
    events: List<TranscriptEvent>,
    modifier: Modifier = Modifier,
    /** The conversation's own title, drawn above it. Null draws no header. */
    title: String? = null,
    /** Said when the kept copy is a TAIL — see [com.silencelen.huginn.data.TranscriptPage.transcriptTruncated]. */
    truncated: Boolean = false,
    /** Copy one row's text. The only verb in here, and it changes nothing. */
    onCopy: (String) -> Unit = {},
    /** Shown INSTEAD of the rows: the transcript is gone, or the read failed. */
    note: String? = null,
) {
    val rows = remember(events) { TranscriptGroups.group(events) }
    val rowKeys = remember(rows) { TranscriptGroups.keys(rows) }
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize().testTag(ARCHIVE_TRANSCRIPT_TAG)) {
        if (!title.isNullOrBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        // ⚠ SAID ONCE, AT THE TOP (P-34/D-29). Everything about this view is a
        // conversation — the same rows, the same colours — and the ONLY thing
        // distinguishing it from a live one was the absence of a composer, which
        // is an absence, and absences are not read. The header says what this is
        // before the first row does.
        Text(
            READ_ONLY_BANNER,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = ARCHIVE_GUTTER, vertical = 6.dp),
        )
        if (note != null) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // ⚠⚠ P-10. THE ROWS RAN EDGE TO EDGE. Every assistant line was laid
            // out from x=0 to x=1080 on the owner's Fold — flush against both
            // screen edges, with words clipped off the right ("…run it detached"
            // lost its tail) and user bubbles overhanging — while the LIVE
            // conversation insets to 37…1043. Same gutters as the live one, from
            // one constant, because two numbers is how they drift apart again.
            contentPadding = PaddingValues(horizontal = ARCHIVE_GUTTER, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (truncated) {
                // ⚠ SAID AT THE TOP, WHERE A READER SCROLLING BACK ARRIVES. The
                // kept copy is a tail of an over-cap conversation, so its first
                // record is not the beginning — and a reader who reached it with
                // no word would conclude that it was.
                item(key = "archive-truncated") {
                    Text(
                        TRUNCATED_NOTE,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
            if (rows.isEmpty()) {
                item(key = "archive-empty") {
                    Text(
                        EMPTY_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
            }
            items(count = rows.size, key = { rowKeys.getOrNull(it) ?: "row-$it" }) { i ->
                TranscriptRowItem(rows[i], onCopy)
            }
        }
    }
}

/** The frame's test tag, so a source gate can name the destination it guards. */
const val ARCHIVE_TRANSCRIPT_TAG: String = "archive.transcript"

/**
 * The horizontal inset the LIVE conversation uses, so the archive reads as the
 * same surface. One constant on purpose: the archived view had none at all
 * (P-10) precisely because the number lived in the live screens and nowhere
 * else.
 */
val ARCHIVE_GUTTER = 14.dp

/**
 * What this view IS, said before the first row (D-29).
 *
 * "Read-only" rather than "you cannot type here": the reason there is nothing to
 * send to is that the tmux session is gone, and a reader who knows that also
 * knows why Revive exists on the row they came from.
 */
const val READ_ONLY_BANNER: String = "Archived · read-only"

/**
 * ⚠ BOTH COPIES HAVE TO BE GONE FOR THIS TO BE TRUE, and which one went is not
 * useful to a reader. What matters is the consequence: a revive now opens a
 * blank conversation in the right directory and looks exactly like a success.
 */
const val ARCHIVE_TRANSCRIPT_GONE: String =
    "The conversation is gone — neither huginn's copy nor Claude Code's is still on disk."

private const val TRUNCATED_NOTE: String =
    "This is the tail of a longer conversation — huginn kept what fit."

private const val EMPTY_NOTE: String = "This archive has no conversation in it."
