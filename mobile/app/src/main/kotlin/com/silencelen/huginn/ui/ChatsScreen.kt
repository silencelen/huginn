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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Device

/**
 * Home surface: every headless conversation with huginn, newest first. "Ask" runs
 * read-only with memory (the CLI's `huginn -p`); "Act" gets tools (`huginn -y`).
 * Both are offered at creation because the choice changes what the turn may do,
 * and it is not something to bury in a settings screen.
 */
@Composable
fun ChatsScreen(
    chats: List<Chat>,
    /** Machines a chat can be started on. Empty is the ordinary case. */
    devices: List<Device> = emptyList(),
    loading: Boolean,
    connected: Boolean?,
    selectedId: String? = null,
    onOpen: (String) -> Unit,
    /** (mode, host) — host is null for this machine. */
    onNew: (String, String?) -> Unit,
    /** The dialog is opening: a good moment to find out what machines exist. */
    onOpenNewChat: () -> Unit = {},
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * The home-screen widget's quick chat: a value that CHANGED asks this screen
     * to open the new-chat question, exactly as tapping the button would. A seq
     * rather than a flag so a second widget tap re-asks after the first was
     * dismissed; 0 means nobody asked.
     */
    newChatRequest: Int = 0,
) {
    var showNew by remember { mutableStateOf(false) }
    // Null = this host. Reset whenever the dialog opens, so a machine picked once
    // does not silently become the default for every chat afterwards.
    var newHost by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(newChatRequest) { if (newChatRequest > 0) showNew = true }
    LaunchedEffect(showNew) { if (showNew) { newHost = null; onOpenNewChat() } }

    Box(Modifier.fillMaxSize()) {
        if (chats.isEmpty() && !loading) {
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
                    EmptyState("No chats yet", "Start one and huginn answers from the host.")
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                items(chats, key = { it.id }) { chat ->
                    ChatRow(
                        chat,
                        selected = chat.id == selectedId,
                        onOpen = { onOpen(chat.id) },
                        onDelete = { onDelete(chat.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showNew = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New chat") },
        )
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New chat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Ask: reasoning and memory, no tools. Act: also reads and edits files, runs commands, fetches the web.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Only when there is a choice to make. With nothing enrolled,
                    // "where" is not a question and asking it would be noise on
                    // the one dialog every chat goes through.
                    if (devices.isNotEmpty()) {
                        Text(
                            "WHERE IT RUNS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        HostChoice(
                            label = "This host",
                            detail = "huginn",
                            selected = newHost == null,
                            enabled = true,
                            onClick = { newHost = null },
                        )
                        devices.forEach { d ->
                            HostChoice(
                                label = d.name,
                                // The reason it cannot be picked, not just that it
                                // cannot: "asleep" and "read-only" need different
                                // actions from whoever is reading.
                                detail = when {
                                    !d.online -> "not reachable"
                                    d.locked -> "locked \u2014 Ask only"
                                    else -> "${d.platform} \u00b7 ${d.effectiveScope}"
                                },
                                selected = newHost == d.id,
                                enabled = d.online,
                                onClick = { newHost = d.id },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                // Act is disabled for a machine that will only Look. The daemon
                // would refuse anyway; refusing here means the answer arrives
                // before the tap rather than after it.
                val actOk = newHost == null ||
                    devices.firstOrNull { it.id == newHost }?.effectiveScope != "look"
                TextButton(
                    onClick = { showNew = false; onNew("act", newHost) },
                    enabled = actOk,
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Act")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNew = false; onNew("ask", newHost) }) {
                    Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ask")
                }
            },
        )
    }
}


/**
 * One machine in the new-chat dialog.
 *
 * A radio rather than a dropdown: the list is short, and where something runs is
 * worth seeing all of at once rather than behind a tap. Unreachable machines stay
 * VISIBLE but unselectable, with the reason — hiding them would leave the reader
 * wondering whether a device they enrolled had disappeared.
 */
@Composable
private fun HostChoice(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One chat's state, as a colour a glance can read: working (pulsing), waiting
 * with queued messages (amber), or settled (quiet). The word next to the dot
 * carries the same fact for anyone who does not carry the colour code around.
 */
@Composable
private fun ChatRow(chat: Chat, selected: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.running) {
                    PulsingDot(MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(7.dp))
                } else if (chat.pending > 0) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    chat.title ?: "Untitled",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Where it runs, when that is not here. Before the act bolt, because
                // "this is happening on another machine" is the bigger fact about a
                // row than which tools it holds.
                if (chat.host != null && chat.host != "local") {
                    Spacer(Modifier.width(6.dp))
                    HostBadge(chat.host, chat.hostName)
                }
                if (chat.mode == "act") {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "tools enabled",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                chat.lastSnippet ?: "No messages yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            when {
                chat.running -> {
                    Text(
                        "working",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (chat.pending > 0) {
                        Text(
                            "+${chat.pending} queued",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                chat.pending > 0 -> Text(
                    "+${chat.pending} queued",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                else -> Text(
                    relTime(chat.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Chat actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
