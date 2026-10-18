package com.silencelen.huginn

import com.silencelen.huginn.data.DaemonTls
import com.silencelen.huginn.data.DaemonTlsException
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.HuginnSettings
import com.silencelen.huginn.data.PinBook
import com.silencelen.huginn.data.RouteGuard
import com.silencelen.huginn.data.TlsPins
import com.silencelen.huginn.data.TlsProbeReply
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client half of docs/dev/protocol-tls.md, held to the contract line by line.
 *
 * ⚠ THE VECTOR IS THE CONTRACT. The same value is asserted by the daemon's
 * `server/appd/test/tls.test.js` and by every other client, so a client that
 * drifts by one byte of the HMAC input fails HERE rather than as "someone may be
 * intercepting the connection" on every real daemon.
 */
class DaemonTlsTest {

    private val token = "a".repeat(64)
    private val nonce = "00112233445566778899aabbccddeeff"
    private val zeroFp = "0".repeat(64)

    // ------------------------------------------------------------ the proof

    @Test
    fun `the bound proof matches the contract's test vector`() {
        assertEquals(
            "5b9228510040befc78c4c4314d23a489b93f403581f0fc85e291e013160240f2",
            DaemonTls.proof(token, nonce, zeroFp),
        )
        assertTrue(DaemonTls.matches(token, nonce, zeroFp, "5b9228510040befc78c4c4314d23a489b93f403581f0fc85e291e013160240f2"))
        assertTrue(DaemonTls.matches(token, nonce, zeroFp, "5B9228510040BEFC78C4C4314D23A489B93F403581F0FC85E291E013160240F2"))
    }

    /**
     * ⚠⚠ THE WHOLE POINT. An interceptor shows its own certificate M; the real
     * daemon's proof is over the REAL fingerprint, so relaying it does not
     * prove the channel the client is actually on.
     */
    @Test
    fun `a proof made for a different certificate is the interception case`() {
        val real = "11".repeat(32)
        val mitm = "22".repeat(32)
        val relayed = DaemonTls.proof(token, nonce, real)
        assertFalse(DaemonTls.matches(token, nonce, mitm, relayed))
        val body = """{"version":"3.11.0","tls":{"v":1,"fp":"$real","proof":"$relayed"}}"""
        val v = DaemonTls.judge(token, nonce, seenFp = mitm, status = 200, body = body, appdHeader = "3.11.0")
        assertEquals(DaemonTls.Verdict.Unproven(echoedOurFp = false), v)
    }

    /**
     * Rule 1: hash what OUR handshake saw. An interceptor that also lies about
     * the fingerprint in the body gets nowhere — and the verdict still refuses.
     */
    @Test
    fun `the fingerprint in the body is never trusted`() {
        val mitm = "22".repeat(32)
        val lie = """{"version":"3.11.0","tls":{"v":1,"fp":"$mitm","proof":"${DaemonTls.proof("not-the-token", nonce, mitm)}"}}"""
        val v = DaemonTls.judge(token, nonce, seenFp = mitm, status = 200, body = lie, appdHeader = null)
        assertIs<DaemonTls.Verdict.Unproven>(v)
        assertTrue(v.echoedOurFp, "it echoed our fp — which is also what a wrong token looks like")
    }

    /** Rule 2: the legacy proof proves the daemon, not the channel. */
    @Test
    fun `a legacy-proof-only answer over https is not proof`() {
        val legacy = com.silencelen.huginn.data.DaemonChallenge.proof(token, nonce)
        val v = DaemonTls.judge(token, nonce, zeroFp, 200, """{"proof":"$legacy","version":"3.11.0"}""", "3.11.0")
        assertEquals(DaemonTls.Verdict.Unproven(echoedOurFp = false), v, "a 3.11 daemon always sends tls; one that does not is not proved")
        // And the legacy value placed where the bound one belongs is no better.
        val swapped = """{"version":"3.11.0","tls":{"v":1,"fp":"$zeroFp","proof":"$legacy"}}"""
        assertIs<DaemonTls.Verdict.Unproven>(DaemonTls.judge(token, nonce, zeroFp, 200, swapped, null))
    }

