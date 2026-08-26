package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.ui.DevicesSection
import kotlinx.coroutines.launch

/**
 * The machines that have offered themselves to huginn.
 *
 * The rows are [DevicesSection], shared with the phone, so a device does not read
 * as one thing here and another in a pocket. What stays here is the frame: the
 * empty state names the toggle that lives in THIS app's Settings, which is not
 * advice a phone can give.
 */
@Composable
fun DevicesPane(store: AppStore) {
    val devices by store.devices.collectAsState()
    val scope = rememberCoroutineScope()
    // ⚠ ASKS FIRST, as the phone already did. Forget sat directly beside "Ask
    // here" and "Act here" in the shared row and fired immediately — and what it
    // does is easy to misread: the machine comes back with the SAME id under a
    // minute later, because a runner that is still running simply re-enrols. So
    // the button looked like it had done nothing, when what it had actually done
    // was kill whatever that device was running.
    // The target is the MACHINE — every credential the box holds. The audit
    // caught per-row callbacks racing this single-slot dialog, keeping only
    // the last row.
    var forgetTarget by remember { mutableStateOf<com.silencelen.huginn.ui.MachineGroup?>(null) }

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
                    "you want to offer, and it appears here. A machine with no desktop app " +
                    "offers itself with \"huginn device on\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
    ) {
        DevicesSection(
            devices = devices,
            onStart = { d, mode -> scope.launch { store.startChatOn(d.id, mode) } },
            onForget = { g -> forgetTarget = g },
            header = null,
        )
    }

    forgetTarget?.let { g ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${g.head.name}?") },
            // The same words as the phone, deliberately: two dialogs describing
            // one action differently is how people learn to distrust both.
            text = {
                Text(
                    if (g.serving.isEmpty()) {
                        "Huginn stops offering it work. Nothing changes on ${g.head.name} itself — " +
                            "if its runner is still going it will enrol again. Stop it there to " +
                            "make this stick."
                    } else {
                        "Huginn stops offering it work and its local models. Nothing changes on " +
                            "${g.head.name} itself — a runner or serving service still going there " +
                            "simply re-enrols. Stop them there (“huginn device off”, " +
                            "“huginn local off”) to make this stick."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = g; forgetTarget = null
                    scope.launch { t.rows.forEach { store.forgetDevice(it.id) } }
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Cancel") } },
        )
    }
}
