package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.Round

/**
 * Writing or editing a Round, full screen.
 *
 * Full screen rather than a dialog because this is a form with seven decisions in
 * it, and a sheet that covers half a phone turns the last two into scrolling
 * inside scrolling. The form itself is [RoundEditor], shared with the desktop.
 */
@Composable
fun RoundEditScreen(
    existing: Round?,
    devices: List<Device>,
    /** Seeds a NEW round's clock. An existing one keeps its own — see RoundDraft.tz. */
    deviceTz: String?,
    onCreate: (RoundDraft, (String?) -> Unit) -> Unit,
    onSave: (String, RoundDraft, (String?) -> Unit) -> Unit,
    onDelete: (String, (String?) -> Unit) -> Unit,
    onDone: () -> Unit,
) {
    // Keyed on the Round's id, so opening a different one starts from ITS values
    // rather than the last one's — and so a poll landing mid-edit does not reset
    // the form under somebody's hands.
    var draft by remember(existing?.id) { mutableStateOf(existing?.toDraft() ?: RoundDraft(tz = deviceTz)) }
    var saving by remember(existing?.id) { mutableStateOf(false) }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        RoundEditor(
            draft = draft,
            onDraft = {
                draft = it
                // Cleared on the next keystroke: a server error about the value
                // you have just changed is stale, and leaving it there makes a
                // fixed form look broken.
                error = null
            },
            devices = devices,
            saving = saving,
            error = error,
            editing = existing != null,
            onSave = {
                saving = true
                val done: (String?) -> Unit = { err ->
                    saving = false
                    error = err
                    if (err == null) onDone()
                }
                if (existing == null) onCreate(draft, done) else onSave(existing.id, draft, done)
            },
            onCancel = onDone,
            onDelete = existing?.let { { confirmDelete = true } },
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.title}?") },
            // The distinction that matters: the schedule stops, the history stays.
            // Somebody deleting a Round is stopping a job, not asking to lose the
            // reports it already wrote.
            text = { Text("It stops running. The reports it has already written stay where they are, as chats.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saving = true
                    onDelete(existing.id) { err ->
                        saving = false
                        error = err
                        if (err == null) onDone()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