    @Test
    fun `a daemon older than 3_11 is named as such, not as an interceptor`() {
        val legacy = com.silencelen.huginn.data.DaemonChallenge.proof(token, nonce)
        assertEquals(
            DaemonTls.Verdict.TooOld("3.10.3"),
            DaemonTls.judge(token, nonce, zeroFp, 200, """{"proof":"$legacy","version":"3.10.3"}""", "3.10.3"),
        )
        // The single port answered the TLS hello in plain HTTP: the transport's
        // unauthenticated ping, read only to name it.
        assertEquals(
            DaemonTls.Verdict.TooOld("3.9.0"),
            DaemonTls.judge(token, nonce, null, 401, """{"error":"unauthorized"}""", "3.9.0", notTls = true),
        )
        assertEquals(
            DaemonTls.Verdict.NotHuginn,
            DaemonTls.judge(token, nonce, null, 200, "<html>router</html>", null, notTls = true),
        )
        assertTrue(DaemonTls.isOlderThanTls("3.10.9"))
        assertTrue(DaemonTls.isOlderThanTls("2.40.0"))
        assertFalse(DaemonTls.isOlderThanTls("3.11.0"))
        assertFalse(DaemonTls.isOlderThanTls("4.0.0"))
        assertFalse(DaemonTls.isOlderThanTls(null), "unknown is never blamed on the daemon's version")
        assertFalse(DaemonTls.isOlderThanTls("garbage"))
    }

    @Test
    fun `the right proof over the certificate we saw is proven`() {
        val fp = "ab".repeat(32)
        val body = """{"version":"3.11.0","tls":{"v":1,"fp":"$fp","proof":"${DaemonTls.proof(token, nonce, fp)}"}}"""
        assertEquals(DaemonTls.Verdict.Proven(fp), DaemonTls.judge(token, nonce, fp.uppercase(), 200, body, null))
        // A future proof version this client does not speak is not guessed at.
        val v2 = body.replace("\"v\":1", "\"v\":2")
        assertIs<DaemonTls.Verdict.Unproven>(DaemonTls.judge(token, nonce, fp, 200, v2, null))
        assertFalse(DaemonTls.matches(token, nonce, fp, ""), "blank is never a match")
        assertFalse(DaemonTls.matches("", nonce, fp, DaemonTls.proof("", nonce, fp)), "no token proves nothing")
    }

    // ------------------------------------------------------------ the URL rule

    @Test
    fun `plain http survives only to loopback`() {
        assertEquals("http://127.0.0.1:8787", DaemonTls.secureUrl("http://127.0.0.1:8787"))
        assertEquals("http://127.8.9.10:8787", DaemonTls.secureUrl("http://127.8.9.10:8787"))
        assertEquals("http://localhost:8787", DaemonTls.secureUrl("http://localhost:8787"))
        assertEquals("http://[::1]:8787", DaemonTls.secureUrl("http://[::1]:8787"))
        assertEquals("http://[0:0:0:0:0:0:0:1]:8787", DaemonTls.secureUrl("http://[0:0:0:0:0:0:0:1]:8787"))

        assertEquals("https://192.168.7.117:8787", DaemonTls.secureUrl("http://192.168.7.117:8787"))
        assertEquals("https://10.0.0.5:8787", DaemonTls.secureUrl("10.0.0.5:8787"), "a bare authority is http, then upgraded")
        assertEquals("https://[fd00::117]:8787", DaemonTls.secureUrl("http://[fd00::117]:8787"))
        assertEquals("https://huginn.lan:8787", DaemonTls.secureUrl("HTTP://huginn.lan:8787"))
        assertEquals("https://app.localhost:8787", DaemonTls.secureUrl("http://app.localhost:8787"), "*.localhost is not guaranteed loopback")
        assertEquals("https://128.0.0.1:8787", DaemonTls.secureUrl("http://128.0.0.1:8787"))
        assertEquals("https://[::2]:8787", DaemonTls.secureUrl("http://[::2]:8787"))

        assertEquals("https://huginn.example.net:8787", DaemonTls.secureUrl("https://huginn.example.net:8787"))
        assertEquals("https://127.0.0.1:8787", DaemonTls.secureUrl("https://127.0.0.1:8787"), "https to loopback is left alone")
    }

    /** Rule 6: the STORED form is the upgraded one, so the reader sees https. */
    @Test
    fun `routes are stored upgraded`() {
        assertEquals("https://192.168.2.117:8787", RouteGuard.normalize("http://192.168.2.117:8787/"))
        assertEquals("https://[fd00::1]:8787", RouteGuard.normalize("http://[FD00::1]:8787"))
        assertEquals("http://127.0.0.1:8787", RouteGuard.normalize("127.0.0.1:8787"))
        assertEquals("http://[::1]:8787", RouteGuard.normalize("http://[::1]:8787"))
        assertEquals("https://example.com", RouteGuard.normalize("http://example.com"))
        assertTrue(RouteGuard.isAllowed("http://example.com"), "upgraded, not refused: the proof guards the token now")
        assertFalse(RouteGuard.isAllowed("http://user:pw@127.0.0.1"), "the shape rules still hold")
        assertFalse(RouteGuard.isAllowed("http://10.0.0.1:8787/path"))
    }

