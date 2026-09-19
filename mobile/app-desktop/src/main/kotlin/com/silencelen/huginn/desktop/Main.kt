package com.silencelen.huginn.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.silencelen.huginn.desktop.ui.settings.SettingsFacts
import com.silencelen.huginn.desktop.ui.settings.desktopProbe
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.Surface as SettingsSurface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.desktop.diag.AppLog
import com.silencelen.huginn.desktop.notify.Activation
import com.silencelen.huginn.desktop.notify.Activations
import com.silencelen.huginn.desktop.notify.NavTarget
import com.silencelen.huginn.desktop.notify.NoNotifier
import com.silencelen.huginn.desktop.notify.canDeliver
import com.silencelen.huginn.desktop.notify.NotifyRequest
import com.silencelen.huginn.desktop.notify.NotifyRouter
import com.silencelen.huginn.desktop.notify.Notifiers
import com.silencelen.huginn.desktop.notify.SchemeRegistrar
import com.silencelen.huginn.desktop.notify.SingleInstance
import com.silencelen.huginn.desktop.setup.Autostart
import com.silencelen.huginn.desktop.setup.FirstRun
import com.silencelen.huginn.desktop.setup.SetupController
import com.silencelen.huginn.desktop.setup.SetupHost
import com.silencelen.huginn.desktop.ui.setup.DesktopSetupProbes
import com.silencelen.huginn.desktop.notify.TargetKind
import com.silencelen.huginn.desktop.tray.RavenMark
import com.silencelen.huginn.desktop.tray.TrayIcons
import com.silencelen.huginn.desktop.tray.TrayModel
import com.silencelen.huginn.desktop.ui.Shell
import com.silencelen.huginn.ui.HeadroomRules
import com.silencelen.huginn.ui.LocalAttachmentImages
import com.silencelen.huginn.ui.LocalLinkPeek
import com.silencelen.huginn.desktop.ui.common.DesktopLinkPeek
import com.silencelen.huginn.desktop.ui.common.openInBrowser
import com.silencelen.huginn.desktop.ui.common.rememberLinkUriHandler
import androidx.compose.ui.platform.LocalUriHandler
import com.silencelen.huginn.desktop.ui.common.DesktopRowTime
import com.silencelen.huginn.ui.LocalRowTime
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.utf16CodePoint
import com.silencelen.huginn.desktop.ui.Cheatsheet
import com.silencelen.huginn.desktop.ui.CommandPalette
import com.silencelen.huginn.desktop.ui.PaletteItem
import com.silencelen.huginn.desktop.ui.Shortcut
import com.silencelen.huginn.desktop.ui.common.Frame
import com.silencelen.huginn.desktop.ui.isChordDebris
import com.silencelen.huginn.desktop.ui.keyName
import com.silencelen.huginn.desktop.ui.match
import com.silencelen.huginn.desktop.ui.padPanelFits
import com.silencelen.huginn.desktop.ui.padPanelHasHome
import com.silencelen.huginn.desktop.ui.padPanelShowing

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
    // ALSO to AppLog, not only to a stdout nobody on Windows ever sees: the
    // packaged launcher is a GUI binary with no console attached, so for six
    // weeks this line reported a half-finished registration into the void while
    // toast buttons answered "don't know how to open the link huginn". In the log
    // ring it rides along in "Copy diagnostics", which is where the next person
    // to ask "why did that button do nothing" will look.
    val schemeStatus = SchemeRegistrar.register()
    println("[huginn] $schemeStatus")
    if (schemeStatus.startsWith("scheme registration failed")) AppLog.warn("notify", schemeStatus)
    else AppLog.info("notify", schemeStatus)

    // Named in the diagnostics report, because which path a notification took is
    // the first thing worth knowing when one did not arrive. Null for NoNotifier,
    // which is not a path but the absence of one, and the report says so.
    AppLog.notifierName = notifier.name.takeIf { notifier !== NoNotifier }

    // The claim's missing third input: this desktop only tells the daemon it is a
    // notification route when the chosen notifier can actually render one. Read
    // live (a FallbackNotifier's health can change), so a backend going dark
    // releases the claim and the Telegram fallback resumes.
    store.canDeliver = { notifier.canDeliver() }

    // ------------------------------------------------------------ first run
    //
    // BUILT HERE, before `application {}`, for the same reason the notifier and
    // the single-instance guard are: a probe in flight — an enrolment, a
    // local-AI install, a shortcut write — must survive the window being hidden
    // to the tray. `LocalServeSection` learned that the hard way and this flow
    // has strictly more to lose.
    //
    // The installer's answers are CONSUMED here, once: `first-run.json` sits
    // beside `settings.json` because the NSIS uninstaller already has to find
    // that directory, and because it is the one channel that survives a second
    // launch and the silent self-update path. A missing or unreadable file means
    // no pre-answers, which is exactly what the `.deb` produces — Linux meets
    // the same flow, it simply arrives with nothing already ticked.
    val setup = SetupController(settings, DesktopSetupProbes(store) { notifier })
    SetupHost.install(setup)
    setup.adopt(FirstRun.consume(configDir))
    // Makes the disk agree with the flag. The startup entry can go without this
    // app being told — an upgrade that replaced the launcher, a restored
    // profile, the desktop's own Startup editor — and the flag is what the owner
    // actually chose, so the flag wins. Off the launch path: on Windows it can
    // spawn a PowerShell, and nothing about a shortcut is worth delaying a window.
    scope.launch { runCatching { Autostart.reconcile(settings) } }
    // ⚠ EMPTINESS IS THE SIGNAL, not a version number or a sentinel. Desktop
    // 1.2.0 made a fresh install an empty route book plus `NO_ROUTE` precisely
    // so a first run is distinguishable from an upgrade; an install that has
    // finished the flow once is never raised again, however empty its book.
    setup.open(routeBookEmpty = settings.routeBookNow().routes.isEmpty())

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

    /**
     * Put a laddered session back on its own model.
     *
     * Reported rather than swallowed, like the answer path: an Undo that may or
     * may not have landed is worse than one that says which, and the session is
     * still sitting on whatever model it is sitting on either way. The notice is
     * posted under the SAME key as the downgrade it reverses, so the toast that
     * offered the button is replaced by its own outcome.
     *
     * ⚠ AND "landed" IS NOT "ok". A session mid-turn has its undo held to the
     * next turn boundary; the route still answers `ok:true`, and this used to
     * report it as done. [HeadroomRules.undoWords] is the one place those two
     * outcomes are told apart.
     */
    fun undoFromActivation(a: Activation.Undo) {
        scope.launch {
            val outcome = runCatching { store.client.undoLadder(a.session) }
                .fold(
                    onSuccess = { HeadroomRules.undoWords(it) },
                    onFailure = { e -> (e as? HuginnClient.HuginnException)?.message ?: "could not undo" },
                )
            store.refreshHeadroom()
            notifier.post(
                NotifyRequest(
                    key = "ladder:${a.session}",
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
            is Activation.Undo -> undoFromActivation(activation)
            // Dismissal, and nothing else: no window, no navigation. "OK" on a
            // downgrade means "I have read that" — moving the reader somewhere is
            // a different verb wearing the same word.
            is Activation.Ack -> notifier.withdraw(activation.key)
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
            // Same argument as the lease: the debounced position writer is still
            // counting when a SIGTERM arrives, so without this the last thing the
            // owner opened is exactly what a force-quit forgets.
            store.flushLanding()
            instance.close()
        }
    )

    // A URL that arrived on OUR argv, once there is something for it to act on.
    val startupUrl = Activations.urlFromArgv(args)

    application {
        // WHERE IT WAS LAST TIME, sanity-checked against the screen it is opening
        // on now. `WindowLayout.restore` is pure and tested because the failure it
        // prevents is indistinguishable from a crash: a window restored onto a
        // monitor that has since been unplugged simply never appears, and the
        // process is running the whole time.
        val restored = remember { WindowLayout.restore(settings.windowLayout.value, screens()) }
        val windowState = rememberWindowState(
            size = DpSize(restored.w.dp, restored.h.dp),
            position = if (restored.placed) {
                WindowPosition(restored.x.dp, restored.y.dp)
            } else {
                WindowPosition(Alignment.Center)
            },
            placement = if (restored.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        )

        // Written back on a 700ms trailing edge. A resize is one change per FRAME,
        // and this file also holds the daemon token — rewriting it sixty times a
        // second through a rename is the one way that token gets truncated.
        LaunchedEffect(Unit) {
            snapshotFlow {
                val p = windowState.position
                WindowLayout(
                    x = if (p.isSpecified) p.x.value.toInt() else WindowLayout.UNPLACED,
                    y = if (p.isSpecified) p.y.value.toInt() else WindowLayout.UNPLACED,
                    w = windowState.size.width.value.toInt(),
                    h = windowState.size.height.value.toInt(),
                    maximized = windowState.placement == WindowPlacement.Maximized,
                )
            }.debounce(700).collect { settings.setWindowLayout(it) }
        }

        val visible by windowVisible.collectAsState()
        val summary by traySummary.collectAsState()
        val closeToTray by settings.closeToTray.collectAsState()

        // The two keyboard-only surfaces. Held here rather than in the shell
        // because the window's key handler has to know one is up: an overlay
        // that lets shortcuts through navigates the app behind it while you
        // are trying to type into it.
        val paletteOpen = remember { mutableStateOf(false) }
        val cheatsOpen = remember { mutableStateOf(false) }

        fun newChat(mode: String) {
            scope.launch {
                runCatching { store.client.createChat(mode) }
                    .onSuccess { store.openChat(it.id); store.refreshChats() }
                    // The shortcut and the palette must not fail SILENTLY while
                    // the list pane's "+ Ask" reports the same refusal — a key
                    // that does nothing teaches people the key is broken.
                    .onFailure { store.noteError(it) }
            }
        }

        /**
         * The pane the page panel would come out of. The rail and the list are
         * already spoken for, so the window's own width is the wrong number —
         * this is the same box the two conversation views measure with their own
         * `BoxWithConstraints`, arrived at from the outside.
         *
         * The arithmetic itself is [Splitter.detailWidth], because a COLLAPSED list
         * takes none of it and subtracting its stored width anyway is how a window
         * with room to spare gets told it is 320dp too narrow for the page panel.
         */
        fun detailWidthDp(): Float = Splitter.detailWidth(
            windowWidth = windowState.size.width.value,
            railWidth = Frame.railWidth.value,
            // THE DRAWN width and the DRAWN collapse, not the persisted ones. A
            // narrow window folds its list away without writing anything, so
            // reading the settings file here would tell a one-pane window it is
            // still paying 320dp for a pane that is not on screen.
            listWidth = store.listWidthNow(),
            collapsed = store.listCollapsedNow(),
        )

        /** Is the page panel actually on screen — the only thing Esc may close. */
        fun padPanelOnScreen(): Boolean = padPanelShowing(
            open = store.padPanel.value,
            view = store.view.value,
            chatOpen = store.chatId.value != null,
            sessionOpen = store.sessionName.value != null,
            padsAvailable = store.padsAvailable.value,
            detailWidthDp = detailWidthDp(),
        )

        /** Is there anywhere to put it — which is what the toggle needs to know. */
        fun padPanelReachable(): Boolean =
            padPanelHasHome(
                view = store.view.value,
                chatOpen = store.chatId.value != null,
                sessionOpen = store.sessionName.value != null,
                padsAvailable = store.padsAvailable.value,
            ) && padPanelFits(detailWidthDp())

        fun quit() {
            // Before the process starts unwinding, while the client is certainly
            // still usable. Doing it twice is free: the holder clears what it holds
            // before the call, so the shutdown hook finds nothing left to do.
            store.paneLease.releaseBlocking()
            store.flushLanding()
            instance.close()
            notifier.close()
            exitApplication()
        }

        // The one way a screen can ask to be quit, and the only reason one does:
        // Settings' "Install and restart" has handed the machine to an installer
        // that has to replace files this process holds open. Collected here
        // because `quit` above is the only exit that releases the lease and
        // flushes the landing position — a view calling exitProcess would skip
        // both. The flow is a latch, so arriving late still sees it.
        LaunchedEffect(Unit) {
            store.quitRequested.collect { if (it) quit() }
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
                // MIRRORS the Appearance & behaviour row rather than duplicating
                // it: one persisted flow, two places to reach it, and the tray is
                // where you are standing when you decide the window should not
                // have closed. (The row is the discoverable half — this checkbox
                // was the setting's ONLY home until the redesign.)
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
            // The taskbar/window-switcher identity. The installed .ico/.png only
            // covers shortcuts; the running window shows what the process hands
            // AWT, which without this is Java's coffee cup.
            icon = RavenMark.windowIcon(),
            // PREVIEW, before the focused field — which is the only place this can
            // be caught. A Ctrl chord's printable character arrives as its own
            // later event (KEY_TYPED on X11/AWT), so consuming the key press below
            // does nothing about it: Ctrl+1 switched views AND typed "1" into the
            // page editor, where the autosave then committed it. See [isChordDebris].
            onPreviewKeyEvent = { e ->
                e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp &&
                    isChordDebris(e.isCtrlPressed, e.isAltPressed, e.utf16CodePoint)
            },
            onKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                // The table lives in ui/Shortcuts.kt so it can be tested; this
                // only maps its answer to an action. An overlay swallows
                // everything but its own dismissal — a palette that navigates
                // the shell underneath it is a palette you cannot type in.
                val overlay = paletteOpen.value || cheatsOpen.value
                // `typing` is left at its default: this shell has no focus signal
                // to give it — Compose puts the whole window in ONE AWT component,
                // so nothing out here can see which composable has the caret, and
                // the only honest way to learn it is for the fields themselves to
                // say so. The one chord that made that dangerous — Ctrl+digit
                // leaking its character into a text field — is stopped at the
                // source in the preview handler above rather than inferred here.
                val shortcut = keyName(e.key)?.let {
                    match(e.isCtrlPressed, e.isShiftPressed, e.isAltPressed, it)
                }
                when {
                    // The cheatsheet installs no key handler of its own (the
                    // palette does, which is why it already closes on Esc), so its
                    // dismissal keys reach this Window handler. Honour the caption's
                    // promise — "Esc or F1 closes this" — BEFORE the overlay branch
                    // swallows every key. Esc (BACK) closes; F1 (CHEATSHEET) toggles.
                    cheatsOpen.value && (shortcut == Shortcut.BACK || shortcut == Shortcut.CHEATSHEET) -> {
                        cheatsOpen.value = false; true
                    }
                    overlay -> false
                    shortcut == null -> false
                    // Ctrl+Shift+H hides to tray. NOT a global hotkey — see the
                    // note at the foot of this file; it fires only while the
                    // window has focus, which makes it a hide and not a summon.
                    shortcut == Shortcut.HIDE_TO_TRAY -> {
                        if (isTraySupported) windowVisible.value = false
                        true
                    }
                    shortcut == Shortcut.PALETTE -> { paletteOpen.value = true; true }
                    shortcut == Shortcut.CHEATSHEET -> { cheatsOpen.value = true; true }
                    shortcut == Shortcut.VIEW_CHATS -> { store.openView(View.CHATS); true }
                    shortcut == Shortcut.VIEW_SESSIONS -> { store.openView(View.SESSIONS); true }
                    shortcut == Shortcut.VIEW_STATUS -> { store.openView(View.STATUS); true }
                    shortcut == Shortcut.VIEW_SETTINGS -> { store.openView(View.SETTINGS); true }
                    shortcut == Shortcut.VIEW_SCRATCHPADS -> { store.openView(View.SCRATCHPADS); true }
                    // ⚠ GATED ON THE PROBE, exactly as the rail item is. A chord
                    // that navigated to a pane the rail refuses to offer would be
                    // the one way in to a screen that can only 404 — and it is
                    // SWALLOWED either way, because a key that sometimes reaches
                    // the field behind it is worse than one that does nothing.
                    shortcut == Shortcut.VIEW_PROJECTS -> {
                        if (store.projectsAvailable.value == true) store.openView(View.PROJECTS)
                        true
                    }
                    shortcut == Shortcut.VIEW_CONSOLES -> {
                        if (store.consolesAvailable.value == true) store.openView(View.CONSOLES)
                        true
                    }
                    // Only where it can actually appear: toggling a panel into a
                    // window with no room for it, or into Settings, is a key that
                    // does nothing and teaches the reader the key is broken.
                    shortcut == Shortcut.TOGGLE_PAD_PANEL -> { if (padPanelReachable()) store.togglePadPanel(); true }
                    shortcut == Shortcut.NEW_ASK -> { newChat("ask"); true }
                    shortcut == Shortcut.NEW_ACT -> { newChat("act"); true }
                    // Esc closes the PANEL first — but only when the panel is
                    // really there. It used to consult the FLAG alone, which is
                    // true in places the panel is never drawn (Settings, a narrow
                    // window, a daemon with no pages), so Escape silently did
                    // nothing instead of leaving the conversation.
                    shortcut == Shortcut.BACK -> {
                        if (padPanelOnScreen()) store.setPadPanel(false) else store.back()
                        true
                    }
                    shortcut == Shortcut.LIST_PREV -> { store.stepList(-1); true }
                    shortcut == Shortcut.LIST_NEXT -> { store.stepList(1); true }
                    // The seam, from the keyboard. Clamping lives in the settings
                    // store so a drag, a key press and a restored file all pass
                    // through one set of bounds.
                    shortcut == Shortcut.SPLIT_NARROWER -> { settings.narrowList(); true }
                    shortcut == Shortcut.SPLIT_WIDER -> { settings.widenList(); true }
                    shortcut == Shortcut.SPLIT_RESET -> { settings.resetListWidth(); true }
                    // Only where there is a list to hide. The same gate as the page
                    // panel and for the same reason: this writes a persisted flag,
                    // and flipping it from Status — where no seam is drawn — would
                    // be a key press with nothing on screen to show for it, then a
                    // pane that turns out to be missing three views later.
                    shortcut == Shortcut.TOGGLE_LIST -> {
                        // Through the store, which is where "is this window narrow
                        // enough that the pane folded itself" is known. On a wide
                        // window this is still exactly the persisted flag.
                        if (Splitter.showsList(store.view.value)) store.toggleList()
                        true
                    }
                    else -> false
                }
            },
        ) {
            // PRESENCE, from window focus. This is what the notification claim
            // rides on, so it must reflect the desk rather than the process being
            // alive.
            // ⚠ THE FLOOR, ENFORCED. `WindowLayout.MIN_W/MIN_H` had only ever been
            // applied when RESTORING a saved rectangle, so every shape below it was
            // one drag of a corner away — and the shapes below it were where the
            // frame fell apart (a 40dp detail pane at 420 wide). AWT owns the
            // resize, so AWT is where the floor has to be set; a Compose-side clamp
            // would fight the window manager for a size it had already granted.
            //
            // Raw ints rather than a density conversion, deliberately consistent
            // with `restore` above, which compares the SAME numbers against
            // `Toolkit.screenSize`. One unit or the other, never half of each.
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(WindowLayout.MIN_W, WindowLayout.MIN_H)
            }

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
            // Quietly brings this machine's CLI install along with the app —
            // presence of the files is the consent; validation before swap.
            LaunchedEffect(Unit) { CliSync.startOnce() }

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
                        // Tighter than the phone's 9/8. The same rhythm that
                        // reads as comfortable under a thumb reads as loose
                        // under a mouse, where the eye travels further per
                        // screen and the reader is scanning rather than
                        // dwelling. The phone keeps its own numbers by default.
                        rowSpacing = 6.dp,
                        rowPadding = 4.dp,
                    ),
                    // Photo attachments render as real thumbnails; without this
                    // (or against an old daemon) the rows fall back to the pill.
                    LocalAttachmentImages provides store.attachmentImages,
                    // A link in an answer opens in the browser — http(s) ONLY, and
                    // never this app's own `huginn://`, which is fingerprint-gated
                    // precisely because it is reachable from outside. What cannot
                    // be opened is copied instead of failing silently.
                    LocalUriHandler provides rememberLinkUriHandler { url ->
                        runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(url), null)
                        }
                    },
                    // Where a link goes, on hover, in a tooltip — a pointer can ask
                    // a question of a thing without doing anything to it.
                    LocalLinkPeek provides DesktopLinkPeek,
                    // Hover a message, see when it was written. The phone leaves
                    // this at its no-op default and reveals times on long-press.
                    LocalRowTime provides DesktopRowTime,
                ) {
                    Shell(store)

                    if (paletteOpen.value) {
                        CommandPalette(
                            chats = store.chats.collectAsState().value,
                            sessions = store.sessions.collectAsState().value,
                            pads = if (store.padsAvailable.collectAsState().value == true) {
                                store.pads.collectAsState().value
                            } else {
                                emptyList()
                            },
                            // The drawers this host actually has, so the palette
                            // cannot offer one the Settings list does not show.
                            settings = SettingsCatalog.visibleCategories(
                                desktopProbe(
                                    SettingsFacts(
                                        status = store.status.collectAsState().value,
                                        headroom = store.headroom.collectAsState().value,
                                    ),
                                ),
                                SettingsSurface.DESKTOP,
                            ),
                            projects = store.projects.collectAsState().value,
                            consoles = store.consoles.collectAsState().value,
                            // The SAME list the rail is drawn from: a palette row
                            // onto a feature this daemon does not have is the one
                            // door a reader who cannot find something walks into.
                            offered = com.silencelen.huginn.desktop.ui.railViews(
                                store.padsAvailable.collectAsState().value,
                                store.projectsAvailable.collectAsState().value,
                                store.consolesAvailable.collectAsState().value,
                            ),
                            onDismiss = { paletteOpen.value = false },
                            onPick = { item ->
                                paletteOpen.value = false
                                when (item) {
                                    is PaletteItem.OpenChat -> store.openChat(item.id)
                                    is PaletteItem.OpenSession -> store.openSession(item.name)
                                    is PaletteItem.OpenSettings -> store.openSettings(item.categoryId)
                                    is PaletteItem.OpenScratchpad -> {
                                        store.openView(View.SCRATCHPADS)
                                        scope.launch { store.openPad(item.id) }
                                    }
                                    is PaletteItem.OpenProject -> store.openProject(item.id)
                                    // Straight to the browser, not to the pane:
                                    // the row's whole purpose is the address, and
                                    // a palette hit that landed on a list the
                                    // reader then has to search again is a step
                                    // backwards. A URL that will not open is
                                    // copied instead of silently doing nothing.
                                    is PaletteItem.OpenConsole ->
                                        if (!openInBrowser(item.url)) {
                                            store.openView(View.CONSOLES)
                                        }
                                    is PaletteItem.Verb -> when (item.shortcut) {
                                        Shortcut.NEW_ASK -> newChat("ask")
                                        Shortcut.NEW_ACT -> newChat("act")
                                        Shortcut.NEW_LOCAL -> scope.launch {
                                            runCatching { store.startLocalChat() }
                                                .onFailure { store.noteError(it) }
                                        }
                                        Shortcut.VIEW_STATUS -> store.openView(View.STATUS)
                                        Shortcut.VIEW_SETTINGS -> store.openView(View.SETTINGS)
                                        Shortcut.VIEW_SCRATCHPADS -> store.openView(View.SCRATCHPADS)
                                        Shortcut.VIEW_PROJECTS -> store.openView(View.PROJECTS)
                                        Shortcut.VIEW_CONSOLES -> store.openView(View.CONSOLES)
                                        // Same gate as the chord: the verb is
                                        // offered from everywhere, and it can
                                        // only do anything in a conversation.
                                        Shortcut.TOGGLE_PAD_PANEL ->
                                            if (padPanelReachable()) store.togglePadPanel() else store.openView(View.SCRATCHPADS)
                                        // Same gate as the chord: a seam that is
                                        // not drawn has nothing to toggle.
                                        Shortcut.TOGGLE_LIST ->
                                            if (Splitter.showsList(store.view.value)) store.toggleList()
                                        else -> Unit
                                    }
                                }
                            },
                        )
                    }
                    // F1, or the Appearance & behaviour row that asks for the same
                    // sheet — one overlay either way, because the key handler has
                    // to know when one is up.
                    val cheatsFromSettings by store.cheatsheet.collectAsState()
                    if (cheatsOpen.value || cheatsFromSettings) {
                        Cheatsheet {
                            cheatsOpen.value = false
                            store.closeCheatsheet()
                        }
                    }
                }
            }
        }
    }
}

/**
 * EVERY display attached to this desk, in the virtual desktop's own coordinates.
 *
 * `Toolkit.screenSize` used to answer this, and on Windows it reports the PRIMARY
 * monitor rather than the virtual desktop — so a window left on a second screen
 * was judged off-screen at every launch, recentred, and its real position
 * overwritten by the debounced writer. `GraphicsEnvironment` is the only API that
 * knows there is more than one, and each device's `defaultConfiguration.bounds`
 * carries the signed origin the position has to be compared against.
 *
 * Empty on a failure or a headless run, which [WindowLayout.restore] reads as
 * "unknown" and answers by keeping the size and dropping the position.
 */
private fun screens(): List<Screen> = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
        val b = it.defaultConfiguration.bounds
        Screen(b.x, b.y, b.width, b.height)
    }
}.getOrElse {
    runCatching {
        val s = java.awt.Toolkit.getDefaultToolkit().screenSize
        listOf(Screen(0, 0, s.width, s.height))
    }.getOrDefault(emptyList())
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
