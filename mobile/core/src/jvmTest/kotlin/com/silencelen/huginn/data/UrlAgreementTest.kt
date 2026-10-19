package com.silencelen.huginn.data

import io.ktor.http.Url
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Three parsers read a route: [RouteGuard] (what may be stored), Ktor (what
 * every request is built from, and what the plain-http re-check reads) and
 * OkHttp (the engine, which re-parses Ktor's URL and dials it). Whether a
 * request may go out in plain http is decided by [DaemonTls.secureUrl], so the
 * one property that matters is that all of them name the same host and port —
 * and that when they cannot, the answer is https rather than a guess.
 *
 * JVM only because OkHttp is: it is the parser that actually dials.
 */
class UrlAgreementTest {

    /** Addresses the guard accepts, written the ways people and older builds wrote them. */
    private val accepted = listOf(
        "192.168.1.20:8787",
        "http://192.168.1.20:8787",
        "HTTP://192.168.1.20:8787/",
        "https://huginn.example.net:8787",
        "https://huginn.example.net",
        "http://[fd00::1]:8787",
        "http://[FD00::1]:8787",
        "http://[0:0:0:0:0:0:0:1]:8787",
        "http://127.0.0.1:8787",
        "http://127.8.9.10:8787",
        "http://127.000.000.001:8787",
        "http://[::1]:8787",
        "localhost:8787",
        "http://LOCALHOST:8787",
        "huginn.taildbeef.ts.net",
        "http://10.0.0.1:08787",
        "https://100.101.102.103",
    )

    /** Strings no stored route can be, which a raw base or a typed candidate still could. */
    private val hostile = listOf(
        "http://evil.example#@127.0.0.1:8787",
        "http://evil.example?@127.0.0.1:8787",
        "http://evil.example\\@127.0.0.1:8787",
        "http://evil.example/@127.0.0.1:8787",
        "http://127.0.0.1#@evil.example:8787",
        "http://127.0.0.1\\@evil.example:8787",
        "http://[::1]evil.example:8787",
        "http://[::1]:8787@evil.example",
        "http://user@127.0.0.1:8787",
        "http://127.0.0.1:+8787",
        "http://127.0.0.1:８７８７",
        "http://127.0.0.1 .evil.example:8787",
        "http://127.0.0.1%2f.evil.example:8787",
    )

    /**
     * Accepted by the guard (as a custom address), but spellings some resolver
     * may read as loopback. Never treated as loopback here, so never kept plain.
     */
    private val oddButAccepted = listOf(
        "http://127.1:8787",
        "http://0x7f.0.0.1:8787",
        "http://2130706433:8787",
    )

    @Test
    fun `every address the guard accepts names one host and one port to all three parsers`() {
        for (raw in accepted) {
            assertTrue(RouteGuard.isAllowed(raw), raw)
            val guard = Url(RouteGuard.normalize(raw))
            for (plaintext in listOf(false, true)) {
                // The stored form and the raw string a background caller may
                // still hand the client: both are what gets dialled.
                for (dialled in listOf(DaemonTls.secureUrl(RouteGuard.normalize(raw), plaintext), DaemonTls.secureUrl(raw, plaintext))) {
                    val ktor = Url(dialled)
                    val okhttp = assertNotNull(ktor.toString().toHttpUrlOrNull(), "OkHttp cannot read Ktor's $ktor")
                    assertTrue(sameHost(guard.host, ktor.host), "$raw: guard ${guard.host}, Ktor ${ktor.host}")
                    assertTrue(sameHost(ktor.host, okhttp.host), "$raw: Ktor ${ktor.host}, OkHttp ${okhttp.host}")
                    assertEquals(guard.port, ktor.port, raw)
                    assertEquals(ktor.port, okhttp.port, raw)
                }
            }
        }
    }

    /**
     * Strings a raw base or a typed candidate may still carry. Whatever any one
     * parser makes of them, plain http survives only if the host OkHttp will
     * dial is loopback; and none of them is ever a stored route.
     */
    @Test
    fun `a string the guard refuses stays plain only if what is dialled is loopback`() {
        for (raw in hostile + oddButAccepted) {
            for (plaintext in listOf(false, true)) {
                val dialled = DaemonTls.secureUrl(raw, plaintext)
                if (!dialled.startsWith("http://")) continue
                val ktor = runCatching { Url(dialled) }.getOrNull()
                val okhttp = ktor?.toString()?.toHttpUrlOrNull()
                if (okhttp == null) continue // nothing is dialled at all
                assertTrue(DaemonTls.isLoopbackHost(okhttp.host), "$raw stayed plain and dials ${okhttp.host}")
                assertTrue(sameHost(ktor.host, okhttp.host), "$raw: Ktor ${ktor.host}, OkHttp ${okhttp.host}")
            }
        }
        for (raw in hostile) assertFalse(RouteGuard.isAllowed(raw), raw)
        for (raw in oddButAccepted) {
            assertTrue(RouteGuard.isAllowed(raw), raw)
            assertTrue(DaemonTls.secureUrl(raw, plaintext = true).startsWith("https://"), raw)
        }
    }

    /**
     * The two the guard used to let through: a sign or another script's digits
     * in the port, which Ktor reads as 8787 and OkHttp refuses.
     */
    @Test
    fun `the guard reads a port the way the engine does`() {
        assertFalse(RouteGuard.isAllowed("http://127.0.0.1:+8787"))
        assertFalse(RouteGuard.isAllowed("http://127.0.0.1:８７８７"))
        assertEquals(null, "http://127.0.0.1:８７８７/".toHttpUrlOrNull(), "the engine refuses it")
        assertTrue(RouteGuard.isAllowed("http://127.0.0.1:08787"))
    }

    /** Case and the written-out IPv6 forms aside, the same address. Never a DNS lookup: literals only. */
    private fun sameHost(a: String, b: String): Boolean {
        val x = a.removePrefix("[").removeSuffix("]").lowercase()
        val y = b.removePrefix("[").removeSuffix("]").lowercase()
        if (x == y) return true
        if (':' in x && ':' in y) {
            return InetAddress.getByName(x).address.contentEquals(InetAddress.getByName(y).address)
        }
        return false
    }
}
