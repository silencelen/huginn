package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Session

/**
 * The live tmux sessions. Each row leads with what the session is actually doing
 * (Claude Code's own generated title, plus the last couple of meaningful pane
 * lines) rather than only its tmux name, because "which of my four sessions is
 * this" is the question the list exists to answer.
 */
@Composable
fun SessionsScreen(
    sessions: List<Session>,
    selectedName: String? = null,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
    onKill: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmKill by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameTo by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        if (sessions.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                EmptyState("No sessions", "Create one and it opens Claude Code on the host, same as cc.")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
            ) {
                items(sessions, key = { it.name }) { s ->
                    SessionRow(
                        s,
                        selected = s.name == selectedName,
                        onOpen = { onOpen(s.name) },
                        onKill = { confirmKill = s.name },
                        onRename = { renaming = s.name; renameTo = s.name },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { newName = ""; showNew = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New session") },
        )
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New session") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Letters, digits and underscore. Opens Claude Code on the host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showNew = false; onCreate(newName) }, enabled = newName.isNotBlank()) {
                    Text("Create")
                }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }

    renaming?.let { from ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename $from") },
            text = {
                OutlinedTextField(
                    value = renameTo,
                    onValueChange = { renameTo = it },
                    singleLine = true,
                    label = { Text("New name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { renaming = null; onRename(from, renameTo) },
                    enabled = renameTo.isNotBlank() && renameTo != from,
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    confirmKill?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmKill = null },
            title = { Text("End $name?") },
            text = { Text("The session and anything running inside it are terminated. Unsaved work in that session is lost.") },
            confirmButton = {
                TextButton(onClick = { confirmKill = null; onKill(name) }) { Text("End session") }
            },
            dismissButton = { TextButton(onClick = { confirmKill = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionRow(
    s: Session,
    selected: Boolean,
    onOpen: () -> Unit,
    onKill: () -> Unit,
    onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                else Modifier
            )
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.state == "running" || s.bgShells > 0 || s.bgAgents > 0) {
                    PulsingDot(
                        if (s.state == "attention") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                } else {
                    StateDot(s.state)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    s.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    // "waiting" over a session running four background shells was
                    // a lie of omission — the list is where "looks stalled" started.
                    if (s.state != "running" && (s.bgShells > 0 || s.bgAgents > 0)) "background work"
                    else stateLabel(s.state),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        s.state == "attention" -> MaterialTheme.colorScheme.error
                        s.state == "running" || s.bgShells > 0 || s.bgAgents > 0 ->
                            MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    relTime(s.activityAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Bound to a local first: `s.title` is a public property of another
            // module (:core) now, so the compiler will not smart-cast it inside
            // the null check — the value could in principle change between the
            // test and the read. A local is a snapshot and needs no assertion.
            val title = s.title
            if (!title.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (s.bgShells > 0 || s.bgAgents > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    buildList {
                        s.bgTask?.let { add("⚙ $it") }
                        if (s.bgShells > 1) add("+${s.bgShells - 1} more")
                        if (s.bgAgents > 0) add("${s.bgAgents} agent${if (s.bgAgents == 1) "" else "s"}")
                    }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (s.preview.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                s.preview.takeLast(2).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val meta = buildList {
                if (s.cols > 0) add("${s.cols}x${s.rows}")
                if (s.attachedClients > 0) add("${s.attachedClients} attached")
                if (s.sizeLeased) add("fitted to phone")
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    meta.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Session actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("End session") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menu = false; onKill() },
                )
            }
        }
    }
}
