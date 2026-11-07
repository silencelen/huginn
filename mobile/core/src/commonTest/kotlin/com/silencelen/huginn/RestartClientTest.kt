package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /v1/sessions/:name/restart` and the `runtime` cell it acts on: the path
 * and body this client puts on the wire, and the three answers — 202 queued
 * (with what holds it), 409 `restarting` (already queued: a success, not the
 * refusal a 409 is on every other queue route), anything else a throw carrying
 * the daemon's sentence.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class RestartClientTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "test-token" },
        transport = mockTransport("test-token") { request -> seen += request; respond(request) },
    )

    private fun lastBody(): String = (seen.last().body as TextContent).text

    /** The 202 body as the daemon serves it: `queued`, plus what holds the job until its safe point. */
    @Test
    fun `a restart is a POST that comes back queued with what holds it`() = runTest {
        val c = client { respond("""{"queued":true,"blockedBy":"running"}""", HttpStatusCode.Accepted) }
        val r = c.restartSession("alex")
        assertEquals("/v1/sessions/alex/restart", seen.single().url.encodedPath)
        assertEquals(HttpMethod.Post, seen.single().method)
        assertEquals("{}", lastBody(), "no force key unless asked for")
        assertTrue(r.queued)
        assertEquals("running", r.blockedBy)
        assertNull(r.reason)
        assertFalse(r.alreadyQueued)
        assertNull(r.error)
        val due = client { respond("""{"queued":true,"blockedBy":null}""", HttpStatusCode.Accepted) }.restartSession("alex")
        assertTrue(due.queued)
        assertNull(due.blockedBy, "nothing holds it: it runs at the next turn boundary")
    }

    /** `force` skips the attached hold only, and leaves the wire alone when it is false. */
    @Test
    fun `force rides as the one body key, and only when asked`() = runTest {
        val c = client { respond("""{"queued":true,"blockedBy":null}""", HttpStatusCode.Accepted) }
        c.restartSession("alex", force = true)
        assertEquals("""{"force":true}""", lastBody())
        c.restartSession("alex", force = false)
        assertEquals("{}", lastBody())
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.url.encodedPath == "/v1/sessions/alex/restart" && it.method == HttpMethod.Post })
    }

    /**
     * ⚠ THIS 409 IS A SUCCESS. On the other queue routes a 409 is a refusal; here
     * it says a restart is already queued or running, which is what the caller
     * wanted, so it reads as queued and the daemon's sentence rides along.
     */
    @Test
    fun `a restart already queued or running reads as queued, not as a failure`() = runTest {
        val r = client { respond("""{"reason":"restarting"}""", HttpStatusCode.Conflict) }.restartSession("alex")
        assertTrue(r.queued)
        assertTrue(r.alreadyQueued)
        assertEquals("restarting", r.reason)
        assertNull(r.blockedBy)
        assertNull(r.error)
        val worded = client { respond("""{"reason":"restarting","error":"a restart is already under way"}""", HttpStatusCode.Conflict) }
            .restartSession("alex")
        assertTrue(worded.queued)
        assertTrue(worded.alreadyQueued)
        assertEquals("a restart is already under way", worded.error, "the daemon's sentence rides along")
    }

    /** Everything else is the daemon's `error` text, thrown like any other route's. */
    @Test
    fun `any other answer throws with the daemon's sentence`() = runTest {
        val gone = assertFailsWith<HuginnClient.HuginnException> {
            client { respond("""{"error":"no such session"}""", HttpStatusCode.NotFound) }.restartSession("alex")
        }
        assertEquals(404, gone.code)
        assertEquals("no such session", gone.message)
        val blind = assertFailsWith<HuginnClient.HuginnException> {
            client { respond("""{"error":"tmux cannot be observed"}""", HttpStatusCode.ServiceUnavailable) }.restartSession("alex")
        }
        assertEquals(503, blind.code)
        assertEquals("tmux cannot be observed", blind.message)
        // A 409 whose reason is NOT `restarting` is a refusal: never mistaken for a queued restart.
        val refused = assertFailsWith<HuginnClient.HuginnException> {
            client { respond("""{"reason":"dialog","error":"answer the waiting question first"}""", HttpStatusCode.Conflict) }
                .restartSession("alex")
        }
        assertEquals(409, refused.code)
        assertEquals("answer the waiting question first", refused.message)
        val bare = assertFailsWith<HuginnClient.HuginnException> {
            client { respond("", HttpStatusCode.Conflict) }.restartSession("alex")
        }
        assertEquals(409, bare.code)
    }

    /**
     * The `runtime` cell through the real decoder (`coerceInputValues`): a row
     * with the whole object, one with only the verdict (a session launched before
     * the daemon kept generations has no `gen`, and counts as stale once), one
     * under restart with explicit nulls, and one from a daemon that has never
     * heard of it — which is NOT stale.
     */
    @Test
    fun `a session row's runtime cell reads whole, partial, or absent`() = runTest {
        val c = client {
            respond(
                """{"sessions":[
                  {"name":"old","runtime":{"stale":true,"gen":"a1b2c3","current":"d4e5f6","claude":"2.1.300","currentClaude":"2.1.316","restarting":false}},
                  {"name":"first","runtime":{"stale":true}},
                  {"name":"cycling","runtime":{"stale":true,"gen":null,"current":"d4e5f6","claude":null,"currentClaude":"2.1.316","restarting":true}},
                  {"name":"legacy"}
                ]}""",
                HttpStatusCode.OK,
            )
        }
        val rows = c.sessions().associateBy { it.name }
        val old = rows.getValue("old").runtime!!
        assertTrue(old.stale)
        assertEquals("a1b2c3", old.gen)
        assertEquals("d4e5f6", old.current)
        assertEquals("2.1.300", old.claude)
        assertEquals("2.1.316", old.currentClaude)
        assertFalse(old.restarting)
        val first = rows.getValue("first").runtime!!
        assertTrue(first.stale, "the verdict alone is a cell")
        assertNull(first.gen)
        assertNull(first.current)
        assertNull(first.claude)
        assertNull(first.currentClaude)
        assertFalse(first.restarting)
        val cycling = rows.getValue("cycling").runtime!!
        assertTrue(cycling.restarting)
        assertNull(cycling.gen, "an explicit null is a null, not a failure")
        assertEquals("2.1.316", cycling.currentClaude)
        assertNull(rows.getValue("legacy").runtime, "no cell on an older daemon")
        assertFalse(rows.getValue("legacy").runtime?.stale == true, "and absent is not stale")
    }
}
