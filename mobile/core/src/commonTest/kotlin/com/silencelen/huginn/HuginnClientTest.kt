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
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.silencelen.huginn.data.RouteGuard
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    /**
     * ⚠ AND THE GUARD KNOWS. This convenience is exactly the walk-past a URL
     * check placed after it would have: type `example.com`, get `http://` for
     * free, send the bearer to a stranger. [RouteGuard] therefore judges a bare
     * address AS http and stamps the scheme on before anything is stored, so what
     * reaches this client has already been seen with its scheme spelled out.
     */
    @Test
    fun `a base URL without a scheme is assumed to be plain http`() = runTest {
        client(base = "192.168.2.117:8787") { respond("""{"ok":true}""") }.ping()
        assertEquals("http://192.168.2.117:8787/v1/ping", seen.single().url.toString())
        assertEquals("http://192.168.2.117:8787", RouteGuard.normalize("192.168.2.117:8787"))
        assertFalse(RouteGuard.isAllowed("example.com:8787"), "a bare public name never gets the free upgrade")
    }

    /** A scheme is a scheme however it is spelled; `HTTP://` was stored by 2.x. */
    @Test
    fun `an upper-case scheme is not prepended to`() = runTest {
        client(base = "HTTP://192.168.2.117:8787") { respond("""{"ok":true}""") }.ping()
        assertEquals("http://192.168.2.117:8787/v1/ping", seen.single().url.toString())
    }

    /**
     * ⚠ A FRESH INSTALL PINS NOTHING, so this is what EVERY call makes on first
     * launch. `withScheme("")` builds `http:///v1/status`, and the parse failure
     * surfaced in the desktop's status bar as the single word "v1" in red — a
     * first run reading as a crash. Said here so both shells say it the same way.
     */
    @Test
    fun `no pinned route is a sentence rather than a malformed URL`() = runTest {
        var dialled = false
        val e = assertFailsWith<HuginnClient.HuginnException> {
            client(base = "") { dialled = true; respond("""{"ok":true}""") }.ping()
        }
        assertEquals(HuginnClient.NO_ROUTE, e.message)
        assertEquals(false, dialled, "and no socket was opened to find that out")
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

    // ------------------------------------------------- the pane-size lease

    @Test
    fun `a screen read carries live only when the caller says it is in live view`() = runTest {
        // OWNER DECISION 52, on the wire. `cols`/`rows` describe the viewer and are
        // sent whenever they are known; `live=1` is the separate, explicit claim on
        // the owner's tmux window, and it is what the daemon gates the resize on.
        // The default had better be the safe one — every existing call site inherits
        // it, and a default of `true` would reinstate the flap silently.
        val c = ok("""{"hash":"h","lines":[]}""")
        c.screen("jtyper", cols = 120, rows = 40)
        val viewing = seen.last().url.toString()
        assertTrue(viewing.contains("cols=120"), "the viewer still describes itself: $viewing")
        assertFalse(viewing.contains("live="), "plain viewing must not claim the window: $viewing")

        c.screen("jtyper", cols = 120, rows = 40, live = true)
        assertTrue(seen.last().url.toString().contains("live=1"),
            "live view is the one thing that may lease: ${seen.last().url}")

        // Spelt out rather than sent as `live=0`: absent IS the no-lease spelling,
        // and it is the one every client built before this sends.
        c.screen("jtyper", cols = 120, rows = 40, live = false)
        assertFalse(seen.last().url.toString().contains("live="))
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
        // A bare 401 with no body and no version header is NOT the daemon's
        // refusal — anything can say 401.
        assertFalse(answered)
        assertEquals(HuginnClient.PROBE_TIMEOUT_MS, timeouts?.connectTimeoutMillis)
        assertEquals(HuginnClient.PROBE_TIMEOUT_MS, timeouts?.socketTimeoutMillis)
        assertEquals("GET", seen.last().method.value)
        assertNull(seen.last().headers[HttpHeaders.Authorization], "probing must not depend on the token being right")
    }

    /**
     * ⚠ A SOCKET IS NOT A DAEMON. The probe used to count ANY completed HTTP
     * exchange as "huginn is here" — a NAS's 404 page, a printer, a captive
     * portal — and the resolver then made that host the active route and sent it
     * the root-equivalent bearer on the very next call. The reply has to prove
     * the daemon: its own JSON refusal, or the version header it stamps on every
     * response.
     */
    @Test
    fun `a stranger answering HTTP is not a daemon`() = runTest {
        assertFalse(
            client { respond("<html>NAS login</html>", HttpStatusCode.OK) }.probe("http://192.168.2.117:8787"),
            "a 200 of somebody else's web page is not huginn",
        )
        assertFalse(
            client { respondError(HttpStatusCode.NotFound, "<html>404</html>") }.probe("http://192.168.2.117:8787"),
            "nor is a 404 from whatever holds that address today",
        )
        assertFalse(
            client { respondError(HttpStatusCode.Unauthorized, "Unauthorized") }.probe("http://192.168.2.117:8787"),
            "nor a 401 in somebody else's words",
        )
    }

    @Test
    fun `the daemon's own refusal is what proves it`() = runTest {
        val answered = client { respondError(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""") }
            .probe("http://192.168.2.117:8787")
        assertTrue(answered)
        assertEquals("GET", seen.last().method.value)
        assertEquals("http://192.168.2.117:8787/v1/ping", seen.last().url.toString())
        assertNull(seen.last().headers[HttpHeaders.Authorization], "a probe must never carry the bearer")
    }

    @Test
    fun `the version header proves the daemon whatever the status is`() = runTest {
        val head = headersOf("X-Huginn-Appd", "3.3.0")
        assertTrue(
            client { respond("", HttpStatusCode.Unauthorized, head) }.probe("http://192.168.2.117:8787"),
            "the header the daemon stamps on every response, 401 included",
        )
        assertTrue(
            client { respond("""{"ok":true}""", HttpStatusCode.OK, head) }.probe("http://192.168.2.117:8787"),
            "and an unauthenticated ping that answers 200 still identifies itself",
        )
    }

    @Test
    fun `a probe that throws is a route that is not there`() = runTest {
        val answered = client { throw kotlinx.io.IOException("no route to host") }
            .probe("http://192.168.2.117:8787")
        assertFalse(answered)
    }

    // ------------------------------------------------------- failures

    /**
     * ⚠ tmux DOES NOT ALWAYS TAKE THE NAME IT IS GIVEN. It silently rewrites '.'
     * to '_' and still exits 0, so the daemon asks tmux what it actually called
     * the session and answers with that. A caller that assumed its own string
     * won closed the pane it had just renamed and ate the draft in it (desktop
     * #83); it can only stop assuming if the name comes back.
     */
    @Test
    fun `a rename answers with the name the daemon actually used`() = runTest {
        val name = ok("""{"ok":true,"name":"my_session"}""").renameSession("old", "my.session")
        assertEquals("my_session", name)
        assertEquals("http://appd.test/v1/sessions/old/rename", seen.single().url.toString())
    }

    @Test
    fun `a daemon too old to answer with a name is not an error`() = runTest {
        assertEquals("newname", ok("""{"ok":true}""").renameSession("old", "newname"))
    }

    /**
     * ⚠ AND IT MUST NOT READ AS A SERVER ERROR. `errorTextFor` prints
     * `e.message` verbatim for anything that is not a [HuginnClient.HuginnException],
     * so a captive portal's or a stranger's 200 used to put
     * `Unexpected JSON token at offset 0: … JSON input: <the page>` on the Status
     * screen. The new type deliberately does NOT extend HuginnException, because
     * both shells use `it !is HuginnException` as their network-vs-server test and
     * wrapping it would stop the client re-resolving away from the portal.
     */
    @Test
    fun `a 2xx that is not huginn's JSON is not a serializer exception`() = runTest {
        val body = "<html><body>Sign in to continue &mdash; guest wifi</body></html>"
        val e = assertFailsWith<HuginnClient.NotHuginnException> {
            client { respond(body, HttpStatusCode.OK) }.status()
        }
        assertEquals(HuginnClient.NOT_HUGINN, e.message)
        val thrown: Throwable = e
        assertTrue(thrown !is HuginnClient.HuginnException,
            "the route-health classifiers key on this, and a portal is a network failure")
        assertFalse("html" in e.message, "and the page itself never reaches the screen")
    }

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

    // ------------------------------------------------------- scratchpads

    @Test
    fun `a message with no page attached does not mention one`() = runTest {
        // ABSENT is not the same as naming Main: the daemon falls back to Main only
        // for a reference it was actually asked for, so a field sent on every
        // message would put a page into conversations nobody attached it to.
        ok("""{"ok":true}""").queueMessage("c1", "just a question")
        assertFalse("scratchpadId" in lastBody(), lastBody())
    }

    @Test
    fun `an attached page travels as an id, never as its text`() = runTest {
        ok("""{"ok":true}""").queueMessage("c1", "what about this", scratchpadId = "pad-9")
        val body = lastBody()
        assertTrue("\"scratchpadId\":\"pad-9\"" in body, body)
        // The daemon composes the frame. A client that pasted the page in would
        // make the queued copy a snapshot of the wrong moment, and would have to
        // keep its own copy of a marker two other files already own.
        assertFalse("Scratchpad" in body, body)
    }

    @Test
    fun `a session send carries the reference too`() = runTest {
        ok("""{"ok":true}""").sendKeys("jtyper", text = "follow this", scratchpadId = "pad-9")
        assertEquals("http://appd.test/v1/sessions/jtyper/keys", seen.single().url.toString())
        assertTrue("\"scratchpadId\":\"pad-9\"" in lastBody(), lastBody())
    }

    @Test
    fun `a held send decodes the reason it is being held`() = runTest {
        // The daemon's answer to a send it could not deliver carries the same word
        // `/typing` reports (appd 3.1.2), and a field this class does not have is a
        // field the composer line cannot say — which is how every client spent the
        // first two seconds of a new session describing a turn that had not begun.
        val r = ok("""{"ok":true,"queued":1,"position":1,"delivered":false,"blockedBy":"starting"}""")
            .sendKeys("jtyper", text = "the first thing I typed", keys = listOf("Enter"))
        assertEquals(1, r.queued)
        assertFalse(r.landed)
        assertEquals("starting", r.blockedBy)
    }

    @Test
    fun `a send from a daemon that does not say why decodes to no reason at all`() = runTest {
        // Not "turn" and not an exception: a pre-3.1.2 daemon simply has nothing to
        // say, and the client must render the sentence it always did.
        val r = ok("""{"ok":true,"queued":2}""").sendKeys("jtyper", text = "hello")
        assertEquals(2, r.queued)
        assertNull(r.blockedBy)
    }

    @Test
    fun `a save carries the rev it was based on`() = runTest {
        val r = ok("""{"id":"pad-9","name":"Main","content":"two","rev":8}""")
            .saveScratchpad("pad-9", rev = 7, content = "two")
        assertEquals("PATCH", seen.single().method.value)
        assertTrue("\"rev\":7" in lastBody(), lastBody())
        assertFalse(r.conflict)
        assertEquals(8, r.pad.rev, "the new rev is what makes the NEXT save safe")
    }

    @Test
    fun `a 409 is an answer carrying the winner's copy, not an exception`() = runTest {
        // Two devices autosaving one page is the ordinary case here. Throwing would
        // send it down the failure path and leave the editor with nothing to adopt.
        val r = client {
            respond(
                """{"id":"pad-9","name":"Main","content":"from the desktop","rev":12}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }.saveScratchpad("pad-9", rev = 7, content = "from the phone")
        assertTrue(r.conflict)
        assertEquals("from the desktop", r.pad.content)
        assertEquals(12, r.pad.rev)
    }

    @Test
    fun `a refusal about the NAME still throws`() = runTest {
        // A 400 is a real refusal and belongs on the failure path; only the
        // conflict is an answer.
        assertFailsWith<HuginnClient.HuginnException> {
            client { respondError(HttpStatusCode.BadRequest, """{"error":"there is already a page with that name"}""") }
                .saveScratchpad("pad-9", rev = 1, name = "Notes")
        }
    }

    @Test
    fun `the pages probe is a plain GET, so a 404 can mean 'no such feature'`() = runTest {
        val pads = ok("""{"pads":[{"id":"p1","name":"Main","main":true,"rev":3,"size":11}]}""").scratchpads()
        assertEquals("http://appd.test/v1/scratchpads", seen.single().url.toString())
        assertEquals("GET", seen.single().method.value)
        assertEquals(1, pads.size)
        assertTrue(pads.single().main)
    }

    // ------------------------------------------------------- headroom 3.0.0

    @Test
    fun `undoLadder decodes what the undo actually did`() = runTest {
        val r = ok("""{"ok":true,"applied":false,"queued":true,"to":"fable","delivery":"queued"}""")
            .undoLadder("jtyper")
        assertEquals("http://appd.test/v1/sessions/jtyper/headroom/undo", seen.single().url.toString())
        assertEquals("POST", seen.single().method.value)
        assertTrue(r.ok)
        assertFalse(r.applied, "mid-turn: the picker cannot be opened inside a running turn")
        assertTrue(r.queued)
        assertEquals("fable", r.to)
    }

    /**
     * ⚠ THE ID GOES THROUGH VERBATIM. `/agents` emits the BARE hex; the
     * transcript route takes bare or `agent-` prefixed. A client that added the
     * prefix — or stripped it — would be a third opinion about an id it did not
     * mint, and the strip 400'd on every chip when the two disagreed.
     */
    @Test
    fun `agentTranscript sends the agent id exactly as the list gave it`() = runTest {
        ok("""{"events":[],"nextOffset":0}""").agentTranscript("jtyper", "af7ca864cee1939de")
        assertEquals(
            "http://appd.test/v1/sessions/jtyper/agents/af7ca864cee1939de/transcript?limit=400",
            seen.single().url.toString(),
        )

        seen.clear()
        ok("""{"events":[],"nextOffset":0}""").agentTranscript("jtyper", "agent-3f9c1a")
        assertEquals(
            "http://appd.test/v1/sessions/jtyper/agents/agent-3f9c1a/transcript?limit=400",
            seen.single().url.toString(),
        )
    }

    @Test
    fun `typing status is a GET, and there is no cancel route to call`() = runTest {
        val t = ok("""{"queued":2,"delivering":false,"blockedBy":"turn","serverTime":1789460000}""")
            .typingStatus("jtyper")
        assertEquals("http://appd.test/v1/sessions/jtyper/typing", seen.single().url.toString())
        assertEquals("GET", seen.single().method.value, "the daemon has never served anything else here")
        assertEquals(2, t.queued)
        assertEquals("turn", t.blockedBy)
    }

    // ------------------------------------------- image file paths (wave 2)

    /**
     * The route `w2-imageroute` is building, stubbed here so this side can be
     * finished and asserted without it: `GET /v1/files/image?path=…&session=…`,
     * bearer-gated, an image MIME allowlist, 403 for anything outside the
     * allowed roots. The client's whole job is to address it correctly — the
     * containment decision is the daemon's and must never be copied here.
     */
    @Test
    fun `imageBytes addresses the daemon's file route with the path encoded whole`() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val bytes = client { respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) }
            .imageBytes("/tmp/claude-0/a shot.png")
        assertContentEquals(png, bytes)
        assertEquals(
            "http://appd.test/v1/files/image?path=%2Ftmp%2Fclaude-0%2Fa%20shot.png",
            seen.single().url.toString(),
        )
        assertEquals("GET", seen.single().method.value)
        assertEquals("Bearer test-token", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a session widens the daemon's search without widening the client's`() = runTest {
        client { respond(byteArrayOf(1), HttpStatusCode.OK) }.imageBytes("/w/out.png", session = "jtyper")
        assertEquals(
            "http://appd.test/v1/files/image?path=%2Fw%2Fout.png&session=jtyper",
            seen.single().url.toString(),
        )
    }

    @Test
    fun `a refused path is the daemon's 403, surfaced as it was sent`() = runTest {
        val e = assertFailsWith<HuginnClient.HuginnException> {
            client { respondError(HttpStatusCode.Forbidden, """{"error":"outside the allowed roots"}""") }
                .imageBytes("/etc/shadow.png")
        }
        assertEquals(403, e.code)
        assertEquals("outside the allowed roots", e.message)
    }

    // ------------------------------------------------- devices: the wire

    /**
     * "Keep act mode while locked" has to REACH the daemon, or the pre-check
     * refuses work the machine would have run — and refuses it a minute earlier,
     * from the half of the system that does not decide.
     *
     * ⚠ AND THE LOCK STATE STAYS HONEST BESIDE IT. Two facts wear the word:
     * whether a screen is locked (reported, always, and what the fleet list
     * shows) and whether that withdraws anything here (this). Collapsing them —
     * sending `locked: false` because the setting is on — would have needed no
     * new field at all, and would have made every surface describe a locked
     * machine as one nobody had locked.
     */
    @Test
    fun aDeviceSaysBothWhetherItIsLockedAndWhetherThatMatters() = runTest {
        val c = ok("""{"id":"d1","name":"RAGNAR","platform":"windows","scope":"own"}""")
        c.registerDevice(
            name = "RAGNAR", platform = "windows", scope = "own",
            locked = true, actWhileLocked = true,
        )
        var body = lastBody()
        assertTrue(""""locked":true""" in body, body)
        assertTrue(""""actWhileLocked":true""" in body, body)

        // Turning it OFF has to travel too: a narrowing that only the machine
        // knows about leaves the daemon offering act on a box that revoked it.
        c.registerDevice(
            name = "RAGNAR", platform = "windows", scope = "own",
            locked = true, actWhileLocked = false,
        )
        body = lastBody()
        assertTrue(""""actWhileLocked":false""" in body, body)

        // A caller that does not pass it says nothing — which is what every
        // client older than the setting does, and what the daemon reads as
        // "never said" rather than as "said no".
        c.registerDevice(name = "RAGNAR", platform = "windows", scope = "own", locked = true)
        assertFalse("actWhileLocked" in lastBody(), lastBody())
    }

    @Test
    fun andTheBeatCarriesItToo() = runTest {
        val c = ok("""{"cancel":false}""")
        c.deviceBeat("d1", locked = true, scope = "own", version = "1.5.2", actWhileLocked = true)
        assertTrue(""""actWhileLocked":true""" in lastBody(), lastBody())
        c.deviceBeat("d1", locked = true, scope = "own", version = "1.5.2", actWhileLocked = false)
        assertTrue(""""actWhileLocked":false""" in lastBody(), lastBody())
        c.deviceBeat("d1", locked = true, scope = "own", version = "1.5.2")
        assertFalse("actWhileLocked" in lastBody(), lastBody())
    }


    // ------------------------------------------ an archived conversation

    /**
     * The archive's conversation comes from its OWN route, keyed on the Claude
     * session uuid.
     *
     * ⚠ NOT A TMUX NAME, AND THAT IS WHY THE ROUTE EXISTS. Every other transcript
     * route gates on the session being live and reads state keyed on its name;
     * both are gone once a session is archived, and the name itself is reused on
     * this host within hours.
     */
    @Test
    fun `an archived transcript is read from the archive route`() = runTest {
        val id = "9f1c8b52-5d2a-4a21-9f65-1a2b3c4d5e6f"
        val page = ok("""{"events":[{"kind":"user","text":"hello"}],"archived":true}""")
            .archiveTranscript(id)
        assertEquals("/v1/archive/$id/transcript", seen.last().url.encodedPath)
        assertEquals(listOf("hello"), page?.events?.map { it.text })
        assertTrue(page?.archived == true, "the daemon says so on every window")
    }

    /** Paged exactly like a session's: `until` walks backwards into history. */
    @Test
    fun `it pages the same way a live transcript does`() = runTest {
        ok("""{"events":[]}""").archiveTranscript("a", limit = 50, until = 4096)
        val q = seen.last().url.parameters
        assertEquals("50", q["limit"])
        assertEquals("4096", q["until"])
    }

    /**
     * ⚠⚠ NULL IS TWO ANSWERS AT ONCE, ON PURPOSE. A daemon older than the route
     * 404s, and so does an archive this daemon does not have. Both mean "there is
     * nothing here to open", and both must leave a row that quietly does not
     * offer the door rather than one that offers an error — the feature-probe
     * shape `projects` and `scratchpads` already use.
     */
    @Test
    fun `a 404 is an answer, not a failure`() = runTest {
        val page = client { respondError(HttpStatusCode.NotFound, """{"error":"no such archived session"}""") }
            .archiveTranscript("a")
        assertNull(page)
    }

    /**
     * ⚠ A 409 IS A DIFFERENT ANSWER AND IT THROWS, so the daemon's own sentence
     * can be shown verbatim. Which of the two copies went is the one fact this
     * whole feature exists to be honest about, and a summary of ours would lose it.
     */
    @Test
    fun `a transcript nobody kept arrives as the daemon's own sentence`() = runTest {
        val why = "no transcript was kept for this archive, and Claude Code no longer has one"
        val thrown = assertFailsWith<HuginnClient.HuginnException> {
            client { respondError(HttpStatusCode.Conflict, """{"error":"$why"}""") }.archiveTranscript("a")
        }
        assertEquals(409, thrown.code)
        assertEquals(why, thrown.message)
    }
}

