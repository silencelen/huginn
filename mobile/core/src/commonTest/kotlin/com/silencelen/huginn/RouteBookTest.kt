package com.silencelen.huginn

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteGuard
import com.silencelen.huginn.data.RouteKind
import com.silencelen.huginn.data.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The list, the names, the order, the cap — and the address every worker reads. */
class RouteBookTest {

    private val lan = "http://192.168.2.117:8787"
    private val tailnet = "http://100.97.198.90:8787"
    private val local = "http://127.0.0.1:8787"

    private fun book(vararg urls: String): RouteBook =
        urls.foldIndexed(RouteBook()) { i, b, u -> b.add("", u, now = i.toLong(), id = "r$i") }

    // ------------------------------------------------- the fresh install

    /**
     * ⚠ THE OWNER'S RULE, VERBATIM: *"the first route they set when setting up
     * the app becomes the first pinned route"*. A fresh install pins nothing, so
     * the first save has to do two things at once — create the pin and make it
     * the one in use — or the app would sit on an empty address with a list of
     * one.
     */
    @Test
    fun `a fresh book is empty and the first address saved becomes pin one and active`() {
        val fresh = RouteBook()
        assertEquals(emptyList(), fresh.routes)
        assertNull(fresh.activeId)
        assertEquals("", fresh.activeUrl)

        val one = fresh.add("Home", lan, now = 5, id = "a")
        assertEquals(listOf("a"), one.routes.map { it.id })
        assertEquals("a", one.activeId)
        assertEquals(lan, one.activeUrl, "the derived base URL is the first pin's address")

        // A second address joins the order and moves nothing.
        val two = one.add("Tailnet", tailnet, now = 6, id = "b")
        assertEquals(listOf("a", "b"), two.routes.map { it.id })
        assertEquals("a", two.activeId, "adding a route must not silently reconnect")
    }

    /**
     * ⚠ THE DERIVED BASE URL. Ten background call sites read this and know
     * nothing about routes; whatever the book says is active IS the address.
     */
    @Test
    fun `the derived base URL follows the active route through every operation`() {
        var b = book(tailnet, lan, local)
        assertEquals(tailnet, b.activeUrl)

        b = b.activate("r1")
        assertEquals(lan, b.activeUrl, "activate")

        b = b.setUrl("r1", "http://10.0.0.9:8787")
        assertEquals("http://10.0.0.9:8787", b.activeUrl, "editing the active pin's address")

        b = b.remove("r1")
        assertEquals(tailnet, b.activeUrl, "removing the active pin falls back to the first")

        b = b.remove("r0").remove("r2")
        assertEquals("", b.activeUrl, "an emptied book has no address to give")
        assertNull(b.activeId)
    }

    // ------------------------------------------------------------- naming

    @Test
    fun `a rename persists and a blank name falls back to the address`() {
        val b = book(lan).rename("r0", "  the mesh  ")
        assertEquals("the mesh", b.routes.single().name)
        assertEquals("192.168.2.117:8787", b.rename("r0", "   ").routes.single().name)
    }

    @Test
    fun `an unnamed route is named after its own authority`() {
        assertEquals("192.168.2.117:8787", RouteBook().add("", lan, now = 0).routes.single().name)
        assertEquals("localhost:8787", RouteBook().add("  ", "http://localhost:8787", now = 0).routes.single().name)
    }

    @Test
    fun `a rename does not move the connection, because the id is what is active`() {
        val b = book(tailnet, lan).rename("r0", "Somewhere else")
        assertEquals("r0", b.activeId)
        assertEquals(tailnet, b.activeUrl)
    }

    // -------------------------------------------------------------- order

    @Test
    fun `moving a route rewrites the order field to match the list`() {
        val b = book(tailnet, lan, local).move("r2", -1)
        assertEquals(listOf("r0", "r2", "r1"), b.routes.map { it.id })
        assertEquals(listOf(0, 1, 2), b.routes.map { it.order }, "order mirrors the index, always")
    }

    @Test
    fun `moving off either end is a clamp and not a wrap`() {
        val b = book(tailnet, lan)
        assertEquals(listOf("r0", "r1"), b.move("r0", -1).routes.map { it.id })
        assertEquals(listOf("r0", "r1"), b.move("r1", 3).routes.map { it.id })
    }

    // ---------------------------------------------------------- the rules

    @Test
    fun `eight pins is the cap and the ninth is refused with a reason`() {
        var b = RouteBook()
        for (i in 1..RouteBook.MAX_PINS) b = b.add("", "http://10.0.0.$i:8787", now = i.toLong(), id = "r$i")
        assertEquals(8, b.routes.size)
        assertTrue(b.isFull)
        val e = assertFailsWith<IllegalArgumentException> { b.add("", "http://10.0.0.99:8787", now = 9) }
        assertEquals(RouteBook.FULL, e.message)
    }

