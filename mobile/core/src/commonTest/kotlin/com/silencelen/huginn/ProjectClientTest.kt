package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
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
 * The non-2xx answers the projects and consoles routes treat as ANSWERS, the one
 * they still treat as a failure, and the two bodies a client has to get exactly
 * right on the way out.
 *
 * Both features ship onto a fleet of daemons that already exist, so the absence
 * of a route is an ordinary state of the world rather than a fault — and every
 * refusal that matters arrives with the whole fix inside it. Getting any of
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

    /** The JSON this client actually put on the wire. The HuginnClientTest shape. */
    private fun lastBody(): String = (seen.last().body as TextContent).text

    // ------------------------------------------------------- the feature probe

    /**
     * ⚠ NULL AND EMPTY ARE DIFFERENT ANSWERS, and this is the whole reason the
     * signature is nullable. A daemon that has never heard of projects 404s; one
     * that knows the feature and holds none answers `{"projects":[],"max":64}`.
     * The shells hide every control on the first and draw an empty state on the
     * second, so collapsing them would put a permanently empty screen on every
     * older host.
     */
    @Test
    fun `an older daemon's 404 is a null, not an error`() = runTest {
        val c = client { respond("""{"error":"not found"}""", HttpStatusCode.NotFound) }
        assertNull(c.projects(), "a 404 means the feature is absent")
        assertNull(c.consoles(), "same contract on the consoles route")
    }

    @Test
    fun `a daemon that has the feature and no projects answers an empty list with its cap`() = runTest {
        val c = client { respond("""{"projects":[],"max":64}""", HttpStatusCode.OK) }
        val list = c.projects()
        assertEquals(emptyList(), list?.projects, "present and empty is not absent")
        assertEquals(64, list?.max, "the cap is on the wire so a client can say what it is")
    }

    @Test
    fun `archived projects are asked for explicitly, because the daemon leaves them out`() = runTest {
        val c = client { respond("""{"projects":[],"max":64}""", HttpStatusCode.OK) }
        c.projects()
        assertEquals("/v1/projects", seen.last().url.encodedPath + (seen.last().url.encodedQuery.ifEmpty { "" }))
        c.projects(all = true)
        assertTrue(seen.last().url.toString().endsWith("/v1/projects?all=1"), seen.last().url.toString())
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
     * takes the typed brief with it; answered, the sheet keeps everything and
     * puts the instruction under the field.
     */
    @Test
    fun `an untrusted directory comes back as an answer with the daemon's own words`() = runTest {
        val why = "/mnt/scratch has not been trusted in Claude Code yet — open it once with " +
            "`claude` there and accept the folder-trust question, then create the project"
        val c = client { respond("""{"error":${quote(why)}}""", HttpStatusCode.Conflict) }
        val created = c.createProject("w3 verify", "infra", "check the wave", "/mnt/scratch")
        assertFalse(created.ok)
        assertNull(created.project)
        assertEquals(why, created.refusal, "verbatim: the fix is in the sentence")
    }

    /**
     * ⚠ THE CREATE BODY IS FOUR FIELDS AND THE BRIEF IS ONE OF THEM. The daemon
     * refuses a create without a brief, because the brief is typed straight into
     * the lead's composer as the whole first message it gets.
     */
    @Test
    fun `a created project comes back whole, and the brief went with the request`() = runTest {
        val c = client {
            respond(
                """{"id":"6f0d2c41-0000-4000-8000-0000000000a1","name":"w3 verify","slug":"w3-verify",
                   "kind":"infra","status":"drafting","brief":"check the wave","cwd":"/root/netplan",
                   "createdAt":1789459900,"updatedAt":1789459900,"rev":1,
                   "lead":{"role":"lead","name":"w3-verify-lead","claudeName":"w3-verify/lead","sessionId":null},
                   "members":[]}""",
                HttpStatusCode.Created,
            )
        }
        val created = c.createProject("w3 verify", "infra", "check the wave")
        assertTrue(created.ok)
        assertEquals("w3-verify-lead", created.project?.lead?.name, "the TMUX name")
        assertEquals("w3-verify/lead", created.project?.lead?.claudeName, "the PEER name")
        assertEquals("w3-verify", created.project?.slug)
        assertEquals("drafting", created.project?.status)
        assertNull(created.refusal)
        assertEquals("POST", seen.last().method.value)
        val body = lastBody()
        assertTrue(body.contains("\"kind\":\"infra\""), body)
        assertTrue(body.contains("\"brief\":\"check the wave\""), body)
    }

    /**
     * ⚠ AND THE STOP SENTINEL, which is a state of the house rather than a fault
     * in the request. Spawning twelve sessions into a red usage window is how a
     * cluster dies half-born; the refusal belongs on the card as a line.
     */
    @Test
    fun `a spawn refused under STOP comes back as an answer too`() = runTest {
        val why = "there is no room on this account right now (the weekly window is spent) " +
            "— spawn when the window resets"
        val c = client { respond("""{"error":${quote(why)}}""", HttpStatusCode.Conflict) }
        val out = c.spawnProject("p1", manifestRev = 1)
        assertFalse(out.ok)
        assertEquals(why, out.refusal)
        assertTrue(out.spawned.isEmpty(), "nothing was started, so nothing is reported as started")
        assertNull(out.project)
    }

    /**
     * ⚠ THE STALE REV CARRIES THE CURRENT PROJECT. A notification that has been
     * sitting on a lock screen while the lead revised its plan must not spawn the
     * revision — and the card has to be able to redraw itself around the plan
     * that is actually on offer.
     */
    @Test
    fun `a stale manifest rev comes back with the project the card should redraw`() = runTest {
        val c = client {
            respond(
                """{"error":"the proposal has changed since that card was drawn",
                   "project":{"id":"p1","name":"Status page flap","slug":"statusflap","status":"proposed",
                              "manifest":{"rev":3,"summary":"four sessions now","sessions":[
                                {"role":"db","firstPrompt":"go"}]}}}""",
                HttpStatusCode.Conflict,
            )
        }
        val out = c.spawnProject("p1", manifestRev = 2)
        assertFalse(out.ok)
        assertEquals("the proposal has changed since that card was drawn", out.refusal)
        assertEquals(3, out.project?.manifest?.rev, "the rev the owner has not seen yet")
    }

    /**
     * ⚠⚠ THE SPAWN BODY IS THE APPROVAL AND THE REV, AND NOTHING ELSE. The
     * manifest on the daemon IS the plan; a client that re-sent the roles would
     * be approving a copy of a proposal rather than the proposal.
     */
    @Test
    fun `a spawn sends approve and the rev, and never the roles`() = runTest {
        val c = client { respond("""{"ok":true,"spawned":[],"failed":[]}""", HttpStatusCode.OK) }
        c.spawnProject("p1", manifestRev = 7)
        val body = lastBody()
        assertTrue(body.contains("\"approve\":true"), body)
        assertTrue(body.contains("\"manifestRev\":7"), body)
        assertFalse(body.contains("role"), "the plan is the daemon's, not ours")
        assertFalse(body.contains("firstPrompt"))
    }

    /**
     * ⚠⚠ ok:false ON A 200 IS THE NORMAL CASE. Spawning is a loop over tmux, and
     * a client that read the status code as the verdict would report a working
     * cluster as a failure.
     */
    @Test
    fun `a partial spawn keeps every role's own outcome, on a 200`() = runTest {
        val c = client {
            respond(
                """{"ok":false,
                   "spawned":[{"role":"db","name":"p-db","claudeName":"p/db"}],
                   "failed":[{"role":"docs","reason":"duplicate session: p-docs"}],
                   "project":{"id":"p1","status":"active"}}""",
                HttpStatusCode.OK,
            )
        }
        val out = c.spawnProject("p1", manifestRev = 1)
        assertTrue(out.ok, "the CALL succeeded; two of the roles are a separate question")
        assertFalse(out.result!!.ok, "and the daemon says plainly that it did not all happen")
        assertEquals(1, out.spawned.size)
        assertEquals("duplicate session: p-docs", out.failed.single().reason)
        assertEquals("active", out.result!!.project?.status)
    }

    // ------------------------------------------------------- the save contract

    /**
     * ⚠ A STALE `rev` COMES BACK AS THE CURRENT PROJECT, bare — the saveScratchpad
     * contract, which both shells already know how to adopt.
     */
    @Test
    fun `a save that lost a race is answered with the project it lost to`() = runTest {
        val c = client {
            respond(
                """{"id":"p1","name":"Halfway","slug":"half","status":"active","rev":9}""",
                HttpStatusCode.Conflict,
            )
        }
        val save = c.saveProject("p1", rev = 8, name = "Halfway")
        assertTrue(save.conflict)
        assertFalse(save.ok)
        assertEquals(9, save.project?.rev, "the current copy, so the editor can adopt it")
        assertNull(save.refusal)
    }

    /**
     * ⚠ AND THE OTHER 409 IS NOT A RACE. "an active project cannot become
     * proposed" is a refusal of the request, arrives with no project in it, and
     * must not be adopted as though somebody else had saved first.
     */
    @Test
    fun `an illegal status move is a refusal, not a conflict`() = runTest {
        val c = client {
            respond("""{"error":"a active project cannot become proposed"}""", HttpStatusCode.Conflict)
        }
        val save = c.saveProject("p1", rev = 9, status = "proposed")
        assertFalse(save.conflict, "there is no current copy in this body to adopt")
        assertFalse(save.ok)
        assertNull(save.project)
        assertEquals("a active project cannot become proposed", save.refusal)
    }

    @Test
    fun `an edited manifest travels as the parsed shape, not as a fence`() = runTest {
        // The daemon re-validates it with the SAME parser the lead's own block
        // goes through — the client's editor is a convenience, not an authority.
        val c = client { respond("""{"id":"p1","rev":10}""", HttpStatusCode.OK) }
        c.saveProject(
            "p1",
            rev = 9,
            manifest = com.silencelen.huginn.data.ProjectManifest(
                summary = "two sessions",
                scope = "rebuild it",
                sessions = listOf(
                    com.silencelen.huginn.data.ManifestSession(role = "db", firstPrompt = "profile it"),
                ),
            ),
        )
        val body = lastBody()
        assertTrue(body.contains("\"manifest\""), body)
        assertTrue(body.contains("\"firstPrompt\":\"profile it\""), body)
        assertFalse(body.contains("huginn-project"), "never a fence — the daemon wraps it itself")
    }

    // -------------------------------------------------------------- the rest

    @Test
    fun `a message answers with the queue depth and what it is waiting on`() = runTest {
        val c = client {
            respond(
                """{"ok":true,"to":"p/docs","from":"p/lead","delivered":false,"queued":1,"blockedBy":"modal"}""",
                HttpStatusCode.OK,
            )
        }
        val r = c.messageProject("p1", from = "lead", to = "docs", text = "status?")
        assertEquals(1, r.queued)
        assertFalse(r.delivered)
        assertEquals("modal", r.blockedBy)
        assertEquals("p/docs", r.to, "the peer names, echoed back")
    }

    /**
     * ⚠ THE DEFAULT ENDS NOTHING. A delete that silently killed twelve live
     * sessions is not a delete anybody meant, so the client sends no `end` unless
     * it was asked for one.
     */
    @Test
    fun `a delete ends nothing unless it was told to`() = runTest {
        val c = client { respond("""{"ok":true,"ended":[],"mode":"none"}""", HttpStatusCode.OK) }
        val r = c.deleteProject("p1")
        assertEquals("none", r.mode)
        assertTrue(r.ended.isEmpty())
        assertFalse(lastBody().contains("end"), "nothing asked for an end")

        val c2 = client {
            respond("""{"ok":true,"ended":["p-db","p-web"],"mode":"graceful"}""", HttpStatusCode.OK)
        }
        val r2 = c2.deleteProject("p1", end = "graceful")
        assertEquals(listOf("p-db", "p-web"), r2.ended)
        assertTrue(lastBody().contains("\"end\":\"graceful\""), lastBody())
    }

    @Test
    fun `discarding a proposal answers with the project, back in drafting`() = runTest {
        val c = client {
            respond("""{"id":"p1","status":"drafting","manifest":{"rev":2}}""", HttpStatusCode.OK)
        }
        val p = c.discardProposal("p1")
        assertEquals("drafting", p.status)
        // ⚠ THE MANIFEST IS KEPT AT ITS REV so Edit can still open it. Only the
        // status moves.
        assertEquals(2, p.manifest?.rev)
    }

    /** JSON string literal, so a refusal containing a quote cannot break the fixture. */
    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
