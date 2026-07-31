package com.silencelen.huginn.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.silencelen.huginn.ui.LocalTranscriptMetrics
import com.silencelen.huginn.ui.TranscriptMetrics
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import com.silencelen.huginn.desktop.ui.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * The Compose Multiplatform desktop client for huginn-appd.
 *
 * Everything it knows about the daemon comes from `:core` — the same Kotlin the
 * phone runs. This file owns a window, wires [Presence] to that window's real
 * state, and starts the loops.
 */
fun main() = application {
    val settings = remember { DesktopSettings() }
    val presence = remember { Presence() }
    // SupervisorJob: one loop failing (a watch stream against an unreachable
    // route) must not take the poll loop and the UI's coroutines down with it.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val store = remember { AppStore(settings, presence, scope) }

    val windowState = rememberWindowState(size = DpSize(1280.dp, 840.dp))

    // THE LAST-CHANCE RELEASE. Registered once, and deliberately not the only one:
    // the ordinary paths (leaving the view, hiding the window, closing it) each
    // release for themselves, and this is what covers everything that never
    // reaches them — SIGTERM, a kill from a session manager, an exception on the
    // way out. Without it a force-quit leaves the owner's tmux window pinned at
    // this window's shape until the daemon's 90-second lease lapses.
    remember {
        Runtime.getRuntime().addShutdownHook(Thread { store.paneLease.releaseBlocking() })
    }

    Window(
        onCloseRequest = {
            // Before the process starts unwinding, while the client is certainly
            // still usable. Doing it twice is free: the holder clears what it holds
            // before the call, so the shutdown hook finds nothing left to do.
            store.paneLease.releaseBlocking()
            exitApplication()
        },
        state = windowState,
        title = "Huginn",
        onKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@Window false
            when (e.key) {
                Key.One -> { store.openView(View.CHATS); true }
                Key.Two -> { store.openView(View.SESSIONS); true }
                Key.Three -> { store.openView(View.STATUS); true }
                Key.Comma -> { store.openView(View.SETTINGS); true }
                else -> false
            }
        },
    ) {
        // VISIBILITY, from the window itself rather than assumed. Minimized
        // counts as hidden: a minimized window that keeps polling holds the tmux
        // size lease, which pins someone else's session to this window's
        // geometry for as long as it stays minimized.
        LaunchedEffect(Unit) {
            snapshotFlow { windowState.isMinimized }
                .collect { presence.setVisible(!it) }
        }

        // PRESENCE, from window focus. This is what the notification claim rides
        // on, so it must reflect the desk rather than the process being alive.
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(Unit) {
            snapshotFlow { windowInfo.isWindowFocused }
                .collect { presence.setFocused(it) }
        }

        LaunchedEffect(Unit) { store.start() }

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
