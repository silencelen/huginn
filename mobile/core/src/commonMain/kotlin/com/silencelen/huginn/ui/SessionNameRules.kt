package com.silencelen.huginn.ui

/**
 * What a tmux session may be called, said the way the daemon says it.
 *
 * ⚠⚠ THE HINT WAS NARROWER THAN THE RULE (P-24). The New-session dialog read
 * *"Letters, digits and underscore"* while the daemon has accepted a DASH since
 * `NAME_RE` was written — every session in this review (`rv-phone-1`,
 * `rvphoneproj-docs-reviewer`) is named with one. A hint that forbids what the
 * host allows is a hint people work around, and then stop reading.
 *
 * ⚠ AND THE DOT IS NOT ALLOWED, whatever a route's path matcher suggests. The
 * per-session ROUTES match `[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}` so an older
 * session carrying a dot can still be addressed; CREATING one is governed by
 * `NAME_RE`, which excludes it — because tmux silently rewrites `.` to `_` and
 * still exits 0, so the name you asked for is not the name you get, and every
 * per-session route on the reported name then 404s (daemon #103/#105/#106).
 *
 * ⚠ ASSERTED AS LITERALS, the [ScratchpadRules] and [AppRules] precedent: the
 * authority is `huginn-appd.js`'s `nameProblem`, in another language. A shared
 * helper would let both sides drift together and stay green.
 */
object SessionNameRules {

    /** The longest a name may be, daemon's `NAME_RE`. */
    const val MAX: Int = 50

    /**
     * The hint under the field. It names the dash, because the dash is what the
     * old wording left out and what everybody actually uses.
     */
    const val HINT: String = "Letters, digits, underscore and dash, up to 50 characters."

    /**
     * Why this name would be refused, as a sentence, or null if it is fine.
     *
     * The reserved-name list is the daemon's and is NOT mirrored here: it is
     * state (`sessreg.isReserved`), not a rule, and a client that guessed at it
     * would refuse names a newer daemon had freed. Those still come back as the
     * daemon's own 400, which is the right place for a fact only it holds.
     */
    fun nameProblem(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return "a session needs a name"
        if ('.' in s) {
            return "a session name cannot contain a \".\" — tmux rewrites it to \"_\", " +
                "so the name you asked for would not be the name you got"
        }
        if (!NAME.matches(s)) {
            return "invalid session name: letters, digits, underscore and dash, " +
                "starting with a letter, digit or underscore, up to 50 characters"
        }
        return null
    }

    /** `huginn-appd.js`'s `NAME_RE`, verbatim. */
    private val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_-]{0,49}$")
}
