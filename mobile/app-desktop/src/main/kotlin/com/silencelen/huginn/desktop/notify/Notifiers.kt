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
        // WRAPPED, like the toast path. `createOrNull` proves notify-send and a
        // display, never a notification daemon on the session bus — so the
        // libnotify path can be chosen and then fail every post. Behind a
        // FallbackNotifier a proven-dead primary hands the next notification to
        // the tray instead of dropping it (and, through `healthy`, stops this
        // desktop claiming to be a delivery route the daemon can hold Telegram
        // back for).
        LibnotifyNotifier.createOrNull()?.let { libnotify ->
            return if (awt != null) FallbackNotifier(libnotify, awt) else libnotify
        }
        return awt ?: NoNotifier
    }

    /** The Settings row that reports [pathWords]. Named here so the catalog and the page agree. */
    const val PATH_ROW_ID: String = "notify.path"

    /**
     * The sentence a machine with nowhere to post gets — the reason, in the same
     * words the startup log and `Copy diagnostics` already use.
     */
    const val NOWHERE_TO_POST: String =
        "nothing on this computer can show a notification: no system tray and no libnotify"

    /**
     * Which backend a notification would actually take, as one word for a
     * read-only Settings row.
     *
     * ⚠ THE APP KNEW THIS AND DID NOT SAY IT. It is logged at startup and it is
     * in the diagnostics blob, while Settings → Notifications carried only the
     * CLAIM toggle — so on a machine where nothing can be posted the page offered
     * a switch for a route that does not exist and said nothing about it. The
     * notification setup step's own failure text sends the reader here.
     *
     * @param name [com.silencelen.huginn.desktop.diag.AppLog.notifierName], which
     *   is null exactly when the chosen backend is [NoNotifier].
     */
    fun pathWords(name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: "none"

    /** Why that is the path, and where attention goes when there is none. */
    fun pathSummary(name: String?): String =
        if (pathWords(name) == "none") {
            "$NOWHERE_TO_POST. Anything that needs you goes to Telegram instead."
        } else {
            "Notifications are posted through ${pathWords(name)} while this window is claiming the route."
        }

    /**
     * What the CLAIM row says, told the same fact the PATH row below it is told.
     *
     * ⚠ D-31. TWO ADJACENT SENTENCES THAT CONTRADICTED EACH OTHER. The claim read
     * "claiming: this window has been attended recently" directly above "How
     * notifications reach this computer — **none** — nothing on this computer can
     * show a notification". Both were true of their own state and neither was true
     * of the machine: the toggle described PRESENCE, the row described the
     * BACKEND, and a reader has no way to know those are different questions.
     *
     * So the claim reads the backend as well. The order is the order the reader
     * needs it in — turned off is a choice they made, nowhere to post is a fact
     * about the machine, and presence is the only one of the three that changes
     * minute to minute. Nothing here is a new refusal: the daemon claim is already
     * gated by [Notifier.canDeliver], and this is that same gate said out loud.
     *
     * @param name [com.silencelen.huginn.desktop.diag.AppLog.notifierName] — the
     *   chosen backend, null exactly when it is [NoNotifier].
     */
    fun claimWords(enabled: Boolean, present: Boolean, name: String?): String = when {
        !enabled -> "off — huginn falls back to Telegram"
        pathWords(name) == "none" ->
            "claiming nothing: $NOWHERE_TO_POST, so Telegram stays live whatever this is set to"
        present -> "claiming: this window has been attended recently"
        else -> "not claiming: window hidden or unattended, so Telegram stays live"
    }

    /**
     * Why a test notification must not even be attempted, or null when it can be.
     *
     * ⚠⚠ THE STEP ASKED A READER TO CONFIRM SOMETHING THAT NEVER HAPPENED. With
     * [NoNotifier] chosen, `post` is a no-op — so setup posted nothing and then
     * printed "A test notification has just been posted. Did it appear on this
     * screen?" beside a "Yes, I saw it" button. Pressing it records a PASS for a
     * route that cannot deliver, and the daemon then holds the household's
     * Telegram fallback back for a window that will never show anything. The app
     * had already logged "nowhere to post" before the flow opened.
     *
     * [Notifier.canDeliver] is the same question the `X-Huginn-Notify` claim asks
     * — a dead backend and no backend are both "not a route" — so the step and
     * the claim cannot disagree about whether this computer can be reached.
     */
    fun testRefusal(notifier: Notifier): String? =
        if (notifier.canDeliver()) {
            null
        } else {
            "$NOWHERE_TO_POST. huginn will keep using Telegram for anything that needs you."
        }

    /** One honest line for the log at startup, and for the diagnostics blob. */
    fun describe(notifier: Notifier): String = buildString {
        append("notifications via ").append(notifier.name)
        append(if (notifier.supportsActions) ", answer buttons" else ", no answer buttons")
        append(if (notifier.supportsWithdraw) ", withdrawable" else ", NOT withdrawable")
        if (notifier === NoNotifier) append(" — nowhere to post: no system tray and no libnotify")
    }
}
