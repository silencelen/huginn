package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.SpawnRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two non-2xx answers the projects routes treat as ANSWERS, and the one they
 * still treat as a failure.
 *
 * Both features ship onto a fleet of daemons that already exist, so the absence
 * of a route is an ordinary state of the world rather than a fault — and both
 * refusals that matter arrive with the whole fix inside them. Getting either of
 * those onto the exception path turns a sentence a person can act on into a red
 * line that says "HTTP 409".
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ProjectClientTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "test-token" },
        engine = MockEngine { request -> seen += request; respond(request) },
    )

    // ------------------------------------------------------- the feature probe

    /**
     * ⚠ NULL AND EMPTY ARE DIFFERENT ANSWERS, and this is the whole reason the
     * signature is nullable. A daemon that has never heard of projects 404s; one
     * that knows the feature and holds none answers `{"projects":[]}`. The shells
     * hide every control on the first and draw an empty state on the second, so
     * collapsing them would put a permanently empty screen on every older host.
     */
    @Test
    fun `an older daemon's 404 is a null, not an error`() = runTest {
        val c = client { respond("""{"error":"not found"}""", HttpStatusCode.NotFound) }
        assertNull(c.projects(), "a 404 means the feature is absent")
        assertNull(c.consoles(), "same contract on the consoles route")
    }

    @Test
    fun `a daemon that has the feature and no projects answers an empty list`() = runTest {
        val c = client { respond("""{"projects":[]}""", HttpStatusCode.OK) }
        assertEquals(emptyList(), c.projects(), "present and empty is not absent")
    }

    @Test
    fun `any other failure on the probe still throws`() = runTest {
        // A 401 is a token problem and belongs on the failure path with its own
        // message; swallowing it would hide Projects behind a login error.
        val c = client { respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<HuginnClient.HuginnException> { c.projects() }
        assertEquals(401, e.code)
        assertEquals("unauthorized", e.message)
    }

    // ------------------------------------------------------ the 409s that answer

    /**
     * ⚠ THE UNTRUSTED DIRECTORY, WHICH IS THE COMMONEST REFUSAL THERE IS. Claude
     * Code will not start in a directory it has not been trusted in, and the
     * daemon's sentence about it IS the fix. Thrown, it arrives as a red line and
     * takes the typed name with it; answered, the sheet keeps everything and puts
     * the instruction under the field.
     */
    @Test
    fun `an untrusted directory comes back as an answer with the daemon's own words`() = runTest {
        val why = "/mnt/scratch is not trusted — open it in Claude Code once, then create the project"
        val c = client { respond("""{"error":${quote(why)}}""", HttpStatusCode.Conflict) }
        val created = c.createProject("w3 verify", "/mnt/scratch")
        assertFalse(created.ok)
        assertNull(created.project)
        assertEquals(why, created.refusal, "verbatim: the fix is in the sentence")
    }

    @Test
    fun `a created project comes back whole`() = runTest {
        val c = client {
            respond(
                """{"id":"6f0d2c41-0000-4000-8000-0000000000a1","name":"w3 verify",
                   "cwd":"/root/netplan","createdAt":1789459900,
                   "lead":{"name":"w3-verify/lead","sessionId":null},"members":[]}""",
                HttpStatusCode.Created,
            )
        }
        val created = c.createProject("w3 verify")
        assertTrue(created.ok)
        assertEquals("w3-verify/lead", created.project?.lead?.name)
        assertNull(created.refusal)
        assertEquals("POST", seen.last().method.value)
    }

    /**
     * ⚠ AND THE STOP SENTINEL, which is a state of the house rather than a fault
     * in the request. Spawning twelve sessions into a red usage window is how a
     * cluster dies half-born; the refusal belongs on the card as a line.
     */
    @Test
    fun `a spawn refused under STOP comes back as an answer too`() = runTest {
        val why = "the host is holding new sessions while usage is red"
        val c = client { respond("""{"error":${quote(why)}}""", HttpStatusCode.Conflict) }
        val out = c.spawnMembers("p1", listOf(SpawnRequest("p/docs", "docs", "write the README")))
        assertFalse(out.ok)
        assertEquals(why, out.refusal)
        assertTrue(out.results.isEmpty(), "nothing was started, so nothing is reported as started")
    }

    @Test
    fun `a partial spawn keeps every member's own outcome`() = runTest {
        val c = client {
            respond(
                """{"results":[{"name":"p/db","ok":true},
                   {"name":"p/docs","ok":false,"error":"a session called p-docs already exists"}]}""",
                HttpStatusCode.OK,
            )
        }
        val out = c.spawnMembers(
            "p1",
            listOf(SpawnRequest("p/db", "db", "…"), SpawnRequest("p/docs", "docs", "…")),
        )
        assertTrue(out.ok, "the CALL succeeded; two of the members are a separate question")
        assertEquals(2, out.results.size)
        assertEquals("a session called p-docs already exists", out.results[1].error)
    }

    @Test
    fun `a message answers with the queue depth and what it is waiting on`() = runTest {
        val c = client { respond("""{"queued":1,"delivered":false,"blockedBy":"turn"}""", HttpStatusCode.OK) }
        val r = c.messageProject("p1", from = "p/lead", to = "p/docs", text = "status?")
        assertEquals(1, r.queued)
        assertFalse(r.delivered)
        assertEquals("turn", r.blockedBy)
    }

    /** JSON string literal, so a refusal containing a quote cannot break the fixture. */
    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
