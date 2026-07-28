package com.silencelen.huginn.ui

/**
 * Optimistic echo for live typing: characters render at the cursor the moment
 * they are typed, before the pane confirms them.
 *
 * Without this, every keystroke waits a full round trip plus the screen poll
 * tick (~200-400ms) to appear, which reads as lag even though delivery is fine.
 * With it, the perceived echo is a frame.
 *
 * The whole design is about when NOT to guess, because a wrong prediction is a
 * ghost character floating in a live pane — worse than the latency it hides:
 *
 *  * Only plain typed text is echoed. Enter, arrows, Tab, backspace-past-the-
 *    buffer: anything whose screen effect this cannot predict MUTES the echo
 *    until the next authoritative frame settles the question.
 *  * The echo never invents layout. It renders inside the cursor's row and is
 *    clipped at the row's end — predicting the composer's wrap is exactly where
 *    ghosts come from, so it is not attempted.
 *  * Every authoritative frame wins. Pending text is consumed by how far the
 *    frame's cursor actually advanced; anything unexplained (row change,
 *    backwards cursor, a jump longer than the buffer) clears the echo entirely.
 *
 * Pure, because these rules are the feature: the rendering is twenty lines, the
 * judgment is here, and only one of them can be tested without a phone.
 */
object LocalEcho {

    /** More pending than this means the pane has stopped confirming; stop guessing. */
    const val MAX_PENDING = 60

    /**
     * @param text  characters typed since the last frame accounted for them
     * @param muted true after an unpredictable key: render nothing, wait for a frame
     */
    data class Echo(val text: String = "", val muted: Boolean = false) {
        val visible: Boolean get() = !muted && text.isNotEmpty()
    }

    fun typed(e: Echo, t: String): Echo {
        if (e.muted) return e
        val next = e.text + t
        // A buffer this deep means frames stopped consuming it; guessing further
        // just builds a longer ghost.
        return if (next.length > MAX_PENDING) Echo(muted = true) else e.copy(text = next)
    }

    fun backspace(e: Echo): Echo = when {
        e.muted -> e
        e.text.isNotEmpty() -> e.copy(text = e.text.dropLast(1))
        // Deleting before our anchor: the effect on screen is unknowable here.
        else -> Echo(muted = true)
    }

    /** Enter, arrows, Tab, control keys: effects this cannot predict. */
    fun otherKey(@Suppress("UNUSED_PARAMETER") e: Echo): Echo = Echo(muted = true)

    /**
     * An authoritative frame arrived. The cursor's actual movement says how much
     * of the pending text the pane has absorbed.
     *
     * @param prev the previous frame's cursor, null on the first frame
     * @param cur  this frame's cursor
     */
    fun frame(e: Echo, prev: Pair<Int, Int>?, cur: Pair<Int, Int>): Echo {
        // A frame always lifts a mute: it IS the resolution being waited for.
        if (e.muted || e.text.isEmpty()) return Echo()
        if (prev == null) return Echo()
        val (px, py) = prev
        val (cx, cy) = cur
        if (cy != py) return Echo()                    // wrapped or moved: unknown land
        val advanced = cx - px
        return when {
            advanced < 0 -> Echo()                     // cursor went backwards: redraw
            advanced > e.text.length -> Echo()         // moved further than we typed
            else -> Echo(text = e.text.drop(advanced))
        }
    }
}
