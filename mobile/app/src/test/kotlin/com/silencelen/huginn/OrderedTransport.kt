package com.silencelen.huginn

import com.silencelen.huginn.data.DaemonTransport
import com.silencelen.huginn.data.TlsProbeReply
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.CoroutineDispatcher

/**
 * A daemon transport whose mock engine runs on the TEST dispatcher, so the ORDER
 * requests reach the wire in is a fact a test can assert rather than a race
 * between engine threads.
 *
 * [MockTransport] answers on Ktor's own IO threads, which is right for what it
 * tests (the TLS rules) and wrong for asking "did the strip wait for the
 * history?": two requests released together reach it in either order. This one
 * answers inside `runCurrent()`. The TLS proof is the same bound proof
 * [MockTransport] gives, under [token]; the pin is not re-checked here because
 * the TLS rules are not what these tests are about.
 *
 * Every request is recorded in [wire] as path and query, in order.
 */
class OrderedTransport(
    private val dispatcher: CoroutineDispatcher,
    private val token: String,
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) : DaemonTransport {

    /** What reached the wire, in order: path and query. */
    val wire = mutableListOf<String>()

    /** Every proof asked for, in order. */
    val probes = mutableListOf<String>()

    override fun engine(pins: () -> Collection<String>): HttpClientEngine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = this@OrderedTransport.dispatcher
                addHandler { request ->
                    wire += request.url.encodedPath +
                        (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                    handler(request)
                }
            },
        )

    override suspend fun probe(url: String, timeoutMs: Long): TlsProbeReply {
        probes += url
        return MockTransport.provenReply(url, token, MockTransport.TEST_FP)
    }

    override fun unpinnedLeaf(failure: Throwable): String? = null
}
