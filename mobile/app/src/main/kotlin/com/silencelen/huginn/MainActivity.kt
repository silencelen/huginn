package com.silencelen.huginn

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.core.app.NotificationManagerCompat
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
import com.silencelen.huginn.notify.Foreground
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.ChatScreen
import com.silencelen.huginn.ui.EmptyState
import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.ui.ChatsScreen
import com.silencelen.huginn.ui.RoundsScreen
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.LocalAttachmentImages
import com.silencelen.huginn.ui.SessionScreen
import com.silencelen.huginn.ui.SessionSubtitle
import com.silencelen.huginn.ui.SessionsScreen
import com.silencelen.huginn.ui.SettingsScreen
import com.silencelen.huginn.ui.ShareTargetSheet
import com.silencelen.huginn.ui.SignInDialog
import com.silencelen.huginn.ui.StatusScreen
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.widget.FleetWidget

class MainActivity : FragmentActivity() {

    /** Compose reads these; the lifecycle below writes them. */
    private val locked = mutableStateOf(AppLock.lockedNow)
    private val lockError = mutableStateOf<String?>(null)

    /**
     * Where a notification tap wants the app to land.
     *
     * State rather than a value read once in [onCreate], because the notifications
     * are launched SINGLE_TOP: while the app is already open, a tap does not
     * recreate the activity, it arrives at [onNewIntent] — and a target captured at
     * creation time can never see it. That made tapping an alert do nothing at all
     * whenever the app happened to be foregrounded, which is exactly when a person
     * watching a session is most likely to tap one.
     */
    private val openTarget = mutableStateOf<OpenTarget?>(null)
    private var targetSeq = 0

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readTarget(intent)
    }

    private fun readTarget(intent: Intent?) {
        val session = intent?.getStringExtra(SessionWatchWorker.EXTRA_SESSION)
        val chat = intent?.getStringExtra(SessionWatchWorker.EXTRA_CHAT)
        val newChat = intent?.getBooleanExtra(FleetWidget.EXTRA_NEW_CHAT, false) == true
        // The system share sheet. Text and images arrive as different extras of
        // the same action, and either one means "start a chat about this".
        var shareText: String? = null
        var shareImage: Uri? = null
        if (intent?.action == Intent.ACTION_SEND) {
            if (intent.type == "text/plain") {
                shareText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    // Some apps put the payload in SUBJECT (title) + TEXT (url);
                    // both is better than either for "what is this page".
                    ?.let { t ->
                        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                        if (!subject.isNullOrBlank() && !t.contains(subject)) "$subject\n$t" else t
                    }
            } else if (intent.type?.startsWith("image/") == true) {
                shareImage = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java)
            }
        }
        // Only when there is something to go to. A plain launcher tap carries
        // none of these, and overwriting the target with nulls would yank the
        // reader out of wherever they already were.
        if (session != null || chat != null || shareText != null || shareImage != null || newChat) {
            openTarget.value = OpenTarget(session, chat, ++targetSeq, shareText, shareImage, newChat)
            // CONSUMED. getIntent() keeps returning the launching intent forever,
            // so without stripping it every activity recreation — rotate, fold,
            // unlock, theme change — re-read the same extras and navigated again,
            // yanking the reader back to a notification they had already dealt
            // with. Clearing the extras leaves an inert intent behind.
            intent?.apply {
                removeExtra(SessionWatchWorker.EXTRA_SESSION)
                removeExtra(SessionWatchWorker.EXTRA_CHAT)
                removeExtra(FleetWidget.EXTRA_NEW_CHAT)
                removeExtra(Intent.EXTRA_TEXT)
                removeExtra(Intent.EXTRA_SUBJECT)
                removeExtra(Intent.EXTRA_STREAM)
                action = null
            }
        }
    }

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
        // With the lock ON, the window is SECURE — which is what actually keeps the
        // conversation out of the Recents thumbnail. Set here so a cold start is
        // covered before the first frame; kept in step with the setting by the
        // effect in HuginnApp.
        //
        // Locking on return was not enough: the thumbnail is captured when the app
        // goes to the background, which is BEFORE the grace period expires, so the
        // system held a picture of the open session and anyone flicking through
        // Recents could read it without ever facing the lock. That is precisely the
        // handed-over-phone case the lock exists for. Screenshots go too, which is
        // the same promise stated once.
        if (AppLock.enabledCache) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Tapping a "needs you" notification opens straight into that session, and a
        // finished-chat notification into that chat, at the answer it announced.
        readTarget(intent)
        setContent {
            HuginnTheme {
                if (locked.value) {
                    LockedScreen(
                        error = lockError.value,
                        onUnlock = {
                            AppLock.authenticate(this) { ok, why ->
                                if (ok) { AppLock.lockedNow = false; locked.value = false; lockError.value = null }
                                else lockError.value = why
                            }
                        },
                    )
                } else {
                    val vm: HuginnViewModel = viewModel(factory = HuginnViewModel.Factory)
                    // Photo attachments render as real thumbnails in chat history;
                    // without this (or against an old daemon) rows fall back to the
                    // "photo attached" pill.
                    CompositionLocalProvider(LocalAttachmentImages provides vm.attachmentImages) {
                        HuginnApp(
                            target = openTarget.value,
                            onLockNow = { lockError.value = null; AppLock.lockedNow = true; locked.value = true },
                            vm = vm,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (AppLock.shouldLock(AppLock.enabledCache, AppLock.lastAwayAt, System.currentTimeMillis())) {
            AppLock.lockedNow = true
            locked.value = true
        }
    }

    override fun onResume() {
        super.onResume()
        Foreground.resumed = true
    }

    override fun onPause() {
        // Cleared FIRST: from this instant a notification about the open screen is
        // no longer redundant, and a push racing the pocket must win.
        Foreground.resumed = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Recorded only while unlocked: a lock screen left in the background must
        // not refresh its own grace period and let the app back in unchallenged.
        if (!locked.value) AppLock.lastAwayAt = System.currentTimeMillis()
    }
}

/**
 * Where "back" goes from [dest], or null when this screen IS a root.
 *
 * One rule, because there are two ways to ask for it. The top-bar arrow had this
 * logic inline and the system gesture had none at all, so back from a session or a
 * chat did not go up — it left the app entirely (confirmed on the device: two
 * presses from a session and topResumedActivity read Terminated). Returning null at
 * a root is deliberate: leaving from there IS what back means on Android, and a
 * handler that swallowed it would trap the user in the app.
 */
internal fun backFrom(dest: Dest, tab: Int): Dest? = when (dest) {
    is Dest.SessionView -> Dest.Sessions
    is Dest.Chat -> Dest.Chats
    is Dest.Settings -> when (tab) {
        0 -> Dest.Chats
        1 -> Dest.Sessions
        3 -> Dest.Rounds
        else -> Dest.Status
    }
    else -> null
}

/**
 * Whether a teardown is the user LEAVING, or merely the activity being rebuilt.
 *
 * A fold, a rotate or a theme change destroys and recreates the activity, which
 * disposes every composition on screen — indistinguishable, from inside
 * `onDispose`, from navigating away. It matters because teardown here throws work
 * away: a staged photo is the user's statement about the message they are writing,
 * and unfolding the phone is not a retraction of it.
 *
 * A context that is not an Activity cannot answer, and then this reports "gone" —
 * the pre-existing behaviour, so an unexpected host degrades to eager cleanup
 * rather than to a leak.
 */
@Composable
private fun rememberStillHere(): () -> Boolean {
    val activity = LocalContext.current as? android.app.Activity
    return { activity?.isChangingConfigurations == true }
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

/**
 * Where a notification tap should land.
 *
 * [seq] exists so that tapping the SAME notification twice still navigates: without
 * it two identical targets compare equal, the effect watching them never re-runs,
 * and a tap made after wandering elsewhere in the app does nothing.
 */
data class OpenTarget(
    val session: String?,
    val chat: String?,
    val seq: Int,
    /** Text handed in through the system share sheet; becomes a new chat's draft. */
    val shareText: String? = null,
    /** An image shared in; staged as the new chat's attachment. */
    val shareImage: Uri? = null,
    /** The widget's quick chat: land on Chats with the new-chat question open. */
    val newChat: Boolean = false,
)

/**
 * Encodes the current screen for [rememberSaveable]. A bundle cannot hold a
 * sealed-interface instance, and losing the screen on every fold is worse than
 * the small tax of a string.
 */
/** The encode half of [DestSaver], exposed so the round trip can be tested. */
internal fun destToKey(d: Dest): String = when (d) {
    is Dest.Chats -> "chats"
    is Dest.Chat -> "chat:${d.id}"
    is Dest.Rounds -> "rounds"
    is Dest.Sessions -> "sessions"
    is Dest.SessionView -> "session:${d.name}"
    is Dest.Status -> "status"
    is Dest.Settings -> "settings"
}

/** The decode half. Anything unrecognised lands on the home screen, never crashes. */
internal fun keyToDest(v: String): Dest = when {
    v == "chats" -> Dest.Chats
    v.startsWith("chat:") -> Dest.Chat(v.removePrefix("chat:"))
    v == "rounds" -> Dest.Rounds
    v == "sessions" -> Dest.Sessions
    v.startsWith("session:") -> Dest.SessionView(v.removePrefix("session:"))
    v == "status" -> Dest.Status
    v == "settings" -> Dest.Settings
    else -> Dest.Sessions
}

internal val DestSaver = androidx.compose.runtime.saveable.Saver<Dest, String>(
    save = { destToKey(it) },
    restore = { keyToDest(it) },
)

internal sealed interface Dest {
    data object Chats : Dest
    data class Chat(val id: String) : Dest
    data object Rounds : Dest
    data object Sessions : Dest
    data class SessionView(val name: String) : Dest
    data object Status : Dest
    data object Settings : Dest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuginnApp(
    target: OpenTarget? = null,
    onLockNow: () -> Unit = {},
    vm: HuginnViewModel = viewModel(factory = HuginnViewModel.Factory),
) {
    // Sessions is home: the owner's real use is watching and steering the
    // sessions already running, with chats the occasional side door.
    var tab by rememberSaveable { mutableStateOf(1) }
    // rememberSaveable, because a fold or a rotate REBUILDS this activity: with a
    // plain remember, unfolding while reading a session threw the reader back to
    // the sessions list every single time. Saved as a short string since Dest is
    // a sealed type Compose cannot bundle on its own.
    var dest by rememberSaveable(stateSaver = DestSaver) { mutableStateOf<Dest>(Dest.Sessions) }
    var sessionTab by rememberSaveable { mutableStateOf(0) }
    var pendingShare by remember { mutableStateOf<OpenTarget?>(null) }
    // The widget's quick chat, handed to ChatsScreen as the target's seq so a
    // second tap on the widget re-opens the question a first tap dismissed.
    var newChatAsk by remember { mutableStateOf(0) }

    // What is on screen, published for notification suppression: a buzz about the
    // conversation the reader is already watching carries nothing. Cleared when
    // this whole tree leaves composition — which is exactly what happens when the
    // app lock takes over, so a locked screen never counts as "looking at it".
    val ctx = LocalContext.current
    DisposableEffect(dest) {
        Foreground.chat = (dest as? Dest.Chat)?.id
        Foreground.session = (dest as? Dest.SessionView)?.name
        // Read = dismissed. Opening the thing a notification pointed at is the
        // strongest possible form of having seen it; leaving the notification up
        // afterwards would just be a chore handed back to the reader.
        val read = when (val d = dest) {
            is Dest.Chat -> SessionWatchWorker.notificationIdFor("chat:${d.id}")
            is Dest.SessionView -> SessionWatchWorker.notificationIdFor(d.name)
            else -> null
        }
        if (read != null) runCatching { NotificationManagerCompat.from(ctx).cancel(read) }
        onDispose { Foreground.chat = null; Foreground.session = null }
    }


    // Navigation asked for by a notification tap, applied here rather than in the
    // initial state so that a tap arriving at an app that is ALREADY open moves it
    // too — the common case, and the one the previous read-once version missed.
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        when {
            t.session != null -> { tab = 1; dest = Dest.SessionView(t.session) }
            t.chat != null -> {
                tab = 0
                // The chat screen renders from the view model, which a destination
                // constructed here has not asked to load; without this it opens empty.
                vm.openChat(t.chat)
                dest = Dest.Chat(t.chat)
            }
            t.newChat -> {
                // The widget's quick chat lands on Chats with the ask/act question
                // already open — creation still costs one deliberate tap, so a
                // stray touch on the launcher cannot mint empty chats on the host.
                tab = 0
                dest = Dest.Chats
                newChatAsk = t.seq
            }
            t.shareText != null || t.shareImage != null -> {
                // Something shared in from another app. Where it lands is the
                // reader's call — the screenshot of an error belongs in the
                // session already working on that error, not necessarily in a
                // fresh chat — so a destination sheet asks. Staged, never sent:
                // the share contract everywhere is "compose around this".
                pendingShare = t
            }
        }
    }

    val chats by vm.chats.collectAsState()
    val rounds by vm.rounds.collectAsState()
    val devices by vm.devices.collectAsState()
    val chatSealed by vm.chatSealed.collectAsState()
    // Recomputed on every recomposition, which the chat/round refresh already
    // drives. A Round row says "in 4h", not "in 3h 59m", so a clock that ticks
    // only when the data does is precise enough and costs nothing.
    val nowMs = System.currentTimeMillis()
    val sessions by vm.sessions.collectAsState()
    val status by vm.status.collectAsState()
    val statusError by vm.statusError.collectAsState()
    val loading by vm.loading.collectAsState()
    val connected by vm.connected.collectAsState()
    val toast by vm.toast.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()
    val routePinned by vm.routePinned.collectAsState()
    val resolvingRoute by vm.resolvingRoute.collectAsState()
    val token by vm.token.collectAsState()
    val fontScale by vm.fontScale.collectAsState()
    val notifyEnabled by vm.notifyEnabled.collectAsState()
    val chatPage by vm.chatPage.collectAsState()
    val chatError by vm.chatError.collectAsState()
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
    val hasEarlier by vm.hasEarlier.collectAsState()
    val loadingHistory by vm.loadingHistory.collectAsState()
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
    // FLAG_SECURE follows the setting LIVE, not just at onCreate. Reading it once on
    // creation meant turning the lock ON left the window insecure — and its contents
    // in the Recents thumbnail — for the rest of the app's life, which is exactly the
    // exposure the user had just asked to close. Clearing on OFF matters too: nobody
    // should have to restart an app to get their screenshots back.
    val lockActivity = LocalContext.current as? android.app.Activity
    LaunchedEffect(appLock) {
        val w = lockActivity?.window ?: return@LaunchedEffect
        if (appLock) w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    val agents by vm.agents.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val attachment by vm.attachment.collectAsState()
    val attachmentOwner by vm.attachmentOwner.collectAsState()

    pendingShare?.let { share ->
        ShareTargetSheet(
            sessions = sessions,
            chats = chats,
            sharingImage = share.shareImage != null,
            onDismiss = { pendingShare = null },
            onNewChat = {
                pendingShare = null
                tab = 0
                vm.newChatForShare(share.shareText, share.shareImage) { id -> dest = Dest.Chat(id) }
            },
            onChat = { id ->
                pendingShare = null
                tab = 0
                vm.openChat(id)
                dest = Dest.Chat(id)
                vm.stageShareInChat(id, share.shareText, share.shareImage)
            },
            onSession = { name ->
                pendingShare = null
                tab = 1
                sessionTab = 0
                dest = Dest.SessionView(name)
                vm.stageShareInSession(name, share.shareText, share.shareImage)
            },
        )
    }

    val autoswitch by vm.autoswitch.collectAsState()

    // Per-surface action menu state (the top-bar slot).
    var surfaceMenu by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }        // session name
    var renameChatTarget by remember { mutableStateOf<String?>(null) }    // chat id
    var renameText by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<String?>(null) }
    var softEndTarget by remember { mutableStateOf<String?>(null) }
    var deleteChatTarget by remember { mutableStateOf<String?>(null) }

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
    // Voice mode's streaming recognizer needs the mic permission; the dictation
    // dialog does not, so denial only narrows, never breaks.
    var voiceReady by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    // The sheet is already open and showing its permission screen when this
    // runs; a denial still toasts, because on some builds the system dialog
    // never appears at all and the sheet's own hint is the only explanation.
    val voicePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        voiceReady = granted
        if (!granted) {
            vm.showToast(
                "Microphone permission was not granted — enable it under App info → " +
                    "Permissions, or use the dictation mic, which needs none."
            )
        }
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
            3 -> Dest.Rounds
            else -> Dest.Status
        }
        vm.refreshAll()
    }

    val isChild = dest is Dest.Chat || dest is Dest.SessionView || dest is Dest.Settings
    // The system gesture, going where the arrow goes. Without this the commonest
    // gesture on the phone closed the app from every child screen.
    androidx.activity.compose.BackHandler(enabled = isChild) {
        backFrom(dest, tab)?.let { dest = it }
    }
    val title = when (val d = dest) {
        is Dest.Chats -> "Huginn"
        is Dest.Chat -> chatTitle ?: "Chat"
        is Dest.Rounds -> "Rounds"
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

    renameTarget?.let { from ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val to = renameText.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
                    if (to.isNotEmpty() && to != from) {
                        vm.renameSession(from, to)
                        dest = Dest.SessionView(to)
                    }
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    renameChatTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { renameChatTarget = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) vm.renameChat(id, renameText)
                    renameChatTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameChatTarget = null }) { Text("Cancel") } },
        )
    }
    killTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("Kill $name?") },
            text = { Text("Ends the tmux session and whatever Claude is doing in it.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.killSession(name)
                    if ((dest as? Dest.SessionView)?.name == name) dest = Dest.Sessions
                    killTarget = null
                }) { Text("Kill") }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
        )
    }
    softEndTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { softEndTarget = null },
            title = { Text("Wind down $name?") },
            text = {
                Text(
                    "Sends Claude the wrap-up instruction (finish, commit, prepare to end). " +
                        "If auto-end is on for the host, the session ends on its own once it settles; " +
                        "a wrap-up question keeps it open.",
                )
            },
            // Not destructive: this sends a message and the session lives on, so no
            // navigation away and the default (primary) button colour.
            confirmButton = {
                TextButton(onClick = {
                    vm.softEndSession(name)
                    softEndTarget = null
                }) { Text("Send wrap-up") }
            },
            dismissButton = { TextButton(onClick = { softEndTarget = null }) { Text("Cancel") } },
        )
    }
    deleteChatTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteChatTarget = null },
            title = { Text("Delete this chat?") },
            text = { Text("Removes it from huginn. The underlying transcript file stays on the host.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(id)
                    if ((dest as? Dest.Chat)?.id == id) dest = Dest.Chats
                    deleteChatTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteChatTarget = null }) { Text("Cancel") } },
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
            is Dest.Rounds -> 3
            // Four, not three: Rounds took 3, and Settings has no bar item of its
            // own so its number only has to be distinct.
            is Dest.Settings -> 4
        }

        // Each surface once, as a lambda, so the narrow and wide layouts are
        // arrangements of the same pieces rather than two copies of them.
        val chatsPane: @Composable (Boolean) -> Unit = { twoPane ->
            ChatsScreen(
                chats = chats,
                devices = devices,
                onOpenNewChat = { vm.refreshDevices() },
                loading = loading,
                connected = connected,
                selectedId = if (twoPane) (dest as? Dest.Chat)?.id else null,
                onOpen = { id -> vm.openChat(id); dest = Dest.Chat(id) },
                onNew = { mode, host ->
                    vm.newChat(mode, host) { id -> vm.openChat(id); dest = Dest.Chat(id) }
                },
                onDelete = { vm.deleteChat(it) },
                onOpenSettings = { dest = Dest.Settings },
                newChatRequest = newChatAsk,
            )
        }
        // Its own destination now, not a strip on top of the chat list. A Round is
        // not a conversation and the chat list is not where you go looking for one;
        // sharing that screen made both of them read as the other's preamble.
        val roundsPane: @Composable () -> Unit = {
            RoundsScreen(
                rounds = rounds,
                nowMs = nowMs,
                loading = loading,
                connected = connected,
                onOpenRound = { r ->
                    r.lastRun?.chatId?.let { id -> vm.openChat(id); dest = Dest.Chat(id) }
                },
                onRunRound = { r -> vm.runRound(r.id) },
                onSetRoundEnabled = { r, on -> vm.setRoundEnabled(r.id, on) },
                onOpenSettings = { dest = Dest.Settings },
                onStartPolling = { vm.startRoundsPolling() },
                onStopPolling = { vm.stopRoundsPolling() },
            )
        }
        val chatDetail: @Composable (String) -> Unit = { id ->
            val stillHere = rememberStillHere()
            DisposableEffect(id) {
                onDispose {
                    vm.detachStream(); vm.clearSuggestions()
                    // A photo staged for THIS chat must not silently ride the next
                    // screen — but only this chat's own claim is cleared, so a share
                    // staged for the DESTINATION while navigating there survives
                    // the previous screen's teardown.
                    //
                    // ...and not on a REBUILD. A fold or a rotate disposes this
                    // composition too, and since `dest` now survives that rebuild the
                    // user lands back on the same chat — with the photo they had
                    // attached silently gone. Leaving the screen is a decision;
                    // unfolding the phone is not.
                    if (stillHere()) return@onDispose
                    vm.clearAttachment(HuginnViewModel.chatDraftKey(id))
                }
            }
            // A turn boundary — the transcript grew and nothing is in flight — is
            // the moment suggestions are worth generating; same rule as sessions.
            val chatBusy = sending || streamingText != null || activeTool != null ||
                chats.firstOrNull { c -> c.id == id }?.running == true
            LaunchedEffect(chatPage?.nextOffset, chatBusy) {
                vm.maybeSuggestChat(id, chatPage, chatBusy)
            }
            ChatScreen(
                sealedRun = chatSealed,
                page = chatPage,
                error = chatError,
                onRetry = { vm.retryChatTranscript(id) },
                streamingText = streamingText,
                activeTool = activeTool,
                sending = sending,
                mode = chatMode,
                model = chatModel,
                effort = chatEffort,
                models = models,
                onSetOptions = { m, e -> vm.setChatOptions(id, model = m, effort = e) },
                chatId = id,
                suggestions = suggestions,
                voiceReady = voiceReady,
                onVoicePermission = { voicePermission.launch(Manifest.permission.RECORD_AUDIO) },
                draft = drafts[HuginnViewModel.chatDraftKey(id)].orEmpty(),
                onDraft = { vm.setDraft(HuginnViewModel.chatDraftKey(id), it) },
                onSend = { vm.send(id, it) },
                onCancel = { vm.cancel(id) },
                onCopy = { vm.copy(it) },
                attachment = if (attachmentOwner == HuginnViewModel.chatDraftKey(id)) attachment else null,
                onAttach = { vm.attachImage(it, HuginnViewModel.chatDraftKey(id)) },
                onAttachFile = { vm.attachFile(it, HuginnViewModel.chatDraftKey(id)) },
                onClearAttachment = { vm.clearAttachment(HuginnViewModel.chatDraftKey(id)) },
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
                onSoftEnd = { vm.softEndSession(it) },
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
                onStopOrDispose {
                    vm.stopScreenPolling(); vm.clearSuggestions(); vm.refreshSessions()
                }
            }
            // The staged photo is NOT lifecycle work, and it used to hang off the
            // effect above — which fires on ON_STOP, so pocketing the phone or
            // glancing at a notification threw the attachment away mid-message.
            // Tied to composition instead, and skipped when the activity is only
            // being rebuilt. Only this session's own claim, so a share staged for
            // the next destination survives this screen's teardown.
            val stillHere = rememberStillHere()
            DisposableEffect(name) {
                onDispose {
                    if (stillHere()) return@onDispose
                    vm.clearAttachment(HuginnViewModel.sessionDraftKey(name))
                }
            }
            // A turn boundary — the transcript grew and the session is idle — is
            // the moment suggestions become worth generating.
            // The TRANSCRIPT's state first, the sessions list only as a fallback.
            //
            // On a folded phone the session detail is rendered ALONE — the
            // sessions pane is not composed, so nothing polls the list and its
            // state freezes at whatever it held when the reader left it. The
            // transcript IS polled here, and carries the same hook state, so it
            // is the live source in exactly the case the list is stale. Frozen,
            // this drove the wrong composer control (send instead of interrupt,
            // or a Stop button on a finished session) and mis-timed suggestions.
            val sessionWorking = (transcript?.state
                ?: sessions.firstOrNull { s -> s.name == name }?.state) == "running"
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
                micGranted = voiceReady,
                onRequestMic = { voicePermission.launch(Manifest.permission.RECORD_AUDIO) },
                onAnswerPrompt = { vm.answerPrompt(name, it, screen?.prompt?.fingerprint) },
                onAnswerMulti = { opts -> vm.answerPromptMulti(name, opts, screen?.prompt?.fingerprint) },
                onForceResize = { vm.forceFit() },
                onInterrupt = { vm.interruptSession(name) },
                working = sessionWorking,
                attachment = if (attachmentOwner == HuginnViewModel.sessionDraftKey(name)) attachment else null,
                onAttach = { vm.attachImage(it, HuginnViewModel.sessionDraftKey(name)) },
                onAttachFile = { vm.attachFile(it, HuginnViewModel.sessionDraftKey(name)) },
                onClearAttachment = { vm.clearAttachment(HuginnViewModel.sessionDraftKey(name)) },
                onCopy = { vm.copy(it) },
                hasEarlier = hasEarlier,
                loadingHistory = loadingHistory,
                onLoadEarlier = { vm.loadEarlierTranscript(name) },
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
            // The update check is check-only (no download), so it never spends data.
            LaunchedEffect(Unit) { vm.refreshAccount(); vm.refreshDelivery(); vm.refreshAutoswitch(); vm.checkForUpdate() }
            val updateState by vm.updateState.collectAsState()
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
                autoswitch = autoswitch,
                onAutoswitch = { vm.setAutoswitch(it) },
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
                routePinned = routePinned,
                resolvingRoute = resolvingRoute,
                onSelectRoute = { vm.selectRoute(it) },
                onResolveRoute = { vm.resolveRoute() },
                onUnpinRoute = { vm.unpinRoute() },
                updateState = updateState,
                installedVersion = vm.installedVersion,
                updateRepo = vm.updateSourceRepo,
                onCheckUpdate = { vm.checkForUpdate() },
                onDownloadUpdate = { vm.downloadUpdate() },
                onInstallUpdate = { vm.installUpdate() },
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
                        backFrom(dest, tab)?.let { up ->
                            IconButton(onClick = { dest = up }) {
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
                        if (dest !is Dest.Settings && dest !is Dest.Chat && dest !is Dest.SessionView) {
                            IconButton(onClick = { dest = Dest.Settings }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        }
                        // The slot the settings gear vacated: controls for the thing
                        // being looked at, not for the app.
                        if (dest is Dest.SessionView || dest is Dest.Chat) {
                            Box {
                                IconButton(onClick = { surfaceMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                                }
                                DropdownMenu(expanded = surfaceMenu, onDismissRequest = { surfaceMenu = false }) {
                                    when (val d = dest) {
                                        is Dest.SessionView -> {
                                            DropdownMenuItem(text = { Text("Rename session") },
                                                onClick = { surfaceMenu = false; renameTarget = d.name; renameText = d.name })
                                            DropdownMenuItem(text = { Text("Fit pane to phone") },
                                                onClick = { surfaceMenu = false; vm.forceFit() })
                                            DropdownMenuItem(text = { Text("Interrupt (Esc)") },
                                                onClick = { surfaceMenu = false; vm.interruptSession(d.name) })
                                            DropdownMenuItem(text = { Text("Compact context") },
                                                onClick = { surfaceMenu = false; vm.compactSession(d.name) })
                                            DropdownMenuItem(text = { Text("Wind down…") },
                                                onClick = { surfaceMenu = false; softEndTarget = d.name })
                                            DropdownMenuItem(text = { Text("Kill session…") },
                                                onClick = { surfaceMenu = false; killTarget = d.name })
                                        }
                                        is Dest.Chat -> {
                                            DropdownMenuItem(text = { Text("Rename chat") },
                                                onClick = {
                                                    surfaceMenu = false
                                                    renameChatTarget = d.id
                                                    renameText = chatTitle ?: ""
                                                })
                                            DropdownMenuItem(text = { Text("Delete chat…") },
                                                onClick = { surfaceMenu = false; deleteChatTarget = d.id })
                                        }
                                        else -> Unit
                                    }
                                }
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
                            selected = section == 3,
                            onClick = { onTab(3) },
                            icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                            label = { Text("Rounds") },
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
                            selected = section == 3,
                            onClick = { onTab(3) },
                            icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                            label = { Text("Rounds") },
                        )
                        NavigationRailItem(
                            selected = section == 2,
                            onClick = { onTab(2) },
                            icon = { Icon(Icons.Filled.MonitorHeart, contentDescription = null) },
                            label = { Text("Status") },
                        )
                        Spacer(Modifier.weight(1f))
                        NavigationRailItem(
                            selected = section == 4,
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
                            is Dest.Rounds -> roundsPane()
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
                            // A reading surface like Status: full width helps nobody
                            // at 900dp, so it keeps a readable measure, centred.
                            is Dest.Rounds -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 840.dp)) { roundsPane() }
                            }
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
