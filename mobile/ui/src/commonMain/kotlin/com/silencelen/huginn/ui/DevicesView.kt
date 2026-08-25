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
    onForget: (Device) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = "DEVICES",
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
        devices.forEach { d ->
            DeviceRow(
                device = d,
                onStart = { mode -> onStart(d, mode) },
                onForget = { onForget(d) },
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

@Composable
private fun DeviceRow(device: Device, onStart: (String) -> Unit, onForget: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (device.online) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(if (device.online) 8.dp else 6.dp),
                ) {}
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        describeDevice(device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            device.root?.takeIf { it.isNotBlank() }?.let { root ->
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
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A serving row cannot start a claude run, so Ask/Act here are
                // ABSENT for it (a button that always fails is worse than none);
                // the line below answers "so how do I use it" instead. Forget
                // stays: it is still the only takeaway.
                if (device.scope != "generate") {
                    // Enabled from what the machine will do RIGHT NOW, not from what it
                    // is enrolled at: a locked machine offering an Act button that always
                    // fails is worse than no button at all.
                    TextButton(
                        onClick = { onStart("ask") },
                        enabled = device.online && !device.running &&
                            DevicePolicy.allows(DevicePolicy.parse(device.effectiveScope), "ask"),
                    ) { Text("Ask here") }
                    TextButton(
                        onClick = { onStart("act") },
                        enabled = device.online && !device.running &&
                            DevicePolicy.allows(DevicePolicy.parse(device.effectiveScope), "act"),
                    ) { Text("Act here") }
                }
                TextButton(onClick = onForget) { Text("Forget") }
            }
            if (device.scope == "generate") {
                Text(
                    "Chat with it by picking its model in any chat's model menu.",
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
fun describeDevice(device: Device): String {
    // A serving row says what it IS: the lock never changes what it will do
    // (generate is exclusive and ignores the lock drop), so no locked clause.
    if (device.scope == "generate") {
        val parts = mutableListOf(device.platform, "serves local models")
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
