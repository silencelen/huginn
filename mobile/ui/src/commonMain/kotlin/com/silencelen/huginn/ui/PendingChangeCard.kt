package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ProjectPendingChange
import com.silencelen.huginn.ui.theme.StateKind
import com.silencelen.huginn.ui.theme.stateColor

/**
 * The lead proposed a ROSTER CHANGE for a running project (appd 3.17:
 * `pendingChange` on the row), waiting for Approve or Decline.
 *
 * Drawn under the dashboard header in the manifest card's idiom — the same
 * surface, the same "rev N" corner, the same quiet verbs — because it is the
 * same kind of thing: a plan the owner approves, in the lead's words, with
 * nothing paraphrased. What it lists is [PendingChangeRules.sections]: the added
 * roles with the first line of their prompt, the removed roles, the re-roled
 * ones. [problem] (the member cap) is the daemon's own sentence; Approve would
 * answer 409 with it, so it is said up front in the warning ink.
 *
 * ⚠ THE REV TRAVELS WITH APPROVE. A card left open while the lead revised cannot
 * approve the revision; the daemon answers 409 with the current project and the
 * shells redraw this card from it. The refusal lands here, verbatim.
 */
@Composable
fun PendingChangeCard(
    change: ProjectPendingChange,
    onApprove: (rev: Int) -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set while an approve or decline is in flight, so neither verb can be pressed twice. */
    busy: Boolean = false,
    /** The daemon's refusal, shown verbatim. */
    refusal: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(horizontal = 14.dp).fillMaxWidth().testTag("change.card"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    PendingChangeRules.TITLE,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "rev ${change.rev}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(PendingChangeRules.headline(change), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("change.summary"))
            Spacer(Modifier.height(4.dp))
            Text(
                PendingChangeRules.countWords(change),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                PendingChangeRules.sections(change).forEach { section ->
                    Text(
                        section.heading,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    section.rows.forEach { row ->
                        Text(
                            row,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                        )
                    }
                }
            }
            change.problem?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor(StateKind.NEEDS_YOU),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("change.problem"),
                )
            }
            refusal?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor(StateKind.ERROR),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("change.refusal"),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                PendingChangeRules.APPROVE_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.Start) {
                TextButton(
                    onClick = { onApprove(change.rev) },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("change.approve"),
                ) { Text(PendingChangeRules.APPROVE) }
                TextButton(
                    onClick = onDecline,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("change.decline"),
                ) { Text(PendingChangeRules.DECLINE) }
            }
        }
    }
}
