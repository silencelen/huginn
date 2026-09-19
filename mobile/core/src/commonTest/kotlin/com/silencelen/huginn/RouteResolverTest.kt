package com.silencelen.huginn

import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteFailures
import com.silencelen.huginn.data.RouteHealth
import com.silencelen.huginn.data.RouteHealthSnapshot
import com.silencelen.huginn.data.RouteResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which pinned route this client talks to, generalized from two addresses to
 * eight.
 *
 * Two rules and they pull against each other on purpose. ORDERED PREFERENCE says
 * the owner's list is the answer; HYSTERESIS says a working connection is worth
 * more than a marginally-preferred one, because a switch is a reconnect. The
 * resolution is that the active route only loses its place by FAILING — and,
 * once it has failed, the list settles back to the owner's order rather than to
 * wherever the outage left it.
 */
class RouteResolverTest {

    private val NOW = 1_700_000_000_000L

    private fun book(vararg urls: String, active: Int = 0, auto: Boolean = true) = RouteBook(
        routes = urls.mapIndexed { i, u -> PinnedRoute("r$i", "Route $i", u, order = i) },
        activeId = "r$active",
        autoSwitch = auto,
    )

    private val a = "http://10.0.0.1:8787"
    private val b = "http://10.0.0.2:8787"
    private val c = "http://10.0.0.3:8787"

    /** Health fresh enough for the hysteresis to bite. */
    private fun fresh(id: String) = mapOf(id to RouteHealth(lastOkAt = NOW - 1_000))

    /** Health old enough that it does not. */
    private fun stale(id: String) = mapOf(id to RouteHealth(lastOkAt = NOW - RouteResolver.HYSTERESIS_MS - 1))

    // ----------------------------------------------------- ordered preference

    @Test
    fun `the first healthy route in the owner's order wins, not the fastest`() = runTest {
        val out = RouteResolver.resolve(book(a, b, c, active = 2), stale("r2"), NOW) { it.url != a }
        // `a` is dead; `b` and `c` both answer and `b` is higher in the list.
        assertEquals("r1", (out.choice as RouteResolver.Choice.Switched).route.id)
    }

    @Test
    fun `reordering the list reorders the preference`() = runTest {
        val reordered = book(a, b, c, active = 2).move("r2", -2)
        val out = RouteResolver.resolve(reordered, stale("r2"), NOW) { true }
        assertTrue(out.choice is RouteResolver.Choice.Stay, "r2 is now first, so it keeps the connection")
    }

    @Test
    fun `every route is probed in one budget rather than one at a time`() = runTest {
        val tried = mutableListOf<String>()
        RouteResolver.resolve(book(a, b, c), stale("r0"), NOW) { tried += it.id; false }
        assertEquals(listOf("r0", "r1", "r2"), tried.sorted(), "all of them, concurrently")
    }

    // ---------------------------------------------------------- hysteresis

    @Test
    fun `a working route keeps the connection even when a preferred route answers`() = runTest {
        // r1 is active and has been fine; r0 sits above it and also answers.
        val out = RouteResolver.resolve(book(a, b, active = 1), fresh("r1"), NOW, force = true) { true }
        assertEquals("r1", (out.choice as RouteResolver.Choice.Stay).route.id)
    }

    @Test
    fun `a route fresh enough is not even probed`() = runTest {
        var probes = 0
        val out = RouteResolver.resolve(book(a, b, active = 1), fresh("r1"), NOW) { probes++; true }
        assertEquals(0, probes, "a route that answered seconds ago is not re-interrogated on every start")
        assertEquals("r1", (out.choice as RouteResolver.Choice.Stay).route.id)
    }

    @Test
    fun `a manual find probes anyway, so the dots refresh, and still does not move`() = runTest {
        var probes = 0
        val out = RouteResolver.resolve(book(a, b, active = 1), fresh("r1"), NOW, force = true) { probes++; true }
        assertEquals(2, probes)
        assertTrue(out.choice is RouteResolver.Choice.Stay)
        assertEquals(NOW, out.health.getValue("r0").lastOkAt, "and the other route's dot was refreshed")
    }

    @Test
    fun `after a real outage the list settles back to the owner's order`() = runTest {
        // r1 is active but has been dead longer than the hysteresis window; both
        // answer again now. Preference, not inertia, decides.
        val out = RouteResolver.resolve(book(a, b, active = 1), stale("r1"), NOW) { true }
        assertEquals("r0", (out.choice as RouteResolver.Choice.Switched).route.id)
    }

    // ------------------------------------------------- adoption needs a person