    // ------------------------------------------------------------ the pin set

    @Test
    fun `pins keep four, newest first, and a re-proved pin moves up`() {
        var p = TlsPins()
        for (i in 1..5) p = p.withPin(token, i.toString().repeat(64))
        assertEquals(listOf("5", "4", "3", "2").map { it.repeat(64) }, p.pinsFor(token))
        p = p.withPin(token, "3".repeat(64))
        assertEquals(listOf("3", "5", "4", "2").map { it.repeat(64) }, p.pinsFor(token))
        assertEquals(DaemonTls.MAX_PINS, p.pins.size)
        p = p.withPin(token, "AB".repeat(32))
        assertEquals("ab".repeat(32), p.pinsFor(token).first(), "stored lowercase, as handshakes are hashed")
    }

    /** Rule 7: pins belong to the token. */
    @Test
    fun `a different token sees no pins and starts a fresh set`() {
        val p = TlsPins().withPin(token, zeroFp)
        assertEquals(listOf(zeroFp), p.pinsFor(token))
        assertEquals(emptyList(), p.pinsFor("b".repeat(64)))
        assertEquals(emptyList(), p.pinsFor(""))
        val q = p.withPin("b".repeat(64), "11".repeat(32))
        assertEquals(listOf("11".repeat(32)), q.pinsFor("b".repeat(64)))
        assertEquals(emptyList(), q.pinsFor(token), "the old daemon's pins are gone, not merged")
        assertFalse(p.encode().contains(token), "the owner is a tag, never the token")
    }

    @Test
    fun `pins survive an encode and junk reads as none`() {
        val p = TlsPins().withPin(token, zeroFp).withPin(token, "11".repeat(32))
        assertEquals(p, TlsPins.decode(p.encode()))
        assertEquals(TlsPins(), TlsPins.decode("{not json"))
        assertEquals(TlsPins(), TlsPins.decode(""))
        assertEquals("", TlsPins().encode())
    }

