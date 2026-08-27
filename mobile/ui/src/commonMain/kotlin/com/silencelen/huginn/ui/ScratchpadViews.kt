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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.ScratchpadSaver

/**
 * The user's own pages: the list of them, the editor, and the composer control
 * that attaches one to a message.
 *
 * All three are shared because a page is the same object under a thumb and under
 * a mouse — plain text, one field, saved as you type. The only thing the shells
 * own is WHERE these are put: the phone gives the editor a whole screen, the
 * desktop gives it a 360dp panel beside the conversation and a full view of its
 * own, and both hand it the same composables.
 *
 * Plain text, deliberately, and there is no markdown preview toggle. A page is
 * somewhere to put a thought mid-sentence; a rendered view of one is a second
 * mode to be in, and the thing being written is usually not a document.
 */

/** The pages, most recently edited first, with Main pinned at the top. */
@Composable
fun ScratchpadListView(
    pads: List<Scratchpad>,
    selectedId: String?,
    nowMs: Long,
    onOpen: (Scratchpad) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Scratchpad) -> Unit,
    modifier: Modifier = Modifier,
) {
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Scratchpad?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Pages", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { naming = true }) { Text("New page") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (pads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No pages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(pads, key = { it.id }) { pad ->
                PadRow(
                    pad = pad,
                    selected = pad.id == selectedId,
                    nowMs = nowMs,
                    onOpen = { onOpen(pad) },
                    // Main has no delete. It is what a reference falls back to, and
                    // an offer that always refuses is worse than no offer.
                    onDelete = if (pad.main) null else ({ deleting = pad }),
                )
            }
        }
    }

    if (naming) {
        NewPadDialog(
            taken = pads.map { it.name },
            onDismiss = { naming = false },
            onConfirm = { name -> naming = false; onCreate(name) },
        )
    }
    deleting?.let { pad ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${pad.name}\"?") },
            text = { Text("The page and everything written on it go. Nothing else is affected.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDelete(pad) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PadRow(
    pad: Scratchpad,
    selected: Boolean,
    nowMs: Long,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Selection is a surface wash, never a left accent bar — house rule.
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                pad.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                padSubtitle(pad, nowMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/**
 * The one line under a page's name.
 *
 * An EMPTY page says so rather than saying "0 characters": whether there is
 * anything on it is the question a picker is actually being asked, and a count of
 * nothing answers it in the least direct way available.
 */
fun padSubtitle(pad: Scratchpad, nowMs: Long): String {
    val size = if (pad.size <= 0) "empty" else "${pad.size} characters"
    val ago = agoWords(pad.updatedAt, nowMs)
    return listOf(size, ago).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun NewPadDialog(taken: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    // Checked as you type rather than on submit: the daemon decides, but hearing
    // "there is already a page with that name" before pressing anything is the
    // difference between a form and a round trip.
    val problem = if (name.isBlank()) null else ScratchpadRules.nameProblem(name, taken)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New page") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = problem != null,
                    placeholder = { Text("What is it for") },
                )
                problem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && problem == null,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------- editor

/**
 * One page, filling whatever it is given.
 *
 * @param onSendHere the direct send, when the surface already knows where — the
 *   desktop's side panel sits beside one conversation, so asking it which one is
 *   a question with a known answer. Null everywhere else.
 * @param onSendElsewhere opens the destination picker. Both are offered where
 *   both make sense; the general one is the overflow, because the specific one is
 *   right nearly every time the panel is open.
 */
@Composable
fun ScratchpadEditorView(
    pad: Scratchpad?,
    pads: List<Scratchpad>,
    state: ScratchpadSaver.State,
    note: String?,
    onEdit: (String) -> Unit,
    onSwitch: (Scratchpad) -> Unit,
    onDismissNote: () -> Unit,
    modifier: Modifier = Modifier,
    sendHereLabel: String = "Send here",
    onSendHere: (() -> Unit)? = null,
    onSendElsewhere: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
) {
    if (pad == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No page open",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var renaming by remember(pad.id) { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PadSwitcher(pads = pads, current = pad, onSwitch = onSwitch, modifier = Modifier.weight(1f))
            // Main cannot be renamed — it is the name every client calls the
            // fallback by — so the control is absent rather than refusing.
            if (onRename != null && !pad.main) {
                TextButton(onClick = { renaming = true }) { Text("Rename") }
            }
        }
        OutlinedTextField(
            value = pad.content,
            onValueChange = onEdit,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text("Anything worth keeping") },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                saveWords(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            onSendElsewhere?.let { TextButton(onClick = it) { Text("Send to…") } }
            onSendHere?.let { TextButton(onClick = it) { Text(sendHereLabel) } }
        }
        note?.let {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissNote) { Text("ok") }
            }
        }
    }

    if (renaming && onRename != null) {
        RenamePadDialog(
            current = pad.name,
            taken = pads.filter { it.id != pad.id }.map { it.name },
            onDismiss = { renaming = false },
            onConfirm = { next -> renaming = false; onRename(next) },
        )
    }
}

/**
 * The state line under the editor.
 *
 * Quiet by design and never a spinner: this is the only feedback there is, and
 * the whole point of an autosave is that the person can stop thinking about it.
 * IDLE says nothing at all — a page just opened has nothing to report, and
 * "Saved" over text nobody has touched is a claim about work that never happened.
 */
fun saveWords(state: ScratchpadSaver.State): String = when (state) {
    ScratchpadSaver.State.IDLE -> ""
    ScratchpadSaver.State.PENDING -> "Editing…"
    ScratchpadSaver.State.SAVING -> "Saving…"
    ScratchpadSaver.State.SAVED -> "Saved"
    ScratchpadSaver.State.FAILED -> "Not saved"
}

@Composable
private fun PadSwitcher(
    pads: List<Scratchpad>,
    current: Scratchpad,
    onSwitch: (Scratchpad) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clickable { open = true }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.EditNote,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                current.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            pads.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (p.id == current.id) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { open = false; if (p.id != current.id) onSwitch(p) },
                )
            }
        }
    }
}

@Composable
private fun RenamePadDialog(
    current: String,
    taken: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    val problem = if (name == current) null else ScratchpadRules.nameProblem(name, taken)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename page") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = problem != null,
                )
                problem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && problem == null && name != current,
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// -------------------------------------------------------------- the composer

/**
 * The composer's reference control: which page, if any, rides along with the next
 * message.
 *
 * ONE control with one verb, not a button plus a chip. Empty it invites, filled
 * it names the page and offers the ✕ — because "attach a page" and "which page is
 * attached" are the same question asked at two moments, and two controls for that
 * is a row of chrome above every composer for the sake of a feature used
 * occasionally.
 *
 * Quiet on purpose: this sits above a text field somebody is writing in, and the
 * message is the thing on that screen worth looking at.
 */
@Composable
fun ScratchpadChip(
    pads: List<Scratchpad>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = pads.firstOrNull { it.id == selectedId }
    Box(modifier) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                Modifier.clickable { open = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    chosen?.name ?: "Attach a page",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // A long page name must not push Send off a narrow composer.
                    modifier = Modifier.widthIn(max = 132.dp),
                )
                if (chosen != null) {
                    IconButton(
                        onClick = { onSelect(null) },
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Send without a page",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (pads.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No pages yet", style = MaterialTheme.typography.bodyMedium) },
                    enabled = false,
                    onClick = {},
                )
            }
            pads.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (p.id == selectedId) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { open = false; onSelect(p.id) },
                )
            }
            if (chosen != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text("No page", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false; onSelect(null) },
                )
            }
        }
    }
}
