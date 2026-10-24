package com.silencelen.huginn

import com.silencelen.huginn.data.ChatFast
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
 * The context, fast-mode and Claude-settings routes (appd 3.14.0): the paths
 * and bodies this client puts on the wire, and the two answers that are not
 * failures — a 409 from a queue route, which carries its reason, and a 404 from
 * the settings route, which says the daemon predates it.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ContextClientTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "test-token" },
        transport = mockTransport("test-token") { request -> seen += request; respond(request) },
    )

    private fun lastBody(): String = (seen.last().body as TextContent).text

    @Test
    fun `the context route is read by session name`() = runTest {
        val c = client { respond("""{"context":{"used":1,"window":2,"percent":50},"model":"m","breakdown":null}""", HttpStatusCode.OK) }
        val got = c.sessionContext("my session")
        assertEquals("/v1/sessions/my%20session/context", seen.single().url.encodedPath)
        assertEquals(HttpMethod.Get, seen.single().method)
        assertEquals(50, got.context?.percent)
        assertNull(got.breakdown)
    }

    @Test
    fun `a refresh is a POST that comes back queued`() = runTest {
        val c = client { respond("""{"queued":true}""", HttpStatusCode.Accepted) }
        val q = c.refreshSessionContext("alex")
        assertEquals("/v1/sessions/alex/context/refresh", seen.single().url.encodedPath)
        assertEquals(HttpMethod.Post, seen.single().method)
        assertTrue(q.queued)
        assertNull(q.reason)
    }

    /** ⚠ A 409 IS AN ANSWER: the reason is the whole message the card shows. */
    @Test
    fun `a refresh the daemon cannot queue answers with its reason instead of throwing`() = runTest {
        val c = client { respond("""{"reason":"dialog"}""", HttpStatusCode.Conflict) }
        val q = c.refreshSessionContext("alex")
        assertFalse(q.queued)
        assertEquals("dialog", q.reason)
        // And a 409 with no body still reads as a refusal rather than a crash.
        val bare = client { respond("", HttpStatusCode.Conflict) }.refreshSessionContext("alex")
        assertFalse(bare.queued)
        assertEquals("busy", bare.reason)
    }

    @Test
    fun `any other failure on a queue route still throws`() = runTest {
        val c = client { respond("""{"error":"not found"}""", HttpStatusCode.NotFound) }
        val e = assertFailsWith<HuginnClient.HuginnException> { c.refreshSessionContext("alex") }
        assertEquals(404, e.code)
    }

    @Test
    fun `fast mode for a session sends on as a boolean and reads the queue answer`() = runTest {
        val c = client { respond("""{"queued":true}""", HttpStatusCode.Accepted) }
        assertTrue(c.setSessionFast("alex", on = true).queued)
        assertEquals("/v1/sessions/alex/fast", seen.last().url.encodedPath)
        assertEquals("""{"on":true}""", lastBody())
        c.setSessionFast("alex", on = false)
        assertEquals("""{"on":false}""", lastBody())
        val busy = client { respond("""{"reason":"busy"}""", HttpStatusCode.Conflict) }.setSessionFast("alex", true)
        assertEquals("busy", busy.reason)
    }

    /** The feature probe: absent and present are different answers. */
    @Test
    fun `an older daemon's 404 on claude settings is a null`() = runTest {
        assertNull(client { respond("""{"error":"not found"}""", HttpStatusCode.NotFound) }.claudeSettings())
        val s = client { respond("""{"fastModeDefault":true,"fastModePerSession":true,"feedbackOff":true}""", HttpStatusCode.OK) }.claudeSettings()
        assertEquals(true, s?.fastModeDefault)
        assertEquals("/v1/settings/claude", seen.last().url.encodedPath)
    }

    @Test
    fun `the default is written as a PATCH of one field`() = runTest {
        val c = client { respond("""{"fastModeDefault":true,"fastModePerSession":true,"feedbackOff":false}""", HttpStatusCode.OK) }
        val s = c.setClaudeSettings(fastModeDefault = true)
        assertEquals(HttpMethod.Patch, seen.single().method)
        assertEquals("/v1/settings/claude", seen.single().url.encodedPath)
        assertEquals("""{"fastModeDefault":true}""", lastBody())
        assertTrue(s.fastModeDefault)
    }

    /**
     * ⚠ THREE DIFFERENT BODIES FOR THREE DIFFERENT MEANINGS. No `fast` key leaves
     * the chat's choice alone; an explicit `null` restores the host default; a
     * boolean pins it. Collapsing the first two would make "back to default"
     * impossible to say.
     */
    @Test
    fun `a chat's fast choice is tri-state on the wire`() = runTest {
        val c = client { respond("""{"id":"c1","fast":true}""", HttpStatusCode.OK) }
        c.updateChat("c1", model = "opus")
        assertEquals("""{"model":"opus"}""", lastBody(), "no fast key when nothing about fast changed")
        c.updateChat("c1", fast = ChatFast.ON)
        assertEquals("""{"fast":true}""", lastBody())
        c.updateChat("c1", fast = ChatFast.OFF)
        assertEquals("""{"fast":false}""", lastBody())
        val d = c.updateChat("c1", fast = ChatFast.DEFAULT)
        assertEquals("""{"fast":null}""", lastBody(), "an explicit null is how the default is restored")
        assertEquals(true, d.fast, "and the answer carries the chat's stored choice")
        assertEquals("/v1/chats/c1", seen.last().url.encodedPath)
        assertEquals(HttpMethod.Patch, seen.last().method)
    }
}
