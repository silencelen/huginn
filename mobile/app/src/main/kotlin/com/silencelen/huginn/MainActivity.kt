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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.EditNote
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
import com.silencelen.huginn.data.lockEnabledOrLocked
import com.silencelen.huginn.notify.AppLock
import com.silencelen.huginn.notify.Foreground
import com.silencelen.huginn.notify.SessionWatchWorker
import com.silencelen.huginn.ui.ChatScreen
import com.silencelen.huginn.ui.EmptyState
import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.ui.ChatsScreen
import com.silencelen.huginn.ui.DevicesScreen
import com.silencelen.huginn.ui.groupByMachine
import com.silencelen.huginn.ui.RoundEditScreen
import com.silencelen.huginn.ui.linksOn
import com.silencelen.huginn.ui.worthContinuing
import com.silencelen.huginn.ui.screenText
import com.silencelen.huginn.ui.RoundsScreen
import com.silencelen.huginn.ui.screenClock
import com.silencelen.huginn.ui.ScratchpadEditorView
import com.silencelen.huginn.ui.ScratchpadListView
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.LocalAttachmentImages
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalUriHandler
import com.silencelen.huginn.ui.OverviewDensity
import com.silencelen.huginn.ui.SessionOverviewView
import com.silencelen.huginn.ui.SessionScreen
import com.silencelen.huginn.ui.SessionSubtitle
import com.silencelen.huginn.ui.SessionsScreen
import com.silencelen.huginn.ui.settings.SettingsPhoneScreen
import com.silencelen.huginn.ui.SendTargetSheet
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
        val project = intent?.getStringExtra(SessionWatchWorker.EXTRA_PROJECT)
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
        if (session != null || chat != null || project != null || shareText != null ||
            shareImage != null || newChat
        ) {
            openTarget.value =
                OpenTarget(session, chat, ++targetSeq, project, shareText, shareImage, newChat)
            // CONSUMED. getIntent() keeps returning the launching intent forever,
            // so without stripping it every activity recreation — rotate, fold,
            // unlock, theme change — re-read the same extras and navigated again,
            // yanking the reader back to a notification they had already dealt
            // with. Clearing the extras leaves an inert intent behind.
            intent?.apply {
                removeExtra(SessionWatchWorker.EXTRA_SESSION)
                removeExtra(SessionWatchWorker.EXTRA_CHAT)
                removeExtra(SessionWatchWorker.EXTRA_PROJECT)
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
            // ⚠ FAILS CLOSED. An unreadable settings store used to throw straight
            // out of here on every launch — an app that could only be recovered
            // by clearing its data, which re-pairs the phone.
            lockEnabledOrLocked { SettingsStore(applicationContext).appLock.first() }
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
                    // A link in an answer hands off to the browser — http(s)
                    // ONLY. `huginn://` is this app's own deep link and `file:`
                    // reads the phone's storage; neither is something a model's
                    // output gets to reach by being tapped. What will not open is
                    // copied, so the reader still has it.
                    val linkHandler = remember(vm) {
                        object : UriHandler {
                            override fun openUri(uri: String) {
                                val scheme = runCatching { Uri.parse(uri).scheme }.getOrNull()?.lowercase()
                                if (scheme != "http" && scheme != "https") return
                                runCatching {
                                    startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.onFailure { vm.copy(uri, "link") }
                            }
                        }
                    }
                    CompositionLocalProvider(
                        LocalAttachmentImages provides vm.attachmentImages,
                        LocalUriHandler provides linkHandler,
                    ) {
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
        if (AppLock.shouldLock(AppLock.enabledCache, AppLock.lastAwayAt, android.os.SystemClock.elapsedRealtime())) {
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
        if (!locked.value) AppLock.lastAwayAt = android.os.SystemClock.elapsedRealtime()
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
    // Back to where it was opened from, not to a tab: the fleet is reached from
    // one row, in one drawer, so that drawer is the only honest answer. (It used
    // to be the Settings home, which was a step above where the reader came
    // from — correct while Settings was one scroll, wrong now that it is nine.)
    is Dest.Devices -> Dest.SettingsSection("devices")
    // Up is the list of drawers, and deliberately not tab-dependent: which tab
    // Settings was opened from is the Settings HOME's problem, one level up.
    is Dest.SettingsSection -> Dest.Settings
    is Dest.RoundEdit -> Dest.Rounds
    is Dest.Scratchpad -> Dest.Scratchpads
    // The tree, always — a project is opened from the list, from a heading over
    // the sessions, or from a notification, and only one of those three is a
    // place to return to. The list is the honest answer for all of them, the
    // same trade Pages and Settings already made.
    is Dest.Project -> Dest.Projects
    // Sessions rather than the tab the reader came from: Projects lives INSIDE
    // Sessions, which is the whole placement decision, and a Settings row that
    // went back to Settings would say it was a setting.
    is Dest.Projects -> Dest.Sessions
    is Dest.Consoles -> Dest.Status
    // Pages are reachable from four different places, so "up" cannot mean the
    // place they were opened from without a destination that carries it. The
    // section is the honest answer, and it is the trade Settings already made.
    is Dest.Scratchpads -> when (tab) {
        0 -> Dest.Chats
        1 -> Dest.Sessions
        3 -> Dest.Rounds
        else -> Dest.Status
    }
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
    /** A proposal notification's project: the tap lands on its dashboard. */
    val project: String? = null,
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
    is Dest.RoundEdit -> "roundedit:${d.id ?: ""}"
    is Dest.Devices -> "devices"
    is Dest.Scratchpads -> "pages"
    is Dest.Scratchpad -> "page:${d.id}"
    is Dest.Sessions -> "sessions"
    is Dest.SessionView -> "session:${d.name}"
    is Dest.Status -> "status"
    is Dest.Projects -> "projects"
    is Dest.Project -> "project:${d.id}"
    is Dest.Consoles -> "consoles"
    is Dest.Settings -> "settings"
    is Dest.SettingsSection -> "settings:${d.id}"
}

/** The decode half. Anything unrecognised lands on the home screen, never crashes. */
internal fun keyToDest(v: String): Dest = when {
    v == "chats" -> Dest.Chats
    v.startsWith("chat:") -> Dest.Chat(v.removePrefix("chat:"))
    v == "rounds" -> Dest.Rounds
    v.startsWith("roundedit:") -> Dest.RoundEdit(v.removePrefix("roundedit:").ifEmpty { null })
    v == "devices" -> Dest.Devices
    v == "pages" -> Dest.Scratchpads
    v.startsWith("page:") -> Dest.Scratchpad(v.removePrefix("page:"))
    v == "sessions" -> Dest.Sessions
    v.startsWith("session:") -> Dest.SessionView(v.removePrefix("session:"))
    v == "status" -> Dest.Status
    v == "projects" -> Dest.Projects
    // An empty id is the LIST, for the reason an empty settings id is the home:
    // a dashboard with no project behind it is a screen about nothing.
    v.startsWith("project:") -> v.removePrefix("project:")
        .let { if (it.isEmpty()) Dest.Projects else Dest.Project(it) }
    v == "consoles" -> Dest.Consoles
    v == "settings" -> Dest.Settings
    // An empty id is the HOME, not an empty drawer: a saved "settings:" from a
    // build that spelled it differently must land on the nine rows rather than
    // on a page with no category behind it.
    v.startsWith("settings:") -> v.removePrefix("settings:")
        .let { if (it.isEmpty()) Dest.Settings else Dest.SettingsSection(it) }
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
    /** null = writing a new one. A child of Rounds either way. */
    data class RoundEdit(val id: String?) : Dest
    /**
     * The fleet, as a destination in its own right.
     *
     * It was "a child of Settings, not a bar item" and shared the rail's Settings
     * slot; the redesign gives it its own section, because Settings' *Devices*
     * drawer now holds only the row that OPENS it and the fleet is a place you
     * go rather than a setting you change. Reached from that row, so back is the
     * drawer — see [backFrom].
     */
    data object Devices : Dest
    /** The user's own pages. Reachable from every list and every conversation. */
    data object Scratchpads : Dest
    /** One page, full screen. A child of Scratchpads. */
    data class Scratchpad(val id: String) : Dest
    data object Sessions : Dest
    data class SessionView(val name: String) : Dest
    data object Status : Dest
    /**
     * The projects tree.
     *
     * NOT A FIFTH TAB, and that is a decision rather than an oversight (delta §4,
     * reaffirming the Devices call of 2026-08-24): the bottom bar's four are the
     * daily loop, and a project IS a set of sessions, so the Sessions tab is its
     * home. It is reached from the Sessions top bar, from the headings over the
     * grouped sessions list, and from a Settings row — three doors, none of them
     * costing one of the four its place.
     */
    data object Projects : Dest
    /** One project: its dashboard, and the proposal waiting on the owner. */
    data class Project(val id: String) : Dest
    /**
     * Every console, full screen.
     *
     * Reached from the card on Status (owner decision 48), which is where consoles
     * live on this shell: a reading surface, not a place you act.
     */
    data object Consoles : Dest
    /** The nine drawers, with the search field pinned above them. */
    data object Settings : Dest
    /**
     * One drawer, full screen.
     *
     * [id] is a [com.silencelen.huginn.settings.SettingsCatalog] category id and
     * NEVER a title: ids are stable and titles are copy, and this one is written
     * into the saved destination, so renaming a drawer must not strand a
     * restored screen.
     */
    data class SettingsSection(val id: String) : Dest
}

// The two surfaces that read the pages poll. Named because on a wide screen they
// are BOTH on screen, and the poll belongs to whichever of them is still there.
private const val PAD_WATCH_LIST = "list"
private const val PAD_WATCH_EDITOR = "editor"

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
            // Where a proposal notification lands, and where EDIT lives: the
            // shade carries Spawn and Discard only, so the third verb is the
            // tap on the body — see ProjectNotices.
            t.project != null -> {
                tab = 1
                vm.openProject(t.project)
                dest = Dest.Project(t.project)
            }
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
    val pads by vm.scratchpads.collectAsState()
    // Null until the probe answers. Every scratchpad control is hidden on false —
    // an older daemon has no such route, and a door that leads to an error is
    // worse than no door.
    val padsAvailable by vm.scratchpadsAvailable.collectAsState()
    val padRefs by vm.padRefs.collectAsState()
    val openPad by vm.padSaver.pad.collectAsState()
    val padSaveState by vm.padSaver.state.collectAsState()
    val padNote by vm.padSaver.note.collectAsState()
    val overview by vm.overview.collectAsState()
    val sessionGraph by vm.sessionGraph.collectAsState()
    val overviewNote by vm.overviewNote.collectAsState()
    val metaGoals by vm.metaSaver.goals.collectAsState()
    val metaNotes by vm.metaSaver.notes.collectAsState()
    val metaSaveState by vm.metaSaver.state.collectAsState()
    val metaNote by vm.metaSaver.note.collectAsState()
    // The map's row height, remembered across a rotation but not across installs:
    // it is a reading preference for the screen you are on, not a setting.
    var overviewDensity by rememberSaveable { mutableStateOf(OverviewDensity.COMPACT) }
    // Where a page is being sent, when the picker is open. The page's own text,
    // captured when the button was pressed rather than read at the far end: it is
    // what the person was looking at when they decided to send it.
    var pendingPadText by remember { mutableStateOf<String?>(null) }
    val devices by vm.devices.collectAsState()
    // Null until the probes answer; false hides EVERY way in, which is what
    // projectEntries and consoleEntries exist to keep in one place.
    val projects by vm.projects.collectAsState()
    val projectsAvailable by vm.projectsAvailable.collectAsState()
    val projectMembers by vm.projectMembers.collectAsState()
    val projectDetail by vm.projectDetail.collectAsState()
    val projectDashboard by vm.projectDashboard.collectAsState()
    val projectRefusal by vm.projectRefusal.collectAsState()
    val projectBusy by vm.projectBusy.collectAsState()
    val consoles by vm.consoles.collectAsState()
    val consoleApproval by vm.consoleApproval.collectAsState()
    val consolesAvailable by vm.consolesAvailable.collectAsState()
    val projectDoors = com.silencelen.huginn.ui.projectEntries(projectsAvailable)
    val consoleDoors = com.silencelen.huginn.ui.consoleEntries(consolesAvailable)
    val chatSealed by vm.chatSealed.collectAsState()
    // ⚠ TICKED, not sampled. This one clock feeds every "in 4h" and "3 minutes
    // ago" the shell draws — Round rows, the pages list, the session map — and it
    // was read straight from the system clock on each recomposition, with nothing
    // to cause one. On a screen that has settled (the overview, a list nobody is
    // scrolling) that means the countdowns simply stop: "in 4h" stays "in 4h" for
    // the rest of the evening. Thirty seconds and lifecycle-gated, exactly as the
    // desktop's Status pane does it — see [screenClock].
    // Survives a fold, like the destination does: typing "token", unfolding the
    // phone and finding an empty field is the same loss as landing back on the
    // sessions list, and on a screen somebody opened to search.
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    val nowMs = screenClock()
    val sessions by vm.sessions.collectAsState()
    val status by vm.status.collectAsState()
    // Usage is no longer a DESTINATION. Everything else about it is fetched only
    // from the Status screen; this one number decides whether tonight's run
    // finishes, so it is polled wherever the reader is — and only while they are
    // actually there, which is what the lifecycle effect below is for.
    val headroomPill by vm.headroomPill.collectAsState()
    // What the daemon is still holding, per session. A message typed into a busy
    // session is queued and delivered at the next turn boundary; without this the
    // composer emptied and nothing anywhere said where the message went.
    val typing by vm.typing.collectAsState()
    // The 5-hour session window, under the Status icon. A DIFFERENT NUMBER from the
    // pill above it — the pill is the worst window anywhere, this is the one that
    // decides whether the next hour of work finishes — and null draws nothing.
    val sessionUsage by vm.sessionUsage.collectAsState()
    val streamAgents by vm.streamAgents.collectAsState()
    val streamsExpanded by vm.streamsExpanded.collectAsState()
    val selectedStream by vm.selectedStream.collectAsState()
    val agentPage by vm.agentPage.collectAsState()
    val loadingAgentHistory by vm.loadingAgentHistory.collectAsState()
    val streamsSupported by vm.streamsSupported.collectAsState()
    val streamNote by vm.streamNote.collectAsState()
    LifecycleStartEffect(Unit) {
        vm.startHeadroomPolling()
        onStopOrDispose { vm.stopHeadroomPolling() }
    }
    val statusError by vm.statusError.collectAsState()
    val loading by vm.loading.collectAsState()
    val connected by vm.connected.collectAsState()
    val toast by vm.toast.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()
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
    val chatStarted by vm.chatStarted.collectAsState()
    val chatGone by vm.chatGone.collectAsState()
    val chatWaking by vm.chatWaking.collectAsState()
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
    // ONE list for every composer; each item names its owner, and `chipsFor`
    // is the filter — the single slot used to need a separate owner flow here.
    val attachments by vm.attachments.collectAsState()

    pendingShare?.let { share ->
        SendTargetSheet(
            sessions = sessions,
            chats = chats,
            title = if (share.shareImage != null) "Share photo to" else "Share to",
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

    pendingPadText?.let { text ->
        SendTargetSheet(
            sessions = sessions,
            chats = chats,
            title = "Send this page to",
            onDismiss = { pendingPadText = null },
            onNewChat = {
                pendingPadText = null
                tab = 0
                vm.newChatForShare(text, null) { id -> dest = Dest.Chat(id) }
            },
            onChat = { id ->
                pendingPadText = null
                tab = 0
                vm.openChat(id)
                dest = Dest.Chat(id)
                vm.stagePadInChat(id, text)
            },
            onSession = { name ->
                pendingPadText = null
                tab = 1
                sessionTab = 0
                dest = Dest.SessionView(name)
                vm.stagePadInSession(name, text)
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

    val isChild = dest is Dest.Chat || dest is Dest.SessionView || dest is Dest.Settings ||
        dest is Dest.SettingsSection || dest is Dest.Devices || dest is Dest.RoundEdit ||
        dest is Dest.Scratchpads || dest is Dest.Scratchpad ||
        dest is Dest.Projects || dest is Dest.Project || dest is Dest.Consoles
    // The system gesture, going where the arrow goes. Without this the commonest
    // gesture on the phone closed the app from every child screen.
    androidx.activity.compose.BackHandler(enabled = isChild) {
        backFrom(dest, tab)?.let { dest = it }
    }
    val title = when (val d = dest) {
        // The TAB's name, not the app's. "Huginn" was the title on one of four
        // tabs inside an app already called Huginn — it named nothing and told
        // the reader nothing about where they were.
        is Dest.Chats -> "Chats"
        is Dest.Chat -> chatTitle ?: "Chat"
        is Dest.Rounds -> "Rounds"
        is Dest.RoundEdit -> if (d.id == null) "New round" else "Edit round"
        is Dest.Devices -> "Devices"
        is Dest.Scratchpads -> "Pages"
        is Dest.Scratchpad -> pads.firstOrNull { it.id == d.id }?.name ?: "Page"
        is Dest.Sessions -> "Sessions"
        is Dest.SessionView -> transcript?.title ?: d.name
        is Dest.Status -> "Status"
        is Dest.Projects -> "Projects"
        // The project's own name, from whichever list already holds it — the
        // detail when it has arrived, the tree row until it does, so the bar
        // never says "Project" for the two seconds a fetch takes.
        is Dest.Project -> projectDetail?.project?.takeIf { it.id == d.id }?.name
            ?: projects.firstOrNull { it.id == d.id }?.name
            ?: "Project"
        is Dest.Consoles -> "Consoles"
        is Dest.Settings -> "Settings"
        is Dest.SettingsSection ->
            com.silencelen.huginn.settings.SettingsCatalog.category(d.id)?.title ?: "Settings"
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
            is Dest.Rounds, is Dest.RoundEdit -> 3
            // Four, not three: Rounds took 3, and Settings has no bar item of its
            // own so its number only has to be distinct. A drawer is Settings as
            // far as the rail is concerned; the FLEET is not — it has been off
            // the "child of Settings" footing since the redesign, and claiming
            // the Settings slot while the reader is looking at machines is the
            // last thing still saying it belongs there.
            is Dest.Settings, is Dest.SettingsSection -> 4
            is Dest.Devices -> 6
            // Sessions, because that is where Projects lives: opening a cluster
            // from a notification must light the tab a project belongs to.
            is Dest.Projects, is Dest.Project -> 1
            // Status, for the same reason — the consoles card is a Status card.
            is Dest.Consoles -> 2
            // FIVE, which matches no bar item and no rail item — deliberately.
            // Pages are opened from wherever you already are, so highlighting a
            // section would claim you had navigated somewhere you had not.
            is Dest.Scratchpads, is Dest.Scratchpad -> 5
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
                models = models,
                onNewLocal = { modelId ->
                    vm.newLocalChat(modelId) { id -> vm.openChat(id); dest = Dest.Chat(id) }
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
                onAcknowledgeRound = { r, ack -> vm.acknowledgeRound(r.id, ack) },
                onOpenRound = { r ->
                    // The report if there is one; otherwise the Round itself. A
                    // schedule that has not fired yet used to swallow the tap
                    // entirely, which reads as a broken row.
                    val chat = r.lastRun?.chatId
                    if (chat != null) { vm.openChat(chat); dest = Dest.Chat(chat) }
                    else dest = Dest.RoundEdit(r.id)
                },
                onRunRound = { r -> vm.runRound(r.id) },
                onSetRoundEnabled = { r, on -> vm.setRoundEnabled(r.id, on) },
                // Refreshed here too, not only for a NEW round: without it a cold
                // launch opened the editor with an empty device list, which hid
                // where-it-runs for every Round that has a machine.
                onEditRound = { r -> vm.refreshDevices(); dest = Dest.RoundEdit(r.id) },
                onNewRound = { vm.refreshDevices(); dest = Dest.RoundEdit(null) },
                onOpenSettings = { dest = Dest.Settings },
                onStartPolling = { vm.startRoundsPolling() },
                onStopPolling = { vm.stopRoundsPolling() },
            )
        }
        val chatDetail: @Composable (String) -> Unit = { id ->
            val stillHere = rememberStillHere()
            DisposableEffect(id) {
                onDispose {
                    vm.detachStream(id); vm.clearSuggestions()
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
            // The Round this chat is the run of, found by the run itself rather
            // than by a field on the chat: it is the same lookup the Rounds list
            // does to open it, so the two cannot disagree about which is which.
            val fromRound = rounds.firstOrNull { it.lastRun?.chatId == id }
            ChatScreen(
                onContinueRound = fromRound
                    ?.takeIf { chatSealed && worthContinuing(it) }
                    ?.let { r -> { vm.continueRound(r) { newId -> vm.openChat(newId); dest = Dest.Chat(newId) } } },
                sealedRun = chatSealed,
                page = chatPage,
                error = chatError,
                messagesGone = chatGone,
                onRetry = { vm.retryChatTranscript(id) },
                streamingText = streamingText,
                activeTool = activeTool,
                sending = sending,
                mode = chatMode,
                model = chatModel,
                effort = chatEffort,
                models = models,
                started = chatStarted || sending,
                waking = chatWaking,
                onSetOptions = { m, e -> vm.setChatOptions(id, model = m, effort = e) },
                onMode = { vm.setChatOptions(id, mode = it) },
                onEscalate = if (com.silencelen.huginn.ui.ModelLabels.isLocal(chatModel, models)) ({
                    vm.escalateLocalChat { newId -> vm.openChat(newId); dest = Dest.Chat(newId) }
                }) else null,
                chatId = id,
                suggestions = suggestions,
                voiceReady = voiceReady,
                onVoicePermission = { voicePermission.launch(Manifest.permission.RECORD_AUDIO) },
                draft = drafts[HuginnViewModel.chatDraftKey(id)].orEmpty(),
                onDraft = { vm.setDraft(HuginnViewModel.chatDraftKey(id), it) },
                onSend = { vm.send(id, it) },
                onCancel = { vm.cancel(id) },
                onCopy = { vm.copy(it) },
                attachments = com.silencelen.huginn.ui.chipsFor(attachments, HuginnViewModel.chatDraftKey(id)),
                onAttach = { vm.attachImages(it, HuginnViewModel.chatDraftKey(id)) },
                onAttachFile = { vm.attachFiles(it, HuginnViewModel.chatDraftKey(id)) },
                onRemoveAttachment = { vm.removeAttachment(it) },
                onPasteImage = { vm.pasteImage(HuginnViewModel.chatDraftKey(id)) },
                pads = if (padsAvailable == true) pads else emptyList(),
                padRefId = padRefs[com.silencelen.huginn.ui.ScratchpadRules.chatRefKey(id)],
                onPadRef = { vm.setPadRef(com.silencelen.huginn.ui.ScratchpadRules.chatRefKey(id), it) },
                quickActions = status?.quickActions,
                onSelectionAction = { action, text ->
                    vm.runSelectionAction(
                        action = action,
                        selection = text,
                        draftKey = HuginnViewModel.chatDraftKey(id),
                        actions = status?.quickActions,
                        // A new chat inherits THIS chat's mode: "ask in a new chat"
                        // about something an Act run produced is still act-shaped.
                        mode = chatMode,
                        onOpened = { newId -> vm.openChat(newId); dest = Dest.Chat(newId) },
                    )
                },
            )
        }
        val sessionsPane: @Composable (Boolean) -> Unit = { twoPane ->
            // Keep the list live while it is visible.
            LifecycleStartEffect(Unit) {
                vm.startSessionsPolling()
                onStopOrDispose { vm.stopSessionsPolling() }
            }
            val archives by vm.archives.collectAsState()
            val archiveAvailable by vm.archiveAvailable.collectAsState()
            // The tree's own poll rides this screen: the headings over the list
            // are drawn from the same rows the Projects list draws, so they must
            // not be a snapshot taken whenever the app last started.
            if (projectDoors.grouping) {
                LifecycleStartEffect(Unit) {
                    vm.startProjectsPolling()
                    onStopOrDispose { vm.stopProjectsPolling() }
                }
            }
            SessionsScreen(
                sessions = sessions,
                // Empty when the daemon has no projects, which draws the list
                // exactly as it has always been drawn — see projectEntries.
                groups = if (projectDoors.grouping)
                    com.silencelen.huginn.ui.groupSessions(projects, projectMembers, sessions)
                else emptyList(),
                onOpenProject = { row -> vm.openProject(row.id); dest = Dest.Project(row.id) },
                onOpenProjects = if (projectDoors.sessionsIcon) ({
                    vm.refreshProjects(); dest = Dest.Projects
                }) else null,
                selectedName = if (twoPane) (dest as? Dest.SessionView)?.name else null,
                onOpen = { name -> dest = Dest.SessionView(name) },
                onCreate = { name -> vm.createSession(name) { dest = Dest.SessionView(it) } },
                onKill = { vm.killSession(it) },
                onSoftEnd = { vm.softEndSession(it) },
                onRename = { from, to -> vm.renameSession(from, to) },
                archives = archives,
                archiveAvailable = archiveAvailable,
                onArchive = { vm.archiveSession(it) },
                // Straight into it. A revive that left you looking at the list
                // would make the whole verb feel like it had not worked, and the
                // NAME has to be the host's answer — the old one is taken when
                // free and numbered when not.
                onRevive = { row -> vm.reviveArchive(row) { name -> dest = Dest.SessionView(name) } },
                onCopyResume = { row -> row.resumeCommand?.let { vm.copy(it, "claude --resume") } },
                onDeleteArchive = { vm.deleteArchive(it) },
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
                // The strip's own list. Lifecycle-gated like everything else
                // here: `?all=1` lifts the daemon's recency filter, so this is
                // the one agents call that grows without bound and must not run
                // from a pocket.
                vm.startStreamAgentsPolling(name)
                // The send queue. Lifecycle-gated like the rest, and it only
                // actually asks the daemon while something is waiting — see
                // startTypingPolling.
                vm.startTypingPolling(name)
                onStopOrDispose {
                    vm.stopScreenPolling(); vm.clearSuggestions(); vm.refreshSessions()
                    vm.stopTypingPolling()
                    // Every stream handle goes with the session: an offset into
                    // one agent's file means nothing in the next session's.
                    vm.stopStreamPolling()
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
                // The daemon's clock, not the phone's: the rows' timestamps are
                // the host's, and a phone minutes out would empty the strip in
                // the middle of a live fan-out. Zero when the transcript has not
                // said, which the picker reads as "no clock, trust the flags".
                streamItems = remember(
                    streamAgents,
                    transcript?.lastActivityTs,
                    selectedStream,
                    streamsExpanded,
                ) {
                    vm.streamItems(transcript?.lastActivityTs?.takeIf { it > 0 } ?: 0L)
                },
                streamsExpanded = streamsExpanded,
                onToggleStreamsExpanded = { vm.toggleStreamsExpanded() },
                selectedStream = selectedStream,
                onSelectStream = { vm.selectStream(name, it) },
                agentPage = agentPage,
                loadingAgentHistory = loadingAgentHistory,
                onLoadEarlierAgent = { vm.loadEarlierAgent(name) },
                streamsSupported = streamsSupported,
                streamNote = streamNote,
                // The session's OWN row from the list, so the marks describe this
                // session rather than the host's worst window.
                sessionHeadroom = sessions.firstOrNull { s -> s.name == name }?.headroom,
                headroom = headroomPill,
                nowMs = nowMs,
                onAutoResume = { vm.setSessionAutoResume(name, it) },
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
                quickActions = status?.quickActions,
                onSelectionAction = { action, text ->
                    vm.runSelectionAction(
                        action = action,
                        selection = text,
                        draftKey = HuginnViewModel.sessionDraftKey(name),
                        actions = status?.quickActions,
                        onOpened = { newId -> vm.openChat(newId); dest = Dest.Chat(newId) },
                    )
                },
                queueNote = com.silencelen.huginn.ui.SendQueue.note(typing[name]),
                onForceResize = { vm.forceFit() },
                onInterrupt = { vm.interruptSession(name) },
                working = sessionWorking,
                attachments = com.silencelen.huginn.ui.chipsFor(attachments, HuginnViewModel.sessionDraftKey(name)),
                onAttach = { vm.attachImages(it, HuginnViewModel.sessionDraftKey(name)) },
                onAttachFile = { vm.attachFiles(it, HuginnViewModel.sessionDraftKey(name)) },
                onRemoveAttachment = { vm.removeAttachment(it) },
                onPasteImage = { vm.pasteImage(HuginnViewModel.sessionDraftKey(name)) },
                onCopy = { vm.copy(it) },
                hasEarlier = hasEarlier,
                loadingHistory = loadingHistory,
                onLoadEarlier = { vm.loadEarlierTranscript(name) },
                pads = if (padsAvailable == true) pads else emptyList(),
                padRefId = padRefs[com.silencelen.huginn.ui.ScratchpadRules.sessionRefKey(name)],
                onPadRef = { vm.setPadRef(com.silencelen.huginn.ui.ScratchpadRules.sessionRefKey(name), it) },
                overviewPane = {
                    // Started and stopped by the TAB's own existence: the map is a
                    // whole-transcript walk on the host, so it must not be polled
                    // from behind the conversation. Lifecycle-gated like every
                    // other poll here — a DisposableEffect alone keeps running
                    // while the phone is in a pocket.
                    LifecycleStartEffect(name) {
                        vm.refreshPlan()
                        vm.startOverviewPolling(name)
                        onStopOrDispose { vm.stopOverviewPolling() }
                    }
                    SessionOverviewView(
                        overview = overview,
                        graph = sessionGraph,
                        plan = plan,
                        nowMs = nowMs,
                        goals = metaGoals,
                        notes = metaNotes,
                        saveState = metaSaveState,
                        density = overviewDensity,
                        onGoals = { vm.metaSaver.setGoals(it) },
                        onNotes = { vm.metaSaver.setNotes(it) },
                        onDensity = { overviewDensity = it },
                        unavailable = if (overview == null && sessionGraph == null) overviewNote else null,
                        note = metaNote,
                        onDismissNote = { vm.metaSaver.clearNote() },
                    )
                },
            )
        }
        val statusPane: @Composable () -> Unit = {
            DisposableEffect(Unit) {
                vm.refreshPlan()
                vm.refreshUsage()
                onDispose { vm.stopUsagePolling() }
            }
            // The registry is polled only while a consoles surface is on screen.
            // The PROBE is the host's and memoised there; this is just how often
            // its verdict is collected.
            if (consoleDoors.statusCard) {
                LifecycleStartEffect(Unit) {
                    vm.startConsolesPolling()
                    onStopOrDispose { vm.stopConsolesPolling() }
                }
            }
            StatusScreen(
                status = status,
                error = statusError,
                sessions = sessions.size,
                chatsRunning = chats.count { it.running },
                plan = plan,
                usage = usage,
                // Owner decision 48: consoles are a card on Status, not a tab.
                consoles = if (consoleDoors.statusCard) consoles else emptyList(),
                consolesApplied = com.silencelen.huginn.ui.ConsoleRules.approvalApplied(consoleApproval),
                onOpenConsole = { c -> openConsole(context, c, vm) },
                onSeeAllConsoles = if (consoleDoors.fullPage) ({ dest = Dest.Consoles }) else null,
                nowMs = nowMs,
            )
        }
        val projectsPane: @Composable () -> Unit = {
            LifecycleStartEffect(Unit) {
                vm.startProjectsPolling()
                onStopOrDispose { vm.stopProjectsPolling() }
            }
            com.silencelen.huginn.ui.ProjectsScreen(
                projects = projects,
                members = projectMembers,
                nowMs = nowMs,
                onOpenProject = { row -> vm.openProject(row.id); dest = Dest.Project(row.id) },
                onOpenMember = { live -> dest = Dest.SessionView(live.name) },
                onExpand = { id -> vm.fetchProjectMembers(id) },
                creating = projectBusy,
                refusal = projectRefusal,
                onDismissSheet = { vm.clearProjectRefusal() },
                // Left to the host: the daemon's WORKDIR is the default when no
                // directory is typed, and a client that guessed one would be
                // proposing a folder it has never seen.
                defaultCwd = null,
                onCreate = { name, kind, brief, cwd ->
                    // Straight into the lead it just launched: creating a project
                    // and then being left on a list of projects is the moment the
                    // whole verb reads as not having worked.
                    vm.createProject(name, kind, brief, cwd) { made ->
                        dest = Dest.Project(made.id)
                        vm.openProject(made.id)
                    }
                },
            )
        }
        val projectPane: @Composable (String) -> Unit = { id ->
            LifecycleStartEffect(id) {
                vm.openProject(id)
                vm.startDashboardPolling(id)
                onStopOrDispose { vm.stopDashboardPolling() }
            }
            com.silencelen.huginn.ui.ProjectDashboardScreen(
                detail = projectDetail?.takeIf { it.project.id == id },
                dashboard = projectDashboard,
                nowMs = nowMs,
                busy = projectBusy,
                refusal = projectRefusal,
                onOpenMember = { m -> dest = Dest.SessionView(m.name) },
                onSpawn = { rev -> vm.spawnProject(id, rev) },
                onDiscard = { vm.discardProposal(id) },
                onSaveManifest = { m ->
                    val rev = projectDetail?.project?.takeIf { it.id == id }?.rev ?: 0
                    vm.saveProjectManifest(id, rev, m)
                },
            )
        }
        val consolesPane: @Composable () -> Unit = {
            LifecycleStartEffect(Unit) {
                vm.startConsolesPolling()
                onStopOrDispose { vm.stopConsolesPolling() }
            }
            com.silencelen.huginn.ui.ConsolesScreen(
                consoles = consoles,
                approval = consoleApproval,
                nowMs = nowMs,
                onOpen = { c -> openConsole(context, c, vm) },
                onProbe = { c -> vm.probeConsole(c.id) },
                // COPY AND NOTHING ELSE. The steps rebind a unit on the host and
                // add firewall lines on heimdall; this app runs neither, ever.
                onCopyApproval = { text -> vm.copy(text, "the rebind steps") },
                onSave = { cid, version, name, url, notes -> vm.saveConsole(cid, version, name, url, notes) },
                onDelete = { c -> vm.deleteConsole(c.id) },
            )
        }
        val roundEditPane: @Composable (String?) -> Unit = { id ->
            RoundEditScreen(
                // Looked up from the live list rather than carried in the
                // destination, so a Round edited here is the one the poll is
                // holding — a stale copy in a nav argument would silently write
                // back whatever it was when the screen opened.
                existing = id?.let { rid -> rounds.firstOrNull { it.id == rid } },
                devices = devices,
                deviceTz = vm.deviceZone(),
                onCreate = { d, cb -> vm.createRound(d, cb) },
                onSave = { rid, d, cb -> vm.saveRound(rid, d, cb) },
                onDelete = { rid, cb -> vm.deleteRound(rid, cb) },
                onPolish = { d, f, cb -> vm.polishRound(d, f, cb) },
                onDone = { dest = Dest.Rounds },
            )
        }
        // The pages themselves. Polled only while one of these two is on screen —
        // a page changes when a person types into it, and nobody is typing into it
        // from a screen that is not showing it.
        val scratchpadsPane: @Composable () -> Unit = {
            LifecycleStartEffect(Unit) {
                vm.startScratchpadsPolling(PAD_WATCH_LIST)
                onStopOrDispose { vm.stopScratchpadsPolling(PAD_WATCH_LIST) }
            }
            ScratchpadListView(
                pads = pads,
                selectedId = null,
                nowMs = nowMs,
                onOpen = { p -> vm.openScratchpad(p.id); dest = Dest.Scratchpad(p.id) },
                onCreate = { name -> vm.createScratchpad(name) { id -> dest = Dest.Scratchpad(id) } },
                onDelete = { p -> vm.deleteScratchpad(p.id) },
            )
        }
        val scratchpadPane: @Composable (String) -> Unit = { id ->
            // Fetched on open, not polled: a poll that replaced the text under a
            // cursor would be an editor that types back at you.
            LaunchedEffect(id) { if (openPad?.id != id) vm.openScratchpad(id) }
            // The scope that writes is the view model's, so leaving the screen is
            // exactly when the flush has to run and exactly when a composition
            // scope would already be gone.
            DisposableEffect(id) { onDispose { vm.padSaver.flush() } }
            // NAMED, because on a wide screen the list is on the same screen and
            // watching the same poll: an unnamed stop from this pane took the
            // list's poll down with it, and the list then sat frozen with nothing
            // on screen looking wrong. See HuginnViewModel.padWatchers.
            LifecycleStartEffect(Unit) {
                vm.startScratchpadsPolling(PAD_WATCH_EDITOR)
                onStopOrDispose { vm.stopScratchpadsPolling(PAD_WATCH_EDITOR) }
            }
            ScratchpadEditorView(
                pad = openPad?.takeIf { it.id == id },
                pads = pads,
                state = padSaveState,
                note = padNote,
                onEdit = { vm.padSaver.set(it) },
                onSwitch = { p -> vm.openScratchpad(p.id); dest = Dest.Scratchpad(p.id) },
                onDismissNote = { vm.padSaver.clearNote() },
                // Absent on an empty page rather than present and inert: there
                // is nothing to send, and a button that does nothing when pressed
                // reads as broken.
                onSendElsewhere = openPad?.content?.takeIf { it.isNotBlank() }
                    ?.let { text -> { pendingPadText = text } },
                onRename = { name -> vm.renameScratchpad(id, name) },
            )
        }
        val devicesPane: @Composable () -> Unit = {
            DevicesScreen(
                devices = devices,
                loading = loading,
                connected = connected,
                onStart = { d, mode ->
                    // Straight into the chat it just made: starting work on a
                    // machine and then being left on a list of machines is the
                    // moment the feature reads as not having worked.
                    vm.newChat(mode, host = d.id) { id -> vm.openChat(id); dest = Dest.Chat(id) }
                },
                onForget = { vm.forgetDevice(it.id) },
                onOpenSettings = { dest = Dest.SettingsSection("host") },
                onStartPolling = { vm.startDevicesPolling() },
                onStopPolling = { vm.stopDevicesPolling() },
            )
        }
        // ONE lambda for the list and for a drawer: the frame decides which of
        // them is on screen, and on a wide display it draws both.
        val settingsPane: @Composable (String?) -> Unit = { section ->
            SettingsPhoneScreen(
                vm = vm,
                section = section,
                onSelectCategory = { id ->
                    dest = if (id == null) Dest.Settings else Dest.SettingsSection(id)
                },
                query = settingsQuery,
                onQuery = { settingsQuery = it },
                twoPane = wide,
                nowMs = nowMs,
                appVersion = vm.installedVersion,
                appdVersion = status?.appdVersion,
                appLockAvailable = remember { AppLock.canLock(context) },
                notificationsAllowed = notificationsAllowed,
                onOpenFleet = { vm.refreshDevices(); dest = Dest.Devices },
                // The second door into Projects, and the reason the bottom bar
                // stays at four. Null against a daemon with no projects route:
                // a Settings row whose only outcome is a 404 is worse than none.
                onOpenProjects = if (projectDoors.settingsRow) ({
                    vm.refreshProjects(); tab = 1; dest = Dest.Projects
                }) else null,
                projectCount = projects.size,
                // Straight to the whole picture, the same place the headroom pill
                // goes: a number you cannot ask "of what, and until when" is a
                // worse version of not saying anything.
                onOpenStatus = { tab = 2; dest = Dest.Status },
                onLockNow = onLockNow,
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
                openLink = { url ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                },
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
                        // ⚠ NO HEADROOM PILL HERE (owner decision 29, 2026-09-15).
                        //
                        // It used to be first and on every screen, and it cost the
                        // TITLE its width: "Usage & headr…", "Appearance & …" and a
                        // session called "Main documentation…" all truncated to make
                        // room for "Fable 47% · resets 5d" — a reading that is
                        // already on the Status page in full and already under the
                        // Status icon as a 2px fill. Two surfaces said it; the third
                        // was taking the one thing the bar exists to say.
                        //
                        // The chip composable is gone from `:ui` (owner decision 29);
                        // TopBarTitleTest keeps this file from growing one back.
                        if (dest !is Dest.Chat && dest !is Dest.SessionView) {
                            IconButton(onClick = { vm.refreshAll() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                            }
                        }
                        if (dest !is Dest.Settings && dest !is Dest.SettingsSection &&
                            dest !is Dest.Chat && dest !is Dest.SessionView
                        ) {
                            IconButton(onClick = { dest = Dest.Settings }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        }
                        // The way into the pages, offered from the four places a
                        // person is when they want one: either list, and either
                        // kind of conversation. Hidden entirely against a daemon
                        // that has no scratchpads — see scratchpadsAvailable.
                        if (padsAvailable == true &&
                            (dest is Dest.Chats || dest is Dest.Sessions ||
                                dest is Dest.Chat || dest is Dest.SessionView)
                        ) {
                            IconButton(onClick = { vm.refreshScratchpads(); dest = Dest.Scratchpads }) {
                                Icon(Icons.Outlined.EditNote, contentDescription = "Pages")
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
                                            // Getting text back OUT of the pane, which until now
                                            // could be read and nothing else. Links first and only
                                            // when there are some: a wrapped URL is the case a
                                            // person cannot solve any other way — they cannot
                                            // retype 450 characters, and selecting it would hand
                                            // back the rows the terminal broke it into.
                                            // SNAPSHOT ON OPEN. Read live, a URL
                                            // scrolling on or off the pane inserts
                                            // or removes the FIRST item while the
                                            // menu is open — shifting every item
                                            // below it by a row, so a tap aimed at
                                            // "Wind down…" lands on "Kill session…".
                                            val links = remember(surfaceMenu) { linksOn(vm.screen.value) }
                                            if (links.size == 1) {
                                                DropdownMenuItem(text = { Text("Copy link") },
                                                    onClick = { surfaceMenu = false; vm.copy(links[0], "link") })
                                            } else if (links.size > 1) {
                                                DropdownMenuItem(text = { Text("Copy ${links.size} links") },
                                                    onClick = { surfaceMenu = false; vm.copy(links.joinToString("\n"), "links") })
                                            }
                                            DropdownMenuItem(text = { Text("Copy screen") },
                                                onClick = { surfaceMenu = false; vm.copy(screenText(vm.screen.value), "screen") })
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
                            icon = { StatusIcon(sessionUsage) },
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
                            icon = { StatusIcon(sessionUsage) },
                            label = { Text("Status") },
                        )
                        Spacer(Modifier.weight(1f))
                        // Its own footing, at last. The fleet used to share the
                        // Settings slot because it was "a child of Settings, not a
                        // bar item"; Settings' Devices drawer now holds only the
                        // row that opens this, so the machines are a place you go
                        // rather than a setting you change. RAIL ONLY — the bottom
                        // bar stays four, because giving this a bar slot would
                        // cost one of the four a place it earns every day.
                        NavigationRailItem(
                            selected = section == 6,
                            onClick = { vm.refreshDevices(); dest = Dest.Devices },
                            icon = { Icon(Icons.Filled.Devices, contentDescription = "Devices") },
                            label = { Text("Devices") },
                        )
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
                            is Dest.RoundEdit -> roundEditPane(d.id)
                            is Dest.Devices -> devicesPane()
                            is Dest.Scratchpads -> scratchpadsPane()
                            is Dest.Scratchpad -> scratchpadPane(d.id)
                            is Dest.Status -> statusPane()
                            is Dest.Projects -> projectsPane()
                            is Dest.Project -> projectPane(d.id)
                            is Dest.Consoles -> consolesPane()
                            // ONE call site for both, deliberately. Two `when`
                            // branches are two composition groups, so opening a
                            // drawer from a search hit would DISCARD the frame's
                            // state — including which row the hit meant to mark,
                            // which is the one thing the hit was for.
                            is Dest.Settings, is Dest.SettingsSection ->
                                settingsPane((dest as? Dest.SettingsSection)?.id)
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
                            // Reading surfaces: full width helps nobody at 900dp,
                            // so they keep a readable measure. Where that measure
                            // HANGS FROM is the next line's business — Status is
                            // left-snapped, these are still centred.
                            is Dest.Rounds -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopStart) {
                                Box(Modifier.widthIn(max = 840.dp)) { roundsPane() }
                            }
                            is Dest.RoundEdit -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopStart) {
                                Box(Modifier.widthIn(max = 840.dp)) { roundEditPane(d.id) }
                            }
                            // ⚠ CAPPED AND LEFT-SNAPPED, not capped and centred.
                            // THE OWNER REPORTED THE CENTRED VERSION: *"instead of
                            // it hitting its limit and staying centered, lets have
                            // it stay snapped on the left."* The cap is right and
                            // stays; what centring adds is a column that TRACKS the
                            // window, so every label slides sideways when the fold
                            // opens and the eye has to find `host` again. The
                            // desktop's `ReadingPane` made the same change, which is
                            // what keeps the two clients one page.
                            //
                            // Rounds, RoundEdit and Devices above and below are the
                            // same cap+centre and are deliberately UNTOUCHED: they
                            // are four separate call sites rather than one shared
                            // modifier, and only this one was reported.
                            is Dest.Status -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopStart) {
                                Box(Modifier.widthIn(max = 840.dp)) { statusPane() }
                            }
                            // Tree on the left, dashboard on the right — the shape
                            // Chats, Sessions and Pages already take when the fold
                            // opens, and the one the design asked for by name.
                            is Dest.Projects, is Dest.Project -> Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(292.dp).fillMaxSize()) { projectsPane() }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.weight(1f).fillMaxSize()) {
                                    val open = dest as? Dest.Project
                                    if (open != null) projectPane(open.id)
                                    else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                        EmptyState("No project open", "Pick one on the left, or start a new one.")
                                    }
                                }
                            }
                            // A reading surface, capped and left-snapped like
                            // Status — it is Status's own card, full screen.
                            is Dest.Consoles -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopStart) {
                                Box(Modifier.widthIn(max = 840.dp)) { consolesPane() }
                            }
                            // List and detail side by side, the shape Chats,
                            // Sessions and Pages already take when the fold
                            // opens. The frame owns the seam and the reading cap,
                            // so this hands it the whole width rather than
                            // centring a column inside it.
                            is Dest.Settings, is Dest.SettingsSection ->
                                settingsPane((dest as? Dest.SettingsSection)?.id)
                            is Dest.Devices -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopStart) {
                                Box(Modifier.widthIn(max = 840.dp)) { devicesPane() }
                            }
                            // List and editor side by side, the same shape the
                            // chats and sessions take when the fold opens.
                            is Dest.Scratchpads, is Dest.Scratchpad -> Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(292.dp).fillMaxSize()) {
                                    LifecycleStartEffect(Unit) {
                                        vm.startScratchpadsPolling(PAD_WATCH_LIST)
                                        onStopOrDispose { vm.stopScratchpadsPolling(PAD_WATCH_LIST) }
                                    }
                                    ScratchpadListView(
                                        pads = pads,
                                        selectedId = (dest as? Dest.Scratchpad)?.id,
                                        nowMs = nowMs,
                                        onOpen = { p -> vm.openScratchpad(p.id); dest = Dest.Scratchpad(p.id) },
                                        onCreate = { name -> vm.createScratchpad(name) { id -> dest = Dest.Scratchpad(id) } },
                                        onDelete = { p -> vm.deleteScratchpad(p.id) },
                                    )
                                }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.weight(1f).fillMaxSize()) {
                                    val open = dest as? Dest.Scratchpad
                                    if (open != null) scratchpadPane(open.id)
                                    else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                        EmptyState("No page open", "Pick one on the left, or start a new one.")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Status destination's icon, with the session-usage fill under it.
 *
 * A COMPOSABLE AND NOT TWO COPIES: the bottom bar and the rail draw the same
 * destination, and the one that got the line while the other did not is exactly
 * the drift a shared composable is for. Draws the bare icon when there is no
 * reading — an older daemon costs 2dp of nothing.
 */
@Composable
private fun StatusIcon(fill: com.silencelen.huginn.ui.UsageFill?) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Icon(Icons.Filled.MonitorHeart, contentDescription = null)
        fill?.let {
            Spacer(Modifier.height(2.dp))
            com.silencelen.huginn.ui.UsageFillLine(it, Modifier.width(24.dp))
        }
    }
}


/**
 * Opens a console in the phone's browser.
 *
 * http(s) ONLY, through `ConsoleRules.openable` — the same bar the link handler
 * sets for anything a model writes. What will not open is copied instead, so the
 * address is still in the reader's hands.
 */
private fun openConsole(
    context: android.content.Context,
    console: com.silencelen.huginn.data.Console,
    vm: HuginnViewModel,
) {
    if (!com.silencelen.huginn.ui.ConsoleRules.openable(console)) {
        vm.copy(console.url, "the address")
        return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(console.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { vm.copy(console.url, "the address") }
}
