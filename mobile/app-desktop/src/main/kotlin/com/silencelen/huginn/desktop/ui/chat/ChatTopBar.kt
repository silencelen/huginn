package com.silencelen.huginn.desktop.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.desktop.ui.Muted
import com.silencelen.huginn.desktop.ui.StateDot

/**
 * The chat's own bar: what it is called, what it is doing, what the next turn
 * will run with, and the two things that can be done TO it.
 *
 * One row rather than the phone's two, because the pane is wide and a second bar
 * of chrome on a 1280pt window is chrome for its own sake. Everything in it is
 * either the chat's name or a control — the state marks are a small dot and a
 * muted suffix in the title's own line, not badges over it.
 */
@Composable
fun ChatTopBar(
    title: String,
    running: Boolean,
    activity: String?,
    mode: String?,
    model: String?,
    effort: String?,
    models: List<ModelChoice>,
    optionsEnabled: Boolean,
    started: Boolean,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onMode: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (running) StateDot(MaterialTheme.colorScheme.primary)
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            activity?.let { Muted("  $it…", Modifier.padding(start = 4.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        ChatOptionsRow(
            mode = mode,
            model = model,
            effort = effort,
            models = models,
            enabled = optionsEnabled,
            started = started,
            onModel = onModel,
            onEffort = onEffort,
            onMode = onMode,
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Chat actions",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename", style = MaterialTheme.typography.bodySmall) },
                    onClick = { menuOpen = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text("Delete", style = MaterialTheme.typography.bodySmall) },
                    onClick = { menuOpen = false; confirmDelete = true },
                )
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; onRename(text) }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this chat?") },
            // The honest sentence, and the second half matters: people hesitate
            // over a delete because they cannot tell what else goes with it.
            text = { Text("Removes it from huginn. The underlying transcript file stays on the host.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