    /**
     * ⚠ AN ADDRESS NOBODY TYPED IS NOT ADOPTED ON ITS OWN. The two built-ins the
     * migration seeds are hard-coded literals in a PUBLIC repo, reached over
     * plain http; whoever holds one of those addresses on the network the phone
     * is on today gets the bearer the moment the client moves there. The client
     * may notice such a route, and must not move to it without being told to.
     */
    @Test
    fun `a plain-http route nobody added by hand is offered, not adopted`() = runTest {
        val book = RouteBook(
            routes = listOf(
                PinnedRoute("r0", "Tailscale", a, order = 0, byHand = false),
                PinnedRoute("r1", "mine", b, order = 1, byHand = true),
            ),
            activeId = "r1",
        )
        // Cold start: nothing in the health map, so everything is probed and both
        // answer. r0 sits above the active route.
        val out = RouteResolver.resolve(book, emptyMap(), NOW) { true }
        val choice = out.choice as RouteResolver.Choice.Stay.Candidate
        assertEquals("r1", choice.route.id, "the connection stays exactly where it was")
        assertEquals("r0", choice.candidate.id, "and the route that answered is named for a person to confirm")
        assertEquals(NOW, out.health.getValue("r0").lastOkAt, "the dots still refresh")
    }

    @Test
    fun `a route the owner typed is adopted immediately`() = runTest {
        val book = RouteBook(
            routes = listOf(
                PinnedRoute("r0", "mine", a, order = 0, byHand = true),
                PinnedRoute("r1", "other", b, order = 1, byHand = true),
            ),
            activeId = "r1",
        )
        val out = RouteResolver.resolve(book, stale("r1"), NOW) { it.url == a }
        assertEquals("r0", (out.choice as RouteResolver.Choice.Switched).route.id)
    }

    @Test
    fun `https is adopted without asking, hand-added or not`() = runTest {
        val book = RouteBook(
            routes = listOf(
                PinnedRoute("r0", "seeded", "https://huginn.example.ts.net", order = 0, byHand = false),
                PinnedRoute("r1", "mine", b, order = 1, byHand = true),
            ),
            activeId = "r1",
        )
        val out = RouteResolver.resolve(book, stale("r1"), NOW) { true }
        assertEquals("r0", (out.choice as RouteResolver.Choice.Switched).route.id,
            "TLS is what makes the host stop mattering")
    }

    /** The health cache is in memory only, so every cold start re-resolves. */
    @Test
    fun `the health cache survives a restart as text`() {
        val health = mapOf("r0" to RouteHealth(lastOkAt = NOW, lastRttMs = 12), "r1" to RouteHealth(lastFailAt = NOW))
        val back = RouteHealthSnapshot.decode(RouteHealthSnapshot.encode(health, NOW))
        assertEquals(health, back)
        assertEquals(emptyMap(), RouteHealthSnapshot.decode(null))
        assertEquals(emptyMap(), RouteHealthSnapshot.decode("not json at all"))
    }

    // ----------------------------------------------------- the other answers

    @Test
    fun `a pinned book is not probed at all`() = runTest {
        var probes = 0
        val out = RouteResolver.resolve(book(a, b, auto = false), emptyMap(), NOW) { probes++; true }
        assertEquals(0, probes)
        assertEquals("r0", (out.choice as RouteResolver.Choice.Pinned).route.id)
    }

    /**
     * ⚠ FORCE MEANS "LOOK", NOT "MOVE". Every "Use this route" tap pins the book,
     * and the pin used to return before `force` was even read: "Find live route"
     * then performed ZERO probes, so the per-route dots and "last reached" text
     * froze for the lifetime of the pin — stuck on "never tried" for a hand-made
     * pin, or on a stale green for a route that has since died.
     */
    @Test
    fun `a manual find probes a PINNED book too, and still does not move`() = runTest {
        var probes = 0
        val out = RouteResolver.resolve(book(a, b, auto = false), emptyMap(), NOW, force = true) { probes++; true }
        assertEquals(2, probes, "refusing to move is not a reason to refuse to look")
        assertEquals("r0", (out.choice as RouteResolver.Choice.Pinned).route.id, "and the pin is kept")
        assertEquals(NOW, out.health.getValue("r1").lastOkAt, "the other route's dot refreshed")
    }

    @Test
    fun `a pinned book whose probe fails records the failure without moving`() = runTest {
        val out = RouteResolver.resolve(book(a, b, auto = false), emptyMap(), NOW, force = true) { it.url != a }
        assertEquals("r0", (out.choice as RouteResolver.Choice.Pinned).route.id)
        assertEquals(false, out.health.getValue("r0").reachable, "a dead pin reads dead")
        assertEquals(true, out.health.getValue("r1").reachable)
    }

    /**
     * ⚠ NOTHING ANSWERED IS NOT "BLANK THE SETTING". That would turn "no
     * connectivity" into "this app looks unconfigured" — the failure the old
     * resolver's doc comment already warned about, carried forward.
     */
    @Test
    fun `nothing answering leaves the active route exactly where it was`() = runTest {
        val before = book(a, b, active = 1)
        val out = RouteResolver.resolve(before, stale("r1"), NOW) { false }
        assertEquals(RouteResolver.Choice.NoRoute, out.choice)
        assertEquals("r1", before.activeId, "the caller's book is untouched")
    }

