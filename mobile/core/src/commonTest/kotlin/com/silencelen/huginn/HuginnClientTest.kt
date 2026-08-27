package com.silencelen.huginn

import com.silencelen.huginn.data.ByteStream
import com.silencelen.huginn.data.HuginnClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the client that are neither SSE nor a wire model: how a request is
 * addressed, which timeout tier it rides, what a non-2xx turns into, and how a
 * large upload leaves the device.
 *
 * The timeout assertions are the reason this file exists. The four tiers are a
 * contract — each number is a production failure that went unnoticed until it had
 * one — and until the client moved to Ktor they lived in four OkHttpClient
 * instances that no unit test could see. Ktor attaches them to the request as a
 * capability, so a mock engine can read them back and they can finally be pinned.
 */
class HuginnClientTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        base: String = "http://appd.test",
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HuginnClient(
        baseUrlProvider = { base },
        tokenProvider = { "test-token" },
        engine = MockEngine { request -> seen += request; respond(request) },
    )

    private fun ok(json: String) = client { respond(json, HttpStatusCode.OK) }

    private val timeouts: HttpTimeoutConfig?
        get() = seen.last().getCapabilityOrNull(HttpTimeoutCapability)

    // ------------------------------------------------------ addressing

    @Test
    fun `a base URL without a scheme is assumed to be plain http`() = runTest {
        client(base = "192.168.2.117:8787") { respond("""{"ok":true}""") }.ping()
        assertEquals("http://192.168.2.117:8787/v1/ping", seen.single().url.toString())
    }

    @Test
    fun `a trailing slash on the base URL does not become a double slash`() = runTest {
        client(base = "http://appd.test/") { respond("""{"ok":true}""") }.ping()
        assertEquals("http://appd.test/v1/ping", seen.single().url.toString())
    }

    // ------------------------------------------------- soft end + uploads

    @Test
    fun `softEndSession posts to the soft-end route and decodes the report`() = runTest {
        val r = ok("""{"ok":true,"phrase":"Finish up.","auto":true,"queued":false}""")
            .softEndSession("jtyper")
        assertEquals("http://appd.test/v1/sessions/jtyper/soft-end", seen.single().url.toString())
        assertEquals("POST", seen.single().method.value)
        assertTrue(r.ok)
        assertTrue(r.auto)
        assertFalse(r.queued)
        assertEquals("Finish up.", r.phrase)
    }

    @Test
    fun `compactSession posts to the compact route and decodes queued`() = runTest {
        val r = ok("""{"ok":true,"sent":"/compact","queued":true}""")
            .compactSession("jtyper")
        assertEquals("http://appd.test/v1/sessions/jtyper/compact", seen.single().url.toString())
        assertEquals("POST", seen.single().method.value)
        assertTrue(r.ok)
        assertTrue(r.queued)
        assertEquals("/compact", r.sent)
    }

    @Test
    fun `uploadBytes fetches by name, with auth, returning the raw bytes`() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4)
        val got = client { respond(payload) }.uploadBytes("up-1-ab.jpg")
        assertEquals("http://appd.test/v1/uploads/up-1-ab.jpg", seen.single().url.toString())
        assertEquals("Bearer test-token", seen.single().headers[HttpHeaders.Authorization])
        assertTrue(payload.contentEquals(got))
    }

    @Test
    fun `uploadBytes surfaces a 404 as an exception (pruned or deleted file)`() = runTest {
        val c = client { respondError(HttpStatusCode.NotFound, """{"error":"not found"}""") }
        assertFailsWith<Exception> { c.uploadBytes("up-gone.jpg") }
    }

    @Test
    fun `the client id and notify headers are sent only when they say something`() = runTest {
        // The plain UI client must NOT claim to be a background listener: the host
        // holds Telegram back for a phone that says it is listening.
        HuginnClient(
            baseUrlProvider = { "http://appd.test" },
            tokenProvider = { "t" },
            engine = MockEngine { request -> seen += request; respond("""{"ok":true}""") },
        ).ping()
        assertNull(seen.last().headers["X-Huginn-Client"])
        assertNull(seen.last().headers["X-Huginn-Notify"])

        HuginnClient(
            baseUrlProvider = { "http://appd.test" },
            tokenProvider = { "t" },
            clientIdProvider = { "install-1" },
            canNotifyProvider = { false },
            engine = MockEngine { request -> seen += request; respond("""{"ok":true}""") },
        ).ping()
        assertEquals("install-1", seen.last().headers["X-Huginn-Client"])
        assertEquals("0", seen.last().headers["X-Huginn-Notify"], "a phone that cannot show a notification must say so")
    }

    // -------------------------------------------------- timeout tiers

    @Test
    fun `an ordinary call rides the 8s connect and 30s read tier`() = runTest {
        ok("""{"ok":true}""").ping()
        // These are read off the request as the ENGINE would see it — the plugin
        // has already folded the client-level defaults in — so this pins the
        // numbers that actually reach the socket, not the ones in the config block.
        assertEquals(HuginnClient.CONNECT_TIMEOUT_MS, timeouts?.connectTimeoutMillis)
        assertEquals(HuginnClient.READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
        assertNull(timeouts?.requestTimeoutMillis, "only the long poll caps a whole call")
    }

    @Test
    fun `every tier keeps the same 8s connect timeout`() = runTest {
        // The tiers differ in how long silence is tolerated once connected. How
        // long it may take to connect is one answer for all of them, and a tier
        // that quietly lost it would stall route resolution behind a dead path.
        val c = ok("""{"hash":"h"}""")
        c.watch(knownHash = "h", waitMs = 120_000)
        assertEquals(HuginnClient.CONNECT_TIMEOUT_MS, timeouts?.connectTimeoutMillis, "poll tier")
        client { respond("event: done\ndata: {}\n\n", HttpStatusCode.OK) }.streamChat("a").collect { }
        assertEquals(HuginnClient.CONNECT_TIMEOUT_MS, timeouts?.connectTimeoutMillis, "stream tier")
        client { respond("event: bye\ndata: {}\n\n", HttpStatusCode.OK) }.watchStream(null).collect { }
        assertEquals(HuginnClient.CONNECT_TIMEOUT_MS, timeouts?.connectTimeoutMillis, "watch tier")
    }

    @Test
    fun `a long poll rides the 150s read and 180s call tier`() = runTest {
        ok("""{"hash":"h"}""").watch(knownHash = "h", waitMs = 120_000)
        assertEquals(HuginnClient.POLL_READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
        assertEquals(HuginnClient.POLL_CALL_TIMEOUT_MS, timeouts?.requestTimeoutMillis)
    }

    @Test
    fun `the same watch without a wait window is an ordinary call`() = runTest {
        // The distinction is the whole reason there are two: the server only holds
        // the connection open when asked to.
        ok("""{"hash":"h"}""").watch(knownHash = "h", waitMs = 0)
        assertEquals(HuginnClient.READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
    }

    @Test
    fun `a screen long poll rides the poll tier, a plain screen read does not`() = runTest {
        val c = ok("""{"hash":"h","lines":[]}""")
        c.screen("jtyper", waitMs = 60_000)
        assertEquals(HuginnClient.POLL_READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
        c.screen("jtyper")
        assertEquals(HuginnClient.READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
    }

    @Test
    fun `suggestions ride the poll tier because generation can take seconds`() = runTest {
        ok("""{"suggestions":[]}""").sessionSuggestions("jtyper")
        assertEquals(HuginnClient.POLL_READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
    }

    @Test
    fun `a chat stream rides the 60s tier, never the infinite one`() = runTest {
        client {
            respond("event: done\ndata: {}\n\n", HttpStatusCode.OK)
        }.streamChat("abc").collect { }
        assertEquals(HuginnClient.STREAM_READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
        assertNull(timeouts?.requestTimeoutMillis, "a Claude turn may legitimately outlast any call cap")
    }

    @Test
    fun `the watch stream rides its own 60s tier`() = runTest {
        client {
            respond("event: bye\ndata: {}\n\n", HttpStatusCode.OK)
        }.watchStream(null).collect { }
        assertEquals(HuginnClient.WATCH_READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
    }

    @Test
    fun `a route probe gives up faster than a real call`() = runTest {
        val answered = client { respond("", HttpStatusCode.Unauthorized) }
            .probe("http://192.168.2.117:8787")
        // Any reply counts: a 401 still proves the daemon is there.
        assertTrue(answered)
        assertEquals(HuginnClient.PROBE_TIMEOUT_MS, timeouts?.connectTimeoutMillis)
        assertEquals(HuginnClient.PROBE_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
        assertEquals("HEAD", seen.last().method.value)
        assertNull(seen.last().headers[HttpHeaders.Authorization], "probing must not depend on the token being right")
    }

    @Test
    fun `a probe that throws is a route that is not there`() = runTest {
        val answered = client { throw kotlinx.io.IOException("no route to host") }
            .probe("http://192.168.2.117:8787")
        assertFalse(answered)
    }

    // ------------------------------------------------------- failures

    @Test
    fun `a non-2xx carries the servers own words`() = runTest {
        val e = assertFailsWith<HuginnClient.HuginnException> {
            client { respondError(HttpStatusCode.NotFound, """{"error":"no such session"}""") }.status()
        }
        assertEquals(404, e.code)
        assertEquals("no such session", e.message)
    }

    @Test
    fun `a non-2xx with an unreadable body still reports its code`() = runTest {
        val e = assertFailsWith<HuginnClient.HuginnException> {
            client { respondError(HttpStatusCode.BadGateway, "<html>nginx</html>") }.status()
        }
        assertEquals(502, e.code)
        assertEquals("HTTP 502", e.message)
    }

    // -------------------------------------------------------- uploads

    @Test
    fun `an upload streams its source and declares the length it was given`() = runTest {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        var body: ByteArray? = null
        var declared: Long? = null

        client { request ->
            val content = request.body as OutgoingContent.WriteChannelContent
            declared = content.contentLength
            val channel = ByteChannel()
            CoroutineScope(Dispatchers.Default).launch {
                content.writeTo(channel)
                channel.flushAndClose()
            }
            body = channel.readRemaining().readByteArray()
            respond("""{"path":"/tmp/x","readable":true}""", HttpStatusCode.OK)
        }.uploadStream("application/octet-stream", "backup.tar", ChunkedSource(payload))

        assertEquals(payload.size.toLong(), declared, "the provider's size must reach the wire as Content-Length")
        assertTrue(payload.contentEquals(body), "every byte of the source must arrive, in order")
        assertEquals("/v1/uploads", seen.last().url.encodedPath)
        assertEquals("name=backup.tar", seen.last().url.encodedQuery)
    }

    @Test
    fun `an upload whose size the provider would not give is sent chunked`() = runTest {
        var declared: Long? = 0
        client { request ->
            declared = (request.body as OutgoingContent).contentLength
            respond("""{"path":"/tmp/x"}""", HttpStatusCode.OK)
        }.uploadStream("application/octet-stream", null, ChunkedSource(ByteArray(10), length = -1))
        // null, not -1: Ktor reads that as "no Content-Length", which is what makes
        // the request chunked — the same thing an OkHttp body returning -1 did.
        assertNull(declared)
    }

    @Test
    fun `an upload name is percent-encoded, not pasted into the query`() = runTest {
        client { respond("""{"path":"/tmp/x"}""", HttpStatusCode.OK) }
            .uploadStream("text/plain", "my report &notes.txt", ChunkedSource(ByteArray(1)))
        val q = seen.last().url.encodedQuery
        assertFalse(q.contains(" "), "a raw space would truncate the name: $q")
        assertFalse(q.contains("&notes"), "an unescaped & would split the query: $q")
    }

    @Test
    fun `a source is closed even when the upload fails`() = runTest {
        val source = ChunkedSource(ByteArray(10))
        runCatching {
            client { throw kotlinx.io.IOException("link dropped") }
                .uploadStream("text/plain", null, source)
        }
        assertTrue(source.closed, "the provider handle must not be leaked by a failed upload")
    }

    /** A [ByteStream] that hands back small pieces, the way a real provider does. */
    private class ChunkedSource(
        private val bytes: ByteArray,
        length: Long = bytes.size.toLong(),
        private val piece: Int = 4096,
    ) : ByteStream {
        override val contentLength: Long = length
        var closed = false; private set
        private var offset = 0
        override suspend fun read(into: ByteArray): Int {
            if (offset >= bytes.size) return -1
            val n = minOf(piece, into.size, bytes.size - offset)
            bytes.copyInto(into, 0, offset, offset + n)
            offset += n
            return n
        }
        override suspend fun close() { closed = true }
    }

    @Test
    fun `createSession returns the name tmux actually used, not the one asked for`() = runTest {
        // tmux rewrites a '.' to '_' and still reports success, and the route's
        // name rule lets one through — so the host reads the name back and
        // reports it. Using the requested name instead is a 404 on every request
        // the client makes afterwards.
        val made = ok("""{"ok":true,"name":"my_session"}""").createSession("my.session")
        assertEquals("my_session", made)
    }

    @Test
    fun `createSession tolerates a host that reports no name`() = runTest {
        // Older daemons echoed nothing useful; an empty string is handled by the
        // callers rather than throwing here.
        val made = ok("""{"ok":true}""").createSession("plain")
        assertEquals("", made)
    }

    // -------------------------------------------------- round patch body

    private fun lastBody(): String = (seen.last().body as TextContent).text

    @Test
    fun `updateRound carries model and effort only when the caller says something`() = runTest {
        // The daemon PATCH accepted both fields all along; the client used to
        // omit them from its signature entirely, so a Round born with a model
        // could never be moved off it from any client.
        val c = ok("""{"id":"r-1"}""")

        c.updateRound("r-1", model = "opus", effort = "high")
        val withBoth = lastBody()
        assertTrue("\"model\":\"opus\"" in withBoth, withBoth)
        assertTrue("\"effort\":\"high\"" in withBoth, withBoth)

        c.updateRound("r-1", title = "renamed")
        val without = lastBody()
        assertFalse("\"model\"" in without, "an omitted model must not ride the patch: $without")
        assertFalse("\"effort\"" in without, "an omitted effort must not ride the patch: $without")

        // An empty string is the CLEAR and must reach the wire: the daemon
        // treats blank as "back to the host default".
        c.updateRound("r-1", model = "", effort = "")
        val cleared = lastBody()
        assertTrue("\"model\":\"\"" in cleared, cleared)
        assertTrue("\"effort\":\"\"" in cleared, cleared)
    }

    // ------------------------------------------------------ round polish

    @Test
    fun `polishRound sends the whole draft and decodes the proposal`() = runTest {
        val r = ok("""{"polished":"Read the alerts and say what changed.","note":"Trimmed to 500 characters."}""")
            .polishRound(field = "prompt", title = "Telegram", prompt = "look at alerts", goal = "g", mode = "act")

        assertEquals("http://appd.test/v1/rounds/polish", seen.single().url.toString())
        assertEquals("POST", seen.single().method.value)
        // The whole draft, not just the field being rewritten: a goal only means
        // something beside its prompt, and both only mean something beside the mode
        // that decides whether the run may change anything.
        val body = lastBody()
        assertTrue("\"field\":\"prompt\"" in body, body)
        assertTrue("\"title\":\"Telegram\"" in body, body)
        assertTrue("\"goal\":\"g\"" in body, body)
        assertTrue("\"mode\":\"act\"" in body, body)
        assertEquals("Read the alerts and say what changed.", r.polished)
        assertEquals("Trimmed to 500 characters.", r.note)
        assertNull(r.error)
    }

    @Test
    fun `polish rides the poll tier because a real model call takes seconds`() = runTest {
        ok("""{"polished":"x"}""").polishRound(field = "goal", goal = "g")
        assertEquals(HuginnClient.POLL_READ_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
        assertEquals(HuginnClient.POLL_CALL_TIMEOUT_MS, timeouts?.requestTimeoutMillis)
    }

    @Test
    fun `a model that was unavailable decodes as an error, not as an exception`() = runTest {
        // The daemon degrades to 200 {error} on purpose — the person is mid-sentence
        // in a text field — so the client must NOT treat this as a broken host.
        val r = ok("""{"error":"polish is unavailable right now"}""")
            .polishRound(field = "goal", goal = "g")
        assertNull(r.polished, "there is nothing to offer them")
        assertEquals("polish is unavailable right now", r.error)
    }

}
