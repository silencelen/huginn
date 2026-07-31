package com.silencelen.huginn.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a human is actually at this machine, and whether the window they would
 * see anything in is on screen.
 *
 * Two distinct questions with two distinct consequences, which is why they are
 * separate fields rather than one "active" boolean:
 *
 * - [visible] gates POLLING. A hidden window that keeps polling holds the tmux
 *   size lease, so a desktop minimized to the tray can pin someone else's session
 *   to this window's geometry indefinitely while they work on it from a terminal.
 *   The phone learned this in 2.0.1; the Electron client learned it again.
 *
 * - [present] gates the NOTIFICATION CLAIM. The daemon holds back the household
 *   Telegram fallback while a client says it is a delivery route, so claiming
 *   while nobody is looking does not merely waste a notification — it SUPPRESSES
 *   the one that would have reached the owner.
 *
 * Presence here is derived from window focus rather than from system-wide input
 * idle time: a plain JVM has no `powerMonitor.getSystemIdleTime()`, and the
 * honest available signal is "this window had focus recently". A grace window
 * keeps a glance at a browser from dropping the claim mid-run; anything longer
 * than that and the safe answer is that the desk is empty.
 */
class Presence(private val nowMs: () -> Long = System::currentTimeMillis) {

    /** How long after losing focus this client still counts as attended. */
    val graceMs: Long = 10 * 60 * 1000

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _present = MutableStateFlow(false)
    val present: StateFlow<Boolean> = _present.asStateFlow()

    /**
     * Bumped whenever the long-lived streams must be DROPPED AND RE-OPENED. Two
     * unrelated reasons, one signal, because the remedy is identical:
     *
     * - **[present] flipped.** The notify claim is stamped on the request when the
     *   socket opens, and a parked SSE re-sends that same header on every
     *   keepalive — so walking away from the desk leaves the daemon believing this
     *   client is still a delivery route (and holding back the household Telegram
     *   fallback) until the 30-minute rotation. Re-opening is what makes the claim
     *   true. Note this is a monotonic counter, not the boolean: a StateFlow
     *   conflates equal values, so a signal that only carried presence could not
     *   also express "reconnect for a different reason".
     * - **[noteResume].** Sleep black-holes every socket at once. Nothing errors
     *   on wake; the connection simply hangs until an idle timeout fires, which is
     *   up to three minutes of a client that looks attached and is not.
     */
    private val _streamKey = MutableStateFlow(0L)
    val streamKey: StateFlow<Long> = _streamKey.asStateFlow()

    private var focused = false
    private var lastFocusedAt = 0L

    fun setVisible(value: Boolean) {
        _visible.value = value
        recompute()
    }

    fun setFocused(value: Boolean) {
        focused = value
        if (value) lastFocusedAt = nowMs()
        recompute()
    }

    /**
     * Re-evaluates the grace window. Called on a timer as well as on every event,
     * because "focus was lost eleven minutes ago" is a transition that no event
     * announces — and the whole failure this guards against is a claim that stays
     * true because nothing happened.
     */
    fun tick() {
        if (focused) lastFocusedAt = nowMs()
        recompute()
    }

    /**
     * The machine woke up. Drops and re-opens whatever is parked on a socket that
     * no longer exists.
     */
    fun noteResume() {
        _streamKey.value += 1
    }

    private fun recompute() {
        val attended = focused || (lastFocusedAt > 0 && nowMs() - lastFocusedAt < graceMs)
        val next = _visible.value && attended
        if (next != _present.value) {
            _present.value = next
            _streamKey.value += 1
        }
    }
}

/**
 * Notices that this machine was asleep.
 *
 * A plain JVM has no `powerMonitor.on('resume')`. What it does have is two clocks
 * that disagree about a suspend, and either disagreement is proof enough:
 *
 * - **The monotonic clock skips.** `System.nanoTime()` does not advance across a
 *   suspend on Linux, and in any case the JVM's threads are frozen — so a ticker
 *   asked to run every 15 seconds finds that far longer has passed.
 * - **The wall clock jumps.** Which also catches an NTP step or a hypervisor
 *   restoring a snapshot, both of which black-hole sockets exactly the same way.
 *
 * Pure, so the threshold logic is testable without sleeping a test for an hour.
 * The first tick only establishes a baseline and can never report a resume.
 */
class SleepDetector(
    private val intervalMs: Long = 15_000,
    /** How much longer than the interval a gap must be before it means "asleep". */
    private val slackMs: Long = 45_000,
) {
    private var lastWallMs = 0L
    private var lastMonoNs = 0L
    private var seeded = false

    fun tick(wallMs: Long, monoNs: Long): Boolean {
        if (!seeded) {
            seeded = true
            lastWallMs = wallMs
            lastMonoNs = monoNs
            return false
        }
        val wallGap = wallMs - lastWallMs
        val monoGap = (monoNs - lastMonoNs) / 1_000_000
        lastWallMs = wallMs
        lastMonoNs = monoNs
        // Absolute value on the wall gap: a clock stepped BACKWARDS is the same
        // evidence as one stepped forward, and only the forward case is obvious.
        val wallJumped = kotlin.math.abs(wallGap) > intervalMs + slackMs
        val monoJumped = monoGap > intervalMs + slackMs
        return wallJumped || monoJumped
    }
}
