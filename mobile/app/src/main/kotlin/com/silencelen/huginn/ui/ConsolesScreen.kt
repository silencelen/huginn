package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Console
import com.silencelen.huginn.data.ConsoleApproval

/**
 * The full Consoles page: every internal page this host serves, its reachability
 * as seen FROM THE HOST, and the one card that says what would make them
 * reachable from anywhere else.
 *
 * ⚠ THE APPROVAL CARD HAS A COPY CONTROL AND NOTHING ELSE, and that is the whole
 * decision (owner, 47). The steps rebind a systemd unit here and add firewall
 * lines on a different machine; the daemon has no business running either and
 * this app has less. Nothing on this screen may grow a button that applies them.
 */
@Composable
fun ConsolesScreen(
    consoles: List<Console>,
    approval: ConsoleApproval?,
    nowMs: Long,
    onOpen: (Console) -> Unit,
    onProbe: (Console) -> Unit,
    onCopyApproval: (String) -> Unit,
    /** id null = a new one. The daemon re-validates everything typed here. */
    onSave: (id: String?, version: Int, name: String, url: String, notes: String?) -> Unit,
    onDelete: (Console) -> Unit,
) {
    var editing by remember { mutableStateOf<Console?>(null) }
    var adding by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        ConsolesView(
            consoles = consoles,
            nowMs = nowMs,
            onOpen = onOpen,
            approval = approval,
            onProbe = onProbe,
            onEdit = { editing = it },
            onCopyApproval = onCopyApproval,
            // ⚠ THIS DESTINATION IS THE SCROLL. Nothing else on it scrolls — the
            // approval card's remaining commands, its note and its only control,
            // Copy steps, were simply off the bottom of the phone.
            scroll = true,
            modifier = Modifier.fillMaxSize(),
        )
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add console") },
        )
    }

    if (adding) {
        ConsoleEditDialog(
            console = null,
            onDismiss = { adding = false },
            onSave = { name, url, notes -> adding = false; onSave(null, 0, name, url, notes) },
            onDelete = null,
        )
    }
    editing?.let { c ->
        ConsoleEditDialog(
            console = c,
            onDismiss = { editing = null },
            onSave = { name, url, notes -> editing = null; onSave(c.id, c.version, name, url, notes) },
            onDelete = { editing = null; onDelete(c) },
        )
    }
}

/**
 * Name, address, note.
 *
 * The address is checked HERE as well as on the host — `ConsoleRules.urlProblem`
 * mirrors the daemon's rule — so a typed `htp://` is answered under the field
 * instead of by a round trip that throws.
 */
@Composable
private fun ConsoleEditDialog(
    console: Console?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, notes: String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember(console) { mutableStateOf(console?.name.orEmpty()) }
    var url by remember(console) { mutableStateOf(console?.url.orEmpty()) }
    var notes by remember(console) { mutableStateOf(console?.notes.orEmpty()) }
    val urlProblem = ConsoleRules.urlProblem(url).takeIf { url.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (console == null) "Add a console" else "Edit ${ConsoleRules.label(console)}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    isError = urlProblem != null,
                    label = { Text("Address") },
                    supportingText = urlProblem?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Note") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Probed from the host, not from this phone.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onDelete?.let {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = it) { Text("Remove this console") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), url.trim(), notes.trim().ifBlank { null }) },
                enabled = name.isNotBlank() && url.isNotBlank() && urlProblem == null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------- what the shell offers

/**
 * Whether the two ways into Consoles are drawn, decided once.
 *
 * Same rule and same reason as [ProjectEntries]: a daemon with no consoles route
 * answers 404, and a card on Status that leads to an error is worse than a Status
 * screen that never mentions consoles.
 */
data class ConsoleEntries(
    /** The card on the Status screen (owner decision 48). */
    val statusCard: Boolean,
    /** The full page it opens. */
    val fullPage: Boolean,
)

fun consoleEntries(available: Boolean?): ConsoleEntries {
    val on = available == true
    return ConsoleEntries(statusCard = on, fullPage = on)
}
