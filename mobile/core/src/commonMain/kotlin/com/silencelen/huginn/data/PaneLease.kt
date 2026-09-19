package com.silencelen.huginn.data

/**
 * When this client may hold a tmux window at its own geometry, and when it owes
 * the window back.
 *
 * A screen poll that carries `?cols=&rows=&live=1` makes the daemon set
 * `window-size manual` on that tmux window for 90 seconds, renewed by the polling
 * itself. That is a lease over something that is NOT ours: the owner works in
 * these sessions from a terminal, and a client that keeps polling while nobody can
 * see it pins their window to a shape chosen by a window they are not looking at.
 * The phone learned this in 2.0.1 and the Electron client learned it again; the
 * rule is written down here so a third client cannot learn it a third time.
 *
 * ⚠ AND `live=1` IS NOW WHAT MAKES IT A CLAIM AT ALL (owner decision 52).
 * Reporting a size no longer leases anything, because two clients doing it at
 * once is not a race either of them can win politely: measured, the owner's pane
 * walked 152x44 <-> 107x44 three times in ninety seconds with neither client
 * typing. Only the surface that SENDS KEYS may reshape the window, and the daemon
 * gives it to one client at a time.
 *
 * Pure, because the rule is the safety property and the I/O around it is not
 * testable: [reported] says what geometry may be described right now, [wanted]
 * says what may be CLAIMED, [toRelease] says what must be handed back, and every
 * exit path in a client reduces to one of those questions.
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
     * The geometry this client may REPORT, or null for "poll without geometry".
     *
     * Reporting is not claiming. Since the daemon gained `live`, `?cols=&rows=`
     * describes the surface that is drawing and nothing more — the pane is
     * captured as it is — so a visible grid may always say how big it is.
     * [wanted] is the separate question of whether it may reshape tmux.
     *
     * @param session   the open session, or null when no session view is open
     * @param visible   whether the window is on screen. A minimized or hidden
     *   window has no geometry worth asking for — that is the whole failure.
     * @param wantsGrid whether the surface currently drawing is the one that
     *   renders a character grid. A conversation tab does not need tmux reshaped.
     * @param cols/rows the measured grid, or null before the first measurement
     */
    fun reported(
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
     * The geometry this client may CLAIM the window at, or null for "take no
     * lease". Null is not an error state and not a reason to stop polling.
     *
     * ⚠ [live] IS THE WHOLE RULE (owner decision 52). Two clients with the same
     * session open used to report geometry on every poll and the last poll won:
     * the owner's real pane was measured walking 152x44 <-> 107x44 three times
     * in ninety seconds with nobody typing. Displaying a pane is not a reason to
     * reshape somebody's terminal; TYPING INTO IT is. So only the surface that
     * sends keys — the desktop's live keyboard mode, the phone's live typing —
     * may lease, and leaving that mode hands the window back.
     *
     * @param live whether this client is in live view: the mode that sends keys.
     */
    fun wanted(
        session: String?,
        visible: Boolean,
        wantsGrid: Boolean,
        live: Boolean,
        cols: Int?,
        rows: Int?,
    ): Want? = if (!live) null else reported(session, visible, wantsGrid, cols, rows)

    /**
     * What one screen poll should put on the wire, for a client whose only
     * geometry state is "the grid I can draw" — the phone.
     *
     * The same rule as [wanted] with the visibility questions already answered by
     * the poll existing at all: the phone stops polling when it leaves the screen.
     */
    data class Poll(val cols: Int?, val rows: Int?, val live: Boolean)

    fun poll(cols: Int?, rows: Int?, liveView: Boolean): Poll =
        // Nothing measured yet is nothing to claim: leasing at a guessed size
        // moves the owner's window twice, once wrongly.
        Poll(cols, rows, live = liveView && cols != null && rows != null)

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
