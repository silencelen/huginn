package com.silencelen.huginn

import com.silencelen.huginn.data.DaemonTls
import com.silencelen.huginn.data.DaemonTransport
import com.silencelen.huginn.data.TlsProbeReply
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.URLProtocol

/**
 * A [DaemonTransport] over a mock engine, with the TLS rules SIMULATED rather
 * than skipped.
 *
 * ⚠ KEPT IDENTICAL in `:core` commonTest, `:app` test and `:app-desktop` test —
 * a test source set cannot see another module's. Edit all three or none.
 *
 * The "server" presents [serverFp] on every connection. The pinned channel
 * refuses an https request whose [serverFp] is not in the client's pin set (as
 * the real trust manager does), so a test can rotate the certificate by
 * changing it; the probe answers the bound challenge correctly for [token]
 * unless [probeAnswer] says otherwise.
 */
class MockTransport(
    private val token: String,
    var serverFp: String = "ab".repeat(32),
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) : DaemonTransport {

    /** Every probe URL, in order — so a test can count proofs. */
    val probes = mutableListOf<String>()

    /** Overrides the probe's answer; default is a correct bound proof over [serverFp]. */
    var probeAnswer: (suspend (url: String) -> TlsProbeReply)? = null

    /** The client's pin lookup, as handed to [engine]. */
    var pins: () -> Collection<String> = { emptyList() }
        private set

    override fun engine(pins: () -> Collection<String>): HttpClientEngine {
        this.pins = pins
        return MockEngine { request ->
            if (request.url.protocol == URLProtocol.HTTPS && serverFp !in pins()) throw Unpinned(serverFp)
            handler(this, request)
        }
    }

    override suspend fun probe(url: String, timeoutMs: Long): TlsProbeReply {
        probes += url
        probeAnswer?.let { return it(url) }
        return provenReply(url, token, serverFp)
    }

    override fun unpinnedLeaf(failure: Throwable): String? {
        var t: Throwable? = failure
        while (t != null) {
            if (t is Unpinned) return t.fp
            t = t.cause
        }
        return null
    }

    /** The mock pinned channel's refusal. */
    class Unpinned(val fp: String) : Exception("server certificate $fp is not pinned for this daemon")

    companion object {
        val TEST_FP: String = "ab".repeat(32)

        fun nonceOf(url: String): String = url.substringAfter("nonce=", "").substringBefore('&')

        /** What a 3.11 daemon behind [fp] answers, for [token]. */
        fun provenReply(url: String, token: String, fp: String, version: String = "3.11.0"): TlsProbeReply {
            val nonce = nonceOf(url)
            val body = """{"proof":"legacy","version":"$version",""" +
                """"tls":{"v":1,"fp":"$fp","proof":"${DaemonTls.proof(token, nonce, fp)}"}}"""
            return TlsProbeReply(leafFp = fp, status = 200, body = body, appdHeader = version)
        }
    }
}

/** Shorthand: `transport = mockTransport { request -> … }`. */
fun mockTransport(
    token: String = "t",
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): MockTransport = MockTransport(token = token, handler = handler)
