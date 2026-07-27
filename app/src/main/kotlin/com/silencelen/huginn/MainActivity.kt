package com.silencelen.huginn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silencelen.huginn.ui.ChatScreen
import com.silencelen.huginn.ui.ChatsScreen
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.SessionsScreen
import com.silencelen.huginn.ui.SettingsScreen
import com.silencelen.huginn.ui.StatusScreen
import com.silencelen.huginn.ui.TerminalScreen
import com.silencelen.huginn.ui.theme.HuginnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HuginnTheme { HuginnApp() }
        }
    }
}

/**
 * Navigation is a small sealed hierarchy held in saveable state rather than
 * navigation-compose: there are five destinations and two of them carry a single
 * string argument, so a nav graph would be more machinery than routing.
 */
private sealed interface Dest {
    data object Chats : Dest
    data class Chat(val id: String) : Dest
    data object Sessions : Dest
    data class Terminal(val name: String) : Dest
    data object Status : Dest
    data object Settings : Dest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuginnApp(vm: HuginnViewModel = viewModel(factory = HuginnViewModel.Factory)) {
    // Tab identity survives configuration changes; the argument-carrying
    // destinations are transient by design (reopening lands you on the tab).
    var tab by rememberSaveable { mutableStateOf(0) }
    var dest by remember { mutableStateOf<Dest>(Dest.Chats) }

    val chats by vm.chats.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val status by vm.status.collectAsState()
    val statusError by vm.statusError.collectAsState()
    val loading by vm.loading.collectAsState()
    val connected by vm.connected.collectAsState()
    val toast by vm.toast.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()
    val token by vm.token.collectAsState()
    val chatDetail by vm.chatDetail.collectAsState()
    val streamingText by vm.streamingText.collectAsState()
    val activeTool by vm.activeTool.collectAsState()
    val sending by vm.sending.collectAsState()
    val screen by vm.screen.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); vm.toastShown() }
    }

    val onTab: (Int) -> Unit = { i ->
        tab = i
        dest = when (i) {
            0 -> Dest.Chats
            1 -> Dest.Sessions
            else -> Dest.Status
        }
        vm.refreshAll()
    }

    val title = when (val d = dest) {
        is Dest.Chats -> "Huginn"
        is Dest.Chat -> chatDetail?.title ?: "Chat"
        is Dest.Sessions -> "Sessions"
        is Dest.Terminal -> d.name
        is Dest.Status -> "Status"
        is Dest.Settings -> "Settings"
    }
    val isChild = dest is Dest.Chat || dest is Dest.Terminal || dest is Dest.Settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = if (dest is Dest.Terminal) FontFamily.Monospace else null,
                    )
                },
                navigationIcon = {
                    if (isChild) {
                        IconButton(onClick = {
                            dest = when (dest) {
                                is Dest.Terminal -> Dest.Sessions
                                is Dest.Chat -> Dest.Chats
                                else -> when (tab) {
                                    0 -> Dest.Chats
                                    1 -> Dest.Sessions
                                    else -> Dest.Status
                                }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (dest !is Dest.Chat && dest !is Dest.Terminal) {
                        IconButton(onClick = { vm.refreshAll() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    if (dest !is Dest.Settings) {
                        IconButton(onClick = { dest = Dest.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        bottomBar = {
            // The terminal and chat composers own the bottom edge; a nav bar there
            // would sit under the keyboard and steal the send button's reach.
            if (!isChild) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { onTab(0) },
                        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                        label = { Text("Chats") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { onTab(1) },
                        icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        label = { Text("Sessions") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { onTab(2) },
                        icon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                        label = { Text("Status") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val d = dest) {
                is Dest.Chats -> ChatsScreen(
                    chats = chats,
                    loading = loading,
                    connected = connected,
                    onOpen = { id -> vm.openChat(id); dest = Dest.Chat(id) },
                    onNew = { mode -> vm.newChat(mode) { id -> vm.openChat(id); dest = Dest.Chat(id) } },
                    onDelete = { vm.deleteChat(it) },
                    onOpenSettings = { dest = Dest.Settings },
                )

                is Dest.Chat -> {
                    // Leaving the screen detaches the SSE stream but never cancels the
                    // server-side run: a locked phone must not kill a turn in progress.
                    DisposableEffect(d.id) { onDispose { vm.detachStream() } }
                    ChatScreen(
                        detail = chatDetail,
                        streamingText = streamingText,
                        activeTool = activeTool,
                        sending = sending,
                        onSend = { vm.send(d.id, it) },
                        onCancel = { vm.cancel(d.id) },
                    )
                }

                is Dest.Sessions -> SessionsScreen(
                    sessions = sessions,
                    onOpen = { name -> dest = Dest.Terminal(name) },
                    onCreate = { name -> vm.createSession(name) { dest = Dest.Terminal(it) } },
                    onKill = { vm.killSession(it) },
                )

                is Dest.Terminal -> {
                    DisposableEffect(d.name) {
                        vm.startScreenPolling(d.name)
                        onDispose { vm.stopScreenPolling(); vm.refreshSessions() }
                    }
                    TerminalScreen(
                        session = d.name,
                        screen = screen,
                        onSendText = { text, enter -> vm.sendText(d.name, text, enter) },
                        onSendKeys = { vm.sendKeys(d.name, it) },
                    )
                }

                is Dest.Status -> StatusScreen(
                    status = status,
                    error = statusError,
                    sessions = sessions.size,
                    chatsRunning = chats.count { it.running },
                )

                is Dest.Settings -> SettingsScreen(
                    baseUrl = baseUrl,
                    token = token,
                    connected = connected,
                    onSave = { u, t -> vm.saveSettings(u, t) },
                    onTest = { vm.testConnection() },
                )
            }
        }
    }
}
