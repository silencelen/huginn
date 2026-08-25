package com.silencelen.huginn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.Round

/**
 * The Rounds surface: what this host does on a schedule, and what it last found.
 *
 * Read top-down, the row answers three questions in the order a person asks
 * them — what is this, when does it go out again, and what did it say last time.
 * The report is the point of the feature, so the headline is a full-width line of
 * its own rather than a truncated trailing fragment.
 *
 * NO accent rail down the side of the card. State is carried by one small dot
 * beside the title, in the app's own palette — a mark you learn once and then
 * read without looking, rather than a stripe that shouts on every row equally.
 */
@Composable
fun RoundsSection(
    rounds: List<Round>,
    nowMs: Long,
    onOpenRound: (Round) -> Unit,
    onRunNow: (Round) -> Unit,
    onSetEnabled: (Round, Boolean) -> Unit,
    /** Null hides the control, for a surface that cannot edit. */
    onEdit: ((Round) -> Unit)? = null,
    modifier: Modifier = Modifier,
    header: String? = "ROUNDS",
) {
    if (rounds.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp),
            )
        }
        rounds.forEach { round ->
            RoundRow(
                round = round,
                nowMs = nowMs,
                onOpen = { onOpenRound(round) },
                onRunNow = { onRunNow(round) },
                onSetEnabled = { onSetEnabled(round, it) },
                onEdit = onEdit?.let { f -> { f(round) } },
            )
        }
    }
}

@Composable
private fun RoundRow(
    round: Round,
    nowMs: Long,
    onOpen: () -> Unit,
    onRunNow: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onEdit: (() -> Unit)?,
) {
    val status = roundStatusOf(round.lastRun?.status)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Nudged to the TITLE's line rather than the centre of the
                // title+cadence column: centred on the pair it reads as floating
                // between them, belonging to neither.
                StatusDot(status, Modifier.align(Alignment.Top).padding(top = 7.dp))
                Column(
                    Modifier
                        .padding(start = 10.dp)
                        .weight(1f),
                ) {
                    Text(
                        round.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // A paused Round is still listed — dropping it would read
                        // as deleted — but it should not look live.
                        color = if (round.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        roundSubtitle(round, nowMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                roundLastLine(round),
                style = MaterialTheme.typography.bodyMedium,
                color = if (round.lastRun == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 8.dp),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    listOfNotNull(
                        roundStatusLabel(status).takeIf { round.lastRun != null },
                        agoWords(round.lastRun?.at, nowMs).takeIf { it.isNotBlank() },
                        itemCountWords(round.lastRun),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 2.dp),
                )
                // Both controls as words, at the same weight, in the same place.
                // This was a filled Switch riding the title row, which on a dark
                // list was the loudest thing on screen — louder than the status
                // mark and the report it is meant to be read alongside — for
                // something you touch about twice a year. Pausing is not a mode
                // you set, it is a thing you do, so it reads like the other thing
                // you can do here.
                TextButton(onClick = { onSetEnabled(!round.enabled) }) {
                    Text(if (round.enabled) "Pause" else "Resume")
                }
                TextButton(onClick = onRunNow, enabled = !round.running && round.enabled) {
                    Text(if (round.running) "Running" else "Run now")
                }
                // A word, not a pencil. The row already carries a status mark and
                // a verdict; an icon here would be a second thing to decode in a
                // place where the text is doing the work.
                onEdit?.let { TextButton(onClick = it) { Text("Edit") } }
            }
        }
    }
}

/**
 * One dot, in the app's own palette rather than a traffic-light set imported for
 * the occasion — semantic colour that still belongs to this theme in both light
 * and dark, because every value comes from the scheme rather than a literal.
 */
@Composable
private fun StatusDot(status: RoundStatus, modifier: Modifier = Modifier) {
    val color: Color = when (status) {
        RoundStatus.ACTION -> MaterialTheme.colorScheme.error
        RoundStatus.ATTENTION -> MaterialTheme.colorScheme.primary
        RoundStatus.OK -> MaterialTheme.colorScheme.onSurfaceVariant
        RoundStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
        RoundStatus.NEVER_RUN -> MaterialTheme.colorScheme.outlineVariant
    }
    // The quiet states are drawn SMALLER as well as duller, so a screen of
    // healthy Rounds recedes and the one that wants something stands out
    // without any of them being loud.
    val size = if (status == RoundStatus.OK || status == RoundStatus.NEVER_RUN) 6.dp else 8.dp
    Surface(color = color, shape = CircleShape, modifier = modifier.size(size)) {}
}
