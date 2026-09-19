package com.silencelen.huginn.ui

/**
 * The two ways a session ends, worded once for both clients.
 *
 * THE WORDS CHANGED IN 3.5.3 and the reason is that the old pair described the
 * MECHANISM rather than the outcome. "Wind down" is a phrase nobody uses for a
 * terminal, and "End session" and "Wind down" sat next to each other in one menu
 * reading as two spellings of the same thing — so the destructive one was picked
 * by people who meant the graceful one. "Wrap up" is what the instruction it
 * sends actually says, and "Kill session" is what the daemon actually does.
 *
 * They live here, in `:core`, for the reason every other shared phrase does: a
 * verb kept in two apps is a verb that gets renamed in one. Three menus draw
 * them — the desktop's right-click, the phone's session row, and the phone's
 * detail-screen overflow — and before this they were five separate literals that
 * had already drifted apart on the ellipsis.
 *
 * NO ELLIPSIS, on either. Both open a confirm dialog, so "…" would be on both or
 * neither, and the desktop's hard end has never carried one. The mark is kept for
 * Rename… / Archive… / Delete…, which are unchanged. ⚠ The CLI's `huginn end`
 * and `huginn kill`, and the daemon routes under them, are NOT affected by this
 * file — those are a typed interface with its own compatibility, and renaming a
 * menu row is not a reason to break a command somebody has in a script.
 */
object EndVerbs {

    /**
     * Ask Claude to finish and prepare to end. Sends a message; the session may
     * well still be there afterwards, which is exactly why it is not the full
     * red.
     */
    const val SOFT: String = "Wrap up"

    /** End the tmux session and whatever is running in it, now. */
    const val HARD: String = "Kill session"

    /**
     * The soft verb for a menu addressing [count] sessions.
     *
     * A multi-selection SAYS HOW MANY, for the reason the delete verbs do: a row
     * that reads "Wrap up" and messages four sessions is the worst version of
     * this feature.
     */
    fun soft(count: Int): String = if (count <= 1) SOFT else "Wrap up $count sessions"

    /** The hard verb for a menu addressing [count] sessions. */
    fun hard(count: Int): String = if (count <= 1) HARD else "Kill $count sessions"
}

/**
 * How hard a verb lands, and therefore what colour it is drawn in.
 *
 * ⚠ THE RULE THIS REPLACES was a single `destructive` boolean, which could only
 * say red or not-red — so the wrap-up had to be drawn as an ordinary row, and a
 * menu whose two ending verbs looked nothing alike taught nobody that they were
 * a pair. Three tones is the smallest model that can say "these two both end the
 * session, and one of them is the one that loses work".
 *
 * The COLOURS are `:ui`'s (`verbInk`), because a colour needs a scheme; what is
 * pure, and therefore assertable, is which tone a given menu row claims.
 */
enum class VerbTone {
    /** The menu's ordinary ink. Nothing ends. */
    PLAIN,

    /**
     * A lighter red: this ends something, and loses nothing. The wrap-up, and
     * only the wrap-up — an archive keeps everything it ends and stays plain.
     */
    SOFT,

    /** The full red: work can be lost here. The kill, and the deletes. */
    DESTRUCTIVE,
}
