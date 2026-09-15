package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.HuginnClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SECOND CURSOR PAIR, and everything that depends on it being genuinely
 * second.
 *
 * A session's transcript and one of its subagents' are different files. The
 * parent's byte offsets describe neither the agent's records nor its length, and
 * during a fan-out the parent's size does not even track its own progress — which
 * is why `GraphCursor` exists and why this controller could not simply filter the
 * page it already had. Every test here is about the two streams staying apart:
 * apart in cursors, apart in pages, and apart in what a failure on one says about
 * the other.
 *
 * The loop BODIES are driven directly rather than through `start()`. The loops
 * themselves are `collectLatest` over presence plus a backoff `delay`, on the
 * controller's own real dispatcher — none of which is where the mistakes live,
 * and all of which would make this suite a race.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`,
 * the REVERSE of JUnit's.
 */
class SessionControllerTest {

    private val seen = mutableListOf<HttpRequestData>()

    /** The URLs asked for, in order, with the base trimmed off. */
    private fun paths(): List<String> = seen.map { it.url.toString().removePrefix(BASE) }

    private fun page(
        text: String,
        nextOffset: Long,
        windowStart: Long = 0,
        seq: Int = 1,
        claudeSessionId: String = "cs-1",
    ) = """{"events":[{"seq":$seq,"kind":"assistant","text":"$text"}],""" +
        """"nextOffset":$nextOffset,"windowStart":$windowStart,"claudeSessionId":"$claudeSessionId"}"""

    /**
     * A controller over a mock daemon.
     *
     * @param handle answers by PATH, so a test can make the agent route fail while
     *   the session route keeps working — which is the whole shape of the compat
     *   case below.
     */
    private fun controller(
        scope: CoroutineScope,
        handle: (String) -> Pair<HttpStatusCode, String>,
    ): SessionController {
        val client = HuginnClient(
            baseUrlProvider = { BASE },
            tokenProvider = { "t" },
            engine = MockEngine { request ->
                seen += request
                val (code, body) = handle(request.url.toString().removePrefix(BASE))
                respond(body, code, io.ktor.http.headersOf("Content-Type", listOf("application/json")))
            },
        )
        return SessionController(
            client = client,
            name = "jtyper",
            presence = Presence(),
            lease = PaneLeaseHolder(client, scope),
            meta = com.silencelen.huginn.data.SessionMetaSaver(
                scope = scope,
                save = { _, _, _ -> com.silencelen.huginn.data.SessionMeta() },
            ),
            appScope = scope,
        )
    }

    // ------------------------------------------------------------- cursors

    @Test
    fun `switching streams resets the agent cursor pair and nothing else`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var agentBody = page("first agent line", nextOffset = 120)
        val c = controller(scope) { path ->
            when {
                "/agents/" in path -> HttpStatusCode.OK to agentBody
                else -> HttpStatusCode.OK to page("main line", nextOffset = 900)
            }
        }

        c.selectStream("agent-aaa")
        c.pollAgentOnce("agent-aaa")
        assertTrue(paths().last().contains("/agents/agent-aaa/transcript"))
        assertFalse("offset=" in paths().last(), "the first read of a stream has no cursor to carry")

        // Second read of the SAME stream tails from where the first ended.
        agentBody = page("second agent line", nextOffset = 240, seq = 2)
        c.pollAgentOnce("agent-aaa")
        assertTrue("offset=120" in paths().last(), paths().last())

        // A different agent is a different file. Carrying 240 into it would tail
        // from a byte position measured in somebody else's transcript.
        c.selectStream("agent-bbb")
        c.pollAgentOnce("agent-bbb")
        assertTrue(paths().last().contains("/agents/agent-bbb/transcript"))
        assertFalse("offset=" in paths().last(), "the cursor pair resets with the stream")

        scope.cancel()
    }

    @Test
    fun `the main page keeps merging while an agent stream is on screen`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var mainBody = page("main one", nextOffset = 100, seq = 1)
        val c = controller(scope) { path ->
            when {
                "/agents/" in path -> HttpStatusCode.OK to page("agent one", nextOffset = 50, seq = 1)
                else -> HttpStatusCode.OK to mainBody
            }
        }

        c.pollMainOnce()
        assertEquals(1, c.page.value?.events?.size)

        // Reading an agent is looking more closely at a session that is still
        // going, not leaving it. The tail underneath must go on arriving, or
        // coming back to Main costs the reader everything that happened while
        // they were reading the agent.
        c.selectStream("agent-aaa")
        c.pollAgentOnce("agent-aaa")
        assertEquals(1, c.agentPage.value?.events?.size)

        mainBody = page("main two", nextOffset = 200, seq = 2)
        c.pollMainOnce()
        assertEquals(2, c.page.value?.events?.size, "the main page merged while an agent was selected")
        assertEquals(1, c.agentPage.value?.events?.size, "and did not leak into the agent's")
        // Two sequences that both start at 1: merged into one page they would
        // collide, which is why these are two pages rather than one filtered.
        assertEquals(listOf("main one", "main two"), c.page.value?.events?.map { it.text })
        assertEquals(listOf("agent one"), c.agentPage.value?.events?.map { it.text })

        scope.cancel()
    }

    @Test
    fun `a 404 from the agent route disables the strip and says it needs appd 3`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = controller(scope) { path ->
            when {
                // The compat case: a daemon older than 3.0.0 has no such route AT
                // ALL, so this is not "no such agent" — it is "no such feature".
                "/agents/" in path -> HttpStatusCode.NotFound to """{"error":"not found"}"""
                else -> HttpStatusCode.OK to page("main line", nextOffset = 100)
            }
        }

        c.selectStream("agent-aaa")
        assertFalse(c.pollAgentOnce("agent-aaa"))
        assertFalse(c.streamsSupported.value)
        assertEquals("needs appd 3.0", c.streamNote.value)
        assertEquals(SessionController.STREAMS_UNSUPPORTED, c.streamNote.value)

        // The session's own transcript is untouched by it: the chips go quiet, the
        // conversation does not.
        c.pollMainOnce()
        assertEquals(1, c.page.value?.events?.size)
        assertNull(c.transcriptError.value)

        scope.cancel()
    }

    /**
     * ⚠ THE OTHER FAILURE, and it is NOT the compat one.
     *
     * A 400 means the daemon HAS the route and rejected this id — so the strip
     * stays alive with every other chip on it, and the daemon's own sentence
     * goes on the strip where the reader will see it. Flipping
     * `streamsSupported` here would take the whole picker away over one bad id,
     * and the old code did neither: it showed the error only while nothing had
     * ever loaded, so a reader who had already opened one agent got a chip that
     * silently did nothing at all.
     */
    @Test
    fun `a 400 keeps the strip alive and puts the daemon's words on it`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failAgent = true
        val c = controller(scope) { path ->
            when {
                "/agents/" in path && failAgent ->
                    HttpStatusCode.BadRequest to """{"error":"invalid agent id"}"""
                "/agents/" in path -> HttpStatusCode.OK to page("agent one", nextOffset = 50)
                else -> HttpStatusCode.OK to page("main line", nextOffset = 100)
            }
        }

        c.selectStream("agent-aaa")
        assertFalse(c.pollAgentOnce("agent-aaa"))
        assertTrue(c.streamsSupported.value, "the route exists — it said so by answering 400")
        assertEquals("invalid agent id", c.streamNote.value, "the daemon's text, verbatim")

        // And the note is shown even once a page IS on screen: it sits on the
        // strip, not in the conversation, so it costs the reader nothing.
        failAgent = false
        assertTrue(c.pollAgentOnce("agent-aaa"))
        assertEquals(1, c.agentPage.value?.events?.size)
        assertNull(c.streamNote.value, "a read that lands clears it again")

        failAgent = true
        assertFalse(c.pollAgentOnce("agent-aaa"))
        assertEquals("invalid agent id", c.streamNote.value)
        assertTrue(c.streamsSupported.value)
        assertEquals(1, c.agentPage.value?.events?.size, "and the page already read stays put")

        scope.cancel()
    }

    @Test
    fun `selecting Main drops the agent page rather than leaving it behind`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = controller(scope) { path ->
            when {
                "/agents/" in path -> HttpStatusCode.OK to page("agent one", nextOffset = 50)
                else -> HttpStatusCode.OK to page("main one", nextOffset = 100)
            }
        }

        c.selectStream("agent-aaa")
        c.pollAgentOnce("agent-aaa")
        assertEquals(1, c.agentPage.value?.events?.size)

        c.selectStream(null)
        assertNull(c.selectedStream.value)
        // Left behind, it would be merged with the NEXT agent's first read — two
        // agents' work in one conversation with nothing to mark where one ended.
        assertNull(c.agentPage.value, "the agent page goes with the stream")

        // An empty string is the same answer as null: the picker's Main row has no
        // agent id, and the two spellings must not become two states.
        c.selectStream("agent-aaa")
        c.pollAgentOnce("agent-aaa")
        c.selectStream("")
        assertNull(c.selectedStream.value)
        assertNull(c.agentPage.value)

        scope.cancel()
    }

    private companion object {
        const val BASE = "http://appd.test"
    }
}
