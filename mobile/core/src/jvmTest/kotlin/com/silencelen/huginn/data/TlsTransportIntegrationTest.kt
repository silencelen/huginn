package com.silencelen.huginn.data

import com.silencelen.huginn.withPlaintextLoopback
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * docs/dev/protocol-tls.md against REAL sockets: the production [OkHttpDaemonTransport] (the
 * pinning trust manager, the trust-anything probe, the plain-text fallback that
 * only names an old daemon) talking to a JDK `HttpsServer` with a self-signed
 * certificate — the same shape appd 3.11 serves.
 *
 * ⚠ JDK ONLY, ON PURPOSE. The certificates come from `keytool`, which ships with
 * every JDK that can run this suite, and the server is `com.sun.net.httpserver`;
 * no new test dependency, and nothing here talks to anything but 127.0.0.1.
 *
 * Most tests point the client at `https://127.0.0.1:<port>`; the loopback ones
 * use `http://127.0.0.1:<port>`, which is dialled over TLS on
 * the same port exactly like any other address.
 */
class TlsTransportIntegrationTest {

    private val token = "k".repeat(64)
    private val servers = mutableListOf<HttpServer>()
    private val authSeenOnChallenge = CopyOnWriteArrayList<String>()
    private val bearersReceived = CopyOnWriteArrayList<String>()

    @AfterTest
    fun stop() = servers.forEach { it.stop(0) }

    private class Cert(val ctx: SSLContext, val fp: String)

