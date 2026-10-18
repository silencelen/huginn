package com.silencelen.huginn.desktop.notify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The proof that a `huginn://answer` or `huginn://undo` came from a toast THIS
 * process posted.
 *
 * ⚠ THE FINGERPRINT IS NOT THAT PROOF, and for a while it was treated as one. It
 * is the host's identity for a question: an unkeyed hash of the question and its
 * options, which anyone who can predict a stock Claude Code dialog can compute.
 * Any web page or local process can fire a scheme URL, so an answer link that
 * carried only the fingerprint could approve a plan or a tool-use prompt on a
 * root-equivalent host with no human involved. The fingerprint keeps the job it
 * is good at, the daemon's staleness check (a 409 when the question has moved
 * on); being asked for by this app is this class's job.
 *
 * Each toast with buttons gets a fresh random nonce, held here and nowhere else:
 * not on disk, not in the daemon. An activation is acted on only if its nonce was
 * issued by this process, is presented once, is younger than [ttlMs] and was
 * issued for the very session, question and option the URL names. Anything else,
 * including every link that started the process (a nonce from a previous run
 * cannot be in a fresh table), is turned into navigation by [Activations.resolve]:
 * the window comes up on the session and the owner answers in the app.
 *
 * One nonce per TOAST, filed under the toast's key: a newer toast for the same key
 * replaces the older one's nonce, and taking the toast down revokes it. Spending
 * it on one button spends the toast, so a three-button "needs you" can answer once.
 */
class ActivationNonces(
    private val ttlMs: Long = TTL_MS,
    /** Milliseconds on a MONOTONIC clock. A wall clock can be set back. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: SecureRandom = SecureRandom(),
    private val capacity: Int = CAPACITY,
) {

    /** What one nonce lets an activation do, and nothing more. */
    sealed interface Grant {
        /** Any ONE of [options] on the question [fingerprint] identifies, in [session]. */
        data class Answer(val session: String, val fingerprint: String, val options: Set<Int>) : Grant

        /** Put [session] back on the model the ladder moved it off. */
        data class Undo(val session: String) : Grant
    }

    /** Why an activation was or was not acted on. Only [ACCEPTED] acts. */
    enum class Verdict { ACCEPTED, UNKNOWN, EXPIRED, MISMATCH }

    private class Entry(val key: String, val nonce: ByteArray, val grant: Grant, val issuedAt: Long)

    private val lock = Any()
    private val live = ArrayList<Entry>()

    /**
     * A new nonce for the toast filed under [key], replacing that toast's last one.
     * The returned text is URL-safe as it stands.
     */
    fun issue(key: String, grant: Grant): String {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val text = ENCODER.encodeToString(bytes)
        synchronized(lock) {
            val t = now()
            live.removeAll { it.key == key || isStale(it, t) }
            while (live.size >= capacity) live.removeAt(0)
            live += Entry(key, text.toByteArray(Charsets.US_ASCII), grant, t)
        }
        return text
    }

    /** The toast filed under [key] is gone, so its buttons are too. */
    fun revoke(key: String) {
        synchronized(lock) { live.removeAll { it.key == key } }
    }

    fun redeemAnswer(nonce: String, session: String, option: Int, fingerprint: String): Verdict =
        redeem(nonce) { g ->
            g is Grant.Answer && g.session == session && g.fingerprint == fingerprint && option in g.options
        }

    fun redeemUndo(nonce: String, session: String): Verdict =
        redeem(nonce) { g -> g is Grant.Undo && g.session == session }

    private fun redeem(nonce: String, fits: (Grant) -> Boolean): Verdict {
        val presented = nonce.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val t = now()
            // Every live entry is compared, with no early exit and with a
            // constant-time comparison, so how long a refusal takes says nothing
            // about how close a guess came.
            var hit: Entry? = null
            for (e in live) {
                if (MessageDigest.isEqual(e.nonce, presented)) hit = e
            }
            if (hit == null) return Verdict.UNKNOWN
            // SPENT ON PRESENTATION, whatever happens next. A nonce that a
            // mismatched or late link failed with must not be retried into a fit.
            live.remove(hit)
            if (isStale(hit, t)) return Verdict.EXPIRED
            return if (fits(hit.grant)) Verdict.ACCEPTED else Verdict.MISMATCH
        }
    }

    /**
     * Older than the TTL, or issued "after" now. The second can only be a clock
     * fault, and a lock that opens on a fault is not a lock (the app-lock lesson).
     */
    private fun isStale(e: Entry, t: Long): Boolean = t < e.issuedAt || t - e.issuedAt > ttlMs

    companion object {
        /**
         * FIVE MINUTES. Long enough for a toast that is read and answered at the
         * desk, which is the case the buttons exist for. Past that, a click on
         * the button opens the session instead of answering it: one more click,
         * never a wrong answer, and a question that has waited that long is worth
         * reading in full before it is approved. It also bounds how long a nonce
         * that escaped (the Action Center keeps toast XML) stays worth anything.
         */
        const val TTL_MS: Long = 5 * 60_000L

        /** 256 bits; the floor is 128. */
        const val NONCE_BYTES: Int = 32

        /** Live toasts with buttons. Far more than a desk ever shows at once. */
        const val CAPACITY: Int = 64

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
