package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.desktop.AppStore
import kotlinx.coroutines.launch

/**
 * The machines that have offered themselves to huginn, and the one control that
 * matters here: start something on one.
 *
 * Deliberately not a management console. A device's own settings live ON that
 * device — that is the whole point of the scope model — so this surface reads
 * state and starts work, and the only thing it can take away is the enrolment.
 */
@Composable
fun DevicesPane(store: AppStore) {
    val devices by store.devices.collectAsState()
    val scope = rememberCoroutineScope()

    if (devices.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No devices yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "A device is another machine that can run work in its own context. " +
                    "Turn on \"Give Huginn access to this PC\" in Settings, on the machine " +
                    "you want to offer, and it appears here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        devices.forEach { d ->
            DeviceRow(
                device = d,
                onStart = { mode -> scope.launch { store.startChatOn(d.id, mode) } },
                onForget = { scope.launch { store.forgetDevice(d.id) } },
            )
        }
    }
}

@Composable
private fun DeviceRow(device: Device, onStart: (String) -> Unit, onForget: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Reachable or not, in the app's own palette. No accent rail.
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
                        describe(device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Enabled from what the machine will do RIGHT NOW, not from what it
                // is enrolled at: a locked machine offering an Act button that always
                // fails is worse than no button.
                TextButton(
                    onClick = { onStart("ask") },
                    enabled = device.online && !device.running,
                ) { Text("Ask here") }
                TextButton(
                    onClick = { onStart("act") },
                    enabled = device.online && !device.running && device.effectiveScope != "look",
                ) { Text("Act here") }
                TextButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

/**
 * One line saying what this machine is and what it will do.
 *
 * Enrolled scope AND effective scope, when they differ: "own, read-only while
 * locked" is a different situation from "enrolled read-only", and showing only the
 * second makes a locked machine look misconfigured.
 */
private fun describe(device: Device): String {
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
        else -> "idle"
    }
    device.version?.takeIf { it.isNotBlank() }?.let { parts += "v$it" }
    return parts.joinToString(" · ")
}
