package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * nothing paraphrased. What it lists is [PendingChangeRules.sections]: every
 * added role with its mode, directory, model and its WHOLE first prompt, every
 * removed role, every re-roled role with its WHOLE new task — the text drawn
 * through the session-request card's wrapped monospace block
 * ([WrappedMonoBlock] of [PromptDisplay.visible]): wrapped, never sideways,
 * control and bidi characters spelled out, a per-item height cap that scrolls
 * rather than clips, and the size as sent beside it. [problem] (the member cap)
 * is the daemon's own sentence; Approve would answer 409 with it, so it is said
 * up front in the warning ink.
 *
 * ⚠ APPROVE IS THE TEXT'S BUTTON (security scan 2026-11-02, F1). Approve makes
 * the daemon type these words into members. The card used to list a re-roled
 * role by name alone and an added one by the first line of its prompt, so the
 * owner approved words they were never shown; the notification offered Approve
 * with no text at all. Now the text is on the card, whole, and a change the card
 * cannot show the text for ([PendingChangeRules.approvable]) has Approve disabled
 * with the reason. The push carries no buttons: it opens this page.
 *
 * ⚠ AND APPROVE WAITS FOR THE END OF EVERY TEXT (third scan 2026-11-02, F1).
 * Each block is capped at [CHANGE_TEXT_FOLD_HEIGHT] and scrolls; a task padded
 * with blank lines or no-break spaces put its real words below the fold with no
 * cue, and Approve was live. Now each block says "↓ more" until scrolled to its
 * end, and Approve is disabled — the reason beside it — until every block on the
 * card has been ([PendingChangeRules.approvable] with `scrolledToEnd`, latched
 * per role for this token; a rewritten proposal is a new token and starts over).
 *
 * ⚠ THE REV AND THE CONTENT TOKEN TRAVEL WITH APPROVE (and the token with
 * Decline). The token is the daemon's digest of exactly what this card lists,
 * so a card left open while the lead rewrote its block cannot approve the
 * rewrite; the daemon answers 409 with the current project and the shells
 * redraw this card from it. The refusal lands here, verbatim.
 */
@Composable
fun PendingChangeCard(
    change: ProjectPendingChange,
    onApprove: (rev: Int, token: String) -> Unit,
    onDecline: (token: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Set while an approve or decline is in flight, so neither verb can be pressed twice. */
    busy: Boolean = false,
    /** The daemon's refusal, shown verbatim. */
    refusal: String? = null,
) {
    // Which roles' text blocks have been scrolled to their end, for THIS proposal:
    // a new token is a new card and the reading starts over.
    val read = remember(change.token, change.rev) { mutableStateMapOf<String, Boolean>() }
    val allRead = PendingChangeRules.textRoles(change).all { read[it] == true }
    val unshown = PendingChangeRules.approvable(change, scrolledToEnd = allRead)
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
            // No outer cap and no outer scroll: every item is on the card, each
            // text block capped and scrolling on its own, so a long prompt can
            // never push the next role's text — or Approve — out of sight.
            Column(Modifier.fillMaxWidth()) {
                PendingChangeRules.sections(change).forEach { section ->
                    Text(
                        section.heading,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    section.rows.forEach { row -> ChangeRow(row, onAtEnd = { if (it) read[row.role] = true }) }
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
            )
            Row(horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onApprove(change.rev, change.token) },
                    enabled = !busy && unshown == null,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("change.approve"),
                ) { Text(PendingChangeRules.APPROVE) }
                TextButton(
                    onClick = { onDecline(change.token) },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("change.decline"),
                ) { Text(PendingChangeRules.DECLINE) }
                // Why Approve is disabled, beside it.
                unshown?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = stateColor(StateKind.NEEDS_YOU),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("change.unshown"),
                    )
                }
            }
        }
    }
}

/** How tall one role's text may draw on the roster-change card before it scrolls inside its block. */
val CHANGE_TEXT_FOLD_HEIGHT: Dp = 160.dp

/**
 * One role: its name, its meta line (an added role's mode, directory, model),
 * and — for an added or re-roled role — the WHOLE text the daemon will type,
 * labelled as the lead's, with its size as sent. Test tags carry the role:
 * `change.text.<role>` is the block (`.scroll` its scroll node, `.more` its
 * "more below" cue), `change.counts.<role>` the counts line. [onAtEnd] is the
 * block's: true once its end is on screen.
 */
@Composable
private fun ChangeRow(row: PendingChangeRules.Row, onAtEnd: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp)) {
        Text(
            row.role,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("change.role.${row.role}"),
        )
        row.meta?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("change.meta.${row.role}"),
            )
        }
        val visible = row.visibleText
        if (visible != null) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(
                    "${row.textLabel ?: PendingChangeRules.FIRST_PROMPT} · ${PendingChangeRules.WHOSE_WORDS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.padding(top = 2.dp).testTag("change.text.${row.role}")) {
                WrappedMonoBlock(visible, maxHeight = CHANGE_TEXT_FOLD_HEIGHT, tag = "change.text.${row.role}", onAtEnd = onAtEnd)
            }
            row.countsLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).testTag("change.counts.${row.role}"),
                )
            }
        }
    }
}
