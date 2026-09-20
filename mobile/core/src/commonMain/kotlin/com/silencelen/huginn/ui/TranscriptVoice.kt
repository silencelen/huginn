package com.silencelen.huginn.ui

import com.silencelen.huginn.data.TranscriptEvent

/**
 * WHO ACTUALLY SAID IT — the rows whose `kind` is honest and whose AUTHOR is not.
 *
 * Claude Code writes a transcript record for what arrived in its composer; it has
 * no way to know whether a person typed it or something else pasted it. Most of
 * the time nothing else does, and `kind: "user"` is the truth. The exceptions are
 * this app's own doing, and they have all been the same bug:
 *
 *  * a skill's body, drawn as if the owner had recited it;
 *  * a peer's `SendMessage`, drawn as the reader's own words (`lib/transcript.js`
 *    re-kinds those `system` now, keyed on `origin.kind === 'peer'`);
 *  * appd's project relay, caught by its `[Huginn] Relayed message from …` header;
 *  * and this one.
 *
 * ⚠⚠ THE WRAP-UP PHRASE IS THE DAEMON TALKING (P-34/D-28). `POST /v1/sessions/
 * :name/soft-end` types `SOFT_END_PHRASE` straight into the pane — "Finish
 * outstanding items, commit your work, and prepare to end the session." — so the
 * record it leaves behind is an ORDINARY typed prompt: no `origin`, no `isMeta`,
 * nothing structural to key on. Drawn as a right-aligned user bubble it is
 * indistinguishable from an instruction the reader wrote, which on an archived
 * transcript is the only account of how the session ended.
 *
 * ⚠ SO THE MATCH IS THE TEXT, AND THE TEXT COMES FROM THE HOST. The phrase is a
 * deployment setting (`HUGINN_APPD_SOFT_END_PHRASE`), which is exactly why the
 * daemon publishes it on `/v1/status` as [com.silencelen.huginn.data.Status.softEndPhrase]
 * — "so clients can show the exact wording instead of carrying a copy that
 * drifts from the host". A client-side literal would be that copy. Null phrase ⇒
 * nothing is ever classified, which is what a daemon too old to send one gets and
 * is the safe direction: an unrecognised wrap-up is a bubble, as it always was.
 */
object TranscriptVoice {

    /** How a wrap-up row is attributed once it stops being a bubble. */
    const val WRAP_UP_LEAD: String = "huginn asked this session to wrap up"

    /**
     * Whether this row is the daemon's wrap-up phrase rather than the reader's.
     *
     * Only a `user` row can be one: the phrase reaches the transcript through the
     * composer and nothing else. Compared on collapsed whitespace because the
     * phrase travels through tmux — a paste that wrapped, or an echo that picked
     * up a trailing space, is the same sentence.
     */
    fun isWrapUp(ev: TranscriptEvent, phrase: String?): Boolean {
        if (ev.kind != "user") return false
        val want = normalise(phrase ?: return false)
        if (want.isEmpty()) return false
        return normalise(ev.text ?: return false) == want
    }

    /**
     * The system row's words: who asked, and what was asked.
     *
     * The phrase is kept rather than replaced by a summary. It is an instruction
     * the model acted on, and a reader working out why a session stopped is
     * entitled to the same sentence the model got.
     */
    fun wrapUpNote(phrase: String): String {
        val body = phrase.trim()
        return if (body.isEmpty()) WRAP_UP_LEAD else "$WRAP_UP_LEAD — $body"
    }

    private fun normalise(s: String): String = s.trim().replace(WHITESPACE_RUN, " ")

    private val WHITESPACE_RUN = Regex("""\s+""")
}
