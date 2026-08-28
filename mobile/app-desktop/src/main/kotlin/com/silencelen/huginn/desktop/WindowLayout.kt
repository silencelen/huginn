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
 *
 * The seam can also be shut entirely, which is a different fact from its width:
 * collapsing does NOT move the number below, so bringing the panel back returns
 * it to whatever it was dragged to rather than to a default nobody chose.
 */
object Splitter {
    const val DEFAULT: Float = 320f
    const val MIN: Float = 220f
    const val MAX: Float = 560f

    /** One press of the keyboard adjust. Coarse on purpose: a drag is for fine. */
    const val STEP: Float = 24f

    fun clamp(value: Float): Float =
        if (value.isNaN()) DEFAULT else value.coerceIn(MIN, MAX)

    // ------------------------------------------------------- with it shut
    //
    // A collapsed seam is still a seam: it keeps its 8dp of hit area, it keeps
    // the notch, and it still answers a drag. What it cannot do is resize a pane
    // that is not on screen — so the same gesture means something else there,
    // and the something else is "bring it back".

    /**
     * How far outward a pointer has to pull a COLLAPSED seam before the list
     * comes back, in dp of travel.
     *
     * Wider than the seam itself (8dp) on purpose: the pull has to LEAVE the
     * seam to count, which is what separates a deliberate drag from the twitch
     * that lands on a 1px line while reaching for the pane behind it. Narrower
     * than [STEP], because this is not an adjustment — it is a yes.
     */
    const val REOPEN_PULL: Float = 12f

    /**
     * Accumulated outward travel, one drag delta at a time.
     *
     * A threshold applied to a SINGLE delta is a threshold on pointer SPEED
     * rather than on distance — a slow, deliberate pull would never reach it and
     * a flick always would, which is exactly backwards. So the deltas are summed;
     * and the sum is dropped the moment the pointer goes back the other way,
     * because a leftward drag on a seam with nothing to its left is not half of a
     * rightward one and must not be banked toward the next.
     */
    fun pull(sum: Float, delta: Float): Float = if (delta <= 0f) 0f else sum + delta

    /** Whether that much travel is a request for the panel back. */
    fun reopens(pull: Float): Boolean = pull >= REOPEN_PULL

    /**
     * Which views have a list to hide, and therefore a seam and a notch at all.
     *
     * ONE definition, asked by the frame that draws it and by the keyboard that
     * toggles it. The page panel already paid for the alternative: three
     * conditions decided whether it was on screen and only one of them decided
     * whether Esc closed it, so Escape "closed" a panel that was not there. A
     * chord that flips a flag with nothing on screen to show for it is the same
     * bug wearing the same disguise — the reader presses it again.
     */
    fun showsList(view: View): Boolean =
        view == View.CHATS || view == View.SESSIONS || view == View.SCRATCHPADS

    /**
     * The pane the page panel would come out of: the window, less the rail, less
     * whatever the list is really taking.
     *
     * @param collapsed a shut list takes NOTHING, and the width it would have
     *   taken belongs to the detail pane. Reading the stored width regardless is
     *   how a window wide enough for the page panel gets told it is 320dp too
     *   narrow for one — the panel silently refusing to appear, with the space
     *   for it visibly right there.
     */
    fun detailWidth(windowWidth: Float, railWidth: Float, listWidth: Float, collapsed: Boolean): Float =
        windowWidth - railWidth - if (collapsed) 0f else listWidth
}
