package com.silencelen.huginn.desktop.notify

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState

/**
 * The floor: a balloon from the tray icon, via AWT's `TrayIcon.displayMessage`.
 *
 * Works anywhere a tray icon works, needs nothing installed, and is honest about
 * being the least it can be:
 *
 * - **No buttons.** AWT has no concept of them. A "needs you" balloon says which
 *   session and what it asked; answering is a click away in the app.
 * - **No withdraw.** There is no API to take a balloon down once shown — the
 *   shell owns it. [withdraw] is therefore a real no-op rather than a pretended
 *   one, and [supportsWithdraw] says so, which is what lets the router's caller
 *   report the truth instead of assuming the question stopped nagging.
 * - **No click routing.** The tray's single action listener is already spoken for
 *   by "show the window" (Compose exposes exactly one), so a balloon click
 *   summons the app rather than the session that raised it.
 *
 * [TrayState] rather than a raw `TrayIcon` because Compose owns the tray icon's
 * lifecycle here; `sendNotification` is safe to call from any thread (it lands on
 * a shared flow the tray composition collects).
 */
class AwtNotifier(private val tray: TrayState) : Notifier {

    override val name: String = "awt-balloon"

    override fun post(request: NotifyRequest) {
        tray.sendNotification(
            Notification(
                title = request.title,
                message = request.body,
                // WARNING vs INFO is the only distinction the balloon API offers,
                // and on Windows it is what decides whether the shell plays a
                // sound. It is the nearest thing here to the urgent/news split.
                type = if (request.urgent) Notification.Type.Warning else Notification.Type.Info,
            )
        )
    }

    override fun withdraw(key: String) = Unit
}
