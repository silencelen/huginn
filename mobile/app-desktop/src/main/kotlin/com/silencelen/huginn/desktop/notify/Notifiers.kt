package com.silencelen.huginn.desktop.notify

import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.isTraySupported
import java.io.File

/**
 * Falls through to a second backend once the first has proven it cannot deliver.
 *
 * Not a retry: the primary is asked once, and if it reports itself unhealthy
 * every subsequent notification goes to the fallback for the life of the process.
 * The alternative — keep asking a path that is not working — costs one lost
 * notification per event, and a lost "needs you" is the whole failure this layer
 * exists to prevent.
 */
class FallbackNotifier(
    private val primary: Notifier,
    private val fallback: Notifier,
) : Notifier {

    private fun active(): Notifier = if (primary.healthy) primary else fallback

    override val name: String get() = "${primary.name}→${fallback.name}(${active().name})"
    override val supportsActions: Boolean get() = active().supportsActions
    override val supportsWithdraw: Boolean get() = active().supportsWithdraw

    override fun post(request: NotifyRequest) = active().post(request)

    /**
     * Withdrawn from BOTH. After a failover the notification that needs taking
     * down may well be sitting in the backend that has since been abandoned, and
     * a withdraw on an unknown key is a no-op everywhere.
     */
    override fun withdraw(key: String) {
        primary.withdraw(key)
        fallback.withdraw(key)
    }

    override fun close() {
        primary.close()
        fallback.close()
    }
}

/**
 * Picks the best notification path this machine actually has.
 *
 * The order is by capability, and every step of it is a measured fact rather than
 * a platform assumption:
 *
 * | backend | buttons | withdraw | where |
 * |---|---|---|---|
 * | [WindowsToastNotifier] | yes | yes | Windows, PACKAGED, WinRT probe passed |
 * | [LibnotifyNotifier] | no | yes | Linux with `notify-send` and a session bus |
 * | [AwtNotifier] | no | no | anywhere a tray icon exists |
 * | [NoNotifier] | no | no | nowhere else left |
 *
 * A machine with no system tray AND no libnotify gets nothing, and gets told so
 * once at startup rather than discovering it the first time something needs an
 * answer.
 */
object Notifiers {

    fun choose(configDir: File, packaged: Boolean, tray: TrayState): Notifier {
        val awt: Notifier? = if (isTraySupported) AwtNotifier(tray) else null

        WindowsToastNotifier.createOrNull(configDir, packaged)?.let { toast ->
            return if (awt != null) FallbackNotifier(toast, awt) else toast
        }
        LibnotifyNotifier.createOrNull()?.let { return it }
        return awt ?: NoNotifier
    }

    /** One honest line for the log at startup, and for the diagnostics blob. */
    fun describe(notifier: Notifier): String = buildString {
        append("notifications via ").append(notifier.name)
        append(if (notifier.supportsActions) ", answer buttons" else ", no answer buttons")
        append(if (notifier.supportsWithdraw) ", withdrawable" else ", NOT withdrawable")
        if (notifier === NoNotifier) append(" — nowhere to post: no system tray and no libnotify")
    }
}
