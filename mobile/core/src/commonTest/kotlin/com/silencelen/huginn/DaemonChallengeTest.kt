package com.silencelen.huginn

import com.silencelen.huginn.data.DaemonChallenge
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The token-proving handshake's arithmetic (decision 58).
 *
 * ⚠ THIS FILE IS WHY A HAND-WRITTEN HASH IS ACCEPTABLE. `:core` has no
 * multiplatform MAC available to it, so SHA-256 and HMAC are written out in
 * `DaemonChallenge` — and a hash that is *almost* right produces plausible hex
 * that never matches the daemon, which would read to every client as "that
 * address is not huginn" and quietly take auto-switch out of service. So the
 * implementation is pinned to the PUBLISHED vectors: FIPS 180-4 for SHA-256 and
 * RFC 4231 for HMAC-SHA-256, plus one vector computed against the daemon's own
 * `crypto.createHmac('sha256', TOKEN).update(nonce, 'utf8')`.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class DaemonChallengeTest {

    // ------------------------------------------------------------ SHA-256

    private fun sha(s: String): String = hex(DaemonChallenge.sha256(s.encodeToByteArray()))

    private fun hex(b: ByteArray): String =
        b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `sha256 matches the published vectors`() {
        // FIPS 180-4 B.1/B.2 plus the empty string, which exercises the padding
        // path with nothing to pad around.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha("abc"),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun `sha256 pads correctly around every block boundary`() {
        // 55, 56 and 64 bytes are the three interesting lengths: the last that
        // fits its length word in the same block, the first that does not, and an
        // exact block. An off-by-one in the pad computation shows up here and
        // nowhere in the short vectors above.
        assertEquals(
            "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
            sha("a".repeat(55)),
        )
        assertEquals(
            "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
            sha("a".repeat(56)),
        )
        assertEquals(
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            sha("a".repeat(64)),
        )
    }

    // --------------------------------------------------------------- HMAC

    @Test
    fun `hmac-sha256 matches RFC 4231`() {
        // Case 1: a 20-byte key of 0x0b over "Hi There".
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hex(DaemonChallenge.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())),
        )
        // Case 2: the key is shorter than the block and is ASCII — the shape
        // every real call here takes.
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hex(DaemonChallenge.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())),
        )
        // Case 6: a key LONGER than the 64-byte block, which must be hashed
        // first. The daemon's token is 64 characters, so this boundary is one
        // byte away from production and has to be right.
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hex(
                DaemonChallenge.hmacSha256(
                    ByteArray(131) { 0xaa.toByte() },
                    "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
                ),
            ),
        )
    }

    /**
     * The exact thing the daemon computes, for a token and a nonce of the real
     * shape. Produced by `crypto.createHmac('sha256', TOKEN).update(NONCE,
     * 'utf8').digest('hex')` — the body of `challengeProof` in `huginn-appd.js`.
     */
    @Test
    fun `the proof matches the daemon's own answer`() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val nonce = "a3f1c09e7b25d48610fe3c9b0d7a5e42"
        assertEquals(
            "e7cacb532240e9262dd93131b4a5f50b90d32c2f151ed33087e0fba11b103505",
            DaemonChallenge.proof(token, nonce),
            "if this fails, compare against `node -e` on the daemon before touching the hash",
        )
    }

    // ------------------------------------------------------------- matching

    @Test
    fun `a matching proof is accepted and a wrong token's is not`() {
        val token = "a".repeat(64)
        val nonce = DaemonChallenge.newNonce(Random(7))
        assertTrue(DaemonChallenge.matches(token, nonce, DaemonChallenge.proof(token, nonce)))
        // ⚠ THE WHOLE POINT. An impostor holding a different token produces a
        // perfectly well-formed 64-hex answer; it just is not this one.
        val impostor = DaemonChallenge.proof("b".repeat(64), nonce)
        assertEquals(64, impostor.length, "the fake's answer is the right SHAPE")
        assertFalse(DaemonChallenge.matches(token, nonce, impostor), "and must still be refused")
    }

    @Test
    fun `a proof for a different nonce never matches`() {
        // The replay case: a proof recorded off somebody else's exchange.
        val token = "c".repeat(64)
        val captured = DaemonChallenge.proof(token, "1111111111111111")
        assertFalse(DaemonChallenge.matches(token, "2222222222222222", captured))
    }

    @Test
    fun `nothing at all is never a match`() {
        val token = "d".repeat(64)
        val nonce = "abcdef0123456789"
        // A 404 has no body; an older daemon's error JSON has no proof field;
        // both arrive here as null or blank, and both must read as NOT proven
        // rather than as a pass.
        assertFalse(DaemonChallenge.matches(token, nonce, null))
        assertFalse(DaemonChallenge.matches(token, nonce, ""))
        assertFalse(DaemonChallenge.matches(token, nonce, "   "))
        assertFalse(DaemonChallenge.matches("", nonce, DaemonChallenge.proof("", nonce)),
            "no token means nothing can be proved, not that everything is")
    }

    @Test
    fun `case on the answer does not decide identity`() {
        val token = "e".repeat(64)
        val nonce = "0f0f0f0f0f0f0f0f"
        assertTrue(DaemonChallenge.matches(token, nonce, DaemonChallenge.proof(token, nonce).uppercase()))
    }

    @Test
    fun `a nonce is the shape the daemon accepts, and a fresh one every time`() {
        // The daemon's own regex is /^[0-9a-fA-F]{16,64}$/ — a nonce outside it
        // is a 400, which the client would read as "not huginn".
        val seen = HashSet<String>()
        repeat(200) {
            val n = DaemonChallenge.newNonce()
            assertEquals(DaemonChallenge.NONCE_CHARS, n.length, n)
            assertTrue(n.all { c -> c in "0123456789abcdef" }, n)
            seen += n
        }
        assertTrue(seen.size > 190, "a nonce that repeats is a proof that can be replayed: ${seen.size}")
    }
}
