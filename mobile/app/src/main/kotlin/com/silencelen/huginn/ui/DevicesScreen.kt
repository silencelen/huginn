package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Device

/**
 * The Devices screen: other machines that can run work in their own context.
 *
 * Reached from Settings rather than the bottom bar, and that is a judgement about
 * how often it is wanted, not about how important it is. Chats, Sessions, Rounds
 * and Status are the daily loop; how many machines are enrolled is something you
 * look at when you are changing it. Giving Devices a bar slot would have cost one
 * of the four a place it earns every day.
 *
 * The phone can see every machine, start work on one, and withdraw an enrolment.
 * It cannot widen what a machine will do — that decision lives on the machine.
 */
@Composable
fun DevicesScreen(
    devices: List<Device>,
    loading: Boolean,
    connected: Boolean?,
    onStart: (Device, String) -> Unit,
    onForget: (Device) -> Unit,
    onOpenSettings: () -> Unit,
    onStartPolling: () -> Unit = {},
    onStopPolling: () -> Unit = {},
) {
    // Tied to the screen, exactly like Rounds: nothing polls the device list while
    // you are reading a chat, and the list is live while you are looking at it.
    DisposableEffect(Unit) {
        onStartPolling()
        onDispose { onStopPolling() }
    }

    // A phone is a pocketful of mis-taps and Forget sits beside two buttons you
    // press often, so it asks. The desktop does not, and does not need to.
    var forgetTarget by remember { mutableStateOf<Device?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (devices.isEmpty() && !loading) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                if (connected == false) {
                    EmptyState(
                        "Not connected",
                        "Add the server URL and token in Settings, then pull to refresh.",
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                    }
                } else {
                    EmptyState(
                        "No devices yet",
                        "A device is another machine that can run work in its own context — " +
                            "your PC, a server, a build box. A machine offers itself: turn on " +
                            "\"Give Huginn access to this PC\" in the desktop app, or run " +
                            "\"huginn device on\" where there is no desktop app.",
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            ) {
                item(key = "devices") {
                    DevicesSection(
                        devices = devices,
                        onStart = onStart,
                        onForget = { forgetTarget = it },
                        header = null,
                    )
                }
            }
        }
    }

    forgetTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${d.name}?") },
            // Said precisely, because "remove" would be a lie: nothing here reaches
            // onto that machine, and a runner still running there simply re-enrols.
            text = {
                Text(
                    "Huginn stops offering it work. Nothing changes on ${d.name} itself — " +
                        "if its runner is still going it will enrol again. Stop it there to " +
                        "make this stick.",
                )
            },
            confirmButton = {
                TextButton(onClick = { val t = d; forgetTarget = null; onForget(t) }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Cancel") } },
        )
    }
}
