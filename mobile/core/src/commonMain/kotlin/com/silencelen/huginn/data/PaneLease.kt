package com.silencelen.huginn.data

/**
 * When this client may hold a tmux window at its own geometry, and when it owes
 * the window back.
 *
 * Reporting `?cols=&rows=` on a screen poll makes the daemon set `window-size
 * manual` on that tmux window for 90 seconds, renewed by the polling itself. That
 * is a lease over something that is NOT ours: the owner works in these sessions
 * from a terminal, and a client that keeps polling while nobody can see it pins
 * their window to a shape chosen by a window they are not looking at. The phone
 * learned this in 2.0.1 and the Electron client learned it again; the rule is
 * written down here so a third client cannot learn it a third time.
 *
 * Pure, because the rule is the safety property and the I/O around it is not
 * testable: [wanted] says what geometry MAY be reported right now, [toRelease]
 * says what must be handed back, and every exit path in a client reduces to one of
 * those two questions.
 */
object PaneLease {

    /** The clamps the daemon applies in `acquireSize`, stated once on both sides. */
    const val MIN_COLS: Int = 20
    const val MAX_COLS: Int = 300
    const val MIN_ROWS: Int = 10
    const val MAX_ROWS: Int = 200

    /** A geometry this client is entitled to report for [session]. */
    data class Want(val session: String, val cols: Int, val rows: Int)

    fun clampCols(cols: Int): Int = cols.coerceIn(MIN_COLS, MAX_COLS)
    fun clampRows(rows: Int): Int = rows.coerceIn(MIN_ROWS, MAX_ROWS)

    /**
     * The geometry this client may report, or null for "poll without geometry".
     *
     * Null is not an error state and not a reason to stop polling: a screen poll
     * with no `cols`/`rows` takes no lease at all, which is exactly what a
     * conversation view that only wants the pane's question should send.
     *
     * @param session   the open session, or null when no session view is open
     * @param visible   whether the window is on screen. A minimized or hidden
     *   window has no geometry worth asking for — that is the whole failure.
     * @param wantsGrid whether the surface currently drawing is the one that
     *   renders a character grid. A conversation tab does not need tmux reshaped.
     * @param cols/rows the measured grid, or null before the first measurement
     */
    fun wanted(
        session: String?,
        visible: Boolean,
        wantsGrid: Boolean,
        cols: Int?,
        rows: Int?,
    ): Want? {
        if (session == null || !visible || !wantsGrid) return null
        if (cols == null || rows == null) return null
        return Want(session, clampCols(cols), clampRows(rows))
    }

    /**
     * The session whose size must be handed back, given what is held and what is
     * now wanted.
     *
     * Deliberately RELEASE-FIRST: any change of session, and any transition to
     * wanting nothing, yields a release. Releasing a lease that has already lapsed
     * is a no-op on the daemon; failing to release one is a stranded window.
     */
    fun toRelease(held: String?, wanted: Want?): String? =
        if (held != null && held != wanted?.session) held else null
}
