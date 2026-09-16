package com.silencelen.huginn.ui

import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.TypingState

/**
 * What a message that has not landed yet SAYS.
 *
 * THE BUG THIS EXISTS FOR: a message typed into a session that is mid-turn is
 * queued by the daemon and delivered at the next turn boundary — correct, and
 * invisible. The composer emptied, the transcript did not grow, and nothing
 * anywhere said why; the owner's report was that the message "just disappears".
 * The facts were already on the wire the whole time ([SendKeysResult.queued] on
 * the send's own answer, [TypingState] on a poll, `Session.pendingSends` on the
 * list) and no client read any of them.
 *
 * Pure, in `:core`, because both clients will want the same sentence and a phrase
 * kept in two apps is a phrase that gets fixed in one.
 */
object SendQueue {

    /**
     * The line under the composer, or null when there is nothing to say.
     *
     * THE ERROR WINS, VERBATIM. When the daemon could not deliver, its own
     * sentence is the only thing that tells a reader whether to retype the
     * message or wait — "queued" over the top of a failure is the worst of both.
     */
    fun note(state: TypingState?): String? {
        val s = state ?: return null
        s.lastError?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (s.queued <= 0) return null
        val waiting = "(${s.queued} waiting)"
        // `modal` is a DIFFERENT wait and needs different words: a dialog is open
        // on the pane and no amount of Claude finishing a turn will clear it —
        // somebody has to answer it. Saying "when Claude finishes its turn" there
        // is an instruction to do nothing about the one thing blocking the send.
        if (s.blockedBy == "modal") return "Queued · a dialog is open on the screen $waiting"
        // `starting` (appd 3.0.7): the session was just created and Claude has not
        // drawn its composer yet — about two seconds, and it clears itself. The turn
        // sentence would describe a turn that has not begun.
        if (s.blockedBy == "starting") return "Queued · waiting for Claude to start $waiting"
        if (s.delivering) return "Sending $waiting"
        return "Queued · will send when Claude finishes its turn $waiting"
    }

    /**
     * The queue state a send's OWN answer implies, or null when it landed.
     *
     * Seeded from the send rather than waited for from the first poll: two seconds
     * of a composer that emptied with no explanation is the whole complaint, and
     * the answer to the send already carries the number.
     */
    fun seed(result: SendKeysResult): TypingState? =
        if (result.landed) null else TypingState(queued = result.queued)

    /**
     * The mark on a session's list row, or null when nothing is waiting.
     *
     * Short because a row has no width: the composer's line says what the wait is
     * for, and this only has to say that there is one.
     */
    fun rowMark(pendingSends: Int): String? =
        if (pendingSends > 0) "$pendingSends queued" else null
}
