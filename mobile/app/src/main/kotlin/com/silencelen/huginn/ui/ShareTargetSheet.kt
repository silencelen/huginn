package com.silencelen.huginn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session

/**
 * Where a share should land.
 *
 * Exists because "always a new chat" was wrong exactly when sharing is most
 * useful: the screenshot of an error belongs in the session already working on
 * that error, with all its context, not in a fresh conversation that has none.
 * A new chat stays the first row — the safe default for a link from the browser
 * — but sessions come immediately after, because that is the high-value case.
 *
 * Dismissing the sheet drops the share. Deliberate: the user was just shown
 * every place it could go and declined them all, and materialising it somewhere
 * anyway would turn "no" into "surprise".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetSheet(
    sessions: List<Session>,
    chats: List<Chat>,
    sharingImage: Boolean,
    onNewChat: () -> Unit,
    onSession: (String) -> Unit,
    onChat: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        ) {
            Text(
                if (sharingImage) "Share photo to" else "Share to",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            TargetRow(
                icon = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                title = "New chat",
                subtitle = "Start a conversation about it",
                onClick = onNewChat,
            )

            if (sessions.isNotEmpty()) {
                SectionLabel("Sessions")
                sessions.forEach { s ->
                    TargetRow(
                        icon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StateDot(s.state)
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Filled.Terminal, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                        title = s.name,
                        subtitle = s.title ?: s.state ?: "session",
                        onClick = { onSession(s.name) },
                    )
                }
            }

            val recentChats = chats.take(6)
            if (recentChats.isNotEmpty()) {
                SectionLabel("Chats")
                recentChats.forEach { c ->
                    TargetRow(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        title = c.title ?: "Untitled chat",
                        subtitle = c.lastSnippet ?: "",
                        onClick = { onChat(c.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun TargetRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
