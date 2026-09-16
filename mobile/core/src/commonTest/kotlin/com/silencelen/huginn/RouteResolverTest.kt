package com.silencelen.huginn

import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteFailures
import com.silencelen.huginn.data.RouteHealth
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

    // ----------------------------------------------------- the other answers

    @Test
    fun `a pinned book is not probed at all`() = runTest {
        var probes = 0
        val out = RouteResolver.resolve(book(a, b, auto = false), emptyMap(), NOW) { probes++; true }
        assertEquals(0, probes)
        assertEquals("r0", (out.choice as RouteResolver.Choice.Pinned).route.id)
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
