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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ProjectManifest

/**
 * The lead's proposal, and the three things that may be done about it.
 *
 * ⚠ THREE BOUNDED CHOICES AND NO MORE — Spawn · Edit · Discard. The same card
 * appears as a notification with the same three buttons, and the notification
 * rule the house learned the hard way is that a notification asks a CLOSED
 * question. Edit opens the app rather than editing in the shade; nothing here
 * takes free text.
 *
 * ⚠ THE PROPOSAL IS RENDERED, NEVER RE-PARSED. The daemon does the structured
 * parse — a tagged fenced block, the Rounds anti-injection shape — and a client
 * that formed its own opinion about what the lead asked for would be a second
 * reading of the thing the owner is about to approve. So the body is text, drawn
 * as text.
 */
@Composable
fun ManifestCard(
    manifest: ProjectManifest,
    onSpawn: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set while a spawn is in flight, so the three verbs cannot be pressed twice. */
    busy: Boolean = false,
    /**
     * The daemon's refusal, shown VERBATIM. The one that matters is the STOP
     * sentinel — "the host is holding new sessions while usage is red" — which is
     * a state of the house rather than a fault in the proposal, and reads that
     * way only if it is said in the daemon's own words.
     */
    refusal: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(horizontal = 14.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "Proposal",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ProjectRules.manifestSummary(manifest)?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            manifest.text?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                // Capped and scrollable rather than truncated: the body is what the
                // owner is approving, and a card that ate the last two roles would
                // be asking for consent to something it had not shown.
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // ⚠ THE SILENT FAILURE, SAID. An untagged block is a proposal its
            // author believes it made and the owner never saw.
            ProjectRules.manifestCaution(manifest)?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                )
            }
            refusal?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.Start) {
                CardAction("Spawn", enabled = !busy, onClick = onSpawn)
                CardAction("Edit", enabled = !busy, onClick = onEdit)
                CardAction("Discard", enabled = !busy, onClick = onDiscard)
            }
        }
    }
}

/**
 * One of the card's verbs: small type, tight padding, one line.
 *
 * All three for the ArchivedSessionsView reason — a TextButton's default padding
 * is 24dp a side and the desktop's list pane starts at 325dp, which is where
 * "Copy resume command" once wrapped to four lines.
 */
@Composable
private fun CardAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
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
