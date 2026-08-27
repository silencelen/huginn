package com.silencelen.huginn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session

/**
 * Where something should land: a new chat, a running session, or a chat that
 * already exists.
 *
 * It began as the phone's share-sheet body and is shared because the QUESTION is
 * the same wherever it is asked — a screenshot of an error belongs in the session
 * already working on that error, and so does a page of notes. Only the frame
 * around it differs: a thumb gets a bottom sheet, a pointer gets a dialog, and
 * neither of those is this composable's business.
 *
 * The staging contract every caller obeys: APPEND to the target's draft, never
 * clobber it — a half-typed message outranks anything being sent to it — navigate
 * there, and never send. Dismissing drops the whole thing, deliberately: the
 * person was shown every destination and declined them all, and materialising it
 * somewhere anyway turns "no" into "surprise".
 */
@Composable
fun SendTargetList(
    sessions: List<Session>,
    chats: List<Chat>,
    title: String,
    onNewChat: (() -> Unit)?,
    onSession: (String) -> Unit,
    onChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** How many chats are worth listing. The list is recency-ordered already. */
    chatLimit: Int = 6,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        // A new chat is the safe default and stays first — but it is optional,
        // because a page being sent from inside a conversation already has a
        // destination in mind and a fresh chat is the one thing it is not.
        if (onNewChat != null) {
            TargetRow(
                icon = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                title = "New chat",
                subtitle = "Start a conversation about it",
                onClick = onNewChat,
            )
        }

        if (sessions.isNotEmpty()) {
            SectionLabel("Sessions")
            sessions.forEach { s ->
                TargetRow(
                    icon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SessionMark(s.state)
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

        val recent = chats.take(chatLimit)
        if (recent.isNotEmpty()) {
            SectionLabel("Chats")
            recent.forEach { c ->
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

/**
 * The thumb's frame around [SendTargetList]: a bottom sheet, which is where a
 * destination picker belongs on a phone and nowhere near where it belongs on a
 * desktop — hence two frames and one body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendTargetSheet(
    sessions: List<Session>,
    chats: List<Chat>,
    title: String,
    onNewChat: (() -> Unit)?,
    onSession: (String) -> Unit,
    onChat: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SendTargetList(
            sessions = sessions,
            chats = chats,
            title = title,
            onNewChat = onNewChat,
            onSession = onSession,
            onChat = onChat,
            modifier = Modifier.navigationBarsPadding().padding(bottom = 18.dp),
        )
    }
}

/**
 * The same list, capped so a long one scrolls inside a dialog rather than growing
 * one past the bottom of the window.
 */
@Composable
fun SendTargetPanel(
    sessions: List<Session>,
    chats: List<Chat>,
    title: String,
    onNewChat: (() -> Unit)?,
    onSession: (String) -> Unit,
    onChat: (String) -> Unit,
    maxHeight: Dp = 420.dp,
) {
    SendTargetList(
        sessions = sessions,
        chats = chats,
        title = title,
        onNewChat = onNewChat,
        onSession = onSession,
        onChat = onChat,
        modifier = Modifier.heightIn(max = maxHeight),
    )
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

/**
 * The session's state, as the same dot both clients already use for it. Drawn
 * here rather than taken as a parameter so the sheet cannot end up with a second
 * vocabulary for a state the rest of the app already has a mark for.
 */
@Composable
private fun SessionMark(state: String?) {
    val color = when (state) {
        "running" -> MaterialTheme.colorScheme.primary
        "attention" -> MaterialTheme.colorScheme.error
        else -> Color.Transparent
    }
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp).clip(CircleShape)) {}
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
