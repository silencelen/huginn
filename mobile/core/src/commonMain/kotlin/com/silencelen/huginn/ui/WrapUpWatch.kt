package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Session

/**
 * ⚠⚠ P-19. A CANCELLED AUTO-END IS INVISIBLE, AND IT LOOKS EXACTLY LIKE A
 * SUCCESSFUL ONE.
 *
 * The walker tapped **Wrap up** on `rv-phone-1`, the wrap-up ran and finished
 * ("Safe to end the session."), and the daemon logged
 *
 *     14:05:21  soft-end: rv-phone-1 asked a question, auto-end cancelled
 *
 * and left the session alive. The app showed **nothing**: the row read `waiting`
 * and the winding-down badge simply went away. A person who tapped Wrap up and
 * put the phone down would believe the session had ended. The dialog's "a
 * wrap-up question keeps it open" is a pre-hoc caveat, not a report — and this is
 * the same failure mode recorded in memory `huginn-soft-end-2026-08-14`, so it
 * is not rare.
 *
 * ⚠ THE DAEMON DOES NOT SAY IT ON THE WIRE. `lib/softend.js` returns the action
 * `cancel` when the session's state is `attention`, and `huginn-appd.js` then
 * deletes the pending record — so the ONLY thing `/v1/sessions` reports is
 * `softEnding` going from true to false. That is three different endings wearing
 * one change:
 *
 *   * **settled** — the session was killed, so it is gone from the list (or
 *     archived, which moves it to the archive);
 *   * **cancelled** — it asked a question, so it is still there and its state is
 *     `attention`, which is the exact condition `stepSoftEnd` cancels on;
 *   * **expired** — it never started a run, so it is still there and is not in
 *     `attention`.
 *
 * So the rule is: still present, no longer winding down, and asking something.
 * Derived rather than guessed, and here rather than in two shells, because a
 * false positive puts "your wrap-up did not finish" on a session that ended
 * perfectly well.
 */
object WrapUpWatch {

    /** The state the daemon cancels an auto-end on. */
    const val ASKING: String = "attention"

    /** Which sessions are winding down right now — what to carry to the next reading. */
    fun winding(sessions: List<Session>): Set<String> =
        sessions.filterTo(HashSet()) { it.softEnding }.mapTo(HashSet()) { it.name }

    /**
     * The sessions whose auto-end was CANCELLED between two readings of the list.
     *
     * @param wasWinding what [winding] returned last time.
     * @param now the list as it is now.
     */
    fun cancelled(wasWinding: Set<String>, now: List<Session>): Set<String> =
        now.filterTo(HashSet()) { it.name in wasWinding && !it.softEnding && it.state == ASKING }
            .mapTo(HashSet()) { it.name }

    /**
     * What the reader is told, in the conversation.
     *
     * ⚠ IT SAYS THE SESSION IS STILL THERE. "The wrap-up failed" would send
     * somebody to retry a thing that worked; what actually happened is that
     * Claude asked a question, which is the one state this feature refuses to end
     * from, and the session is sitting waiting for an answer.
     */
    const val NOTICE: String =
        "Wrap-up held — it asked a question instead of finishing, so the session is still " +
            "running. Answer it, or end the session yourself."
}
