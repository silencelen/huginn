package com.silencelen.huginn

import com.silencelen.huginn.data.ChatEvent
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.PinBook
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * NO REQUEST LEAVES THE CLIENT BEFORE ITS SETTINGS HAVE LOADED, whoever calls.
 *
 * The phone reads its route book, address and token off disk while its screens
 * are already composing, and a screen asks the moment it appears. Two entries hit
 * that window on the owner's phone: a session opened from a notification (the
 * address was read, the token not yet: "Rejected by huginn: check the token in
 * Settings" for ~3 s), and a session restored after Android had killed the
 * process (nothing read yet: "No route yet — add the address huginn answers on
 * in Settings" for ~2 s). Both were the client failing on the spot, on a session
 * that was fine; the history then waited for a retry.
 *
 * The gate is the client's own `settingsLoaded`, awaited at the two doors to the
 * network. These tests stand where the view model stands: providers that read
 * blank until [Settings.load], and a gate that opens when it does.
 *
 * kotlin.test order: `assertEquals(expected, actual, message)`.
 */
class SettingsGateTest {

    /** The view model's half: blank until the store answers, then all of it at once. */
    private class Settings {
        var base = ""
        var token = ""
        val loaded = MutableStateFlow(false)

        /** Every time a request or a probe stopped at the gate. */
        val asked = MutableStateFlow(0)

        fun load(base: String, token: String) {
            this.base = base
            this.token = token
            loaded.value = true
        }
    }

    /** What reached the wire, in order: path and query, and the bearer it carried. */
    private val wire = MutableStateFlow<List<Pair<String, String?>>>(emptyList())

    private fun paths(): List<String> = wire.value.map { it.first }

    private val transport = mockTransport(TOKEN) { request ->
        val path = request.url.encodedPath + (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
        wire.update { it + (path to request.headers["Authorization"]) }
        val stream = request.url.encodedPath.endsWith("/stream")
        respond(
            content = ByteReadChannel(answerFor(request.url.encodedPath)),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, if (stream) "text/event-stream" else "application/json"),
        )
    }

    private fun answerFor(path: String): String = when {
        path.endsWith("/transcript") -> """{"events":[],"nextOffset":10,"windowStart":0,"title":"history"}"""
        path.endsWith("/screen") -> """{"width":80,"height":24,"hash":"h1"}"""
        path.endsWith("/stream") -> "event: delta\ndata: {\"text\":\"hi\"}\n\nevent: done\ndata: {}\n\n"
        path.startsWith("/v1/chats/") -> """{"id":"c1","title":"a chat","mode":"act"}"""
        else -> "{}"
    }

    /** Built the way the phone's view model builds its client. */
    private fun gated(s: Settings) = HuginnClient(
        baseUrlProvider = { s.base },
        tokenProvider = { s.token },
        transport = transport,
        pins = PinBook(),
        settingsLoaded = {
            s.asked.update { it + 1 }
            s.loaded.first { it }
        },
    )

    /** Whatever ran on the test dispatcher has reached the gate or got past it. */
    private fun TestScope.settle() = runCurrent()

    @Test
    fun `a session restored before anything is read asks for nothing and fails nothing`() = runTest {
        // The restore entry: rememberSaveable brings the session view back on the
        // first frame of a new process, before a single setting has been read.
        val s = Settings()
        val client = gated(s)
        val history = async { client.sessionTranscript("s1") }
        val pane = async { client.screen("s1") }
        settle()

        assertEquals(2, s.asked.value, "both calls stopped at the gate")
        assertEquals(emptyList(), paths(), "nothing reached the wire")
        assertEquals(emptyList(), transport.probes, "not even the TLS proof")
        assertTrue(history.isActive && pane.isActive, "and neither failed, so there is no error to draw")

        s.load(BASE, TOKEN)
        assertEquals("history", history.await().title)
        assertEquals("h1", pane.await().hash)
        // A set: the mock engine answers on its own threads, so two requests
        // released together reach it in either order.
        assertEquals(
            setOf("/v1/sessions/s1/transcript?limit=400", "/v1/sessions/s1/screen"),
            paths().toSet(),
            "each went after the load",
        )
        assertEquals(2, paths().size, "and once")
        assertTrue(wire.value.all { it.second == "Bearer $TOKEN" }, "with the token that was loaded")
        assertEquals(1, transport.probes.size, "proved once, under the loaded token")
    }

    @Test
    fun `a session opened from a notification waits for the token it has not read yet`() = runTest {
        // The cold-intent entry: the address is read first, so it was the TOKEN
        // that was missing, and the 401 the client made up was drawn as the
        // daemon rejecting it.
        val s = Settings().apply { base = BASE }
        val client = gated(s)
        val history = async { client.sessionTranscript("s1") }
        settle()

        assertEquals(emptyList(), paths())
        assertTrue(history.isActive, "no `no token set` before the token has been read")

        s.load(BASE, TOKEN)
        assertEquals(10L, history.await().nextOffset)
        assertEquals(listOf("/v1/sessions/s1/transcript?limit=400"), paths())
    }

    @Test
    fun `a chat opened before the settings load waits, its stream included`() = runTest {
        val s = Settings()
        val client = gated(s)
        val meta = async { client.chat("c1") }
        val history = async { client.chatTranscript("c1") }
        // The reattach to a running answer: an SSE flow, read off the main thread.
        val stream = async { client.streamChat("c1", since = 0).toList() }
        settle()
        // The stream's reader runs on the IO dispatcher, so it is waited for in
        // real time; the gate counts it the instant it gets there.
        withContext(Dispatchers.Default) { withTimeout(10_000) { s.asked.first { it >= 3 } } }

        assertEquals(emptyList(), paths(), "no meta, no history, no stream before the load")
        assertTrue(meta.isActive && history.isActive && stream.isActive, "and nothing failed")

        s.load(BASE, TOKEN)
        assertEquals("act", meta.await().mode)
        assertEquals("history", history.await().title)
        val events = stream.await()
        assertTrue(ChatEvent.Delta("hi") in events, "the stream was read: $events")
        assertTrue(events.none { it is ChatEvent.Failure }, "and did not fail: $events")
        assertEquals(
            setOf("/v1/chats/c1", "/v1/chats/c1/transcript?limit=400", "/v1/chats/c1/stream?since=0"),
            paths().toSet(),
        )
        assertEquals(3, paths().size, "each once")
    }

    @Test
    fun `a route probe waits for the token it proves with`() = runTest {
        // Route resolution on a cold start probes every pinned route. With no
        // token read yet every proof fails, and a route that is fine reads as
        // unproved.
        val s = Settings()
        val client = gated(s)
        val probe = async { client.probeDaemon(BASE) }
        settle()

        assertEquals(emptyList(), transport.probes, "no probe before the token is known")
        assertTrue(probe.isActive)

        s.load(BASE, TOKEN)
        assertTrue(probe.await().proven, "proved under the loaded token")
        assertEquals(1, transport.probes.size)
    }

    @Test
    fun `once the settings have loaded, a missing token still fails at once and says so`() = runTest {
        // The gate holds a call until the store has ANSWERED, not until it says
        // something useful: a phone that was never given a token must still be
        // told so, not left waiting.
        val s = Settings()
        val client = gated(s)
        s.load(BASE, token = "")
        val e = assertFailsWith<HuginnClient.HuginnException> { client.sessionTranscript("s1") }
        assertEquals(401, e.code)
        assertEquals("no token set", e.message)
        assertEquals(emptyList(), paths(), "and nothing was sent")
        assertEquals(emptyList(), transport.probes)
    }

    @Test
    fun `once the settings have loaded, a missing address still says there is no route`() = runTest {
        val s = Settings()
        val client = gated(s)
        s.load(base = "", token = TOKEN)
        val e = assertFailsWith<HuginnClient.HuginnException> { client.chatTranscript("c1") }
        assertEquals(HuginnClient.NO_ROUTE, e.message)
        assertEquals(emptyList(), paths())
    }

    @Test
    fun `a client built from settings already read asks at once`() = runTest {
        // The desktop and the phone's receivers and workers read their settings
        // before they build a client. They pass no gate and must not wait on one.
        val client = HuginnClient(
            baseUrlProvider = { BASE },
            tokenProvider = { TOKEN },
            transport = transport,
            pins = PinBook(),
        )
        assertEquals("history", client.sessionTranscript("s1").title)
        assertEquals(listOf("/v1/sessions/s1/transcript?limit=400"), paths())
    }

    private companion object {
        const val BASE = "http://appd.test"
        const val TOKEN = "tok-1"
    }
}