    @Test
    fun `an empty book is the connect flow, not an error`() = runTest {
        val out = RouteResolver.resolve(RouteBook(), emptyMap(), NOW) { true }
        assertEquals(RouteResolver.Choice.Empty, out.choice)
    }

    // -------------------------------------------------------- health cache

    @Test
    fun `the health cache records both answers and is never an input to ranking`() = runTest {
        val out = RouteResolver.resolve(book(a, b, c), stale("r0"), NOW) { it.id == "r2" }
        assertEquals(NOW, out.health.getValue("r2").lastOkAt)
        assertEquals(NOW, out.health.getValue("r0").lastFailAt)
        assertEquals(false, out.health.getValue("r1").reachable)
        assertEquals(true, out.health.getValue("r2").reachable)
    }

    @Test
    fun `a route nobody has tried is a third state rather than a bad one`() {
        assertEquals(null, RouteHealth().reachable)
        assertEquals(true, RouteHealth(lastOkAt = 2, lastFailAt = 1).reachable)
        assertEquals(false, RouteHealth(lastOkAt = 1, lastFailAt = 2).reachable)
    }

    // ------------------------------------------------- ordinary traffic

    /**
     * ⚠⚠ THE WITNESS IS A SEPARATE FIELD FROM THE PROBE'S, AND THAT IS THE WHOLE
     * POINT. `lastOkAt` is read by [RouteResolver.HYSTERESIS_MS] — a route that
     * answered within the last minute is not re-interrogated — so writing
     * ordinary traffic into it would mean the three-failures-in-a-row re-probe,
     * which by definition happens seconds after the last success, would find the
     * active route "fresh" and never sweep. A laptop that moved networks would
     * stay broken exactly as it did before the re-probe existed.
     */
    @Test
    fun `ordinary traffic marks the route reached without feeding the hysteresis`() {
        val touched = RouteResolver.touch(emptyMap(), "r0", NOW)
        assertEquals(NOW, touched.getValue("r0").lastSeenAt)
        assertEquals(0L, touched.getValue("r0").lastOkAt, "the probe's own record is not forged")
        assertEquals(true, touched.getValue("r0").reachable, "traffic that worked is proof it is reachable")
    }

    @Test
    fun `a touch never moves the clock backwards and never invents a route`() {
        val held = mapOf("r0" to RouteHealth(lastSeenAt = NOW))
        assertEquals(held, RouteResolver.touch(held, "r0", NOW - 5_000), "an older success may not win")
        assertEquals(held, RouteResolver.touch(held, null, NOW), "nothing active, nothing to record")
        assertEquals(held, RouteResolver.touch(held, "  ", NOW), "a blank id is not a route")
        assertEquals(held, RouteResolver.touch(held, "r0", 0), "no clock, no witness")
    }

    @Test
    fun `a touch leaves the probe's failure standing until traffic is newer than it`() {
        val failed = mapOf("r0" to RouteHealth(lastFailAt = NOW))
        assertEquals(false, failed.getValue("r0").reachable)
        assertEquals(
            true,
            RouteResolver.touch(failed, "r0", NOW + 1_000).getValue("r0").reachable,
            "traffic AFTER the failed probe is the newer evidence",
        )
        assertEquals(
            false,
            RouteResolver.touch(mapOf("r0" to RouteHealth(lastFailAt = NOW)), "r0", NOW - 1_000)
                .getValue("r0").reachable,
            "traffic from before it is not",
        )
    }

    // ----------------------------------------------------- failure counter

    /**
     * ⚠ THE RE-PROBE THE DESKTOP NEVER HAD. It resolved once from `start()` and
     * then never again — no button, no failure-driven retry — so a laptop that
     * moved networks stayed broken until it was restarted.
     */
    @Test
    fun `three failures in a row ask for a re-probe, and then the count starts over`() {
        val f = RouteFailures()
        assertEquals(3, RouteResolver.FAILURES_BEFORE_REPROBE)
        assertTrue(!f.fail())
        assertTrue(!f.fail())
        assertTrue(f.fail(), "the third consecutive failure")
        assertEquals(0, f.count, "and the run starts over rather than firing on every call after it")
        assertTrue(!f.fail())
    }

    /** One timeout is ordinary. A run of them is a network that moved. */
    @Test
    fun `a success in the middle clears the run`() {
        val f = RouteFailures()
        f.fail(); f.fail()
        f.ok()
        assertEquals(0, f.count)
        assertTrue(!f.fail())
        assertTrue(!f.fail())
        assertTrue(f.fail())
    }
}
