package com.silencelen.huginn.desktop

/**
 * Where the window was, and whether that is still a place it can be.
 *
 * RESTORING A POSITION IS NOT THE SAME AS SAVING ONE. A saved rectangle describes
 * a display arrangement that no longer has to exist: the laptop was docked to a
 * 3440-wide monitor and now is not, the second screen was above and is now to the
 * left, the desktop was 4K and someone changed the scaling. Restoring the numbers
 * blindly puts the window somewhere the mouse cannot reach, and the only visible
 * symptom is an app that "did not start" — the process is running and the window
 * is at x=2400 on a 1920 display.
 *
 * So this is a pure function of the saved rectangle and the CURRENT screen, kept
 * apart from the composition and asserted, because every one of those failures is
 * indistinguishable from a crash to the person it happens to.
 */
data class WindowLayout(
    val x: Int = UNPLACED,
    val y: Int = UNPLACED,
    val w: Int = DEFAULT_W,
    val h: Int = DEFAULT_H,
    val maximized: Boolean = false,
) {
    val placed: Boolean get() = x != UNPLACED && y != UNPLACED

    companion object {
        /** No saved position: let the window manager centre it. */
        const val UNPLACED: Int = -1

        const val DEFAULT_W: Int = 1280
        const val DEFAULT_H: Int = 840

        /** Below this the three panes stop being three panes. */
        const val MIN_W: Int = 720
        const val MIN_H: Int = 480

        /**
         * How much of the window's title bar must land on a screen for the window
         * to be draggable. A window whose top-left is off the bottom edge is a
         * window with no handle.
         */
        private const val VISIBLE_MARGIN: Int = 80

        /**
         * The rectangle to actually open, given what the screen is now.
         *
         * @param screenW,screenH the usable desktop, as the toolkit reports it.
         *   Zero or negative means "unknown" — a headless check, or a display the
         *   JDK could not measure — and then the saved size is trusted and only the
         *   POSITION is dropped, because a size that is too big is merely ugly
         *   while a position that is off-screen is fatal.
         */
        fun restore(saved: WindowLayout, screenW: Int, screenH: Int): WindowLayout {
            val w = saved.w.coerceAtLeast(MIN_W)
            val h = saved.h.coerceAtLeast(MIN_H)
            if (screenW <= 0 || screenH <= 0) {
                return WindowLayout(UNPLACED, UNPLACED, w, h, saved.maximized)
            }
            // Never larger than the screen it is opening on.
            val fitW = w.coerceAtMost(screenW)
            val fitH = h.coerceAtMost(screenH)
            if (!saved.placed) return WindowLayout(UNPLACED, UNPLACED, fitW, fitH, saved.maximized)

            val onScreen = saved.x + VISIBLE_MARGIN in 0..screenW &&
                saved.y in 0..(screenH - VISIBLE_MARGIN / 2)
            return if (onScreen) {
                WindowLayout(saved.x, saved.y, fitW, fitH, saved.maximized)
            } else {
                // The display it remembers is gone. Centring is better than
                // clamping to an edge: clamped windows pile up in one corner.
                WindowLayout(UNPLACED, UNPLACED, fitW, fitH, saved.maximized)
            }
        }
    }
}

/**
 * The list/detail seam.
 *
 * The bounds are a readability decision rather than a safety one: under ~220dp a
 * chat title is three words and an ellipsis, and over ~560dp the list is wider
 * than the transcript it is next to, which inverts what the window is for.
 */
object Splitter {
    const val DEFAULT: Float = 320f
    const val MIN: Float = 220f
    const val MAX: Float = 560f

    /** One press of the keyboard adjust. Coarse on purpose: a drag is for fine. */
    const val STEP: Float = 24f

    fun clamp(value: Float): Float =
        if (value.isNaN()) DEFAULT else value.coerceIn(MIN, MAX)
}
