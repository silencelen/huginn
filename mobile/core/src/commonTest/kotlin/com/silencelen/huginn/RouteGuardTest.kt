package com.silencelen.huginn

import com.silencelen.huginn.data.RouteGuard
import com.silencelen.huginn.data.RouteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE GUARD THAT PROTECTS A ROOT-EQUIVALENT BEARER — and, until this file, the
 * one rule in this product with ZERO tests.
 *
 * The desktop's version was four literal hosts checked in a setter. The phone
 * had nothing at all: `setBaseUrl` and `selectRoute` took whatever was typed.
 * One bearer token follows the base URL on every request, so that field was a
 * one-field path to handing the daemon token — root on huginn — to any address
 * somebody could be talked into typing.
 *
 * Each case below is a CLASS of address, not an example, because the rule is
 * about shape: https anywhere, http only where the packet cannot leave a trusted
 * network.
 */
class RouteGuardTest {

    // -------------------------------------------------------------- allowed

    @Test
    fun `plain http is allowed exactly where the packet cannot leave a trusted network`() {
        val allowed = mapOf(
            "http://127.0.0.1:8787" to RouteKind.LOCAL,
            "http://127.1.2.3:8787" to RouteKind.LOCAL,
            "http://localhost:8787" to RouteKind.LOCAL,
            "http://[::1]:8787" to RouteKind.LOCAL,
            "http://10.0.0.9:8787" to RouteKind.LAN,
            "http://192.168.2.117:8787" to RouteKind.LAN,
            "http://172.16.0.4:8787" to RouteKind.LAN,
            "http://172.31.255.254:8787" to RouteKind.LAN,
            "http://100.97.198.90:8787" to RouteKind.TAILNET,
            "http://100.64.0.1:8787" to RouteKind.TAILNET,
            "http://100.127.255.255:8787" to RouteKind.TAILNET,
            "http://huginn.tailnet-1234.ts.net:8787" to RouteKind.TAILNET,
            "http://[fd00::1]:8787" to RouteKind.MESH,
            "http://[fc00::abcd]:8787" to RouteKind.MESH,
        )
        for ((url, kind) in allowed) {
            assertTrue(RouteGuard.isAllowed(url), "should be reachable over plain http: $url")
            assertEquals(kind, RouteGuard.kindOf(url), "badge for $url")
        }
    }

    @Test
    fun `https is allowed anywhere, because TLS is what makes the host stop mattering`() {
        for (url in listOf("https://huginn.example.com", "https://1.2.3.4:8787", "https://example.org:443")) {
            assertTrue(RouteGuard.isAllowed(url), url)
            assertEquals(RouteKind.CUSTOM, RouteGuard.kindOf(url), "a public name is still badged Custom")
        }
    }

    // -------------------------------------------------------------- refused

    @Test
    fun `plain http to anything public is refused`() {
        val refused = listOf(
            "http://example.com:8787",           // an arbitrary DNS name
            "http://1.2.3.4:8787",               // a public literal
            "http://8.8.8.8",                    // ditto, no port
            "http://172.32.0.1:8787",            // just outside RFC1918
            "http://100.128.0.1:8787",           // just outside the CGNAT block
            "http://[2001:db8::1]:8787",         // global unicast v6
            "http://huginn.ts.net.evil.com",     // the MagicDNS suffix as a prefix
        )
        for (url in refused) {
            assertFalse(RouteGuard.isAllowed(url), "should be refused: $url")
            assertEquals(RouteKind.CUSTOM, RouteGuard.kindOf(url))
        }
    }

    /**
     * ⚠ THE BARE-HOSTNAME WALK-PAST. `HuginnClient` prepends `http://` to an
     * address typed without a scheme, which is convenient and stays. A guard that
     * ran BEFORE that upgrade could therefore be walked past by typing
     * `example.com` — so the guard assumes http for a bare address, and
     * [RouteGuard.normalize] stamps the scheme on before anything is stored.
     */
    @Test
    fun `a bare address is judged as http and stored with the scheme spelled out`() {
        assertFalse(RouteGuard.isAllowed("example.com:8787"), "bare public name must not slip through")
        assertTrue(RouteGuard.isAllowed("192.168.2.117:8787"))
        assertEquals("http://192.168.2.117:8787", RouteGuard.normalize("192.168.2.117:8787"))
        assertEquals("http://192.168.2.117:8787", RouteGuard.require(" 192.168.2.117:8787/ "))
    }

    @Test
    fun `a path is refused, because a base URL is an authority and not a prefix`() {
        assertFalse(RouteGuard.isAllowed("http://127.0.0.1:8787/v1"))
        assertFalse(RouteGuard.isAllowed("http://127.0.0.1:8787/anything/at/all"))
        // A lone trailing slash is not a path, it is punctuation.
        assertTrue(RouteGuard.isAllowed("http://127.0.0.1:8787/"))
    }

    @Test
    fun `a userinfo segment is refused, because a credential does not belong in this field`() {
        assertFalse(RouteGuard.isAllowed("http://user:pass@127.0.0.1:8787"))
        assertFalse(RouteGuard.isAllowed("https://user@example.com"))
    }

    @Test
    fun `nonsense is refused rather than guessed at`() {
        for (url in listOf(
            "",
            "   ",
            "ftp://127.0.0.1",
            "http://",
            "http://127.0.0.1:0",
            "http://127.0.0.1:99999",
            "http://127.0.0.1:ssh",
            "http://fd00::1:8787",   // unbracketed v6 — host and port are ambiguous
            "http://127.0.0.1 :8787",
        )) {
            assertFalse(RouteGuard.isAllowed(url), "should be refused: '$url'")
        }
    }

    // ------------------------------------------------------------- refusal

    /**
     * The sentence is shown to the reader verbatim, on both shells. It is the
     * daemon's own words and is asserted so a reword has to be deliberate.
     */
    @Test
    fun `the refusal says what it says`() {
        assertEquals(
            "refusing that server address — huginn only talks to its own daemon",
            RouteGuard.REFUSED,
        )
        val e = assertFailsWith<IllegalArgumentException> { RouteGuard.require("http://example.com") }
        assertEquals(RouteGuard.REFUSED, e.message)
    }

    @Test
    fun `the authority is the authority and never more`() {
        assertEquals("100.97.198.90:8787", RouteGuard.authorityOf("http://100.97.198.90:8787/"))
        assertEquals("localhost", RouteGuard.authorityOf("http://localhost"))
        assertEquals("[::1]:8787", RouteGuard.authorityOf("http://[::1]:8787"))
        assertEquals("", RouteGuard.authorityOf("http://user@127.0.0.1"), "a refused address has no authority to give")
    }
}
