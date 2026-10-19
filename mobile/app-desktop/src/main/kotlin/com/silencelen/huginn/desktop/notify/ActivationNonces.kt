package com.silencelen.huginn.desktop.notify

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The proof that a `huginn://` link came from a toast THIS process posted: the
 * only thing that lets an `answer` or an `undo` act, and an `ack` or an `open`
 * take a notification down.
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
 *
 * Every toast that carries links gets one, buttons or not, because a click on the
 * toast itself (`open`) and its OK (`ack`) take it down, and taking a "needs you"
 * down is not something a web page that guessed its key gets to do. That right
 * lives longer ([ackTtlMs]) than the right to act ([ttlMs]): a toast read an hour
 * late should still go away when clicked, but should not answer anything.
 */
class ActivationNonces(
    private val ttlMs: Long = TTL_MS,
    /** How long a nonce can still open its toast's target and take the toast down. */
    private val ackTtlMs: Long = ACK_TTL_MS,
    /** Milliseconds on a MONOTONIC clock. A wall clock can be set back. */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: SecureRandom = SecureRandom(),
    private val capacity: Int = CAPACITY,
) {

    /**
     * What one nonce lets an activation do, and nothing more. Every grant also
     * lets the toast be opened (its target) and taken down (its key); see
     * [redeemOpen] and [redeemAck].
     */
    sealed interface Grant {
        /** Any ONE of [options] on the question [fingerprint] identifies, in [session]. */
        data class Answer(val session: String, val fingerprint: String, val options: Set<Int>) : Grant

        /** Put [session] back on the model the ladder moved it off. */
        data class Undo(val session: String) : Grant

        /** Nothing but the toast itself: opening what it points at, and taking it down. */
        data object Notice : Grant
    }

    /** Why an activation was or was not acted on. Only [ACCEPTED] acts. */
    enum class Verdict { ACCEPTED, UNKNOWN, EXPIRED, MISMATCH }

    private class Entry(
        val key: String,
        /** Where a click on the toast itself goes, or null when it goes nowhere. */
        val opens: NavTarget?,
        val nonce: ByteArray,
        val grant: Grant,
        val issuedAt: Long,
    )

    private val lock = Any()
    private val live = ArrayList<Entry>()

    /**
     * A new nonce for the toast filed under [key], replacing that toast's last one.
     * [opens] is where a click on the toast goes; an answer or an undo goes to its
     * session unless told otherwise. The returned text is URL-safe as it stands.
     */
    fun issue(key: String, grant: Grant, opens: NavTarget? = sessionOf(grant)): String {
        val bytes = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val text = ENCODER.encodeToString(bytes)
        synchronized(lock) {
            val t = now()
            live.removeAll { it.key == key || isStale(it, t, maxOf(ttlMs, ackTtlMs)) }
            while (live.size >= capacity) live.removeAt(0)
            live += Entry(key, opens, text.toByteArray(Charsets.US_ASCII), grant, t)
        }
        return text
    }

    /** The toast filed under [key] is gone, so its buttons are too. */
    fun revoke(key: String) {
        synchronized(lock) { live.removeAll { it.key == key } }
    }

    fun redeemAnswer(nonce: String, session: String, option: Int, fingerprint: String): Verdict =
        redeem(nonce, ttlMs) { e ->
            val g = e.grant
            g is Grant.Answer && g.session == session && g.fingerprint == fingerprint && option in g.options
        }

    fun redeemUndo(nonce: String, session: String): Verdict =
        redeem(nonce, ttlMs) { e -> val g = e.grant; g is Grant.Undo && g.session == session }

    /** A click on the toast itself: [target] must be where that toast opens. */
    fun redeemOpen(nonce: String, target: NavTarget): Verdict =
        redeem(nonce, ackTtlMs) { e -> e.opens == target }

    /** The toast's OK: [key] must be the toast's own. */
    fun redeemAck(nonce: String, key: String): Verdict =
        redeem(nonce, ackTtlMs) { e -> e.key == key }

    private fun redeem(nonce: String, ttl: Long, fits: (Entry) -> Boolean): Verdict {
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
            if (isStale(hit, t, ttl)) return Verdict.EXPIRED
            return if (fits(hit)) Verdict.ACCEPTED else Verdict.MISMATCH
        }
    }

    /**
     * Older than [ttl], or issued "after" now. The second can only be a clock
     * fault, and a lock that opens on a fault is not a lock (the app-lock lesson).
     */
    private fun isStale(e: Entry, t: Long, ttl: Long): Boolean = t < e.issuedAt || t - e.issuedAt > ttl

    private fun sessionOf(grant: Grant): NavTarget? = when (grant) {
        is Grant.Answer -> NavTarget(TargetKind.SESSIONS, grant.session)
        is Grant.Undo -> NavTarget(TargetKind.SESSIONS, grant.session)
        Grant.Notice -> null
    }

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

        /**
         * A DAY, for opening a toast's target and taking the toast down. Withdrawing
         * a notification acts for nobody, so it may outlive the right to answer; but
         * not forever, and the table's [CAPACITY] bounds it before this usually does.
         */
        const val ACK_TTL_MS: Long = 24 * 60 * 60_000L

        /** 256 bits; the floor is 128. */
        const val NONCE_BYTES: Int = 32

        /** Live toasts. Far more than a desk ever shows at once. */
        const val CAPACITY: Int = 64

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
