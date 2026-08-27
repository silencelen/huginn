package com.silencelen.huginn.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.device.DevicePolicy

/**
 * The machines that have offered themselves to huginn.
 *
 * NOT a management console, and the difference is the security model rather than
 * a scoping decision. What a device is willing to do is decided ON that device —
 * that is the whole reason a leaked token here does not become somebody's PC — so
 * this surface reads state, starts work, and can take away exactly one thing: the
 * enrolment. A scope control drawn here would be a lie about where the decision is
 * made, and the lie would only surface the day it mattered.
 *
 * Shared by the phone and the desktop so a device does not read as one thing on a
 * screen and another on a laptop. Same shape as [RoundsSection] beside it: one
 * small state dot by the title, and no accent rail down the card.
 */
@Composable
fun DevicesSection(
    devices: List<Device>,
    onStart: (Device, String) -> Unit,
    onForget: (MachineGroup) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = "DEVICES",
    /**
     * The reader's own machine key (the daemon's normalised-hostname grouping
     * key), so their machine's card can say so. Null — a phone, which never
     * enrols — marks nothing.
     */
    thisMachine: String? = null,
) {
    if (devices.isEmpty()) return
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
        groupByMachine(devices).forEach { g ->
            MachineCard(
                group = g,
                isThis = g.isThisMachine(thisMachine),
                onStart = onStart,
                onForget = onForget,
            )
        }
        // Said once, under the list, because the commonest question this screen
        // raises is "where do I change the scope" and the answer is a property
        // worth teaching rather than a limitation worth hiding.
        Text(
            "What a machine will do is set on the machine itself, not from here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
        )
    }
}

/**
 * One MACHINE, seen whole. A box that both runs claude work and serves local
 * models holds two enrolments on purpose — authority is decided per-row, and
 * the serving credential must never gain claude reach — but a person owns a
 * box, not a credential. Rows sharing the daemon's machine key fold into one
 * group; the `-llm` row becomes the "serves local AI" facet of its machine.
 */
data class MachineGroup(val rows: List<Device>) {
    val claude: List<Device> = rows.filter { it.scope != "generate" }
    val serving: List<Device> = rows.filter { it.scope == "generate" }
    /** The name a person knows the box by — its claude row's, when it has one. */
    val head: Device = claude.firstOrNull() ?: rows.first()
    val online: Boolean = rows.any { it.online }

    /** Whether this card IS the machine the reader is sitting at. */
    fun isThisMachine(machineKey: String?): Boolean =
        machineKey != null && rows.any { it.machine == machineKey }
}

fun groupByMachine(devices: List<Device>): List<MachineGroup> {
    val groups = LinkedHashMap<String, MutableList<Device>>()
    for (d in devices) {
        groups.getOrPut(d.machine?.takeIf { it.isNotBlank() } ?: d.id) { mutableListOf() }.add(d)
    }
    return groups.values.map { MachineGroup(it) }
}

@Composable
private fun MachineCard(group: MachineGroup, isThis: Boolean, onStart: (Device, String) -> Unit, onForget: (MachineGroup) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (group.online) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(if (group.online) 8.dp else 6.dp),
                ) {}
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            group.head.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isThis) {
                            // Muted and in the words a person would use — the
                            // machine they are sitting at, said once, no badge.
                            Text(
                                " (this device)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    // Each capability on its own line: what it will DO (claude),
                    // then what it SERVES. Two facets of one machine, never two
                    // machines.
                    group.claude.forEach { d ->
                        Text(
                            describeDevice(d),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    group.serving.forEach { d ->
                        Text(
                            describeDevice(d, includePlatform = group.claude.isEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            group.claude.forEach { d ->
                d.root?.takeIf { it.isNotBlank() }?.let { root ->
                    // Said plainly, because the word "work" invites exactly the wrong
                    // assumption: this is where a run STARTS, not a fence it stays behind.
                    Text(
                        "work starts in $root — not a sandbox",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 18.dp, top = 2.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Ask/Act belong to the claude capability — a serving facet can
                // never start a claude run, so a machine without that capability
                // shows no such buttons (one that always fails is worse than
                // none); the line below answers "so how do I use it" instead.
                group.claude.firstOrNull()?.let { d ->
                    // Enabled from what the machine will do RIGHT NOW, not from what it
                    // is enrolled at: a locked machine offering an Act button that always
                    // fails is worse than no button at all.
                    TextButton(
                        onClick = { onStart(d, "ask") },
                        enabled = d.online && !d.running &&
                            DevicePolicy.allows(DevicePolicy.parse(d.effectiveScope), "ask"),
                    ) { Text("Ask here") }
                    TextButton(
                        onClick = { onStart(d, "act") },
                        enabled = d.online && !d.running &&
                            DevicePolicy.allows(DevicePolicy.parse(d.effectiveScope), "act"),
                    ) { Text("Act here") }
                }
                // One Forget for the machine: it takes away every credential the
                // box holds, because forgetting half a machine is a state nobody
                // asks for by pressing a button called Forget. The GROUP goes to
                // the dialog — the audit caught the per-row callbacks racing a
                // single-slot confirm, which kept only the last row.
                TextButton(onClick = { onForget(group) }) { Text("Forget") }
            }
            if (group.serving.isNotEmpty()) {
                // "any chat" was a lie the daemon corrected with a 409: a chat
                // with history is pinned to its family and machine. A NEW chat
                // is the door that always opens.
                Text(
                    "Chat with its local AI: start a new chat and pick its model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One line saying what this machine is and what it will do.
 *
 * Enrolled scope AND effective scope, when they differ: "own, read-only while
 * locked" is a different situation from "enrolled read-only", and showing only the
 * second makes a locked machine look misconfigured by somebody.
 */
fun describeDevice(device: Device, includePlatform: Boolean = true): String {
    // A serving row says what it IS: the lock never changes what it will do
    // (generate is exclusive and ignores the lock drop), so no locked clause.
    // Inside a merged machine card the claude facet already named the platform,
    // so the serving line may drop it rather than say "windows" twice.
    if (device.scope == "generate") {
        val parts = mutableListOf<String>()
        if (includePlatform) parts += device.platform
        parts += "serves local models"
        if (device.models.isNotEmpty()) parts += device.models.joinToString(", ") { it.display.ifBlank { it.slug } }
        parts += when {
            !device.online -> "not reachable"
            device.running -> "generating"
            else -> "serving"
        }
        device.version?.takeIf { it.isNotBlank() }?.let { parts += "v$it" }
        return parts.joinToString(" · ")
    }
    val parts = mutableListOf<String>()
    parts += device.platform
    parts += if (device.scope == device.effectiveScope) {
        device.scope
    } else {
        "${device.scope}, ${device.effectiveScope} while locked"
    }
    parts += when {
        device.running -> "running something"
        !device.online -> "not reachable"
        device.queued > 0 -> "${device.queued} queued"
        // Said as a QUESTION, because that is the honest shape of it. Claiming
        // "idle" here is what let the daemon hand work to a machine that was
        // still finishing an earlier job, where it sat undelivered until it was
        // declared "no word for 5 minutes".
        device.awaitingPoll -> "free? not asked for work since huginn restarted"
        else -> "idle"
    }
    device.version?.takeIf { it.isNotBlank() }?.let { parts += "v$it" }
    return parts.joinToString(" · ")
}
