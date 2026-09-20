package com.silencelen.huginn.ui

/**
 * ⚠⚠ THE LIST MUST NOT MOVE WHILE SOMEBODY IS AIMING AT IT (P-02, high).
 *
 * The session list sorts by `activityAt` and the daemon re-answers every couple
 * of seconds, so with four to six live sessions the order changes constantly.
 * The phone walker opened the ⋮ on row 2 (`rvphoneproj-docs-reviewer`,
 * confirmed in the dump), tapped **Kill session** where it was drawn — and got
 * **"Wrap up rv-desktop-1?"**: a different verb on a different session. Twice
 * more in the same five minutes a tap on one row opened another. The only thing
 * standing between that and an ended session was the confirm dialog's wording,
 * which is why the dialogs also got louder (see the shells) and why this is not
 * a P0.
 *
 * The rule is one sentence: **while a row menu, a confirm dialog or a long-press
 * is open, the order the reader can see is the order they get.** New rows still
 * appear and gone rows still vanish — freezing the CONTENTS would make a list
 * that lies about what exists — but nothing already on screen changes place.
 *
 * Pure, and in `:core`, because both shells have the same list and the same
 * gesture, and "which row did they actually mean" is not a question either shell
 * should be answering on its own.
 *
 * ⚠ THE LOCK IS NOT A CACHE. When nothing is open this returns the incoming
 * order untouched and the shells re-record what they drew. A lock that outlived
 * the gesture would be a list that had quietly stopped sorting.
 */
object OrderLock {

    /**
     * The order to draw, given what is on screen now.
     *
     * @param shown the keys in the order the reader is currently looking at,
     *   newest render first — what [keysOf] returned last time.
     * @param incoming the fresh list in the server's order.
     * @param frozen true while a row menu, confirm dialog or long-press is open.
     * @param key the stable identity of a row. The session NAME, not its index:
     *   an index is exactly the thing that re-points when the list re-sorts.
     *
     * The ordering when frozen, precisely:
     *
     *  * every incoming row whose key was on screen keeps the position it had,
     *    relative to the others that were on screen;
     *  * a row that has appeared since goes to the END, in the incoming order,
     *    because there is no position it could have taken that the reader has
     *    already seen;
     *  * a row that has gone is gone. A dialog naming a session that no longer
     *    exists is a dialog that cannot do anything, and the shells' confirm
     *    dialogs carry the name so the reader is told either way.
     */
    fun <T> order(shown: List<String>, incoming: List<T>, frozen: Boolean, key: (T) -> String): List<T> {
        if (!frozen || shown.isEmpty() || incoming.isEmpty()) return incoming
        val rank = HashMap<String, Int>(shown.size)
        shown.forEachIndexed { i, k -> rank.putIfAbsent(k, i) }
        val kept = ArrayList<T>(incoming.size)
        val fresh = ArrayList<T>()
        for (row in incoming) {
            if (rank.containsKey(key(row))) kept += row else fresh += row
        }
        // Stable by construction: `sortedBy` in the standard library keeps the
        // order of equal elements, and two rows cannot share a rank anyway.
        return kept.sortedBy { rank[key(it)] } + fresh
    }

    /** What to remember about a render, so the next one can be held to it. */
    fun <T> keysOf(rows: List<T>, key: (T) -> String): List<String> = rows.map(key)
}
