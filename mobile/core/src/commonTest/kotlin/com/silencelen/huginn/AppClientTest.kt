package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `/v1/apps` routes: the probe that hides the feature, the ONE-RELEASE
 * fallback to the name it used to have, and the two non-2xx answers that are
 * answers rather than failures.
 *
 * ⚠⚠ 422 IS AN ANSWER, AND IT IS THE NEW ONE. Adding an app now has a
 * PREREQUISITE (decision 54): the daemon probes the address on every address
 * huginn itself answers on, and refuses the add until the app answers on all of
 * them. A client that let that throw would drop the form and the typed address
 * with it, and the person would have no idea what was missing — which is the
 * whole thing the refusal exists to tell them.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AppClientTest {

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "test-token" },
        engine = MockEngine { request -> seen += request; respond(request) },
    )

    /**
     * ⚠ BOTH NAMES HAVE TO 404 BEFORE THE FEATURE IS ABSENT. `/v1/apps` alone
     * answering 404 says nothing about a daemon that is simply older than the
     * rename, and hiding the page against one of those would take four working
     * rows off the screen.
     */
    @Test
    fun `the feature is absent only when BOTH names are gone`() = runTest {
        val c = client { respond("""{"error":"not found"}""", HttpStatusCode.NotFound) }
        assertNull(c.apps(), "a 404 on both routes means the feature is absent")
        assertEquals(
            listOf("/v1/apps", "/v1/consoles"),
            seen.map { it.url.encodedPath },
            "it asks for the new name first and only then for the old one",
        )
    }

    /**
     * ⚠ THE ONE-RELEASE FALLBACK (decision 56). A daemon that still only serves
     * `/v1/consoles` sends the same rows under the old array name and without the
     * new fields; they land as apps with `icon = false` and no reachability
     * verdict, which is exactly what is true of them.
     */
    @Test
    fun `a daemon that only has consoles still fills the Apps page`() = runTest {
        val c = client { request ->
            if (request.url.encodedPath == "/v1/apps") respond("""{"error":"not found"}""", HttpStatusCode.NotFound)
            else respond(
                """{"consoles":[{"id":"armap","name":"Architecture map","url":"http://huginn:8088/",
                       "kind":"docs","addedAt":1789000000,"version":1,"up":true,
                       "lastProbeAt":1789459940,"latencyMs":12,"httpStatus":200,"reachableFrom":"host"}],
                   "max":32,"kinds":["dashboard","tool","docs","lab","other"],
                   "reachableFrom":"host","probeIntervalMs":300000,
                   "approval":{"applied":true,"runBy":"owner","steps":[]}}""",
                HttpStatusCode.OK,
            )
        }
        val list = c.apps()!!
        assertEquals(1, list.apps.size)
        assertEquals("armap", list.apps.first().id)
        assertEquals(32, list.max)
        assertEquals(listOf("dashboard", "tool", "docs", "lab", "other"), list.kinds)
        assertFalse(list.apps.first().icon, "an old daemon serves no icons, so no row claims one")
        assertNull(list.apps.first().reachable.ok, "and it has no verdict about your devices")
        assertTrue(list.retrofitApplied, "the old approval marker is the same fact under a new name")
        assertTrue(list.clientAddresses.isEmpty(), "an old daemon does not know what they are")
    }

    @Test
    fun `the list carries the registry's caps, its kinds and the client addresses`() = runTest {
        val c = client {
            respond(
                """{"apps":[],"max":32,"kinds":["dashboard","tool","docs","lab","other"],
                   "retrofitApplied":false,"clientAddresses":["192.168.7.31","100.97.198.90"]}""",
                HttpStatusCode.OK,
            )
        }
        val list = c.apps()!!
        assertTrue(list.apps.isEmpty(), "present and empty is not absent")
        assertEquals(32, list.max)
        assertFalse(list.retrofitApplied)
        assertEquals(listOf("192.168.7.31", "100.97.198.90"), list.clientAddresses)
        assertEquals("/v1/apps", seen.single().url.encodedPath, "the new name answered, so nothing else was asked")
    }

    /**
     * ⚠⚠ THE 422 CARRIES THE FIX, AND THE FIX IS THE POINT. The daemon refused
     * the add because the app does not answer where this person's devices arrive;
     * the lines it sends back are what makes it answer. They must reach the form
     * verbatim rather than through an exception's message, which is one string
     * with the list flattened out of it.
     */
    @Test
    fun `a failed prerequisite comes back as an answer with its fix lines`() = runTest {
        val c = client {
            respond(
                """{"error":"boardserver answers here but not on the addresses your devices arrive from",
                   "reachable":{"ok":false,"checkedAt":1789460000,
                     "addresses":[{"addr":"127.0.0.1:8092","ok":true},
                                  {"addr":"192.168.7.31:8092","ok":false,"error":"connection refused"}],
                     "fix":["systemctl edit boardserver.service","systemctl restart boardserver"]}}""",
                HttpStatusCode.UnprocessableEntity,
            )
        }
        val made = c.createApp("Board view", "http://huginn:8092/", kind = "tool")
        assertFalse(made.ok, "the add did not happen")
        assertNull(made.app)
        assertEquals(
            "boardserver answers here but not on the addresses your devices arrive from",
            made.refusal,
        )
        assertEquals(false, made.reachable?.ok)
        assertContentEquals(
            listOf("systemctl edit boardserver.service", "systemctl restart boardserver"),
            made.reachable?.fix,
            "the lines arrive as lines, in the daemon's order",
        )
        assertEquals(2, made.reachable?.addresses?.size)
        val failed = made.reachable!!.addresses.first { !it.ok }
        assertEquals("192.168.7.31:8092", failed.addr)
        assertEquals("connection refused", failed.error)
        assertEquals("POST", seen.last().method.value)
    }

    /**
     * ⚠ A BRAND NEW ROW HAS NEVER BEEN PROBED, WHICH IS NOT THE SAME AS BEING
     * DOWN. The daemon answers the create and lets the sweep find out whether
     * anything is there.
     */
    @Test
    fun `an accepted add comes back with no verdict on it`() = runTest {
        val c = client {
            respond(
                """{"id":"board-view","name":"Board view","url":"http://huginn:8092/","kind":"tool",
                   "notes":"KiCad","unit":"boardserver.service","addedAt":1789460000,"version":1,
                   "up":null,"lastProbeAt":0,"latencyMs":null,"httpStatus":null,"icon":false,
                   "reachable":{"ok":true,"checkedAt":1789460000,"addresses":[],"fix":[]}}""",
                HttpStatusCode.Created,
            )
        }
        val made = c.createApp("Board view", "http://huginn:8092/", kind = "tool", notes = "KiCad", unit = "boardserver.service")
        assertTrue(made.ok)
        assertNull(made.refusal)
        assertEquals("board-view", made.app!!.id, "the id is derived from the name when none is given")
        assertEquals("boardserver.service", made.app!!.unit)
        assertNull(made.app!!.up, "never probed is not down")
        assertEquals(true, made.app!!.reachable.ok, "it got in because it answered everywhere")
    }

    /**
     * ⚠ AND SO IS THE 409, which arrives carrying the row that already holds the
     * id. Renaming is one keystroke; emptying the form to say "that name is
     * taken" would make the person type the address again as well.
     */
    @Test
    fun `a taken name comes back as an answer carrying the row that has it`() = runTest {
        val c = client {
            respond(
                """{"error":"an app called board-view is already listed",
                   "app":{"id":"board-view","name":"Board view","url":"http://huginn:8092/","version":5}}""",
                HttpStatusCode.Conflict,
            )
        }
        val made = c.createApp("Board view", "http://huginn:8092/")
        assertFalse(made.ok)
        assertNull(made.app)
        assertEquals("an app called board-view is already listed", made.refusal)
        assertEquals("board-view", made.existing?.id, "the row that has the name comes with the refusal")
        assertNull(made.reachable, "nothing was probed — the id never got that far")
    }

    /**
     * ⚠⚠ A NULL VERDICT WITH A REASON. On this daemon `reachable.ok = null` means
     * the PROBE SET WAS EMPTY — no usable bind address and no tailnet address to
     * try — and `note` is the only thing that says so. Dropped, the row reads as
     * "not checked yet" forever and nobody finds out why.
     */
    @Test
    fun `the reachability note survives, because it is why there is no verdict`() = runTest {
        val c = client {
            respond(
                """{"apps":[{"id":"armap","name":"Architecture map","url":"http://huginn:8088/",
                       "unit":"","version":1,"up":null,"lastProbeAt":0,"icon":false,
                       "reachable":{"ok":null,"checkedAt":0,"addresses":[],"fix":[],
                                    "note":"no address to probe yet"}}],
                   "max":32,"kinds":[],"retrofitApplied":false,"clientAddresses":[]}""",
                HttpStatusCode.OK,
            )
        }
        val row = c.apps()!!.apps.single()
        assertNull(row.reachable.ok)
        assertEquals("no address to probe yet", row.reachable.note)
        assertEquals("", row.unit, "the daemon sends an empty string for none, never null")
    }

    @Test
    fun `an address the daemon refuses outright is still a refusal of the request`() = runTest {
        // A 400 about the URL is not a state of the world, it is a no.
        val c = client { respond("""{"error":"that address is not on this host"}""", HttpStatusCode.BadRequest) }
        val e = assertFailsWith<HuginnClient.HuginnException> { c.createApp("X", "https://example.com/") }
        assertEquals(400, e.code, "a 400 is the one add answer that is still a throw")
        assertEquals("that address is not on this host", e.message)
    }

    /**
     * ⚠ THE 409 IS AN ANSWER TOO, and it arrives carrying the row the daemon now
     * holds — the `saveScratchpad` shape. Two clients editing one registry is the
     * ordinary case.
     */
    @Test
    fun `a stale version comes back as the current row, not as a throw`() = runTest {
        val c = client {
            respond(
                """{"error":"that app was edited somewhere else",
                   "app":{"id":"armap","name":"Architecture map","url":"http://huginn:8088/",
                          "version":7,"up":null,"lastProbeAt":0,"icon":true}}""",
                HttpStatusCode.Conflict,
            )
        }
        val save = c.saveApp("armap", version = 3, name = "Armap")
        assertTrue(save.conflict)
        assertEquals(7, save.app.version, "the editor adopts the version it was handed")
        assertEquals("Architecture map", save.app.name)
        assertEquals("that app was edited somewhere else", save.refusal)
        assertEquals("PATCH", seen.last().method.value)
    }

    @Test
    fun `an accepted edit is not a conflict`() = runTest {
        val c = client {
            respond(
                """{"id":"armap","name":"Armap","url":"http://huginn:8088/","version":4,
                   "up":true,"lastProbeAt":1789459940,"icon":true}""",
                HttpStatusCode.OK,
            )
        }
        val save = c.saveApp("armap", version = 3, name = "Armap")
        assertFalse(save.conflict)
        assertEquals(4, save.app.version)
        assertNull(save.refusal)
    }

    @Test
    fun `a probe answers with the refreshed row`() = runTest {
        val c = client {
            respond(
                """{"id":"board","name":"PCB board view","url":"http://huginn:8092/","version":1,
                   "up":false,"lastProbeAt":1789459999,"latencyMs":2000,"httpStatus":null,"icon":true,
                   "reachable":{"ok":false,"checkedAt":1789459999,"addresses":[],
                                "fix":["systemctl restart boardserver"]}}""",
                HttpStatusCode.OK,
            )
        }
        val row = c.probeApp("board")
        assertEquals(false, row.up)
        assertEquals(1789459999L, row.lastProbeAt)
        assertEquals(listOf("systemctl restart boardserver"), row.reachable.fix)
        assertEquals("POST", seen.last().method.value)
        assertEquals("/v1/apps/board/probe", seen.last().url.encodedPath)
    }

    @Test
    fun `an app delete is a call, and a second one is a 404 that throws`() = runTest {
        val c = client { respond("""{"ok":true}""", HttpStatusCode.OK) }
        c.deleteApp("board-view")
        assertEquals("DELETE", seen.last().method.value)
        assertEquals("/v1/apps/board-view", seen.last().url.encodedPath)

        val gone = client { respond("""{"error":"no such app"}""", HttpStatusCode.NotFound) }
        val e = assertFailsWith<HuginnClient.HuginnException> { gone.deleteApp("board-view") }
        assertEquals(404, e.code, "a second delete is not a second success")
    }

    /**
     * The icon rides the bearer, like `/v1/files/image` — it is not a public URL
     * a browser could be pointed at, so the row cannot draw it with an `AsyncImage`
     * and has to fetch it the way the transcript fetches a thumbnail.
     */
    @Test
    fun `an icon is fetched with the bearer and a missing one is a 404 that throws`() = runTest {
        val c = client {
            respond(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "image/png"),
            )
        }
        val bytes = c.appIconBytes("armap")
        assertEquals(4, bytes.size)
        assertEquals("/v1/apps/armap/icon", seen.last().url.encodedPath)
        assertEquals("Bearer test-token", seen.last().headers[HttpHeaders.Authorization])

        val none = client { respond("""{"error":"no icon"}""", HttpStatusCode.NotFound) }
        val e = assertFailsWith<HuginnClient.HuginnException> { none.appIconBytes("btc15m") }
        assertEquals(404, e.code, "the loader turns this into the initial-letter tile")
    }
}