    // ------------------------------------------------------- through the client

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        transport: MockTransport,
        pins: PinBook = PinBook(),
        tokenNow: () -> String = { token },
        base: String = "http://192.168.7.117:8787",
    ) = HuginnClient(baseUrlProvider = { base }, tokenProvider = tokenNow, transport = transport, pins = pins)

    private fun daemon() = MockTransport(token = token) { request ->
        seen += request
        respond("""{"ok":true,"version":"3.11.0"}""", HttpStatusCode.OK)
    }

    @Test
    fun `the first request proves, pins, and only then sends the bearer`() = runTest {
        val t = daemon()
        val pins = PinBook()
        client(t, pins).ping()
        assertEquals(1, t.probes.size, "one proof")
        assertTrue(t.probes.single().startsWith("https://192.168.7.117:8787/v1/challenge?nonce="), t.probes.single())
        assertEquals(listOf(MockTransport.TEST_FP), pins.pinsFor(token))
        assertEquals("https://192.168.7.117:8787/v1/ping", seen.single().url.toString())
        assertEquals("Bearer $token", seen.single().headers[HttpHeaders.Authorization])

        client(t, pins).ping()
        assertEquals(1, t.probes.size, "a pinned daemon is not re-proved on every call")
    }

    @Test
    fun `an address that cannot prove itself never sees the bearer`() = runTest {
        val t = daemon()
        t.probeAnswer = { url ->
            // A relaying interceptor: the real daemon's proof, over the real
            // fingerprint, delivered on a connection showing the interceptor's.
            val real = MockTransport.provenReply(url, token, "11".repeat(32))
            TlsProbeReply("22".repeat(32), real.status, real.body, real.appdHeader)
        }
        val pins = PinBook()
        val e = assertFailsWith<DaemonTlsException> { client(t, pins).ping() }
        assertEquals(DaemonTlsException.Kind.INTERCEPTED, e.kind)
        assertEquals(DaemonTls.INTERCEPTED, e.message)
        assertTrue(seen.isEmpty(), "not one bearer-carrying request was made: $seen")
        assertEquals(emptyList(), pins.pinsFor(token))
    }

    @Test
    fun `a legacy-only daemon over https is refused, not downgraded`() = runTest {
        val t = daemon()
        t.probeAnswer = { url ->
            val legacy = com.silencelen.huginn.data.DaemonChallenge.proof(token, MockTransport.nonceOf(url))
            TlsProbeReply(MockTransport.TEST_FP, 200, """{"proof":"$legacy","version":"3.10.3"}""", "3.10.3")
        }
        val e = assertFailsWith<DaemonTlsException> { client(t).ping() }
        assertEquals(DaemonTlsException.Kind.TOO_OLD, e.kind)
        assertEquals(DaemonTls.TOO_OLD, e.message)
        assertTrue(seen.isEmpty(), "and nothing was sent to it, over https or otherwise")
    }

    /** Rule 5: a rotated certificate is re-proved and added, then the call goes through. */
    @Test
    fun `a rotated certificate is re-proved, pinned beside the old one, and retried`() = runTest {
        val t = daemon()
        val pins = PinBook()
        val c = client(t, pins)
        c.ping()
        val old = MockTransport.TEST_FP
        t.serverFp = "cd".repeat(32)
        c.ping()
        assertEquals(2, t.probes.size, "the new certificate was proved before it was trusted")
        assertEquals(listOf("cd".repeat(32), old), pins.pinsFor(token))
        assertEquals(2, seen.size, "and the refused call was retried once it was")
    }

    @Test
    fun `a rotated certificate that cannot prove itself is a hard stop`() = runTest {
        val t = daemon()
        val pins = PinBook()
        val c = client(t, pins)
        c.ping()
        t.serverFp = "ee".repeat(32)
        t.probeAnswer = { url -> MockTransport.provenReply(url, "somebody-else's-token", "ee".repeat(32)) }
        val e = assertFailsWith<DaemonTlsException> { c.ping() }
        assertEquals(DaemonTlsException.Kind.INTERCEPTED, e.kind)
        assertTrue(e.message!!.startsWith(DaemonTls.INTERCEPTED))
        assertEquals(listOf(MockTransport.TEST_FP), pins.pinsFor(token), "nothing new was pinned")
        assertEquals(1, seen.size, "the second call never reached the far end")
    }

    /**
     * A selective interceptor: the probe connection reaches the real daemon,
     * the bearer-carrying one is diverted. The proof pins the real certificate,
     * the pinned channel refuses the other one, and after ONE re-proof the
     * answer is the contract's sentence — never a pin for what was refused.
     */
    @Test
    fun `different certificates on the probe and the pinned channel stop after one retry`() = runTest {
        val t = daemon()
        t.serverFp = "99".repeat(32)
        t.probeAnswer = { url -> MockTransport.provenReply(url, token, MockTransport.TEST_FP) }
        val pins = PinBook()
        val e = assertFailsWith<DaemonTlsException> { client(t, pins).ping() }
        assertEquals(DaemonTlsException.Kind.INTERCEPTED, e.kind)
        assertEquals(listOf(MockTransport.TEST_FP), pins.pinsFor(token), "the diverted certificate is never pinned")
        assertTrue(seen.isEmpty())
        assertEquals(2, t.probes.size, "one proof, one re-proof, no third")
    }

    @Test
    fun `a new token proves again rather than trusting the old daemon's pin`() = runTest {
        val pins = PinBook()
        var tok = token
        val t = daemon()
        client(t, pins, tokenNow = { tok }).ping()
        tok = "b".repeat(64)
        val t2 = MockTransport(token = tok) { request -> seen += request; respond("{}", HttpStatusCode.OK) }
        client(t2, pins, tokenNow = { tok }).ping()
        assertEquals(1, t2.probes.size, "re-pairing re-proves")
        assertEquals(listOf(MockTransport.TEST_FP), pins.pinsFor(tok))
    }

    @Test
    fun `loopback stays plain and is never proved`() = runTest {
        val t = daemon()
        client(t, base = "http://127.0.0.1:8787").ping()
        assertTrue(t.probes.isEmpty())
        assertEquals("http://127.0.0.1:8787/v1/ping", seen.single().url.toString())
    }

    @Test
    fun `a blank token proves nothing and sends nothing`() = runTest {
        val t = daemon()
        val e = assertFailsWith<HuginnClient.HuginnException> { client(t, tokenNow = { "" }).ping() }
        assertEquals(401, e.code)
        assertTrue(t.probes.isEmpty() && seen.isEmpty())
    }

    @Test
    fun `the route probe over https uses the bound proof and pins on success`() = runTest {
        val t = daemon()
        val pins = PinBook()
        val probe = client(t, pins).probeDaemon("http://10.0.0.1:8787")
        assertTrue(probe.proven)
        assertTrue(probe.secure)
        assertEquals("https://10.0.0.1:8787", probe.address.let { RouteGuard.normalize(it) })
        assertEquals(listOf(MockTransport.TEST_FP), pins.pinsFor(token))
        assertTrue(seen.isEmpty(), "the probe never touched the pinned channel")
    }

    @Test
    fun `the route probe names an interceptor and an old daemon differently`() = runTest {
        val t = daemon()
        t.probeAnswer = { url -> MockTransport.provenReply(url, "wrong", "33".repeat(32)) }
        val mitm = client(t).probeDaemon("https://10.0.0.1:8787")
        assertFalse(mitm.proven)
        assertTrue(mitm.intercepted)
        assertTrue(HuginnClient.probeWords(mitm).contains("could not prove it is your huginn daemon"), HuginnClient.probeWords(mitm))

        t.probeAnswer = { TlsProbeReply(null, 401, """{"error":"unauthorized"}""", "3.10.0", notTls = true) }
        val old = client(t).probeDaemon("https://10.0.0.1:8787")
        assertFalse(old.proven)
        assertTrue(old.tooOld)
        assertEquals("3.10.0", old.version)
        assertTrue(HuginnClient.probeWords(old).contains("3.11"), HuginnClient.probeWords(old))

        // Before a token exists (the setup flow's address step) a real 3.11
        // daemon is "answers, cannot prove" — not an interception.
        t.probeAnswer = null
        val fresh = client(t, tokenNow = { "" }).probeDaemon("https://10.0.0.1:8787")
        assertFalse(fresh.proven)
        assertTrue(fresh.answered)
        assertFalse(fresh.intercepted)
    }

    // ----------------------------------------------------- persistence

    private class PinStore(initial: String = "") : HuginnSettings by Unused {
        val raw = MutableStateFlow(initial)
        override val tlsPins: Flow<String> get() = raw
        override suspend fun setTlsPins(value: String) { raw.value = value }
    }

    @Test
    fun `pins are read from and written through to settings`() = runTest {
        val persisted = TlsPins().withPin(token, MockTransport.TEST_FP).encode()
        val store = PinStore(persisted)
        val pins = PinBook().apply { attach(store) }
        val t = daemon()
        client(t, pins).ping()
        assertTrue(t.probes.isEmpty(), "a pin from the last run spares the proof")

        t.serverFp = "cd".repeat(32)
        client(t, pins).ping()
        assertEquals(listOf("cd".repeat(32), MockTransport.TEST_FP), TlsPins.decode(store.raw.value).pinsFor(token))
    }
}

