package com.silencelen.huginn

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.ChatScreen
import com.silencelen.huginn.ui.ChatsScreen
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.SessionScreen
import com.silencelen.huginn.ui.SessionSubtitle
import com.silencelen.huginn.ui.SessionsScreen
import com.silencelen.huginn.ui.SettingsScreen
import com.silencelen.huginn.ui.StatusScreen
import com.silencelen.huginn.ui.theme.HuginnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Tapping a "needs you" notification opens straight into that session.
        val openSession = intent?.getStringExtra(SessionWatchWorker.EXTRA_SESSION)
        setContent {
            HuginnTheme { HuginnApp(openSession = openSession) }
        }
    }
}

private sealed interface Dest {
    data object Chats : Dest
    data class Chat(val id: String) : Dest
    data object Sessions : Dest
    data class SessionView(val name: String) : Dest
    data object Status : Dest
    data object Settings : Dest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuginnApp(
    openSession: String? = null,
    vm: HuginnViewModel = viewModel(factory = HuginnViewModel.Factory),
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var dest by remember {
        mutableStateOf<Dest>(if (openSession != null) Dest.SessionView(openSession) else Dest.Chats)
    }
    var sessionTab by rememberSaveable { mutableStateOf(0) }

    val chats by vm.chats.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val status by vm.status.collectAsState()
    val statusError by vm.statusError.collectAsState()
    val loading by vm.loading.collectAsState()
    val connected by vm.connected.collectAsState()
    val toast by vm.toast.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()
    val token by vm.token.collectAsState()
    val fontScale by vm.fontScale.collectAsState()
    val notifyEnabled by vm.notifyEnabled.collectAsState()
    val chatPage by vm.chatPage.collectAsState()
    val chatMode by vm.chatMode.collectAsState()
    val chatTitle by vm.chatTitle.collectAsState()
    val streamingText by vm.streamingText.collectAsState()
    val activeTool by vm.activeTool.collectAsState()
    val sending by vm.sending.collectAsState()
    val screen by vm.screen.collectAsState()
    val transcript by vm.transcript.collectAsState()
    val transcriptError by vm.transcriptError.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* declined simply means no notifications; nothing else changes */ }
    LaunchedEffect(notifyEnabled) {
        if (notifyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    val isChild = dest is Dest.Chat || dest is Dest.SessionView || dest is Dest.Settings
    val title = when (val d = dest) {
        is Dest.Chats -> "Huginn"
        is Dest.Chat -> chatTitle ?: "Chat"
        is Dest.Sessions -> "Sessions"
        is Dest.SessionView -> transcript?.title ?: d.name
        is Dest.Status -> "Status"
        is Dest.Settings -> "Settings"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = if (dest is Dest.SessionView && transcript?.title == null) FontFamily.Monospace else null,
                        )
                        if (dest is Dest.SessionView) SessionSubtitle(transcript, screen)
                    }
                },
                navigationIcon = {
                    if (isChild) {
                        IconButton(onClick = {
                            dest = when (dest) {
                                is Dest.SessionView -> Dest.Sessions
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
                    if (dest !is Dest.Chat && dest !is Dest.SessionView) {
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
                    DisposableEffect(d.id) { onDispose { vm.detachStream() } }
                    ChatScreen(
                        page = chatPage,
                        streamingText = streamingText,
                        activeTool = activeTool,
                        sending = sending,
                        mode = chatMode,
                        onSend = { vm.send(d.id, it) },
                        onCancel = { vm.cancel(d.id) },
                        onCopy = { vm.copy(it) },
                    )
                }

                is Dest.Sessions -> SessionsScreen(
                    sessions = sessions,
                    onOpen = { name -> dest = Dest.SessionView(name) },
                    onCreate = { name -> vm.createSession(name) { dest = Dest.SessionView(it) } },
                    onKill = { vm.killSession(it) },
                    onRename = { from, to -> vm.renameSession(from, to) },
                )

                is Dest.SessionView -> {
                    DisposableEffect(d.name) {
                        vm.startTranscriptPolling(d.name)
                        vm.startScreenPolling(d.name)
                        onDispose { vm.stopScreenPolling(); vm.refreshSessions() }
                    }
                    SessionScreen(
                        name = d.name,
                        transcript = transcript,
                        transcriptError = transcriptError,
                        screen = screen,
                        tab = sessionTab,
                        onTab = { sessionTab = it },
                        fontScale = fontScale,
                        onFontScale = { vm.setFontScale(it) },
                        onGeometry = { c, r -> vm.setGeometry(c, r) },
                        onSendText = { text, enter -> vm.sendText(d.name, text, enter) },
                        onSendKeys = { vm.sendKeys(d.name, it) },
                        onAnswerPrompt = { vm.answerPrompt(d.name, it) },
                        onForceResize = { vm.forceFit() },
                        onCopy = { vm.copy(it) },
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
                    notifyEnabled = notifyEnabled,
                    onNotifyEnabled = { vm.setNotifyEnabled(it) },
                    onSave = { u, t -> vm.saveSettings(u, t) },
                    onTest = { vm.testConnection() },
                )
            }
        }
    }
}
