package com.silencelen.huginn.desktop.ui.common

/**
 * Multi-select for a list pane: what a mouse and a modifier key can express and a
 * thumb cannot.
 *
 * This is the affordance a phone has no way to offer. On Android the same need
 * turns into a long-press that flips the whole screen into a selection MODE with
 * its own app bar, because a finger has no chord — and that mode is worth its cost
 * only when the payoff is big. Here it is Ctrl-click and Shift-click, it costs no
 * chrome at all, and it turns "delete eleven throwaway chats" from eleven
 * confirmations into one.
 *
 * PURE, and separate from the composable that draws it, because the interesting
 * part is the range: the anchor is not the same thing as the last row clicked, it
 * survives a Ctrl-click that did not extend anything, and getting that wrong makes
 * Shift-click select an arbitrary block that then gets deleted. That deserves a
 * test rather than a lambda inside a row.
 */
data class Selection(
    val ids: Set<String> = emptySet(),
    /** Where a Shift-click measures FROM. Null when nothing has been clicked yet. */
    val anchor: String? = null,
) {
    val size: Int get() = ids.size
    val isEmpty: Boolean get() = ids.isEmpty()
    operator fun contains(id: String): Boolean = id in ids

    /** A plain click: this row alone, and it becomes the new anchor. */
    fun only(id: String): Selection = Selection(setOf(id), id)

    /**
     * Ctrl-click: add or remove one row. The anchor MOVES to the clicked row even
     * when the click removed it — that is what the platforms do, and it is what
     * makes "ctrl-click here, shift-click there" mean the obvious thing.
     */
    fun toggle(id: String): Selection {
        val next = if (id in ids) ids - id else ids + id
        return Selection(next, id)
    }

    /**
     * Shift-click: everything between the anchor and here, in the list's own
     * order. Replaces the selection rather than adding to it, and leaves the
     * anchor where it was so the range can be re-dragged from the same end.
     *
     * With no anchor — or an anchor for a row that has since disappeared, which
     * happens on every poll — this degrades to selecting the clicked row rather
     * than selecting nothing or throwing.
     */
    fun extendTo(id: String, order: List<String>): Selection {
        val from = order.indexOf(anchor ?: return only(id))
        val to = order.indexOf(id)
        if (from < 0 || to < 0) return only(id)
        val range = if (from <= to) order.subList(from, to + 1) else order.subList(to, from + 1)
        return Selection(range.toSet(), anchor)
    }

    fun cleared(): Selection = Selection()

    /**
     * Drop ids the list no longer has. The lists re-poll every 5s and a deleted
     * chat must not stay in a selection that a later "Delete 3 chats" would then
     * send to the daemon.
     */
    fun retaining(present: Collection<String>): Selection {
        val set = present.toSet()
        if (ids.all { it in set }) return this
        val kept = ids.intersect(set)
        return Selection(kept, anchor?.takeIf { it in set })
    }
}

/**
 * What a click means, given the modifiers. Kept here rather than in the row so the
 * three cases are one expression instead of three nested ifs at two call sites
 * (chats and sessions) that could drift.
 */
fun clickSelection(
    current: Selection,
    id: String,
    order: List<String>,
    ctrl: Boolean,
    shift: Boolean,
): Selection = when {
    shift -> current.extendTo(id, order)
    ctrl -> current.toggle(id)
    else -> current.only(id)
}

/**
 * Whether a plain click should also OPEN the row. It should not while a
 * multi-selection is being built — opening a chat mid-selection scrolls a detail
 * pane the reader is not looking at and starts a transcript fetch nobody asked
 * for.
 */
fun opensOnClick(ctrl: Boolean, shift: Boolean): Boolean = !ctrl && !shift
