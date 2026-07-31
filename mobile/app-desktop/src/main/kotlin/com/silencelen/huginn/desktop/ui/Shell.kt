package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.View
import kotlinx.coroutines.launch
import java.awt.Cursor

/**
 * The three-pane shell: nav rail | list pane | detail pane.
 *
 * Desktop is always wide, so there is no fold/rotate gymnastics — just panes and
 * a seam the user can drag. Status and Settings have no list, so they span both
 * columns and the splitter is not drawn: a handle on an edge that is not there is
 * a handle that does nothing.
 */
@Composable
fun Shell(store: AppStore) {
    val view by store.view.collectAsState()
    val chats by store.chats.collectAsState()
    val sessions by store.sessions.collectAsState()
    val loaded by store.listsLoaded.collectAsState()
    val chatId by store.chatId.collectAsState()
    val sessionName by store.sessionName.collectAsState()
    val watchConnected by store.watchConnected.collectAsState()
    val error by store.error.collectAsState()
    val route by store.route.collectAsState()
    val status by store.status.collectAsState()
    val plan by store.plan.collectAsState()
    val usage by store.usage.collectAsState()
    val present by store.presence.present.collectAsState()
    val notifyEnabled by store.settings.notifyEnabled.collectAsState(initial = true)
    val scope = rememberCoroutineScope()

    var listWidth by remember { mutableStateOf(300f) }
    val showsList = view == View.CHATS || view == View.SESSIONS

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavRail(view, watchConnected) { store.openView(it) }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (showsList) {
            Box(Modifier.width(listWidth.dp).fillMaxHeight()) {
                when (view) {
                    View.CHATS -> ChatsList(
                        chats = chats,
                        loaded = loaded,
                        activeId = chatId,
                        onOpen = { store.openChat(it) },
                        onNew = { mode ->
                            scope.launch {
                                runCatching { store.client.createChat(mode) }
                                    .onSuccess { store.openChat(it.id); store.refreshChats() }
                            }
                        },
                    )
                    View.SESSIONS -> SessionsList(sessions, sessionName) { store.openSession(it) }
                    else -> Unit
                }
            }
            Splitter(onDrag = { listWidth = (listWidth + it).coerceIn(200f, 560f) })
        }

        Column(Modifier.fillMaxSize()) {
            error?.let {
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { store.clearError() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.weight(1f))
                    Muted("dismiss")
                }
            }
            when (view) {
                View.CHATS ->
                    if (chatId != null) ChatView(store.client, chatId!!)
                    else Placeholder("Select a chat, or start one.")

                // 3c: the pane-size lease and the prompt cards. The grid's LOGIC
                // is in :core (TerminalGrid) and its PAINTER is now in :ui
                // (TerminalCanvas + SkiaCellPainter, tested against a real skia
                // surface), so what is left here is the lease lifecycle — and a
                // half-built lease is worse than none, it pins tmux geometry.
                View.SESSIONS ->
                    if (sessionName != null) SessionPlaceholder(sessionName!!)
                    else Placeholder("Select a session.")

                View.STATUS -> StatusView(status, plan, usage, route, watchConnected)
                View.SETTINGS -> SettingsView(store.settings, route, present, notifyEnabled)
            }
        }
    }
}

@Composable
private fun NavRail(current: View, watchConnected: Boolean, onSelect: (View) -> Unit) {
    Column(
        Modifier.width(104.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        RailItem("Chats", current == View.CHATS) { onSelect(View.CHATS) }
        RailItem("Sessions", current == View.SESSIONS) { onSelect(View.SESSIONS) }
        RailItem("Status", current == View.STATUS) { onSelect(View.STATUS) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth()
                .background(if (current == View.SETTINGS) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .clickable { onSelect(View.SETTINGS) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The one liveness mark in the frame, and it earns its place: the
            // watch stream being attached is the difference between "quiet" and
            // "not listening", and nothing else on screen distinguishes them.
            StateDot(if (watchConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Text("Settings", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A rail row. Selection is a surface tint and weight, NOT a left accent bar —
 * house rule, and the reason is that an accent bar is the single most legible
 * tell of a generated interface.
 */
@Composable
private fun RailItem(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The draggable seam. Four pixels of hit area over a one-pixel line. */
@Composable
private fun Splitter(onDrag: (Float) -> Unit) {
    Box(
        Modifier.width(5.dp).fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onDrag(it) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Muted(text) }
}

@Composable
private fun SessionPlaceholder(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Muted("terminal view lands in phase 3c", Modifier.padding(top = 6.dp))
        }
    }
}
