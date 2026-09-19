package com.silencelen.huginn.data

import kotlin.random.Random

/**
 * ─── PROVING AN ADDRESS IS THE DAEMON, BEFORE IT IS SENT THE TOKEN ─────────
 *
 * ⚠⚠ D-1 (round-2 review, 2026-09-19; decision 58). `HuginnClient.provesDaemon`
 * returned true for ANY non-blank `X-Huginn-Appd` header, and auto-switch then
 * adopted that address and began sending it the real bearer: 129 authenticated
 * requests to a thirty-line Python fake in about two minutes on the desktop
 * walker's bench. A header anybody can print is a fingerprint, not proof, and the
 * comment on `provesDaemon` had already said so — the durable fix needed a daemon
 * change, and appd 3.6.0 is that change.
 *
 * The handshake, in three lines:
 *
 *   1. the client mints a fresh random nonce (32 hex characters, [newNonce]);
 *   2. it asks `GET /v1/challenge?nonce=…` **with no Authorization header**;
 *   3. it adopts the address only when the answer equals
 *      `HMAC-SHA256(key = token, message = nonce)` computed HERE.
 *
 * An impostor that does not hold the token cannot produce that value, so the
 * token never leaves this client until the far end has already proved it has one.
 *
 * ⚠ THE NONCE IS OURS, WHICH IS WHAT MAKES A RECORDED PROOF USELESS. A proof
 * captured off somebody else's exchange answers somebody else's nonce; replaying
 * it here fails. That is the only reason this can be asked unauthenticated.
 *
 * ⚠ WHY A HAND-WRITTEN SHA-256 RATHER THAN A LIBRARY. `:core` depends on
 * kotlinx-serialization, coroutines, Compose runtime/ui and ktor-client-core, and
 * none of them exposes a multiplatform MAC. The alternatives were a new
 * third-party crypto dependency in a PUBLIC repo for one 32-byte hash, or a third
 * `expect`/`actual` pair (the project has two, both in `data/Platform.kt`, and
 * `docs/ADDING-A-FEATURE.md` says that number should stay small). Ninety lines of
 * FIPS 180-4 pinned by the RFC 4231 vectors in `DaemonChallengeTest` is the
 * smaller liability: it is a pure function of its inputs, it has no key
 * management, and its correctness is decidable by a test rather than by trust.
 */
object DaemonChallenge {

    /** The route, asked without a bearer. */
    const val PATH: String = "/v1/challenge"

    /**
     * The nonce length in hex characters. The daemon accepts 16–64; 32 is 128
     * bits of randomness, which is far past any birthday concern for a value
     * used once and never stored.
     */
    const val NONCE_CHARS: Int = 32

    /**
     * What a route that answers but cannot prove itself is CALLED, on both
     * shells.
     *
     * ⚠ NOT "unreachable" and not "failed". An older daemon 404s this route and
     * is a perfectly good daemon; so does a port-forward to one. The distinction
     * a reader has to be able to make is between "huginn is there" (proved) and
     * "something is there that has not shown it holds the token" — and only the
     * first may be adopted without a person saying so.
     */
    const val NOT_PROVEN: String = "answers, cannot prove it is huginn"

    /** A fresh nonce. Lowercase hex, [NONCE_CHARS] long, never reused. */
    fun newNonce(random: Random = Random.Default): String {
        val sb = StringBuilder(NONCE_CHARS)
        repeat(NONCE_CHARS) { sb.append(HEX[random.nextInt(16)]) }
        return sb.toString()
    }

    /**
     * The proof the daemon should have produced for [nonce].
     *
     * ⚠ THE NONCE IS HASHED AS THE STRING THAT WAS SENT, not as the bytes it
     * decodes to. `challengeProof` on the daemon does
     * `createHmac('sha256', TOKEN).update(String(nonce), 'utf8')` — the query
     * parameter verbatim — so a client that helpfully hex-decoded it first would
     * never match, and would read that as "this is not huginn".
     */
    fun proof(token: String, nonce: String): String =
        hmacSha256(token.encodeToByteArray(), nonce.encodeToByteArray()).toHex()

    /**
     * Whether [answer] is the proof for [nonce] under [token].
     *
     * Case-insensitive on the answer (the daemon sends lowercase; a proxy that
     * re-cased it is not an impostor) and false for anything blank, so a 404's
     * empty body can never read as a match. Compared in constant time out of
     * habit rather than need: the comparand is public, but a timing-variable
     * equality on a MAC is the exact shape that is wrong somewhere else later.
     */
    fun matches(token: String, nonce: String, answer: String?): Boolean {
        val got = answer?.trim()?.lowercase() ?: return false
        if (got.isEmpty() || token.isEmpty() || nonce.isEmpty()) return false
        val want = proof(token, nonce)
        if (got.length != want.length) return false
        var diff = 0
        for (i in want.indices) diff = diff or (got[i].code xor want[i].code)
        return diff == 0
    }

    // ----------------------------------------------------------- the hash

    private const val HEX: String = "0123456789abcdef"

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** RFC 2104 over [sha256], block size 64 bytes. */
    internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val block = ByteArray(BLOCK)
        // A key longer than the block is hashed first; a shorter one is
        // zero-padded, which `ByteArray(BLOCK)` already is.
        val k = if (key.size > BLOCK) sha256(key) else key
        k.copyInto(block, endIndex = k.size)
        val inner = ByteArray(BLOCK + message.size)
        for (i in 0 until BLOCK) inner[i] = (block[i].toInt() xor 0x36).toByte()
        message.copyInto(inner, BLOCK)
        val innerHash = sha256(inner)
        val outer = ByteArray(BLOCK + innerHash.size)
        for (i in 0 until BLOCK) outer[i] = (block[i].toInt() xor 0x5c).toByte()
        innerHash.copyInto(outer, BLOCK)
        return sha256(outer)
    }

    private const val BLOCK: Int = 64

    /**
     * The round constants, written as Longs and narrowed: half of them exceed
     * `Int.MAX_VALUE`, and a hand-written two's-complement table is a transcription
     * error waiting to be found by nothing at all.
     */
    private val K: IntArray = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    /** FIPS 180-4 SHA-256. Pure; allocates one padded copy of the message. */
    internal fun sha256(message: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )
        // One 0x80 byte, zeros, then the length in BITS as a big-endian 64-bit
        // value, padded so the whole thing is a multiple of the block.
        val pad = (BLOCK - (message.size + 9) % BLOCK) % BLOCK
        val total = message.size + 1 + pad + 8
        val buf = ByteArray(total)
        message.copyInto(buf)
        buf[message.size] = 0x80.toByte()
        val bits = message.size.toLong() * 8
        for (i in 0 until 8) buf[total - 1 - i] = ((bits ushr (8 * i)) and 0xff).toByte()

        val w = IntArray(64)
        var off = 0
        while (off < total) {
            for (i in 0 until 16) {
                val p = off + i * 4
                w[i] = ((buf[p].toInt() and 0xff) shl 24) or
                    ((buf[p + 1].toInt() and 0xff) shl 16) or
                    ((buf[p + 2].toInt() and 0xff) shl 8) or
                    (buf[p + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val x = w[i - 15]
                val y = w[i - 2]
                val s0 = rotr(x, 7) xor rotr(x, 18) xor (x ushr 3)
                val s1 = rotr(y, 17) xor rotr(y, 19) xor (y ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            off += BLOCK
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }
}
