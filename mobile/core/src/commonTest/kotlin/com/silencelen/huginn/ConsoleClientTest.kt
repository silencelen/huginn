package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
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
 * The consoles routes: the probe that hides the feature, and the edit conflict
 * that is an answer rather than a failure.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ConsoleClientTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "test-token" },
        engine = MockEngine { request -> seen += request; respond(request) },
    )

    /**
     * ⚠ NULL AND EMPTY ARE DIFFERENT ANSWERS. A daemon that has never heard of
     * consoles 404s and every control is hidden (`consolesAvailable = false`, the
     * `padsAvailable` pattern); one that knows the feature and holds no rows
     * answers a list with an empty `consoles`, and that draws an empty state.
     */
    @Test
    fun `an older daemon's 404 hides the feature instead of raising an error`() = runTest {
        val c = client { respond("""{"error":"not found"}""", HttpStatusCode.NotFound) }
        assertNull(c.consoles(), "a 404 means the feature is absent")
    }

    @Test
    fun `the list carries the registry's caps and its approval`() = runTest {
        val c = client {
            respond(
                """{"consoles":[],"max":32,"kinds":["dashboard","tool","docs","lab","other"],
                   "reachableFrom":"host","probeIntervalMs":300000,
                   "approval":{"applied":false,"runBy":"owner","steps":[]}}""",
                HttpStatusCode.OK,
            )
        }
        val list = c.consoles()!!
        assertTrue(list.consoles.isEmpty(), "present and empty is not absent")
        assertEquals(32, list.max)
        assertEquals(300_000L, list.probeIntervalMs)
        assertEquals("owner", list.approval?.runBy)
    }

    /**
     * ⚠ THE 409 IS AN ANSWER, and it arrives carrying the row the daemon now
     * holds — the `saveScratchpad` shape. Two clients editing one registry is the
     * ordinary case, and a conflict that threw would make the editor lose an edit
     * to recover from a state it was handed everything to recover from.
     */
    @Test
    fun `a stale version comes back as the current row, not as a throw`() = runTest {
        val c = client {
            respond(
                """{"error":"that console was edited somewhere else",
                   "console":{"id":"armap","name":"Architecture map","url":"http://huginn:8088/",
                              "version":7,"up":null,"lastProbeAt":0}}""",
                HttpStatusCode.Conflict,
            )
        }
        val save = c.saveConsole("armap", version = 3, name = "Armap")
        assertTrue(save.conflict)
        assertEquals(7, save.console.version, "the editor adopts the version it was handed")
        assertEquals("Architecture map", save.console.name)
        assertEquals("that console was edited somewhere else", save.refusal)
        assertEquals("PATCH", seen.last().method.value)
    }

    @Test
    fun `an accepted edit is not a conflict`() = runTest {
        val c = client {
            respond(
                """{"id":"armap","name":"Armap","url":"http://huginn:8088/","version":4,
                   "up":true,"lastProbeAt":1789459940}""",
                HttpStatusCode.OK,
            )
        }
        val save = c.saveConsole("armap", version = 3, name = "Armap")
        assertFalse(save.conflict)
        assertEquals(4, save.console.version)
        assertNull(save.refusal)
    }

    @Test
    fun `a bad address is still a refusal of the request`() = runTest {
        // A 400 about the URL is not a state of the world, it is a no.
        val c = client { respond("""{"error":"that address is not on this host"}""", HttpStatusCode.BadRequest) }
        val e = assertFailsWith<HuginnClient.HuginnException> {
            c.saveConsole("armap", version = 3, url = "https://example.com/")
        }
        assertEquals(400, e.code)
        assertEquals("that address is not on this host", e.message)
    }

    @Test
    fun `a probe answers with the refreshed row`() = runTest {
        val c = client {
            respond(
                """{"id":"board","name":"PCB board view","url":"http://huginn:8092/","version":1,
                   "up":false,"lastProbeAt":1789459999,"latencyMs":null,"httpStatus":null}""",
                HttpStatusCode.OK,
            )
        }
        val row = c.probeConsole("board")
        assertEquals(false, row.up)
        assertEquals(1789459999L, row.lastProbeAt)
        assertEquals("POST", seen.last().method.value)
    }
}
