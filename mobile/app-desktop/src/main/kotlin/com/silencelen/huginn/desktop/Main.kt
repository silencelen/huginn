package com.silencelen.huginn.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.desktop.diag.NotifierSeam
import com.silencelen.huginn.desktop.notify.Activation
import com.silencelen.huginn.desktop.notify.Activations
import com.silencelen.huginn.desktop.notify.NavTarget
import com.silencelen.huginn.desktop.notify.NotifyRequest
import com.silencelen.huginn.desktop.notify.NotifyRouter
import com.silencelen.huginn.desktop.notify.Notifiers
import com.silencelen.huginn.desktop.notify.SchemeRegistrar
import com.silencelen.huginn.desktop.notify.SingleInstance
import com.silencelen.huginn.desktop.notify.TargetKind
import com.silencelen.huginn.desktop.tray.TrayIcons
import com.silencelen.huginn.desktop.tray.TrayModel
import com.silencelen.huginn.desktop.ui.Shell
import com.silencelen.huginn.ui.LocalTranscriptMetrics
import com.silencelen.huginn.ui.TranscriptMetrics
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The Compose Multiplatform desktop client for huginn-appd.
 *
 * Everything it knows about the daemon comes from `:core` — the same Kotlin the
 * phone runs. This file owns the process: the single-instance guard, the tray, the
 * notification router, `huginn://` activation, and one window whose visibility
 * drives [Presence].
 *
 * The ALWAYS-ON shape is deliberate and is the reason so much lives outside the
 * composition. The window is a view onto a client that keeps running: closing it
 * (with close-to-tray on) hides it, and the watch stream, the notification claim
 * and the tray summary carry on. Anything that must survive the window being gone
 * is built here, before `application {}`, and merely READ from the composition.
 */
