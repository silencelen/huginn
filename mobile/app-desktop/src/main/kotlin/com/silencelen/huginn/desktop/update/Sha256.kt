package com.silencelen.huginn.desktop.update

import java.io.File
import java.security.MessageDigest

/**
 * The check that decides whether a downloaded installer is allowed to be run.
 *
 * Streamed in 64 KB blocks rather than `readBytes()`: an installer is ~90 MB and
 * this runs inside the client's own heap, which is sized for a UI.
 */
object Sha256 {

    fun ofBytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun ofFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    /**
     * Case-insensitive and length-checked. A comparison that accepted a short or
     * empty `expected` would pass for a file that hashes to anything — which is
     * exactly the state a half-written manifest leaves you in.
     */
    fun matches(file: File, expected: String): Boolean {
        val want = expected.trim().lowercase()
        if (want.length != 64 || !want.all { it in "0123456789abcdef" }) return false
        return ofFile(file).equals(want, ignoreCase = true)
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
