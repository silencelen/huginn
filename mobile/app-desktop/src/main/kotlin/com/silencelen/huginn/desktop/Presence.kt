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

    private fun recompute() {
        val attended = focused || (lastFocusedAt > 0 && nowMs() - lastFocusedAt < graceMs)
        _present.value = _visible.value && attended
    }
}