/**
 * Throws on everything — [DaemonTlsTest.PinStore] overrides the two members a
 * [PinBook] touches and nothing else may be called.
 */
private object Unused : HuginnSettings {
    private fun no(): Nothing = error("not used by PinBook")
    override val baseUrl: Flow<String> get() = no()
    override val token: Flow<String> get() = no()
    override val routeBook: Flow<com.silencelen.huginn.data.RouteBook> get() = no()
    override suspend fun setToken(value: String) = no()
    override suspend fun setRouteBook(value: com.silencelen.huginn.data.RouteBook) = no()
    override suspend fun clientId(): String = no()
    override val fontScale: Flow<Float> get() = no()
    override suspend fun setFontScale(value: Float) = no()
    override val notifyEnabled: Flow<Boolean> get() = no()
    override suspend fun setNotifyEnabled(value: Boolean) = no()
    override val watchEnabled: Flow<Boolean> get() = no()
    override suspend fun setWatchEnabled(value: Boolean) = no()
    override val watchSeeded: Flow<Boolean> get() = no()
    override suspend fun setWatchSeeded(value: Boolean) = no()
    override val notifiedSessions: Flow<Set<String>> get() = no()
    override suspend fun setNotifiedSessions(value: Set<String>) = no()
    override val runningChats: Flow<Set<String>> get() = no()
    override suspend fun setRunningChats(value: Set<String>) = no()
    override val chatRuns: Flow<Map<String, Long>> get() = no()
    override suspend fun setChatRuns(value: Map<String, Long>) = no()
    override val drafts: Flow<Map<String, String>> get() = no()
    override suspend fun setDrafts(value: Map<String, String>) = no()
    override val lastContactAt: Flow<Long> get() = no()
    override val lastAlarmAt: Flow<Long> get() = no()
    override val lastWatchError: Flow<String> get() = no()
    override val lastWatchErrorAt: Flow<Long> get() = no()
    override suspend fun noteContact(atMs: Long) = no()
    override suspend fun noteAlarm(atMs: Long) = no()
    override suspend fun noteWatchError(message: String, atMs: Long) = no()
}
