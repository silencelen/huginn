package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.desktop.notify.ActivationNonces.Grant
import com.silencelen.huginn.desktop.notify.ActivationNonces.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The toast nonces: the only thing that lets a `huginn://answer` or
 * `huginn://undo` from outside the window act for the owner.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`.
 */
class ActivationNoncesTest {

    private var clock = 1_000_000L
    private fun table(capacity: Int = ActivationNonces.CAPACITY) =
        ActivationNonces(now = { clock }, capacity = capacity)

    private val question = Grant.Answer("jotter", "fp-abc123", setOf(1, 2, 3))

    @Test
    fun `an issued nonce answers what it was issued for, exactly once`() {
        val t = table()
        val n = t.issue("sess:jotter", question)
        assertEquals(Verdict.ACCEPTED, t.redeemAnswer(n, "jotter", 2, "fp-abc123"))
        // REUSED: the same link fired again, by a replay or a second click.
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer(n, "jotter", 2, "fp-abc123"))
    }

    @Test
    fun `a nonce this process never issued is refused, however close it is`() {
        val t = table()
        val n = t.issue("sess:jotter", question)
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer("not-a-nonce", "jotter", 1, "fp-abc123"))
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer("", "jotter", 1, "fp-abc123"))
        val nearMiss = n.dropLast(1) + (if (n.last() == 'A') 'B' else 'A')
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer(nearMiss, "jotter", 1, "fp-abc123"))
        // ...and the guesses did not spend the real one.
        assertEquals(Verdict.ACCEPTED, t.redeemAnswer(n, "jotter", 1, "fp-abc123"))
    }

    @Test
    fun `a nonce from before a restart is unknown to the new process`() {
        // Every link that STARTS the app is this case: the toast was posted by a
        // run that has since ended, and its table went with it.
        val before = table()
        val n = before.issue("sess:jotter", question)
        val after = table()
        assertEquals(Verdict.UNKNOWN, after.redeemAnswer(n, "jotter", 1, "fp-abc123"))
    }

    @Test
    fun `a nonce expires at its TTL`() {
        val t = table()
        val a = t.issue("sess:a", Grant.Answer("a", "fp", setOf(1)))
        val b = t.issue("sess:b", Grant.Answer("b", "fp", setOf(1)))
        clock += ActivationNonces.TTL_MS
        assertEquals(Verdict.ACCEPTED, t.redeemAnswer(a, "a", 1, "fp"), "still inside the TTL")
        clock += 1
        assertEquals(Verdict.EXPIRED, t.redeemAnswer(b, "b", 1, "fp"))
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer(b, "b", 1, "fp"), "an expired nonce is gone, not retried")
    }

    @Test
    fun `a clock reading earlier than the issue refuses rather than accepts`() {
        val t = table()
        val n = t.issue("sess:jotter", question)
        clock -= 1
        assertEquals(Verdict.EXPIRED, t.redeemAnswer(n, "jotter", 1, "fp-abc123"))
    }

    @Test
    fun `a nonce names one session, one question and only the options shown`() {
        val t = table()
        assertEquals(Verdict.MISMATCH, t.redeemAnswer(t.issue("k", question), "other", 1, "fp-abc123"))
        assertEquals(Verdict.MISMATCH, t.redeemAnswer(t.issue("k", question), "jotter", 1, "fp-different"))
        // Option 4 existed on the pane but had no button on the toast.
        assertEquals(Verdict.MISMATCH, t.redeemAnswer(t.issue("k", question), "jotter", 4, "fp-abc123"))
    }

    @Test
    fun `a mismatched link spends the nonce, so it cannot be retried into a fit`() {
        val t = table()
        val n = t.issue("sess:jotter", question)
        assertEquals(Verdict.MISMATCH, t.redeemAnswer(n, "jotter", 9, "fp-abc123"))
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer(n, "jotter", 1, "fp-abc123"))
    }

    @Test
    fun `one nonce per toast, so a three-button toast answers once`() {
        val t = table()
        val n = t.issue("sess:jotter", question)
        assertEquals(Verdict.ACCEPTED, t.redeemAnswer(n, "jotter", 1, "fp-abc123"))
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer(n, "jotter", 2, "fp-abc123"))
    }

    @Test
    fun `an undo nonce cannot answer and an answer nonce cannot undo`() {
        val t = table()
        assertEquals(Verdict.MISMATCH, t.redeemAnswer(t.issue("ladder:a", Grant.Undo("a")), "a", 1, "fp"))
        assertEquals(Verdict.MISMATCH, t.redeemUndo(t.issue("sess:a", Grant.Answer("a", "fp", setOf(1))), "a"))
        val undo = t.issue("ladder:a", Grant.Undo("a"))
        assertEquals(Verdict.MISMATCH, t.redeemUndo(t.issue("ladder:b", Grant.Undo("b")), "a"))
        assertEquals(Verdict.ACCEPTED, t.redeemUndo(undo, "a"))
        assertEquals(Verdict.UNKNOWN, t.redeemUndo(undo, "a"))
    }

    @Test
    fun `a newer toast under the same key takes its nonce over`() {
        val t = table()
        val first = t.issue("sess:jotter", question)
        val second = t.issue("sess:jotter", question.copy(fingerprint = "fp-next"))
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer(first, "jotter", 1, "fp-abc123"))
        assertEquals(Verdict.ACCEPTED, t.redeemAnswer(second, "jotter", 1, "fp-next"))
    }

    @Test
    fun `taking a toast down revokes its nonce and nobody else's`() {
        val t = table()
        val mine = t.issue("sess:a", Grant.Answer("a", "fp", setOf(1)))
        val other = t.issue("sess:b", Grant.Answer("b", "fp", setOf(1)))
        t.revoke("sess:a")
        assertEquals(Verdict.UNKNOWN, t.redeemAnswer(mine, "a", 1, "fp"))
        assertEquals(Verdict.ACCEPTED, t.redeemAnswer(other, "b", 1, "fp"))
    }

    @Test
    fun `the table is bounded and gives up the oldest first`() {
        val t = table(capacity = 2)
        val a = t.issue("sess:a", Grant.Undo("a"))
        val b = t.issue("sess:b", Grant.Undo("b"))
        val c = t.issue("sess:c", Grant.Undo("c"))
        assertEquals(Verdict.UNKNOWN, t.redeemUndo(a, "a"))
        assertEquals(Verdict.ACCEPTED, t.redeemUndo(b, "b"))
        assertEquals(Verdict.ACCEPTED, t.redeemUndo(c, "c"))
    }

    @Test
    fun `nonces are long, url-safe and do not repeat`() {
        assertTrue(ActivationNonces.NONCE_BYTES * 8 >= 128, "at least 128 bits")
        val t = table()
        val seen = HashSet<String>()
        repeat(500) { i ->
            val n = t.issue("k$i", Grant.Undo("s"))
            assertTrue(n.matches(Regex("[A-Za-z0-9_-]{43}")), "not a url-safe 256-bit nonce: <$n>")
            assertTrue(seen.add(n), "a nonce repeated")
        }
    }
}
