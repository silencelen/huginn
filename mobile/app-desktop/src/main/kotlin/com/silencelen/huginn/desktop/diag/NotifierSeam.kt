package com.silencelen.huginn.desktop.diag

/**
 * THE SEAM for a desktop notifier that does not exist in this module yet.
 *
 * Settings wants a "Send test notification" button, and a test button that does
 * not exercise the real delivery path is worse than no button — it is a green
 * light wired to nothing. So rather than building a SECOND notification path
 * beside the one the tray/notifications work is landing, this holds a slot.
 *
 * TO WIRE IT (whoever owns `notify/`): from wherever the real `Notifier` is
 * chosen — one place, two lines, no other change:
 *
 * ```
 * NotifierSeam.name = notifier.name
 * NotifierSeam.sendTest = {
 *     notifier.post(
 *         NotifyRequest(
 *             key = "diag-test",
 *             title = "Huginn",
 *             body = "Test notification from Settings",
 *             urgent = false,
 *             target = NavTarget(TargetKind.CHATS, ""),
 *         )
 *     )
 *     notifier.healthy
 * }
 * ```
 *
 * The button enables itself, and the diagnostics blob stops saying "NOT WIRED"
 * and starts naming the backend instead. Until then the button is visibly
 * disabled and says why, which is the honest reading of "this window is not a
 * delivery route".
 */
object NotifierSeam {

    /**
     * Fires a test notification through the real desktop notifier. Null while no
     * notifier is installed. Returns false when the platform refused (no
     * notification daemon, permissions), so the caller can say so rather than
     * claiming success.
     */
    @Volatile
    var sendTest: (() -> Boolean)? = null

    /**
     * The backend's own name — "libnotify", "windows-toast", "awt-tray". Which
     * path a notification took is the first thing worth knowing when one did not
     * arrive, which is why `Notifier` already carries it.
     */
    @Volatile
    var name: String? = null

    val available: Boolean get() = sendTest != null

    /** @return null when there is no notifier at all; otherwise what it reported. */
    fun fire(): Boolean? = sendTest?.let { runCatching { it() }.getOrDefault(false) }
}
