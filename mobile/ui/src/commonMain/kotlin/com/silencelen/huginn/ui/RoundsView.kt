package com.silencelen.huginn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    /** "I have read this and dealt with it." Null hides it, same rule as [onEdit]. */
    onAcknowledge: ((Round, Boolean) -> Unit)? = null,
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
                onAcknowledge = onAcknowledge?.let { f -> { ack: Boolean -> f(round, ack) } },
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
    onAcknowledge: ((Boolean) -> Unit)? = null,
) {
    val status = roundStatusOf(round.lastRun?.status)
    val acked = isAcknowledged(round.lastRun)
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
                StatusDot(status, acked, Modifier.align(Alignment.Top).padding(top = 7.dp))
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
                // Beside the mark it clears, rather than down among Pause and Run
                // now: those act on the SCHEDULE, this acts on the report, and
                // the dot on this line is the thing it turns off.
                //
                // Appears only when there is something to answer — never on a
                // clean run — so it is not a fourth permanent control, and it
                // shows up at the moment it means something.
                if (onAcknowledge != null && (acked || canAcknowledge(round))) {
                    TextButton(onClick = { onAcknowledge(!acked) }) {
                        Text(if (acked) "Undo" else "Mark done")
                    }
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

            // ⚠ THE VERDICT IS NOT METADATA. "Needs you" sat in the same
            // muted grey as "4 days ago · 8 items" while Pause / Run now /
            // Edit took the accent beside it — the controls louder than the
            // thing they are controls for. The word takes the mark's own
            // colour, from the mark's own vocabulary; what follows it is the
            // metadata it was wrongly wearing. One Text, so the line still
            // reads as one line.
            RoundVerdictRow(
                buildAnnotatedString {
                    val label = roundStatusLabel(status, acked).takeIf { round.lastRun != null }
                    val rest = listOfNotNull(
                        agoWords(round.lastRun?.at, nowMs).takeIf { it.isNotBlank() },
                        itemCountWords(round.lastRun),
                    )
                    if (label != null) {
                        withStyle(
                            SpanStyle(
                                color = statusColor(status, acked),
                                fontWeight = FontWeight.Medium,
                            ),
                        ) { append(label) }
                        if (rest.isNotEmpty()) append(" · ")
                    }
                    append(rest.joinToString(" · "))
                },
            ) {
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
private fun StatusDot(status: RoundStatus, acknowledged: Boolean = false, modifier: Modifier = Modifier) {
    // A report that has been dealt with draws like a clean one. The verdict is
    // not rewritten anywhere — it is still what the row says it was — but the
    // MARK is the thing that pulls the eye across a list, and leaving it lit for
    // something already handled is how a screen of Rounds stops being scannable.
    val effective = if (acknowledged) RoundStatus.OK else status
    // The quiet states are drawn SMALLER as well as duller, so a screen of
    // healthy Rounds recedes and the one that wants something stands out
    // without any of them being loud.
    val size = if (effective == RoundStatus.OK || effective == RoundStatus.NEVER_RUN) 6.dp else 8.dp
    Surface(color = statusColor(status, acknowledged), shape = CircleShape, modifier = modifier.size(size)) {}
}

/**
 * The colour of a Round's state — for the dot AND for the word, from one
 * vocabulary in `:core` ([roundStatusColorKey]).
 *
 * Two mappings for one fact is how a row came to carry a red dot and a grey
 * verdict, which is a row telling the reader two different things about the
 * same run.
 */
@Composable
private fun statusColor(status: RoundStatus, acknowledged: Boolean): Color =
    when (roundStatusColorKey(status, acknowledged)) {
        "error" -> MaterialTheme.colorScheme.error
        "primary" -> MaterialTheme.colorScheme.primary
        "outline" -> MaterialTheme.colorScheme.outline
        "outlineVariant" -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * A Round's verdict line and its controls: side by side when both fit, the
 * verdict on a line of its own above them when they do not.
 *
 * ⚠ 2026-10-02, on the Fold at font scale 1.3 and 2.0: this was one Row with the
 * verdict as a weighted single-line Text. Weighted children are measured LAST,
 * so Pause / Run now / Edit (whose labels grow with the font) took their width
 * first and the verdict got the rest: 456 px at 1.0, 370 at 1.3, 210 at 2.0,
 * where "Worth a look · 13h ago · 1 item" read "Worth a…" and the age and item
 * count were simply gone. The verdict is the point of the row; the controls give
 * way to it, not the other way round. At normal size nothing moves.
 */
@Composable
internal fun RoundVerdictRow(
    verdict: AnnotatedString,
    onVerdictLayout: (TextLayoutResult) -> Unit = {},
    controls: @Composable RowScope.() -> Unit,
) {
    Layout(
        content = {
            Text(
                verdict,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Two lines once it has the width to itself; past that it still
                // ellipsises rather than pushing the card open without limit.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = onVerdictLayout,
                modifier = Modifier.padding(start = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, content = controls)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val (text, buttons) = measurables
        val b = buttons.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val wants = text.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else wants + b.width
        if (wants + b.width <= width) {
            // Side by side: the verdict takes exactly what is left, as before.
            val t = text.measure(Constraints(minWidth = width - b.width, maxWidth = width - b.width))
            val h = maxOf(t.height, b.height)
            layout(width, h) {
                t.place(0, (h - t.height) / 2)
                b.place(width - b.width, (h - b.height) / 2)
            }
        } else {
            val t = text.measure(Constraints(minWidth = width, maxWidth = width))
            layout(width, t.height + b.height) {
                t.place(0, 0)
                b.place(width - b.width, t.height)
            }
        }
    }
}
