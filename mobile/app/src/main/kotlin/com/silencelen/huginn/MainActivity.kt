package com.silencelen.huginn

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silencelen.huginn.data.SettingsStore
import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.ChatScreen
import com.silencelen.huginn.ui.EmptyState
import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.ui.ChatsScreen
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.SessionScreen
import com.silencelen.huginn.ui.SessionSubtitle
import com.silencelen.huginn.ui.SessionsScreen
import com.silencelen.huginn.ui.SettingsScreen
import com.silencelen.huginn.ui.SignInDialog
import com.silencelen.huginn.ui.StatusScreen
import com.silencelen.huginn.ui.theme.HuginnTheme

class MainActivity : FragmentActivity() {

    /** Compose reads these; the lifecycle below writes them. */
    private val locked = mutableStateOf(false)
    private val lockError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The lock decision at ON_START cannot wait on DataStore, so the setting is
        // read once here into a process-wide cache. runBlocking on a preferences
        // read is a few milliseconds on the app's first frame, which is the one
        // moment it is acceptable — and the one moment it is needed.
        AppLock.enabledCache = runBlocking {
            SettingsStore(applicationContext).appLock.first()
        }
        // Tapping a "needs you" notification opens straight into that session.
        val openSession = intent?.getStringExtra(SessionWatchWorker.EXTRA_SESSION)
        setContent {
            HuginnTheme {
                if (locked.value) {
                    LockedScreen(
                        error = lockError.value,
                        onUnlock = {
                            AppLock.authenticate(this) { ok, why ->
                                if (ok) { locked.value = false; lockError.value = null }
                                else lockError.value = why
                            }
                        },
                    )
                } else {
                    HuginnApp(
                        openSession = openSession,
                        onLockNow = { lockError.value = null; locked.value = true },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (AppLock.shouldLock(AppLock.enabledCache, AppLock.lastAwayAt, System.currentTimeMillis())) {
            locked.value = true
        }
    }

    override fun onStop() {
        super.onStop()
        // Recorded only while unlocked: a lock screen left in the background must
        // not refresh its own grace period and let the app back in unchallenged.
        if (!locked.value) AppLock.lastAwayAt = System.currentTimeMillis()
    }
}

/**
 * What a locked app shows: nothing. No chat titles, no session names — the lock
 * would mean little if the surface underneath kept narrating.
 */
@Composable
private fun LockedScreen(error: String?, onUnlock: () -> Unit) {
    // Offer the sheet as soon as the screen appears; the button is for after a
    // dismissal, not the primary path.
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Text("Huginn is locked", style = MaterialTheme.typography.titleMedium)
            // A prompt that cannot show must SAY so: an invisible failure here is
            // exactly how the first version of this feature disappeared.
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
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
    onLockNow: () -> Unit = {},
    vm: HuginnViewModel = viewModel(factory = HuginnViewModel.Factory),
) {
    // Sessions is home: the owner's real use is watching and steering the
    // sessions already running, with chats the occasional side door.
    var tab by rememberSaveable { mutableStateOf(1) }
    var dest by remember {
        mutableStateOf<Dest>(if (openSession != null) Dest.SessionView(openSession) else Dest.Sessions)
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
    val scrollback by vm.scrollback.collectAsState()
    val loadingScrollback by vm.loadingScrollback.collectAsState()
    val chatModel by vm.chatModel.collectAsState()
    val chatEffort by vm.chatEffort.collectAsState()
    val models by vm.models.collectAsState()
    val transcript by vm.transcript.collectAsState()
    val transcriptError by vm.transcriptError.collectAsState()
    val drafts by vm.drafts.collectAsState()
    val account by vm.account.collectAsState()
    val usage by vm.usage.collectAsState()
    val plan by vm.plan.collectAsState()
    val savedAccounts by vm.savedAccounts.collectAsState()
    val switching by vm.switching.collectAsState()
    val loginUrl by vm.loginUrl.collectAsState()
    val login by vm.login.collectAsState()
    val loginBusy by vm.loginBusy.collectAsState()
    val watchEnabled by vm.watchEnabled.collectAsState()
    val hostAlerts by vm.alerts.collectAsState()
    val health by vm.health.collectAsState()
    val clients by vm.clients.collectAsState()
    val push by vm.push.collectAsState()
    val appLock by vm.appLock.collectAsState()
    val agents by vm.agents.collectAsState()
    val suggestions by vm.suggestions.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.toastShown() } }

    // A session killed while its view is open closes that view: leaving the
    // reader on a dead screen that no longer exists helps nobody.
    val sessionGone by vm.sessionGone.collectAsState()
    LaunchedEffect(sessionGone) {
        sessionGone?.let { gone ->
            if ((dest as? Dest.SessionView)?.name == gone) dest = Dest.Sessions
            vm.sessionGoneHandled()
        }
    }

    // The sign-in URL is 450 characters and hard-wrapped in the pane, so hand it
    // straight to a browser instead of asking anyone to copy it off a terminal.
    val context = LocalContext.current
    LaunchedEffect(loginUrl) {
        loginUrl?.let { url ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { vm.copy(url, "sign-in URL") }
            vm.loginUrlHandled()
        }
    }

    // Re-checked on every resume, because the answer can change in system
    // settings while the app is in the background.
    var notificationsAllowed by remember { mutableStateOf(SessionWatchWorker.canNotify(context)) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notificationsAllowed = SessionWatchWorker.canNotify(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) notificationsAllowed = SessionWatchWorker.canNotify(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationsAllowed = SessionWatchWorker.canNotify(context)
        }
    }
    LaunchedEffect(notifyEnabled) {
        if (notifyEnabled && !SessionWatchWorker.canNotify(context)) requestNotifications()
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

    login?.let { st ->
        SignInDialog(
            state = st,
            busy = loginBusy,
            onStart = { email -> vm.startLogin(email) },
            onOpenUrl = { url ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { vm.copy(url, "sign-in URL") }
            },
            onSubmit = { vm.submitLoginCode(it) },
            onDismiss = { vm.dismissLogin() },
        )
    }

    // The Z Fold's inner display is ~840dp across; phones are ~360-410. The
    // two-pane threshold sits between them, so unfolding turns the list screens
    // into list-plus-detail and folding collapses them back — and the navigation
    // moves from a thumb-reach bottom bar to a rail, which is where hands sit on
    // a wide screen.
    BoxWithConstraints {
        val wide = maxWidth >= 700.dp
        // Where the reader is, derived from the destination rather than the last
        // tapped tab, so opening a chat from a notification highlights Chats.
        val section = when (dest) {
            is Dest.Chats, is Dest.Chat -> 0
            is Dest.Sessions, is Dest.SessionView -> 1
            is Dest.Status -> 2
            is Dest.Settings -> 3
        }

        // Each surface once, as a lambda, so the narrow and wide layouts are
        // arrangements of the same pieces rather than two copies of them.
        val chatsPane: @Composable (Boolean) -> Unit = { twoPane ->
            ChatsScreen(
                chats = chats,
                loading = loading,
                connected = connected,
                selectedId = if (twoPane) (dest as? Dest.Chat)?.id else null,
                onOpen = { id -> vm.openChat(id); dest = Dest.Chat(id) },
                onNew = { mode -> vm.newChat(mode) { id -> vm.openChat(id); dest = Dest.Chat(id) } },
                onDelete = { vm.deleteChat(it) },
                onOpenSettings = { dest = Dest.Settings },
            )
        }
        val chatDetail: @Composable (String) -> Unit = { id ->
            DisposableEffect(id) { onDispose { vm.detachStream() } }
            ChatScreen(
                page = chatPage,
                streamingText = streamingText,
                activeTool = activeTool,
                sending = sending,
                mode = chatMode,
                model = chatModel,
                effort = chatEffort,
                models = models,
                onSetOptions = { m, e -> vm.setChatOptions(id, model = m, effort = e) },
                chatId = id,
                draft = drafts[HuginnViewModel.chatDraftKey(id)].orEmpty(),
                onDraft = { vm.setDraft(HuginnViewModel.chatDraftKey(id), it) },
                onSend = { vm.send(id, it) },
                onCancel = { vm.cancel(id) },
                onCopy = { vm.copy(it) },
            )
        }
        val sessionsPane: @Composable (Boolean) -> Unit = { twoPane ->
            // Keep the list live while it is visible.
            LifecycleStartEffect(Unit) {
                vm.startSessionsPolling()
                onStopOrDispose { vm.stopSessionsPolling() }
            }
            SessionsScreen(
                sessions = sessions,
                selectedName = if (twoPane) (dest as? Dest.SessionView)?.name else null,
                onOpen = { name -> dest = Dest.SessionView(name) },
                onCreate = { name -> vm.createSession(name) { dest = Dest.SessionView(it) } },
                onKill = { vm.killSession(it) },
                onRename = { from, to -> vm.renameSession(from, to) },
            )
        }
        val sessionDetail: @Composable (String) -> Unit = { name ->
            // Tied to the lifecycle, not just to composition: a DisposableEffect
            // does not dispose when the app is merely backgrounded, so polling
            // would keep running — and keep renewing the server-side pane-size
            // lease, pinning an attached laptop at phone geometry indefinitely.
            LifecycleStartEffect(name) {
                vm.startTranscriptPolling(name)
                vm.startScreenPolling(name)
                onStopOrDispose { vm.stopScreenPolling(); vm.clearSuggestions(); vm.refreshSessions() }
            }
            // A turn boundary — the transcript grew and the session is idle — is
            // the moment suggestions become worth generating.
            val sessionWorking = sessions.firstOrNull { s -> s.name == name }?.state == "running"
            LaunchedEffect(transcript?.nextOffset, sessionWorking) {
                vm.maybeSuggest(name, transcript, sessionWorking)
            }
            SessionScreen(
                name = name,
                transcript = transcript,
                transcriptError = transcriptError,
                screen = screen,
                scrollback = scrollback,
                loadingScrollback = loadingScrollback,
                onLoadScrollback = { vm.loadScrollback(name) },
                tab = sessionTab,
                onTab = { sessionTab = it },
                fontScale = fontScale,
                onFontScale = { vm.setFontScale(it) },
                onGeometry = { c, r -> vm.setGeometry(c, r) },
                models = models,
                draft = drafts[HuginnViewModel.sessionDraftKey(name)].orEmpty(),
                onDraft = { vm.setDraft(HuginnViewModel.sessionDraftKey(name), it) },
                onSendText = { text, enter -> vm.sendText(name, text, enter) },
                onSendKeys = { vm.sendKeys(name, it) },
                onLive = { vm.sendLive(name, it) },
                agents = agents,
                onAgentsOpen = { vm.startAgentsPolling(name) },
                onAgentsClose = { vm.stopAgentsPolling() },
                suggestions = suggestions,
                onAnswerPrompt = { vm.answerPrompt(name, it) },
                onForceResize = { vm.forceFit() },
                onInterrupt = { vm.interruptSession(name) },
                working = sessions.firstOrNull { s -> s.name == name }?.state == "running",
                onCopy = { vm.copy(it) },
            )
        }
        val statusPane: @Composable () -> Unit = {
            DisposableEffect(Unit) {
                vm.refreshPlan()
                vm.refreshUsage()
                onDispose { vm.stopUsagePolling() }
            }
            StatusScreen(
                status = status,
                error = statusError,
                sessions = sessions.size,
                chatsRunning = chats.count { it.running },
                plan = plan,
                usage = usage,
            )
        }
        val settingsPane: @Composable () -> Unit = {
            // Re-read on every visit rather than once: the Doze exemption is held
            // by the system and can be revoked outside this app, so a cached
            // "granted" would keep reassuring long after it stopped being true.
            LaunchedEffect(Unit) { vm.refreshAccount(); vm.refreshDelivery() }
            SettingsScreen(
                baseUrl = baseUrl,
                token = token,
                connected = connected,
                notifyEnabled = notifyEnabled,
                onNotifyEnabled = { vm.setNotifyEnabled(it) },
                health = health,
                clients = clients,
                push = push,
                onRequestDozeExemption = { vm.requestDozeExemption() },
                onRefreshDelivery = { vm.refreshDelivery() },
                onTestPush = { vm.sendTestPush() },
                onAlertsMode = { vm.setAlertsMode(it) },
                appLock = appLock,
                appLockAvailable = remember { AppLock.canLock(context) },
                onAppLock = { vm.setAppLock(it) },
                onLockNow = onLockNow,
                notificationsAllowed = notificationsAllowed,
                onRequestNotifications = requestNotifications,
                onOpenSystemNotificationSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onTestNotification = {
                    SessionWatchWorker.post(
                        context,
                        "Notifications are working",
                        "This is what a session needing you will look like.",
                        null,
                    )
                },
                account = account,
                savedAccounts = savedAccounts,
                switching = switching,
                onSwitchAccount = { vm.activateAccount(it) },
                onForgetAccount = { vm.forgetAccount(it) },
                onSignIn = { vm.beginAddAccount() },
                alerts = hostAlerts,
                onAlertsEnabled = { vm.setAlertsEnabled(it) },
                onTestAlert = { vm.sendTestAlert() },
                watchEnabled = watchEnabled,
                onWatchEnabled = { vm.setWatchEnabled(it) },
                onSignOut = { vm.logout() },
                onSave = { u, t -> vm.saveSettings(u, t) },
                onTest = { vm.testConnection() },
            )
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
                        // Not inside a chat or a session: nothing there is a settings
                        // task, and the slot is reserved for per-session controls.
                        if (dest !is Dest.Settings && dest !is Dest.Chat && dest !is Dest.SessionView) {
                            IconButton(onClick = { dest = Dest.Settings }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!isChild && !wide) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = section == 0,
                            onClick = { onTab(0) },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                            label = { Text("Chats") },
                        )
                        NavigationBarItem(
                            selected = section == 1,
                            onClick = { onTab(1) },
                            icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            label = { Text("Sessions") },
                        )
                        NavigationBarItem(
                            selected = section == 2,
                            onClick = { onTab(2) },
                            icon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                            label = { Text("Status") },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            // Zero, deliberately: Scaffold's default contentWindowInsets ALSO
            // reserves the navigation-bar height, and every composer already
            // applies navigationBarsPadding() itself — the two stacked into a
            // doubled band of dead space under the entry bubble (tallest on
            // 3-button One UI, where the bar is a real 48dp inset). One owner
            // per inset: the bars and composers handle their own.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { pad ->
            Row(Modifier.fillMaxSize().padding(pad)) {
                if (wide) {
                    NavigationRail {
                        Spacer(Modifier.height(8.dp))
                        NavigationRailItem(
                            selected = section == 0,
                            onClick = { onTab(0) },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                            label = { Text("Chats") },
                        )
                        NavigationRailItem(
                            selected = section == 1,
                            onClick = { onTab(1) },
                            icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            label = { Text("Sessions") },
                        )
                        NavigationRailItem(
                            selected = section == 2,
                            onClick = { onTab(2) },
                            icon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                            label = { Text("Status") },
                        )
                        Spacer(Modifier.weight(1f))
                        NavigationRailItem(
                            selected = section == 3,
                            onClick = { dest = Dest.Settings },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Box(Modifier.weight(1f).fillMaxSize()) {
                    if (!wide) {
                        when (val d = dest) {
                            is Dest.Chats -> chatsPane(false)
                            is Dest.Chat -> chatDetail(d.id)
                            is Dest.Sessions -> sessionsPane(false)
                            is Dest.SessionView -> sessionDetail(d.name)
                            is Dest.Status -> statusPane()
                            is Dest.Settings -> settingsPane()
                        }
                    } else {
                        when (val d = dest) {
                            is Dest.Chats, is Dest.Chat -> Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(292.dp).fillMaxSize()) { chatsPane(true) }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.weight(1f).fillMaxSize()) {
                                    val open = dest as? Dest.Chat
                                    if (open != null) chatDetail(open.id)
                                    else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                        EmptyState("No chat open", "Pick one on the left, or start a new one.")
                                    }
                                }
                            }
                            is Dest.Sessions, is Dest.SessionView -> Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(292.dp).fillMaxSize()) { sessionsPane(true) }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.weight(1f).fillMaxSize()) {
                                    val open = dest as? Dest.SessionView
                                    if (open != null) sessionDetail(open.name)
                                    else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                        EmptyState("No session open", "Pick one on the left.")
                                    }
                                }
                            }
                            // Reading surfaces: full width helps nobody at 900dp, so
                            // they keep a readable measure, centred.
                            is Dest.Status -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 840.dp)) { statusPane() }
                            }
                            is Dest.Settings -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 840.dp)) { settingsPane() }
                            }
                        }
                    }
                }
            }
        }
    }
}
