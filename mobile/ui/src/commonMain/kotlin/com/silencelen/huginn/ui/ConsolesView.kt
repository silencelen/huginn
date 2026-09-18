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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.Console
import com.silencelen.huginn.data.ConsoleApproval

/**
 * The internal pages this host serves, and how to reach them.
 *
 * A reading surface: a list of addresses with a liveness mark, opened in the
 * host's browser through whichever handoff the shell owns. It is deliberately
 * NOT the Devices surface it resembles — a device is another machine that
 * enrols and decides for itself what it will do; a console is a URL. No shared
 * key, no shared lifecycle, no shared security story.
 *
 * ⚠ EVERY ROW SAYS WHERE IT WAS SEEN FROM, until the rebind has been applied. A
 * page bound to the host's own address is genuinely up and genuinely unreachable
 * from the phone in your hand, and a row that said "up" full stop would be lying
 * by omission to the one person who would then tap it.
 */
@Composable
fun ConsolesView(
    consoles: List<Console>,
    nowMs: Long,
    onOpen: (Console) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = "CONSOLES",
    /**
     * The registry-wide approval. One card for the whole list, because the
     * rebind and the firewall lines are one job, not one per row.
     */
    approval: ConsoleApproval? = null,
    /** Null hides the control on a shell with nowhere to run a probe from. */
    onProbe: ((Console) -> Unit)? = null,
    onEdit: ((Console) -> Unit)? = null,
    /** Hands the approval's whole text to the shell's clipboard. */
    onCopyApproval: ((String) -> Unit)? = null,
) {
    val applied = ConsoleRules.approvalApplied(approval)
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp),
            )
        }
        if (consoles.isEmpty()) {
            Text(
                CONSOLES_EMPTY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
            )
        } else {
            consoles.forEach { c ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ConsoleRow(c, nowMs, applied, onOpen = { onOpen(c) }, onProbe = onProbe, onEdit = onEdit)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (ConsoleRules.hasApproval(approval)) {
            Spacer(Modifier.height(10.dp))
            ConsoleApprovalCard(
                approval!!,
                modifier = Modifier.padding(horizontal = 14.dp),
                onCopy = onCopyApproval,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ConsoleRow(
    console: Console,
    nowMs: Long,
    applied: Boolean,
    onOpen: () -> Unit,
    onProbe: ((Console) -> Unit)?,
    onEdit: ((Console) -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReachDot(console)
            Spacer(Modifier.width(9.dp))
            Text(
                ConsoleRules.label(console),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            ConsoleRules.subtitle(console),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            listOfNotNull(
                ConsoleRules.reachabilityWords(console.up, console.lastProbeAt, nowMs, applied),
                ConsoleRules.probeDetail(console),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.Start) {
            if (ConsoleRules.openable(console)) RowVerb("Open", onOpen)
            onProbe?.let { probe -> RowVerb("Check now") { probe(console) } }
            onEdit?.let { edit -> RowVerb("Edit") { edit(console) } }
        }
    }
}

/**
 * The steps that would make these consoles reachable from beyond the host — and
 * the sentence saying who runs them.
 *
 * ⚠⚠ NO ACTION BUTTON, ON PURPOSE AND ON BOTH SIDES. There is no route that
 * applies this: the steps rebind a systemd unit on this host and add firewall
 * lines on a different machine entirely, and neither is a thing a daemon or a
 * phone has any business doing. The only control is COPY. Without
 * [ConsoleRules.APPROVAL_NEVER_RUN] a card with no button reads as one somebody
 * forgot to finish, and somebody would eventually finish it — which is why that
 * sentence and the verbatim commands are both asserted by `ConsolesViewTest`.
 */
@Composable
fun ConsoleApprovalCard(
    approval: ConsoleApproval,
    modifier: Modifier = Modifier,
    onCopy: ((String) -> Unit)? = null,
) {
    val steps = ConsoleRules.approvalSteps(approval)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                approval.title?.takeIf { it.isNotBlank() } ?: ConsoleRules.approvalWords(approval),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!approval.title.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    ConsoleRules.approvalWords(approval),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            approval.why?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            steps.forEach { step ->
                Spacer(Modifier.height(6.dp))
                Text(
                    listOfNotNull(
                        step.where.takeIf { it.isNotBlank() },
                        step.summary.takeIf { it.isNotBlank() },
                    ).joinToString(" — "),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
                step.file?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Monospace, one per line, in the daemon's order. They are going
                // to be read and then typed.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    step.commands.filter { it.isNotBlank() }.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            ConsoleRules.approvalNote(approval)?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                ConsoleRules.APPROVAL_NEVER_RUN,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Three lines: the sentence ends in the instruction, and a rule cut
                // before its own consequence is a rule that has not been stated.
                maxLines = 3,
            )
            // The ONLY control. See the KDoc.
            if (onCopy != null) {
                Row(horizontalArrangement = Arrangement.Start) {
                    RowVerb("Copy steps") { onCopy(ConsoleRules.approvalText(approval)) }
                }
            }
        }
    }
}

/**
 * Consoles as the Status screen carries them: a card, not a destination.
 *
 * The phone's bottom bar deliberately stays at four, and a list of URLs is a
 * thing you read rather than a place you work — so on the phone this is where
 * Consoles lives (decision 48). The desktop uses the same card in its Status
 * column and the full [ConsolesView] in a pane of its own.
 */
@Composable
fun ConsolesStatusCard(
    consoles: List<Console>,
    nowMs: Long,
    onOpen: (Console) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the rebind has been applied, so the rows' words can drop the caveat. */
    applied: Boolean = false,
    /** Opens the full list. Null on a shell that has no such destination. */
    onSeeAll: (() -> Unit)? = null,
) {
    if (consoles.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CONSOLES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) {
                    Text("All", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        Text(
            consolesStatusWords(consoles),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 2.dp),
        )
        consoles.forEach { c ->
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReachDot(c)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ConsoleRules.label(c),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ConsoleRules.reachabilityWords(c.up, c.lastProbeAt, nowMs, applied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (ConsoleRules.openable(c)) RowVerb("Open") { onOpen(c) }
            }
        }
    }
}

/**
 * The card's one-line summary.
 *
 * ⚠ THE UNCHECKED ONES ARE COUNTED SEPARATELY, never folded into "down". "3 of 4
 * answering" with the fourth never probed is a number that sends somebody to
 * restart a healthy service — and after a daemon restart that is EVERY row,
 * because probe state lives in memory.
 */
fun consolesStatusWords(consoles: List<Console>): String {
    if (consoles.isEmpty()) return "none listed"
    val up = consoles.count { ConsoleRules.reach(it) == ConsoleRules.Reach.UP }
    val unknown = consoles.count { ConsoleRules.reach(it) == ConsoleRules.Reach.UNKNOWN }
    val parts = mutableListOf("$up of ${consoles.size} answering from the host")
    if (unknown > 0) parts += "$unknown not checked"
    return parts.joinToString(" · ")
}

/** The liveness mark. UNKNOWN draws a hole, not a grey dot that reads as "down". */
@Composable
private fun ReachDot(console: Console) {
    val colour = when (ConsoleRules.reach(console)) {
        ConsoleRules.Reach.UP -> MaterialTheme.colorScheme.primary
        ConsoleRules.Reach.DOWN -> MaterialTheme.colorScheme.error
        ConsoleRules.Reach.UNKNOWN -> null
    }
    if (colour == null) {
        Spacer(Modifier.size(8.dp))
        return
    }
    Surface(color = colour, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
}

@Composable
private fun RowVerb(label: String, onClick: () -> Unit) {
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

/** What an empty consoles list says, so nothing reads as broken. */
const val CONSOLES_EMPTY: String =
    "No consoles listed. A console is an internal page this host serves — the registry " +
        "is a file on the host, and each row is probed from there."
