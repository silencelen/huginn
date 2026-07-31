package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session

/**
 * The list pane, both flavours.
 *
 * ROW SHAPE, and it is a house rule rather than a preference: no left accent bar
 * on a row or a card. Selection is a surface tint, state is a small dot in the
 * text flow, and a count is a muted suffix — marks that live in the row's own
 * vernacular rather than a badge shouting over it.
 */
@Composable
fun ChatsList(
    chats: List<Chat>,
    loaded: Boolean,
    activeId: String?,
    onOpen: (String) -> Unit,
    onNew: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ListHeader("Chats") {
            TextButton(onClick = { onNew("ask") }) { Text("+ Ask") }
            TextButton(onClick = { onNew("act") }) { Text("+ Act") }
        }
        if (chats.isEmpty()) {
            Empty(if (loaded) "No chats yet. Ask answers questions; Act can change things on the host." else "Loading chats…")
            return@Column
        }
        val state = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(chats, key = { it.id }) { chat ->
                ChatRow(chat, chat.id == activeId) { onOpen(chat.id) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (chat.running) StateDot(MaterialTheme.colorScheme.primary)
            Text(
                chat.title ?: "Untitled",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (chat.mode == "act") Muted("act", Modifier.padding(start = 6.dp))
            Muted(relTime(chat.updatedAt), Modifier.padding(start = 8.dp))
        }
        val snippet = chat.lastSnippet
        if (chat.pending > 0 || !snippet.isNullOrBlank()) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (chat.pending > 0) Muted("+${chat.pending} queued  ")
                if (!snippet.isNullOrBlank()) {
                    Muted(snippet.replace('\n', ' '), Modifier.weight(1f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SessionsList(sessions: List<Session>, activeName: String?, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ListHeader("Sessions") {}
        if (sessions.isEmpty()) {
            Empty("No tmux sessions.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
            items(sessions, key = { it.name }) { s ->
                SessionRow(s, s.name == activeName) { onOpen(s.name) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SessionRow(session: Session, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dot = when (session.state) {
        "running" -> scheme.primary
        "attention" -> scheme.error
        else -> null
    }
    Column(
        Modifier.fillMaxWidth()
            .background(if (active) scheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            dot?.let { StateDot(it) }
            Text(
                session.title ?: session.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Muted(relTime(session.activityAt), Modifier.padding(start = 8.dp))
        }
        Row(Modifier.padding(top = 2.dp)) {
            Muted(session.name, Modifier.padding(end = 8.dp))
            if (session.bgShells > 0) Muted("${session.bgShells} bg  ")
            session.preview.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Muted(it, Modifier.weight(1f), maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun ListHeader(title: String, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row { actions() }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
        Muted(text)
    }
}

/** Working. A dot inside the row's text flow — not a bar, not a badge. */
@Composable
fun StateDot(color: Color) {
    Box(Modifier.padding(end = 6.dp).size(7.dp).clip(CircleShape).background(color))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Epoch SECONDS, as the daemon reports every timestamp on a list row. */
fun relTime(epochSec: Long): String {
    if (epochSec <= 0) return ""
    val s = (System.currentTimeMillis() / 1000 - epochSec).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}
