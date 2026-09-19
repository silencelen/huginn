package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.RouteHealth
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop twin of the phone's RouteWitnessTest: a route's "last reached" must
 * be kept current by ORDINARY traffic, not only by a probe.
 *
 * ⚠⚠ AND "ORDINARY TRAFFIC" DID NOT MEAN WHAT THIS FILE THOUGHT IT MEANT. The
 * only writers were the two success branches of `/v1/status`, and `/v1/status` is
 * polled only while the Status pane is open. Measured against the live daemon
 * through a logging proxy: over one window the client made 42 successful
 * `/v1/chats`, 42 `/v1/sessions`, 39 each of `/v1/rounds`, `/v1/devices`,
 * `/v1/scratchpads` and `/v1/projects`, and held a watch stream open — and the
 * row for the route carrying all of it walked from "reached just now" to "4m
 * ago". `/v1/status` was requested ZERO times. The gate below was green the whole
 * time, because it asserted the two calls that existed rather than the rule.
 *
 * So the witness is stamped at the CLIENT'S RESPONSE PATH, and this file now
 * holds both halves: the rule ([RouteWitness], pure) and the wiring (source-level,
 * because AppStore needs a window).
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class RouteWitnessDesktopTest {
    private val store = File("src/main/kotlin/com/silencelen/huginn/desktop/AppStore.kt").readText()

    @Test
    fun `a successful status poll marks the active route as reached`() {
        val hits = Regex("""faults\.ok\(Faults\.STATUS\); routeFailures\.ok\(\); noteRouteReached\(\)""").findAll(store).count()
        assertTrue(hits >= 2, "both status-poll success branches must call noteRouteReached(); found $hits")
        assertTrue(store.contains("RouteResolver.touch("), "noteRouteReached must go through RouteResolver.touch (lastSeenAt, never lastOkAt)")
    }

    /**
     * ⚠ THE WIRING IS THE FIX. `RouteWitness` can be perfect and unreferenced:
     * the failure mode here is a row that is merely old, which throws nothing,
     * draws correctly, and is only wrong if you know when the last request was.
     */
    @Test
    fun `every response goes through the witness, not every caller`() {
        val client = store.substringAfter("val client = HuginnClient(").substringBefore("\n    )")
        assertTrue(client.length in 1..3_000, "the client construction was not found")
        assertTrue(
            "addInterceptor" in client,
            "the one client must stamp the witness from its own response path:\n$client",
        )
        assertTrue(
            "noteRouteReached(" in client,
            "the interceptor must record the route as reached:\n$client",
        )
    }

    // --------------------------------------------------------------- the rule

    @Test
    fun `an authority is a place, however the url spells it`() {
        assertEquals("127.0.0.1:8787", RouteWitness.authority("http://127.0.0.1:8787/v1/chats"))
        assertEquals("127.0.0.1:8787", RouteWitness.authority("127.0.0.1:8787"))
        assertEquals("huginn.example:443", RouteWitness.authority("https://huginn.example/v1/status"))
        assertEquals("huginn.example:80", RouteWitness.authority("http://huginn.example"))
        assertEquals("box:8787", RouteWitness.authority("HTTP://BOX:8787/v1/sessions?preview=1"))
        assertEquals("", RouteWitness.authority("   "), "nothing is not a place")
    }

    @Test
    fun `the same place is the same place with or without the default port`() {
        assertTrue(RouteWitness.sameEndpoint("https://huginn.example/v1/chats", "https://huginn.example:443"))
        assertTrue(RouteWitness.sameEndpoint("http://127.0.0.1:8787/v1/chats", "127.0.0.1:8787"))
        assertFalse(
            RouteWitness.sameEndpoint("http://127.0.0.1:18821/v1/ping", "127.0.0.1:8787"),
            "a probe of another address must not stamp the active route",
        )
        assertFalse(RouteWitness.sameEndpoint("", "127.0.0.1:8787"))
    }

    @Test
    fun `only a success on the active route stamps it`() {
        val route = "127.0.0.1:8787"
        assertTrue(RouteWitness.stamps(200, "http://127.0.0.1:8787/v1/chats", route, null, NOW))
        assertTrue(RouteWitness.stamps(204, "http://127.0.0.1:8787/v1/devices", route, null, NOW))
        // ⚠ A 401 PROVES THE ADDRESS ANSWERS, NOT THAT THE ROUTE WORKS. That
        // distinction is `RouteFailures`', and the witness must make the same one:
        // the walker's fake daemon answered 401 to everything.
        assertFalse(RouteWitness.stamps(401, "http://127.0.0.1:8787/v1/chats", route, null, NOW))
        assertFalse(RouteWitness.stamps(500, "http://127.0.0.1:8787/v1/chats", route, null, NOW))
        assertFalse(
            RouteWitness.stamps(200, "http://127.0.0.1:18821/v1/ping", route, null, NOW),
            "probes of other addresses go through the same engine",
        )
        assertFalse(RouteWitness.stamps(200, "http://127.0.0.1:8787/v1/chats", "", null, NOW))
    }

    /**
     * The health map is written to the settings FILE on every change and the poll
     * makes eight or more requests every five seconds. Without a granularity the
     * fix trades a stale row for a hundred disk writes a minute.
     */
    @Test
    fun `a route already stamped seconds ago is not re-stamped`() {
        val url = "http://127.0.0.1:8787/v1/chats"
        val route = "127.0.0.1:8787"
        val fresh = RouteHealth(lastSeenAt = NOW - 1_000)
        assertFalse(RouteWitness.stamps(200, url, route, fresh, NOW), "one second is not worth a disk write")
        val stale = RouteHealth(lastSeenAt = NOW - RouteWitness.GRANULARITY_MS)
        assertTrue(RouteWitness.stamps(200, url, route, stale, NOW))
        assertTrue(
            RouteWitness.stamps(200, url, route, RouteHealth(lastSeenAt = 0), NOW),
            "a route never witnessed is always worth witnessing",
        )
    }

    private companion object {
        const val NOW = 1_800_000_000_000
    }
}
