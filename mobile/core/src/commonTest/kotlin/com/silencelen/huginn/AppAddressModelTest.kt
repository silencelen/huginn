package com.silencelen.huginn

import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppAddress
import com.silencelen.huginn.data.AppList
import com.silencelen.huginn.data.AppReachability
import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteHealth
import com.silencelen.huginn.ui.AppRules
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client half of the app-ADDRESS model, after the 2026-10-02 breaker round:
 * which routes a device reports, how the "Also check from" field splits, and
 * what the client says when the daemon refuses or drops what it was sent.
 *
 * The daemon half is server/appd/test/apps-address-model.test.js; the two must
 * agree on separators, de-duplication, the limit and the host rule.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AppAddressModelTest {

    // ------------------------------------------------- the Also-check field

    @Test
    fun `the field splits on any whitespace a paste can carry, like the daemon does`() {
        // 2026-10-02: NBSP, VT and FF were not separators here, so a pasted
        // "10.0.0.1<NBSP>10.0.0.2" went up as ONE address and came back a 400.
        for (sep in listOf(" ", "\u000b", "\u000c", " ", "　", "\r\n")) {
            assertEquals(listOf("10.0.0.1", "10.0.0.2"), AppRules.splitAddresses("10.0.0.1${sep}10.0.0.2"), "sep U+${sep[0].code.toString(16)}")
        }
    }

    @Test
    fun `the field de-duplicates the way the daemon counts`() {
        // 2026-10-02: "FD00::1 fd00::1 [fd00::1]" stayed three entries here and
        // was one address on the daemon, so the 8-address limit disagreed.
        assertEquals(listOf("FD00::1"), AppRules.splitAddresses("FD00::1 fd00::1 [fd00::1] fd00:0:0:0:0:0:0:1"))
        assertEquals(8, AppRules.MAX_APP_ADDRS)
    }

    // ------------------------------------------------- which routes are reported

    @Test
    fun `a migrated seed nobody chose and the device never reached is not reported`() {
        // 2026-10-02: an install that talked on the mesh address reported the
        // migrated Tailscale literal 100.97.198.90 too — gone from the host since
        // 09-30 — and every app add became a 422 against it. That literal is no
        // longer a seed at all (AppdRoutes.RETIRED); the migrated seed nobody chose
        // is now the LAN address, and the rule is the same.
        val book = AppdRoutes.migrate("http://192.168.2.117:8787", routePinned = false)
        assertEquals(listOf("192.168.2.117"), AppRules.routeHosts(book, emptyMap()))
        // Once it has actually worked from this device it is a route the device uses.
        val lan = book.routes.first { it.url.contains("192.168.7.117") }
        assertEquals(
            listOf("192.168.2.117", "192.168.7.117"),
            AppRules.routeHosts(book, mapOf(lan.id to RouteHealth(lastOkAt = 5))),
        )
        // But one that worked once and has FAILED since is history, not a route.
        assertEquals(
            listOf("192.168.2.117"),
            AppRules.routeHosts(book, mapOf(lan.id to RouteHealth(lastOkAt = 5, lastFailAt = 9))),
        )
        // A route a person typed is reported whether or not it has answered yet.
        val typed = RouteBook(routes = listOf(PinnedRoute(id = "m", name = "mesh", url = "http://[FD00::117]:8787")))
        assertEquals(listOf("fd00::117"), AppRules.routeHosts(typed, emptyMap()), "v6 without brackets, case folded")
    }

    @Test
    fun `a route host the daemon would refuse is held back and named, not sent to be dropped`() {
        // 2026-10-02: https://huginn.jnet.ad was sent, silently dropped by the
        // daemon, and the reply thrown away — nobody was told.
        val book = RouteBook(
            routes = listOf(
                PinnedRoute(id = "a", name = "lan", url = "http://192.168.2.117:8787"),
                PinnedRoute(id = "b", name = "jnet", url = "https://huginn.jnet.ad"),
                PinnedRoute(id = "c", name = "ts", url = "https://huginn.tail1234.ts.net"),
            ),
            activeId = "a",
        )
        val r = AppRules.routeReport(book, emptyMap())
        assertEquals(listOf("192.168.2.117", "huginn.tail1234.ts.net"), r.hosts)
        assertEquals(listOf("huginn.jnet.ad"), r.refused)
        val notice = AppRules.routeNotice(r.refused)
        assertNotNull(notice)
        assertTrue("huginn.jnet.ad" in notice, notice)
        assertNull(AppRules.routeNotice(emptyList()))
        assertEquals(null, AppRules.addressProblem("10.0.0.1"))
        assertNotNull(AppRules.addressProblem("127.0.0.3#"), "a bare host only, like the daemon")
        assertNotNull(AppRules.addressProblem("10.0.0.1:80"))
    }

    @Test
    fun `the page note carries the route notice beside the retrofit line`() {
        val list = AppList()
        assertNull(AppRules.pageNote(list, null))
        assertEquals("x", AppRules.pageNote(list, "x"))
    }

    // ------------------------------------------------- the wire

    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "test-token" },
        engine = MockEngine { request -> seen += request; respond(request) },
    )

    @Test
    fun `a report answer says what was refused, and an older daemon is not an error`() = runTest {
        val refused = client {
            respond(
                """{"error":"'huginn.jnet.ad' is not an address huginn can check from","refused":[{"addr":"huginn.jnet.ad","why":"x"}]}""",
                HttpStatusCode.BadRequest,
            )
        }.reportRoutes(listOf("huginn.jnet.ad"))
        assertTrue(refused.supported)
        assertEquals(listOf("huginn.jnet.ad"), refused.refused)

        val old = client { respond("""{"error":"no such app"}""", HttpStatusCode.NotFound) }.reportRoutes(listOf("10.0.0.1"))
        assertFalse(old.supported, "a 3.8 daemon has no route to report to")

        val ok = client {
            respond("""{"fresh":["10.0.0.1"],"gone":[],"reported":[],"requiredAddresses":["127.0.0.1","10.0.0.1"]}""", HttpStatusCode.OK)
        }.reportRoutes(listOf("10.0.0.1"))
        assertTrue(ok.supported)
        assertEquals(emptyList(), ok.refused)

        // The partial answer: the valid hosts were applied, the refused one is named in the 200.
        val partial = client {
            respond(
                """{"fresh":["10.0.0.1"],"gone":[],"refused":[{"addr":"huginn.example","why":"x"}],"reported":[],"requiredAddresses":[]}""",
                HttpStatusCode.OK,
            )
        }.reportRoutes(listOf("10.0.0.1", "huginn.example"))
        assertTrue(partial.supported)
        assertEquals(listOf("huginn.example"), partial.refused)
    }

    // ------------------------------------------------- an older daemon drops addresses

    @Test
    fun `a row that comes back without the addresses that were sent is noticed`() {
        // 2026-10-02: appd 3.8 ignores `addresses` and still answers 201/200, so
        // the form said nothing while the list was thrown away.
        val sent = listOf("10.0.0.1")
        assertTrue(AppRules.addressesDropped(sent, App(id = "x", addresses = emptyList())))
        assertFalse(AppRules.addressesDropped(sent, App(id = "x", addresses = listOf("10.0.0.1"))))
        assertFalse(AppRules.addressesDropped(emptyList(), App(id = "x")))
        assertTrue(AppRules.ADDRESSES_DROPPED.isNotBlank())
        // And the field is only SENT to a daemon that keeps it, or when it has something in it.
        assertTrue(AppRules.supportsRowAddresses(AppList(requiredAddresses = listOf("127.0.0.1"))))
        assertFalse(AppRules.supportsRowAddresses(AppList()))
    }

    // ------------------------------------------------- an advisory miss

    @Test
    fun `an advisory miss is not a failing row`() {
        // 2026-10-02: a row read "reachable from your devices" with a "Why"
        // toggle beside it, because failing() counted addresses nobody requires.
        val reach = AppReachability(
            ok = true,
            addresses = listOf(
                AppAddress("127.0.0.1", ok = true, required = true),
                AppAddress("127.0.0.2", ok = false, error = "connection refused", required = false),
            ),
            note = "also not answering at 127.0.0.2",
        )
        val row = App(id = "a", reachable = reach)
        assertFalse(AppRules.failing(row))
        assertEquals("Details", AppRules.expandVerb(row))
        // A pre-3.9 daemon sends no `required`: every address counted then, and still does.
        assertTrue(AppRules.failing(App(id = "b", reachable = reach.copy(addresses = listOf(AppAddress("x", ok = false))))))
    }
}