fun main(args: Array<String>) {
    val settings = DesktopSettings()
    val configDir = File(settings.path).parentFile ?: File(System.getProperty("user.home"), ".config")

    // FIRST, before anything opens a socket or claims a lease. A protocol
    // activation launches a whole new process with the URL in argv, so without
    // this every toast button click would start a second client: two watch
    // streams, two notification claims, two clients fighting over the tmux size
    // lease. Null means an instance was already running and has taken delivery.
    val instance = SingleInstance.claimOrForward(configDir, Activations.urlFromArgv(args))
        ?: return

    val presence = Presence()
    // SupervisorJob: one loop failing (a watch stream against an unreachable
    // route) must not take the poll loop and the UI's coroutines down with it.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = AppStore(settings, presence, scope)

    // The tray icon is also the fallback notification surface, so its state is
    // built before the notifier that may need it.
    val trayState = TrayState()
    val notifier = Notifiers.choose(configDir, DesktopSettings.isPackaged(), trayState)
    println("[huginn] ${Notifiers.describe(notifier)}")
    println("[huginn] ${SchemeRegistrar.register()}")

    // Settings' "send test notification" fires through the REAL delivery path, not
    // a second one built to look like it — a test button wired to its own code path
    // is a green light that proves nothing. NotifierSeam holds the slot.
    NotifierSeam.name = notifier.name
    NotifierSeam.sendTest = {
        notifier.post(
            NotifyRequest(
                key = "diag-test",
                title = "Huginn",
                body = "Test notification from Settings",
                urgent = false,
                target = NavTarget(TargetKind.CHATS, ""),
            )
        )
        notifier.healthy
    }

    // Window control, held OUTSIDE the composition because the tray, an
    // activation and a second launch all have to reach it — and two of those can
    // happen while the window is hidden.
    val windowVisible = MutableStateFlow(true)
    val summonTick = MutableStateFlow(0L)
    val windowFocused = MutableStateFlow(false)
    val traySummary = MutableStateFlow(TrayModel.EMPTY)

    fun summon() {
        windowVisible.value = true
        summonTick.value += 1
    }

    /**
     * What the reader is looking at, or null when they are not looking at this
     * window at all. The router suppresses a notification for exactly this target:
     * its question is already on screen in front of them.
     */
    fun focusedTarget(): NavTarget? {
        if (!windowFocused.value) return null
        return when (store.view.value) {
            View.CHATS -> store.chatId.value?.let { NavTarget(TargetKind.CHATS, it) }
            View.SESSIONS -> store.sessionName.value?.let { NavTarget(TargetKind.SESSIONS, it) }
            else -> null
        }
    }

    val router = NotifyRouter(
        scope = scope,
        notifier = { notifier },
        // One-shot, no long poll: this runs on the notification path and a parked
        // request would hold the enrichment open past the moment it is worth.
        fetchPrompt = { name -> runCatching { store.client.screen(name).prompt }.getOrNull() },
        enabled = { settings.notifyEnabledNow() },
        focusedTarget = ::focusedTarget,
    )

    store.onDigest = { watch ->
        router.onDigest(watch)
        traySummary.value = TrayModel.summarize(watch)
    }

    fun navigate(target: NavTarget) {
        summon()
        when (target.kind) {
            TargetKind.CHATS -> store.openChat(target.id)
            TargetKind.SESSIONS -> store.openSession(target.id)
        }
        // Opening a target reads as acknowledgement, like the phone.
        router.onViewed(target)
    }

    fun answerFromActivation(a: Activation.Answer) {
        scope.launch {
            val outcome = runCatching { store.client.answerPrompt(a.session, a.option, a.fingerprint) }
                .fold(
                    onSuccess = { r -> if (r.ok) "option ${a.option}" else (r.error ?: "the question moved on") },
                    // A 409 lands here carrying the daemon's own sentence. ORDINARY:
                    // the click was right when it was offered, so it is reported and
                    // never retried.
                    onFailure = { e -> (e as? HuginnClient.HuginnException)?.message ?: "could not answer" },
                )
            // Reported rather than swallowed — a button that may or may not have
            // worked is worse than one that says which.
            notifier.post(
                NotifyRequest(
                    key = "answer:${a.session}",
                    title = a.session,
                    body = outcome,
                    urgent = false,
                    target = NavTarget(TargetKind.SESSIONS, a.session),
                )
            )
        }
    }

    fun handle(url: String?) {
        when (val activation = Activations.parse(url)) {
            // Includes an activation that was REFUSED — most importantly an
            // `answer` with no fingerprint. Bringing the window up is the right
            // response to that: the reader gets to see the question and decide,
            // and nothing was typed on their behalf.
            null -> summon()
            is Activation.Open -> navigate(activation.target)
            is Activation.Answer -> answerFromActivation(activation)
        }
    }

    instance.listen { url -> handle(url) }

    // RESUME FROM SLEEP. A suspend black-holes every socket at once; nothing
    // errors on wake, the connection simply hangs until an idle timeout fires —
    // up to three minutes of a client that looks attached and is not. There is no
    // powerMonitor in a plain JVM, so this infers it from two clocks disagreeing.
    scope.launch {
        val detector = SleepDetector(intervalMs = RESUME_TICK_MS)
        while (isActive) {
            delay(RESUME_TICK_MS)
            if (detector.tick(System.currentTimeMillis(), System.nanoTime())) {
                println("[huginn] resumed from sleep — re-opening streams")
                presence.noteResume()
            }
        }
    }

    // THE LAST-CHANCE RELEASE. Registered once, and deliberately not the only one:
    // the ordinary paths (leaving the view, hiding the window, closing it) each
    // release for themselves, and this is what covers everything that never
    // reaches them — SIGTERM, a kill from a session manager, an exception on the
    // way out. Without it a force-quit leaves the owner's tmux window pinned at
    // this window's shape until the daemon's 90-second lease lapses.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            store.paneLease.releaseBlocking()
            instance.close()
        }
    )

    // A URL that arrived on OUR argv, once there is something for it to act on.
    val startupUrl = Activations.urlFromArgv(args)

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 840.dp))
        val visible by windowVisible.collectAsState()
        val summary by traySummary.collectAsState()
        val closeToTray by settings.closeToTray.collectAsState()

        fun quit() {
            // Before the process starts unwinding, while the client is certainly
            // still usable. Doing it twice is free: the holder clears what it holds
            // before the call, so the shutdown hook finds nothing left to do.
            store.paneLease.releaseBlocking()
            instance.close()
            notifier.close()
            exitApplication()
        }

        // VISIBILITY, from the window's real state rather than assumed. BOTH
        // minimized and hidden-to-tray count as invisible: a window that keeps
        // polling while nobody can see it renews the tmux size lease, pinning
        // someone else's session to this window's geometry for as long as it
        // stays away.
        LaunchedEffect(Unit) {
            combine(snapshotFlow { windowState.isMinimized }, windowVisible) { minimized, shown ->
                shown && !minimized
            }.collect { presence.setVisible(it) }
        }

        if (isTraySupported) {
            Tray(
                icon = TrayIcons.painter(summary.state),
                state = trayState,
                tooltip = summary.tooltip,
                onAction = { summon() },
            ) {
                Item("Open Huginn", onClick = { summon() })

                if (summary.attention.isNotEmpty()) {
                    Separator()
                    // The sessions blocked on a human, by name and one click away.
                    // This is the tray earning its place: it is the only surface
                    // that answers "what is waiting on me" without opening
                    // anything.
                    for (name in summary.attention) {
                        Item("$name needs you", onClick = { navigate(NavTarget(TargetKind.SESSIONS, name)) })
                    }
                }

                if (summary.working > 0) {
                    Separator()
                    Item(workingLabel(summary.working, summary.workingChats), enabled = false, onClick = {})
                }

                Separator()
                CheckboxItem(
                    "Close to tray",
                    checked = closeToTray,
                    onCheckedChange = { settings.setCloseToTray(it) },
                )
                Separator()
                Item("Quit Huginn", onClick = { quit() })
            }
        }

        Window(
            onCloseRequest = {
                // Close-to-tray: the watch stream, the notification router and the
                // tray summary all live on. With it off, closing the window really
                // does mean quitting — an app that goes on running headless after
                // its window is gone is one nothing can get rid of.
                if (closeToTray && isTraySupported) windowVisible.value = false else quit()
            },
            state = windowState,
            visible = visible,
            title = "Huginn",
            onKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@Window false
                when {
                    // Ctrl+Shift+H: hide to tray. NOT a global hotkey — see the
                    // note at the foot of this file; it only fires while the window
                    // already has focus, which makes it a hide and not a summon.
                    e.isShiftPressed && e.key == Key.H -> {
                        if (isTraySupported) windowVisible.value = false
                        true
                    }
                    e.isShiftPressed -> false
                    e.key == Key.One -> { store.openView(View.CHATS); true }
                    e.key == Key.Two -> { store.openView(View.SESSIONS); true }
                    e.key == Key.Three -> { store.openView(View.STATUS); true }
                    e.key == Key.Comma -> { store.openView(View.SETTINGS); true }
                    else -> false
                }
            },
        ) {
            // PRESENCE, from window focus. This is what the notification claim
            // rides on, so it must reflect the desk rather than the process being
            // alive.
            val windowInfo = LocalWindowInfo.current
            LaunchedEffect(Unit) {
                snapshotFlow { windowInfo.isWindowFocused }.collect {
                    windowFocused.value = it
                    presence.setFocused(it)
                }
            }

            // Bringing the window back: un-minimize, raise, take focus. Guarded on
            // a non-zero tick so opening the app does not fight the window manager
            // for focus it already has.
            val tick by summonTick.collectAsState()
            LaunchedEffect(tick) {
                if (tick > 0L) {
                    windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }

            // ACKNOWLEDGEMENT. Arriving at a target takes its notification down,
            // wherever the navigation came from — the tray, an activation, or the
            // reader simply clicking the row.
            LaunchedEffect(Unit) {
                combine(store.view, store.chatId, store.sessionName) { view, chat, session ->
                    when (view) {
                        View.CHATS -> chat?.let { NavTarget(TargetKind.CHATS, it) }
                        View.SESSIONS -> session?.let { NavTarget(TargetKind.SESSIONS, it) }
                        else -> null
                    }
                }.collect { target -> target?.let { router.onViewed(it) } }
            }

            LaunchedEffect(Unit) { store.start() }

            // An activation that started the process, replayed once there is a
            // window and a store to act on.
            LaunchedEffect(Unit) { startupUrl?.let { handle(it) } }

            // The SAME theme the phone applies, told three things about this window:
            // dark outright (a light scheme nobody has asked for is a scheme nobody
            // has checked), mono two points larger (arm's length, not reading
            // distance), and a root Surface — which is load-bearing and silent when
            // missing, because `LocalContentColor` defaults to BLACK and only a
            // Surface provides it. The phone's root is a Scaffold and needs none.
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                // The one thing the shared transcript rows cannot work out for
                // themselves: a bubble sized as 90% of a phone is a bubble; 90% of a
                // 1280pt window is a bar. Full width with a reading-measure cap, set
                // once here so every surface that renders transcript rows — chat now,
                // the session view in 3c — gets the same answer.
                CompositionLocalProvider(
                    LocalTranscriptMetrics provides TranscriptMetrics(
                        userBubbleFraction = 1f,
                        userBubbleMaxWidth = 640.dp,
                    )
                ) {
                    Shell(store)
                }
            }
        }
    }
}

private fun workingLabel(working: Int, chats: Int): String =
    "$working working" + if (chats > 0) " ($chats chat${if (chats == 1) "" else "s"})" else ""

/** How often the resume detector looks at the two clocks. */
private const val RESUME_TICK_MS: Long = 15_000

// A GLOBAL hotkey — one that summons the window from ANOTHER application — is NOT
// achievable from a plain JVM. Windows needs RegisterHotKey, X11 needs XGrabKey,
// Wayland needs a compositor portal, and the JDK exposes none of the three; every
// library that does it (JNativeHook, JIntellitype) ships native code, which an
// unsigned build on the owner's daily driver has no business loading.
//
// What exists instead, and covers the same need without a native blob in this
// process: the tray icon summons on click, and `huginn://open` summons from
// anywhere — so a desktop-level shortcut bound by the OS itself to run
// `xdg-open huginn://open?view=sessions&id=<name>` (or the Windows equivalent)
// does the job with the OS's own key grabbing. Ctrl+Shift+H below is the HIDE
// half only, and is honest about being in-window.
