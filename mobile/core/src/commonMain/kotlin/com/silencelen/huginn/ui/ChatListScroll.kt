package com.silencelen.huginn.ui

/**
 * Where the chat list lands when you arrive on it.
 *
 * TWO ARRIVALS THAT FEEL NOTHING ALIKE, and the list used to treat them as one.
 *
 *  * **From another destination** — the Sessions tab, Status, Settings, the rail.
 *    You came to Chats to look at Chats, and what you almost always want is the
 *    newest conversation. Landing 40 rows down, at whatever you happened to be
 *    reading last week, reads as a list that failed to refresh.
 *  * **Back out of a chat you opened FROM the list** — you are drilling in and
 *    out, comparing two conversations, working down a run of them. Throwing the
 *    list back to the top on every step is the behaviour that makes a person
 *    stop using the back gesture.
 *
 * So the rule, stated once for both clients: **entering the chat list from
 * another top-level destination snaps to the latest chat; coming back from a
 * chat keeps the position.**
 *
 * It is here rather than in either shell because the two shells express "where
 * am I" completely differently — the phone has a `Dest` with a saved string key,
 * the desktop a `View` enum and a separate open-chat id — and the only thing they
 * can both hand a shared rule is the NAME of where they were and where they now
 * are. Given those two strings the answer is the same on both, which is the whole
 * reason it is one rule rather than two lookalikes.
 */
object ChatListScroll {

    /** The chat list itself. The phone's `destToKey(Dest.Chats)`. */
    const val CHATS: String = "chats"

    /**
     * What one open chat's key starts with — `chat:<id>` on the phone.
     *
     * The desktop never produces one (opening a chat there changes the id beside
     * the list, not the destination, so the list pane is never left at all) and
     * that costs nothing: a transition it cannot make is a transition this rule
     * simply never sees.
     */
    const val CHAT_PREFIX: String = "chat:"

    /**
     * Should the list jump to the latest chat, having moved from [from] to [to]?
     *
     * @param from where the reader was, or null when that is not known — a cold
     *   start, or the first composition after an activity rebuild. NULL IS NOT A
     *   SNAP, deliberately: a fresh list is already at the top, so the only thing
     *   an unknown origin could do is throw a folded-and-unfolded phone away from
     *   the row it was on, which is the bug the saved destination exists to fix.
     */
    fun shouldSnap(from: String?, to: String): Boolean {
        // Only this list has an opinion. Every other destination's scroll is its
        // own business.
        if (to != CHATS) return false
        if (from == null) return false
        // Not a transition at all: a recomposition, or the same destination
        // re-delivered. Nobody navigated, so nothing moves.
        if (from == CHATS) return false
        // Drilling back out. The list is the way back INTO the conversation
        // beside the one just read.
        if (isChat(from)) return false
        return true
    }

    /** Whether a destination key names one open chat rather than the list. */
    fun isChat(key: String): Boolean = key.startsWith(CHAT_PREFIX)
}
