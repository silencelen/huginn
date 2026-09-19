package com.silencelen.huginn.desktop.setup

/**
 * What is TYPED into a setup step but not yet committed.
 *
 * ⚠ A HOLDER BESIDE THE FLOW, for the same reason [SetupController] is one: the
 * probes run outside the composition and cannot see a step body's `remember`.
 * "Try the token" therefore read [com.silencelen.huginn.data.HuginnSettings
 * .tokenNow] — the SAVED token — and reported "no token saved yet" at a field
 * the reader had just pasted into and could see the dots of. The step's one
 * button contradicted the step's one field.
 *
 * Deliberately not a settings value and deliberately not persisted: a draft is
 * what somebody is in the middle of typing, and writing every keystroke of a
 * bearer to disk to make a button work would be a worse fix than the bug.
 * Cleared when the flow closes.
 */
object SetupDrafts {

    /** The token field's current text, as the step body last drew it. */
    @Volatile
    var token: String = ""

    /**
     * The token a probe should actually try.
     *
     * The field first, because it is what the reader is looking at; the saved
     * one when the field has not been touched, which is the "Run setup again"
     * case on a working install. TRIMMED, because a bearer pasted out of a
     * terminal or a password manager arrives with a newline more often than not
     * and a token with a trailing newline is a 401 indistinguishable from a
     * wrong one.
     */
    fun tokenToUse(draft: String, saved: String): String =
        draft.trim().ifBlank { saved }

    fun clear() {
        token = ""
    }
}
