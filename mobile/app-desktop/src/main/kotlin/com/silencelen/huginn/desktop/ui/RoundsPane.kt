package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.PolishResult
import com.silencelen.huginn.data.Round
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.ui.RoundDraft
import com.silencelen.huginn.ui.RoundEditor
import com.silencelen.huginn.ui.RoundsSection
import com.silencelen.huginn.ui.toDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Rounds destination: every scheduled job, what it last found, and now the
 * ability to write one — until this release a Round could only be made with curl.
 *
 * Full width rather than list-and-detail, because a Round row already carries its
 * whole report. Opening one means reading the run behind it, which is a chat, so
 * it hands off to the Chats destination instead of growing a second reader here.
 * The editor REPLACES the list rather than floating over it: it is a form with
 * seven decisions in it, and a dialog would put half of them behind a scrollbar
 * inside a scrollbar.
 */
@Composable
fun RoundsPane(store: AppStore) {
    val rounds by store.rounds.collectAsState()
    val devices by store.devices.collectAsState()
    val scope = rememberCoroutineScope()

    // null = the list. Present = the editor, with the Round it is editing, or a
    // fresh draft for a new one.
    var editing by remember { mutableStateOf<Round?>(null) }
    var writingNew by remember { mutableStateOf(false) }

    if (editing != null || writingNew) {
        val target = editing
        RoundEditorPane(
            existing = target,
            devices = devices,
            deviceTz = store.deviceZone(),
            onCreate = { d -> store.createRound(d) },
            onSave = { id, d -> store.saveRound(id, d) },
            onDelete = { id -> store.deleteRound(id) },
            onPolish = { d, f -> store.polishRound(d, f) },
            onDone = { editing = null; writingNew = false },
        )
        return
    }

    // A ticking clock, because this pane can sit open for hours and its rows are
    // all relative times. Thirty seconds: the rows round to minutes at the finest,
    // so anything faster would recompose to redraw identical text.
    var nowMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000)
        }
    }

    if (rounds.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No rounds yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "A round is work this host does on a schedule and reports back on — " +
                    "a weekly alert review, a nightly check.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            Button(onClick = { writingNew = true }) { Text("New round") }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = { writingNew = true }) { Text("New round") }
        }
        RoundsSection(
            rounds = rounds,
            nowMs = nowMs,
            // The rail already says "Rounds"; repeating it here would be the same
            // word in adjacent columns, which is the thing the rail lost its labels over.
            header = null,
            onOpenRound = { r ->
                // The report if there is one; otherwise the Round itself, so a
                // schedule that has not fired yet does not swallow the click.
                val chat = r.lastRun?.chatId
                if (chat != null) store.openChat(chat) else editing = r
            },
            onRunNow = { r -> scope.launch { store.runRound(r.id) } },
            onSetEnabled = { r, on -> scope.launch { store.setRoundEnabled(r.id, on) } },
            onAcknowledge = { r, ack -> scope.launch { store.acknowledgeRound(r.id, ack) } },
            // Same reason as the phone: the editor needs a device list to draw
            // where-it-runs, and a stale empty one hides it for every Round.
            onEdit = { r -> scope.launch { store.refreshDevices() }; editing = r },
        )
    }
}

@Composable
private fun RoundEditorPane(
    existing: Round?,
    devices: List<com.silencelen.huginn.data.Device>,
    deviceTz: String?,
    onCreate: suspend (RoundDraft) -> String?,
    onSave: suspend (String, RoundDraft) -> String?,
    onDelete: suspend (String) -> String?,
    onPolish: suspend (RoundDraft, String) -> PolishResult,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(existing?.id) { mutableStateOf(existing?.toDraft() ?: RoundDraft(tz = deviceTz)) }
    var saving by remember(existing?.id) { mutableStateOf(false) }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    RoundEditor(
        draft = draft,
        // Cleared on the next keystroke: a server error about the value you have
        // just changed is stale, and leaving it makes a fixed form look broken.
        onDraft = { draft = it; error = null },
        devices = devices,
        saving = saving,
        error = error,
        editing = existing != null,
        onSave = {
            saving = true
            scope.launch {
                val err = if (existing == null) onCreate(draft) else onSave(existing.id, draft)
                saving = false
                error = err
                if (err == null) onDone()
            }
        },
        onCancel = onDone,
        onDelete = existing?.let { { confirmDelete = true } },
        // The shared editor is callback-shaped so it can be used from a plain
        // composable; the store's side is suspend. The launch is the whole
        // adaptation, exactly as onSave does it above.
        onPolish = { d, f, done -> scope.launch { done(onPolish(d, f)) } },
    )

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.title}?") },
            text = { Text("It stops running. The reports it has already written stay where they are, as chats.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saving = true
                    scope.launch {
                        val err = onDelete(existing.id)
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