/**
 * THE UNAUTHENTICATED PROBE, AND WHAT IT CAN SAY OUT LOUD.
 *
 * ⚠⚠ `/v1/ping` IS TOKEN-GATED — `huginn-appd.js` authorizes before the ping
 * handler — so the first-run flow's Address step, which called `ping()`, failed
 * with the word "unauthorized" against a perfectly correct address. On every
 * fresh install: the step exists precisely to separate "nothing answers there"
 * from "something answers and does not like your bearer", and it was reporting
 * the second for the first, pointing the reader at a token the flow had not
 * asked for yet.
 *
 * [HuginnClient.provesDaemon] already knew the real contract — the 401 plus the
 * `X-Huginn-Appd` header IS the proof — and [HuginnClient.probe] already used it.
 * What was missing is that a boolean cannot carry a sentence, so the step had
 * nothing to print. [HuginnClient.probeDaemon] answers with the version off the
 * header, which is the same fact `ping()` would have given and needs no token.
 */
class DaemonProbeTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "test-token" },
        engine = MockEngine { request -> seen += request; respond(request) },
    )

    @Test
    fun `a token-gated 401 proves the daemon and carries its version`() = runTest {
        val probe = client {
            respond(
                """{"error":"unauthorized"}""",
                HttpStatusCode.Unauthorized,
                headersOf(HuginnClient.APPD_HEADER, "3.4.0"),
            )
        }.probeDaemon("http://192.168.2.117:8787")

        assertTrue(probe.proven, "a 401 with the version header is the daemon answering")
        assertEquals("3.4.0", probe.version)
        assertNull(seen.last().headers[HttpHeaders.Authorization], "the probe must never carry the bearer")
        assertEquals(
            "appd 3.4.0 answered at 192.168.2.117:8787",
            HuginnClient.probeWords(probe),
            "the step prints the world's own words, not a paraphrase",
        )
    }

    @Test
    fun `a daemon too old to stamp the header still proves itself by its refusal`() = runTest {
        val probe = client { respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized) }
            .probeDaemon("http://192.168.2.117:8787")
        assertTrue(probe.proven, "the JSON error shape is the older marker and still counts")
        assertNull(probe.version)
        assertEquals("huginn answered at 192.168.2.117:8787", HuginnClient.probeWords(probe))
    }

    @Test
    fun `a stranger at that address is not the daemon`() = runTest {
        val probe = client { respond("<html>NAS login</html>", HttpStatusCode.OK) }
            .probeDaemon("http://192.168.2.117:8787")
        assertFalse(probe.proven, "a 200 of somebody else's web page is not huginn")
    }

    @Test
    fun `an empty route is a state rather than an address`() = runTest {
        val probe = client { respond("", HttpStatusCode.OK) }.probeDaemon("   ")
        assertFalse(probe.proven)
        assertEquals(HuginnClient.NO_ROUTE, HuginnClient.probeWords(probe))
    }
}
