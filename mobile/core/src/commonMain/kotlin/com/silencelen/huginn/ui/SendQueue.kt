package com.silencelen.huginn.ui

import com.silencelen.huginn.data.IntoDraft
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
        // ⚠ BEFORE THE COUNT, because a duplicate usually has no count. The
        // daemon dropped a send it already holds (or delivered seconds ago), so
        // there is nothing in the queue that belongs to THIS tap — and falling
        // through to `queued <= 0 -> null` is what leaves the composer looking
        // as though the message vanished, which is the whole complaint.
        if (s.blockedBy == DUPLICATE_REASON) return DUPLICATE
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
        // `attention` (appd 3.4.0): a question is waiting on the screen and the daemon
        // holds a human send behind it until somebody answers — same family as a modal.
        if (s.blockedBy == "attention") return "Queued · a question is waiting on the screen $waiting"
        // `draft` (appd 3.5.0): there is unsent text in the live view and a paste in front
        // of it would be submitted as one sentence. Cleared by sending or clearing it —
        // a thing the reader can DO, so it gets its own words.
        if (s.blockedBy == "draft") return "Queued · unsent text in the live view $waiting"
        if (s.delivering) return "Sending $waiting"
        return "Queued · will send when Claude finishes its turn $waiting"
    }

    /**
     * The queue state a send's OWN answer implies, or null when it landed.
     *
     * Seeded from the send rather than waited for from the first poll: two seconds
     * of a composer that emptied with no explanation is the whole complaint, and
     * the answer to the send already carries the number.
     *
     * ⚠ AND THE REASON, NOT JUST THE NUMBER. The seed used to carry a count alone,
     * so [note] fell through to its default sentence — "will send when Claude
     * finishes its turn" — until the first `/typing` poll landed. On a session
     * that has only just been created that describes a turn which has not begun,
     * which is the one wait a reader is most likely to meet: the line was wrong
     * for about two seconds and then quietly corrected itself. appd 3.1.2 puts
     * `blockedBy` on the send's own answer, using the same words `/typing` reports,
     * so the first sentence is the right one. Null from an older daemon, which is
     * the old sentence exactly.
     */
    fun seed(result: SendKeysResult): TypingState? = when {
        // ⚠ CHECKED BEFORE `landed`, WHICH A DUPLICATE ALWAYS IS. The daemon did
        // not queue this send — it already has the text — so `queued` is 0 and
        // the old rule returned null: the composer emptied and said nothing, on
        // exactly the send whose fate a reader most wants explained.
        result.duplicate -> TypingState(queued = result.queued, blockedBy = DUPLICATE_REASON)
        result.landed -> null
        else -> TypingState(queued = result.queued, blockedBy = result.blockedBy)
    }

    /**
     * The composer's word for a send the daemon already had.
     *
     * ⚠ IT SAYS THE MESSAGE IS FINE. "Duplicate", "rejected" or "not sent" would
     * all be read as "retype it", which is what produced the three copies in the
     * first place. The one fact worth giving is that the text is on its way.
     */
    const val DUPLICATE: String = "That message is already on its way"

    /**
     * The `blockedBy` word this client MINTS for a duplicate.
     *
     * The daemon's 3.5.1 answer carries `duplicate: true` rather than a reason
     * word, and the seed has to survive as far as [note] somehow; `blockedBy` is
     * already "why this send is not on the pane yet" and needs no second field
     * to carry one more reason. A daemon that later sends the word itself lands
     * on the same sentence, which is the right outcome either way.
     */
    const val DUPLICATE_REASON: String = "duplicate"

    /**
     * The mark on a session's list row, or null when nothing is waiting.
     *
     * Short because a row has no width: the composer's line says what the wait is
     * for, and this only has to say that there is one.
     */
    fun rowMark(pendingSends: Int): String? =
        if (pendingSends > 0) "$pendingSends queued" else null

    // ------------------------------------------- the message that went in anyway

    /**
     * ⚠⚠ D-7, DECISION 59. THE ONE THING BOTH CLIENTS USED TO SAY NOTHING ABOUT.
     *
     * The draft hold is a hold with a ceiling: sixty seconds after the last
     * live-view keystroke, and then the queued message is pasted in front of
     * whatever is in the composer and submitted as one prompt. The desktop walker
     * watched `draft in progress` and `say OK2` leave as
     * `draft in progresssay OK2` — and the client's account of it was that the
     * "Queued" line vanished and a merged user bubble appeared. Decision 59 keeps
     * the ceiling and ends the silence: the daemon reports it (`intoDraft` on
     * `/typing` and on the `/keys` answer) and both clients say so.
     *
     * The sentence names the ACT and then SHOWS the text, because "your message
     * was merged" invites the reader to guess which one and with what; the
     * composer excerpt is what turns it into something they can go and look at.
     * The daemon has already collapsed the whitespace and cut it to 120
     * characters.
     *
     * @return null when nothing went into a draft — which is almost always.
     */
    fun draftNotice(into: IntoDraft?): String? {
        val d = into ?: return null
        if (d.at <= 0) return null
        val composer = d.composer.trim()
        if (composer.isEmpty()) return DRAFT_NOTICE_BARE
        return "$DRAFT_NOTICE_LEAD “$composer” — check the session"
    }

    /** The lead-in, alone in the suite so the two sentences below cannot drift. */
    const val DRAFT_NOTICE_LEAD: String =
        "Your message was sent into text someone was still typing:"

    /**
     * When the daemon reported the delivery but not what it landed in — an empty
     * `composer`, which happens when the draft was whitespace or the capture
     * raced the paste. Still worth saying: the fact is the merge, not the quote.
     */
    const val DRAFT_NOTICE_BARE: String =
        "Your message was sent into text someone was still typing — check the session"

    /**
     * Whether a `/typing` answer is worth reading [draftNotice] out of yet.
     *
     * ⚠ THE DAEMON'S OWN RULE, MOVED HERE SO BOTH SHELLS OBEY IT. `intoDraft`
     * deliberately outlives the queue, so it is present WHILE a later message is
     * still queued — and a notice raised then describes a delivery the reader has
     * not seen the result of. Read it once the queue is empty and nothing is in
     * flight.
     */
    fun draftNoticeReady(state: TypingState?): Boolean {
        val s = state ?: return false
        return s.queued <= 0 && !s.delivering && s.intoDraft != null
    }
}