    @Test
    fun `the same address cannot be pinned twice`() {
        val b = book(lan)
        assertEquals(RouteBook.DUPLICATE, assertFailsWith<IllegalArgumentException> { b.add("Copy", lan, 1) }.message)
        // Trailing slashes and whitespace do not make it a different address.
        assertEquals(RouteBook.DUPLICATE, assertFailsWith<IllegalArgumentException> { b.add("Copy", " $lan/ ", 1) }.message)
        // Editing a pin to its OWN address is not a duplicate.
        assertEquals(lan, b.setUrl("r0", "$lan/").routes.single().url)
    }

    /** The guard is the same on both paths into the list — adding and editing. */
    @Test
    fun `an address the guard refuses cannot be added or edited in`() {
        val b = book(lan)
        assertEquals(RouteGuard.REFUSED, assertFailsWith<IllegalArgumentException> { b.add("Evil", "http://example.com", 1) }.message)
        assertEquals(RouteGuard.REFUSED, assertFailsWith<IllegalArgumentException> { b.setUrl("r0", "http://1.2.3.4:8787") }.message)
        assertEquals(lan, b.routes.single().url, "and the book is unchanged")
    }

    /**
     * ⚠ THE HOLE THE OLD ALLOWLIST HAD. The desktop checked its setter and read
     * `baseUrl` straight back out of the file, so a hand-edited settings file was
     * never judged at all. A book arriving from a store is normalized, and
     * normalizing drops what the guard would refuse.
     */
    @Test
    fun `a hand-edited store cannot smuggle a public http address into the list`() {
        val smuggled = RouteBook(
            routes = listOf(
                PinnedRoute("evil", "Totally fine", "http://attacker.example", RouteKind.LAN, 0, 0),
                PinnedRoute("real", "Home", lan, RouteKind.LAN, 1, 0),
            ),
            activeId = "evil",
        ).normalized()
        assertEquals(listOf("real"), smuggled.routes.map { it.id })
        assertEquals("real", smuggled.activeId, "and the connection falls back to one that is allowed")
        assertEquals(lan, smuggled.activeUrl)
        assertEquals("http://attacker.example", smuggled.droppedUrl,
            "dropped, but no longer WITHOUT A WORD — a screen can now say which address went and why")
    }

    /**
     * A book that has nothing to say about a dropped address says nothing. The
     * field is a notice, not a state; it must not appear out of an ordinary read.
     */
    @Test
    fun `an ordinary book reports no dropped address`() {
        assertNull(book(tailnet, lan).normalized().droppedUrl)
        assertNull(RouteBook().normalized().droppedUrl)
    }

    @Test
    fun `an active id naming nothing falls back to the first pin rather than to nowhere`() {
        val b = RouteBook(routes = book(tailnet, lan).routes, activeId = "gone").normalized()
        assertEquals("r0", b.activeId)
    }

    @Test
    fun `the badge is recomputed when the address changes`() {
        val b = book(tailnet)
        assertEquals(RouteKind.TAILNET, b.routes.single().kind)
        assertEquals(RouteKind.LOCAL, b.setUrl("r0", local).routes.single().kind)
    }

    @Test
    fun `manual pinning is a route plus a refusal to move`() {
        val b = book(tailnet, lan).activate("r1").withAutoSwitch(false)
        assertEquals("r1", b.activeId)
        assertTrue(!b.autoSwitch)
        assertEquals("r1", b.activate("nonexistent").activeId, "a stale row cannot blank the connection")
    }

    // --------------------------------------------------------- persistence

    /**
     * ⚠ NULL IS "NEVER WRITTEN" AND `[]` IS "EMPTY". Confusing the two would
     * re-seed the two built-ins on every launch after the owner deleted them.
     */
    @Test
    fun `the codec distinguishes an unwritten book from an emptied one`() {
        assertNull(SettingsCodec.decodeRoutes(null))
        assertNull(SettingsCodec.decodeRoutes(""))
        assertEquals(emptyList(), SettingsCodec.decodeRoutes("[]"))
        assertNull(SettingsCodec.decodeRoutes("{not json"), "unreadable falls back to the migration")
    }

    @Test
    fun `a book survives a round trip through the store's encoding`() {
        val before = AppdRoutes.migrate(lan, routePinned = true).rename("yggdrasil", "the mesh")
        val after = SettingsCodec.decodeRoutes(SettingsCodec.encodeRoutes(before.routes))
        assertEquals(before.routes, after)
    }

    @Test
    fun `an id is minted once and is stable`() {
        val a = RouteBook.newId(now = 1_700_000_000_000)
        val b = RouteBook.newId(now = 1_700_000_000_000)
        assertTrue(a.startsWith("r-"), a)
        assertTrue(a != b, "two pins minted in the same millisecond must not collide")
    }
}