    private fun cert(name: String): Cert {
        val dir = Files.createTempDirectory("huginn-tls-test").toFile()
        val ks = File(dir, "$name.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").takeIf { it.exists() }?.path ?: "keytool"
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=huginn-appd", "-validity", "3650", "-storetype", "PKCS12",
            "-keystore", ks.path, "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "keytool failed: $out" }
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        val leaf = store.getCertificate(name) as X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        dir.deleteRecursively()
        return Cert(ctx, MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) })
    }

    private fun HttpExchange.send(status: Int, body: String, appd: String? = "3.11.0") {
        appd?.let { responseHeaders.add(HuginnClient.APPD_HEADER, it) }
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /**
     * A 3.11-shaped daemon serving [cert]. [proveOver] is the fingerprint its
     * challenge proof is computed over — its own, unless a test is playing a
     * relaying interceptor.
     */
    private fun daemon(cert: Cert, proveOver: String = cert.fp, proofToken: String = token): Int {
        val s = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.httpsConfigurator = HttpsConfigurator(cert.ctx)
        s.createContext("/v1/challenge") { ex ->
            ex.requestHeaders.getFirst("Authorization")?.let { authSeenOnChallenge += it }
            val nonce = ex.requestURI.rawQuery.substringAfter("nonce=").substringBefore('&')
            val proof = DaemonTls.proof(proofToken, nonce, proveOver)
            ex.send(200, """{"proof":"legacy","version":"3.11.0","tls":{"v":1,"fp":"$proveOver","proof":"$proof"}}""")
        }
        s.createContext("/v1/ping") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            if (auth != null) bearersReceived += auth
            if (auth == "Bearer $token") ex.send(200, """{"ok":true,"version":"3.11.0"}""")
            else ex.send(401, """{"error":"unauthorized"}""")
        }
        s.start()
        servers += s
        return s.address.port
    }

    private fun client(port: Int, pins: PinBook) = HuginnClient(
        baseUrlProvider = { "https://127.0.0.1:$port" },
        tokenProvider = { token },
        transport = OkHttpDaemonTransport(),
        pins = pins,
    )

    @Test
    fun `a real self-signed daemon is proved, pinned, and then sent the bearer`() = runBlocking {
        val c = cert("one")
        val port = daemon(c)
        val pins = PinBook()
        val ping = client(port, pins).ping()
        assertTrue(ping.ok)
        assertEquals(listOf(c.fp), pins.pinsFor(token), "the fingerprint OUR handshake saw is the one pinned")
        assertTrue(authSeenOnChallenge.isEmpty(), "the probe never carried the bearer: $authSeenOnChallenge")
        assertEquals(listOf("Bearer $token"), bearersReceived.toList())
    }

    @Test
    fun `a relaying interceptor is refused before the bearer leaves`() = runBlocking {
        val mitm = cert("mitm")
        val real = cert("real")
        // The interceptor shows its own certificate and passes along the real
        // daemon's proof, which is over the real certificate's fingerprint.
        val port = daemon(mitm, proveOver = real.fp)
        val pins = PinBook()
        val e = assertFailsWith<DaemonTlsException> { client(port, pins).ping() }
        assertEquals(DaemonTlsException.Kind.INTERCEPTED, e.kind)
        assertTrue(bearersReceived.isEmpty(), "the bearer never reached it: $bearersReceived")
        assertTrue(pins.pinsFor(token).isEmpty())
    }

    @Test
    fun `a rotated certificate is re-proved over a real handshake refusal`() = runBlocking {
        val old = cert("old")
        val new = cert("new")
        val pins = PinBook()
        client(daemon(old), pins).ping()
        // Same daemon, new certificate (pins are per token, not per address).
        val ping = client(daemon(new), pins).ping()
        assertTrue(ping.ok)
        assertEquals(listOf(new.fp, old.fp), pins.pinsFor(token))
    }

    @Test
    fun `a pinned channel refuses a certificate the proof did not cover`() = runBlocking {
        val real = cert("real")
        val other = cert("other")
        val pins = PinBook()
        client(daemon(real), pins).ping()
        // A stranger holding no token, at an address this client is told to use.
        val port = daemon(other, proofToken = "not-the-token")
        val e = assertFailsWith<DaemonTlsException> { client(port, pins).ping() }
        assertEquals(DaemonTlsException.Kind.INTERCEPTED, e.kind)
        assertEquals(listOf("Bearer $token"), bearersReceived.toList(), "only the real daemon ever got it")
        assertEquals(listOf(real.fp), pins.pinsFor(token))
    }

    /**
     * Rule 8: a pre-3.11 daemon's single port speaks plain HTTP to a TLS hello.
     * The real transport notices, asks in plain text with no bearer, and hands
     * back what answered marked `notTls` — which is rule 8's "too old" only
     * for an address that has proved nothing and is not this machine.
     */
    @Test
    fun `a port without TLS is reported as such, and is never sent the bearer`() = runBlocking {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            ex.requestHeaders.getFirst("Authorization")?.let { bearersReceived += it }
            ex.send(401, """{"error":"unauthorized"}""", appd = "3.10.3")
        }
        s.start()
        servers += s
        val nonce = DaemonChallenge.newNonce()
        val reply = OkHttpDaemonTransport().probe("https://127.0.0.1:${s.address.port}/v1/challenge?nonce=$nonce", 1_000)
        assertTrue(reply.notTls)
        assertEquals(null, reply.leafFp)
        assertEquals(401, reply.status)
        assertEquals("3.10.3", reply.appdHeader)
        assertEquals(
            DaemonTls.Verdict.TooOld("3.10.3"),
            DaemonTls.judge(token, nonce, reply.leafFp, reply.status, reply.body, reply.appdHeader, reply.notTls),
            "off this machine, for a token that has proved nothing, rule 8",
        )
        // This port IS this machine, where a 3.11+ daemon answers in TLS: the
        // interception sentence, and what else it can be here.
        val e = assertFailsWith<DaemonTlsException> { client(s.address.port, PinBook()).ping() }
        assertEquals(DaemonTlsException.Kind.INTERCEPTED, e.kind)
        assertEquals(DaemonTls.INTERCEPTED + DaemonTls.LOOPBACK_HINT, e.message)
        assertTrue(bearersReceived.isEmpty(), "not over https, not over a downgrade: $bearersReceived")
    }

    // ------------------------------------------------ loopback

    /** A daemon on this machine, or a port-forward to one: TLS on the same port, proved first. */
    @Test
    fun `a loopback http address is dialled over TLS, proved, and only then sent the bearer`() = runBlocking {
        withPlaintextLoopback(on = false) {
            val c = cert("loopback")
            val port = daemon(c)
            val pins = PinBook()
            val client = HuginnClient(
                baseUrlProvider = { "http://127.0.0.1:$port" }, tokenProvider = { token },
                transport = OkHttpDaemonTransport(), pins = pins,
            )
            assertTrue(client.ping().ok)
            assertEquals(listOf(c.fp), pins.pinsFor(token))
            assertTrue(authSeenOnChallenge.isEmpty(), "the probe never carried the bearer: $authSeenOnChallenge")
            assertEquals(listOf("Bearer $token"), bearersReceived.toList())
        }
    }

    /**
     * What binds a loopback port while the forward or the daemon is down: any
     * other app on the phone, any other account on a desktop. Plain http,
     * answering like a daemon, recording what it is sent. It used to be sent the
     * bearer on the very first call.
     */
    @Test
    fun `a plain-http squatter on a loopback port is never sent the bearer`() = runBlocking {
        withPlaintextLoopback(on = false) {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.createContext("/") { ex ->
                ex.requestHeaders.getFirst("Authorization")?.let { bearersReceived += it }
                ex.send(200, """{"ok":true,"version":"3.13.0"}""", appd = "3.13.0")
            }
            s.start()
            servers += s
            val client = HuginnClient(
                baseUrlProvider = { "http://127.0.0.1:${s.address.port}" }, tokenProvider = { token },
                transport = OkHttpDaemonTransport(), pins = PinBook(),
            )
            val e = assertFailsWith<DaemonTlsException> { client.ping() }
            // Whatever version it prints: plain http where the daemon answers TLS.
            assertEquals(DaemonTlsException.Kind.INTERCEPTED, e.kind)
            assertEquals(DaemonTls.INTERCEPTED + DaemonTls.LOOPBACK_HINT, e.message)
            assertTrue(bearersReceived.isEmpty(), "the squatter was handed: $bearersReceived")
        }
    }

    /**
     * The relay the scan describes: a squatter that forwards the challenge to
     * the daemon's real address and hands back the legacy proof, which the
     * daemon gives anybody. It used to make the route read as proved.
     */
    @Test
    fun `a relayed legacy proof on a loopback http address adopts nothing`() = runBlocking {
        withPlaintextLoopback(on = false) {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.createContext("/") { ex ->
                ex.requestHeaders.getFirst("Authorization")?.let { bearersReceived += it }
                val nonce = ex.requestURI.rawQuery.orEmpty().substringAfter("nonce=", "").substringBefore('&')
                ex.send(200, """{"proof":"${DaemonChallenge.proof(token, nonce)}","version":"3.13.0"}""", appd = "3.13.0")
            }
            s.start()
            servers += s
            val pins = PinBook()
            val probe = client(s.address.port, pins).probeDaemon("http://127.0.0.1:${s.address.port}")
            assertTrue(!probe.proven, "a relayed legacy proof read as proved: $probe")
            assertTrue(pins.pinsFor(token).isEmpty())
            assertTrue(bearersReceived.isEmpty(), "and nothing carried the bearer: $bearersReceived")
        }
    }

    @Test
    fun `the route probe proves a real daemon without the pinned channel`() = runBlocking {
        val c = cert("probe")
        val port = daemon(c)
        val pins = PinBook()
        val probe = client(port, pins).probeDaemon("https://127.0.0.1:$port")
        assertTrue(probe.proven, probe.toString())
        assertEquals(listOf(c.fp), pins.pinsFor(token))
        assertTrue(bearersReceived.isEmpty() && authSeenOnChallenge.isEmpty())
    }
}
