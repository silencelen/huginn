package com.silencelen.huginn.ui

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How soon the pane poll asks again after a NEW frame.
 *
 * ⚠ A BUSY PANE CHANGES ON EVERY TICK. The long poll answers as soon as the
 * pane's hash differs, and Claude Code's spinner and timer redraw several times
 * a second, so a working session's poll came straight back and asked again at
 * once: about four captures a second, from every phone with that session open,
 * whatever tab it was on. On the Screen tab that is the point, since the reader
 * is watching the pane. Behind it the poll is there for the question bar and
 * the header's live model, which need a second, not every frame. An idle pane
 * still parks for the whole long poll either way.
 *
 * The desktop runs the same rule (SessionController.SCREEN_BEHIND_MS).
 */
object PanePace {
    /** The wait after a new frame while the Screen tab is not showing. */
    const val BEHIND_MS: Long = 1_000

    /**
     * Returns at once while [screenOnTab] is true. Otherwise returns after
     * [behindMs], or as soon as the Screen tab comes on screen, so arriving on
     * the tab is never a wait.
     */
    suspend fun afterNewFrame(screenOnTab: StateFlow<Boolean>, behindMs: Long = BEHIND_MS) {
        if (screenOnTab.value) return
        withTimeoutOrNull(behindMs) { screenOnTab.first { it } }
    }
}
