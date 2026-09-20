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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ManifestSession
import com.silencelen.huginn.data.ProjectManifest

/**
 * The lead's proposal, and the three things that may be done about it.
 *
 * ⚠ THREE BOUNDED CHOICES AND NO MORE — Spawn · Edit · Discard. The same card
 * appears as a notification with two of them, and the notification rule the
 * house learned the hard way is that a notification asks a CLOSED question. Edit
 * opens the app rather than editing in the shade; nothing here takes free text.
 *
 * ⚠⚠ SPAWN SENDS THE REV, NOT THE PLAN. `POST …/spawn` takes `{approve:true,
 * manifestRev}` and nothing else: the manifest on the daemon IS the plan, and a
 * card that re-sent the roles would be approving a copy of a proposal rather
 * than the proposal. The rev is what makes a card that has been sitting on a
 * lock screen unable to approve a plan its owner never saw — which is why
 * [ProjectManifest.rev] is drawn on the card as well as sent with the verb.
 *
 * ⚠ THE PROPOSAL IS RENDERED, NEVER RE-PARSED. The daemon does the structured
 * parse — a tagged fenced block, the Rounds anti-injection shape — and refuses
 * anything that decides what gets created. This draws the result: the summary
 * line, then one row per session with the settings it will start under.
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
     * sentinel — "there is no room on this account right now (…)" — which is a
     * state of the house rather than a fault in the proposal, and reads that way
     * only if it is said in the daemon's own words.
     */
    refusal: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(horizontal = 14.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Proposal",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                // The rev, said out loud. It is what Spawn quotes back, and a card
                // showing a different one from the one that is live is exactly the
                // thing the rev check exists to catch.
                Text(
                    "rev ${manifest.rev}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProjectRules.manifestSummary(manifest)?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            manifest.scope.trim().takeIf { it.isNotEmpty() }?.let { scope ->
                Spacer(Modifier.height(4.dp))
                // ⚠⚠ P-16. THE CARD ASKS FOR CONSENT TO TEXT IT HID. The brief was
                // clipped at six lines with an ellipsis and NO way to open it —
                // the walker's was over 500 characters — with Spawn, Edit and
                // Discard directly underneath. You have to be able to read the
                // whole thing before you approve it.
                var open by remember(scope) { mutableStateOf(false) }
                // Offered only when there is something behind the fold, and the
                // cut is MEASURED rather than guessed from a character count: a
                // brief of six short lines has nothing to open, and a control
                // over nothing is worse than no control. Recorded only while
                // folded — expanded, there is no overflow to see, and the value
                // that opened it is the one that keeps "Show less" on screen.
                var clipped by remember(scope) { mutableStateOf(false) }
                Text(
                    scope,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (open) Int.MAX_VALUE else SCOPE_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!open) clipped = it.hasVisualOverflow },
                )
                if (clipped) {
                    TextButton(
                        onClick = { open = !open },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                    ) {
                        Text(
                            if (open) "Show less" else "Show the whole brief",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                ProjectRules.manifestWords(manifest),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ⚠ EVERY SESSION IS SHOWN. A card that ate the last two roles would
            // be asking for consent to something it had not shown, so the list is
            // capped in height and scrolls rather than being truncated.
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                manifest.sessions.forEach { SessionRow(it) }
            }
            // ⚠ THE SILENT FAILURE, SAID. An untagged block is a proposal its
            // author believes it made and the owner never saw — and the same
            // signal a planted block would raise.
            ProjectRules.manifestCaution(manifest)?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                )
            }
            if (ProjectRules.alreadySpawned(manifest)) {
                Spacer(Modifier.height(6.dp))
                Text(
                    MANIFEST_ALREADY_SPAWNED,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
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
 * One proposed session: the role, and what it will be started with.
 *
 * The first prompt is NOT here. It is the whole first message that session gets
 * — a paragraph, sometimes several — and a card that inlined twelve of them
 * would stop being a card. Edit is where it is read.
 */
@Composable
private fun SessionRow(session: ManifestSession) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(
            session.role,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ProjectRules.sessionWords(session),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only when it is not the project's own directory — an absent fact is
            // absent rather than a line saying "the usual place".
            ProjectRules.sessionCwd(session)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

/**
 * Said when this exact rev has already been carried out.
 *
 * The daemon stamps `spawnedRev` when it spawns and keeps the manifest, so a
 * card redrawn from a notification that has been sitting on a lock screen can
 * still be looking at a plan that is already running. The daemon would refuse
 * (the project is `active`, not `proposed`); saying so first is cheaper than
 * finding out by pressing.
 */
const val MANIFEST_ALREADY_SPAWNED: String =
    "These sessions have already been created from this proposal."

/**
 * How much of a proposal's brief is shown before the disclosure (P-16).
 *
 * Six lines was the old hard cut with no way past it; it stays as the FOLD
 * rather than as the ceiling.
 */
private const val SCOPE_LINES: Int = 6
