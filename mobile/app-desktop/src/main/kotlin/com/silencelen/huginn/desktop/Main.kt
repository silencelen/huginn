package com.silencelen.huginn.desktop

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
import com.silencelen.huginn.desktop.theme.HuginnDesktopTheme
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

    Window(
        onCloseRequest = ::exitApplication,
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

        HuginnDesktopTheme { Shell(store) }
    }
}
