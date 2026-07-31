package com.silencelen.huginn.desktop.notify

/** One bounded choice a toast may offer, when the platform can render buttons. */
data class AnswerOption(val number: Int, val label: String)

/**
 * A notification, as the router describes it — before any platform has had an
 * opinion about what it can actually show.
 */
data class NotifyRequest(
    /** Post/withdraw identity. See [NavTarget.key]. */
    val key: String,
    val title: String,
    val body: String,
    /**
     * "Needs you" versus "news". The two natures stay separate everywhere, because
     * a blocking question and a finished run are not the same interruption: urgent
     * makes a sound and (where the platform allows) stays on screen until it is
     * dealt with; news is silent and may expire on its own.
     */
    val urgent: Boolean,
    val target: NavTarget,
    /**
     * Bounded single choices, at most a handful, and ONLY on a backend that says
     * [Notifier.supportsActions]. Never free text: the owner's rule for answering
     * from a lock screen or a toast is predetermined choices only.
     */
    val options: List<AnswerOption> = emptyList(),
    /**
     * The host's identity for the question the buttons answer. Null means the
     * buttons must not be rendered at all — an answer without a fingerprint is one
     * the host will type into whatever is on the pane. See [Activations.parse].
     */
    val fingerprint: String? = null,
)

/**
 * Where a notification actually goes.
 *
 * Split out because the honest answer differs per platform and per install, and
 * the router must not care: it decides WHAT to say and WHEN to take it back, and
 * a backend does as much of that as its OS allows. What each one really manages
 * is documented on the implementation, not promised here.
 */
interface Notifier {

    /** For the log and the diagnostics blob: which path notifications took. */
    val name: String

    /** True only where bounded answer buttons genuinely render. Never aspirational. */
    val supportsActions: Boolean get() = false

    /** True where [withdraw] genuinely takes a notification down. */
    val supportsWithdraw: Boolean get() = false

    /**
     * False once a backend has proven it cannot deliver. A notification path that
     * fails silently is the worst failure this layer has — the app looks quiet
     * rather than broken — so a backend that can detect its own failure says so
     * here and [FallbackNotifier] moves the next one to a path that works.
     */
    val healthy: Boolean get() = true

    fun post(request: NotifyRequest)

    /** Takes down whatever was posted under [key]. A no-op for an unknown key. */
    fun withdraw(key: String)

    fun close() {}
}

/** Used when there is nowhere to put a notification at all. Says so once. */
object NoNotifier : Notifier {
    override val name: String = "none"
    override fun post(request: NotifyRequest) = Unit
    override fun withdraw(key: String) = Unit
}
