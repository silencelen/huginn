package com.silencelen.huginn

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.RouteKind
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * What is LEFT of the two built-in addresses: a seed and a migration.
 *
 * Everything else this file used to assert — `labelFor`, `candidates`, the whole
 * "Tailscale or Yggdrasil or Custom" vocabulary — went with the feature. Routes
 * are the owner's list now, so a label this app chose is not a fact about
 * anything. What survives is the one thing an upgrade turns on: that a phone
 * which has been talking to one of these addresses for a year keeps talking to
 * exactly that one, under a name it recognises.
 */
class AppdRoutesTest {

    @Test
    fun `normalize makes trailing slashes and whitespace irrelevant`() {
        val want = "http://100.97.198.90:8787"
        for (raw in listOf(want, "$want/", " $want ", "$want///")) {
            assertEquals(want, AppdRoutes.normalize(raw))
        }
    }

    @Test
    fun `the built-ins are still recognised, by address, for migration`() {
        assertEquals(AppdRoutes.TAILSCALE, AppdRoutes.match("http://100.97.198.90:8787/"))
        assertEquals(AppdRoutes.YGGDRASIL, AppdRoutes.match("http://192.168.2.117:8787"))
        assertNull(AppdRoutes.match("http://10.0.0.9:8787"), "a hand-typed address is nobody's built-in")
    }

    /**
     * The seed carries the built-in's own label as the pin's NAME and a stable
     * id, so a rename later ("the mesh") does not lose which pin is active.
     */
    @Test
    fun `a seeded pin is named after the built-in and badged from its address`() {
        val t = AppdRoutes.seed(AppdRoutes.TAILSCALE, now = 0)
        assertEquals("tailscale", t.id)
        assertEquals("Tailscale", t.name)
        assertEquals(RouteKind.TAILNET, t.kind, "100.64/10 is the tailnet block")

        val y = AppdRoutes.seed(AppdRoutes.YGGDRASIL, now = 0)
        assertEquals("yggdrasil", y.id)
        assertEquals("Yggdrasil", y.name)
        // The NAME says which path; the BADGE says what the address is, and
        // huginn's yggdrasil route is its VLAN-2 address behind the mesh gateway.
        assertEquals(RouteKind.LAN, y.kind)
    }

    // --------------------------------------------------------- the table

    /**
     * ⚠ THE MIGRATION TABLE, EVERY ROW. An upgrade must change nothing about
     * where this client talks, and the way that is guaranteed is by the order
     * being the same order the old `candidates()` produced: the stored address
     * first, then the built-ins in `ALL` order.
     */
    @Test
    fun `an install on the tailnet address migrates to Tailscale as pin one`() {
        val book = AppdRoutes.migrate(AppdRoutes.TAILSCALE.url, routePinned = false)
        assertEquals(listOf("tailscale", "yggdrasil"), book.routes.map { it.id })
        assertEquals(listOf("Tailscale", "Yggdrasil"), book.routes.map { it.name })
        assertEquals("tailscale", book.activeId)
        assertEquals(AppdRoutes.TAILSCALE.url, book.activeUrl)
        assertTrue(book.autoSwitch)
    }

    @Test
    fun `an install on the mesh address migrates to Yggdrasil as pin one`() {
        val book = AppdRoutes.migrate(AppdRoutes.YGGDRASIL.url, routePinned = false)
        assertEquals(listOf("yggdrasil", "tailscale"), book.routes.map { it.id })
        assertEquals("yggdrasil", book.activeId)
        assertEquals(0, book.routes.first().order)
        assertEquals(1, book.routes.last().order)
    }

    @Test
    fun `a hand-typed address stays chosen and is named after its own host`() {
        val book = AppdRoutes.migrate("http://10.0.0.9:8787", routePinned = false)
        assertEquals(listOf(AppdRoutes.MIGRATED_ID, "tailscale", "yggdrasil"), book.routes.map { it.id })
        assertEquals("10.0.0.9:8787", book.routes.first().name)
        assertEquals(RouteKind.LAN, book.routes.first().kind)
        assertEquals(AppdRoutes.MIGRATED_ID, book.activeId)
        assertEquals("http://10.0.0.9:8787", book.activeUrl)
    }

    @Test
    fun `a trailing slash in the stored address does not produce a third pin`() {
        val book = AppdRoutes.migrate("${AppdRoutes.TAILSCALE.url}/", routePinned = false)
        assertEquals(2, book.routes.size, "normalized before matching: ${book.routes.map { it.url }}")
        assertEquals("tailscale", book.activeId)
    }

    @Test
    fun `a pinned route becomes autoSwitch off, which is the same refusal to move`() {
        val pinned = AppdRoutes.migrate(AppdRoutes.YGGDRASIL.url, routePinned = true)
        assertTrue(!pinned.autoSwitch)
        assertEquals("yggdrasil", pinned.activeId, "the pinned route is still the active one")

        val auto = AppdRoutes.migrate(AppdRoutes.YGGDRASIL.url, routePinned = false)
        assertTrue(auto.autoSwitch)
    }

    /**
     * ⚠ A FRESH INSTALL PINS NOTHING. The owner's rule: the first address saved
     * becomes pin #1. Seeding the two built-ins here instead would mean a brand
     * new phone arrived already believing it knew where huginn lives.
     */
    @Test
    fun `a fresh install migrates to an empty book rather than to the built-ins`() {
        for (nothing in listOf(null, "", "   ")) {
            val book = AppdRoutes.migrate(nothing, routePinned = false)
            assertEquals(emptyList(), book.routes, "stored=${nothing?.let { "'$it'" }}")
            assertNull(book.activeId)
            assertEquals("", book.activeUrl, "and therefore no derived base URL")
        }
    }

    /**
     * ⚠ AN UPGRADE MUST NOT DELETE THE OWNER'S ADDRESS WITHOUT SAYING SO. The
     * phone's old "Base URL" field was free text with no validation at all, so a
     * plain-http hostname both stored and worked: `huginn.lan`, the short MagicDNS
     * name `huginn`, a DDNS name, a public literal, anything with a path. The
     * guard refuses all of those (correctly — plain http to a name is not safe to
     * send a bearer over), and `normalized()` dropped them in silence, leaving the
     * install pointed at a hard-coded built-in it was never told about, with
     * `autoSwitch=false` carried over from `appd_route_pinned` so it never even
     * probed. The address is now carried back as [RouteBook.droppedUrl] and the
     * book is left with NO active route, which is the honest state.
     */
    @Test
    fun `a refused legacy address is named, and no built-in is adopted in its place`() {
        val refused = listOf(
            "http://huginn.lan:8787",
            "http://huginn:8787",
            "http://203.0.113.9:8787",
            "http://192.168.2.117:8787/api",
        )
        for (url in refused) for (pinned in listOf(false, true)) {
            val book = AppdRoutes.migrate(url, routePinned = pinned)
            assertEquals(url, book.droppedUrl, "stored=$url pinned=$pinned")
            assertNull(book.activeId, "no built-in is adopted in its place: $url")
            assertEquals("", book.activeUrl, "and therefore no derived base URL: $url")
            assertEquals(listOf("tailscale", "yggdrasil"), book.routes.map { it.id },
                "the built-ins are still offered, they are just not chosen: $url")
        }
    }

    @Test
    fun `an address the guard allows is not reported as dropped`() {
        assertNull(AppdRoutes.migrate("http://10.0.0.9:8787", routePinned = false).droppedUrl)
        assertNull(AppdRoutes.migrate(AppdRoutes.TAILSCALE.url, routePinned = false).droppedUrl)
        assertNull(AppdRoutes.migrate(null, routePinned = false).droppedUrl)
    }

    /** The migrated `addedAt` is zero on purpose — see [AppdRoutes.MIGRATED_AT]. */
    @Test
    fun `migration is a pure function of the stored bytes`() {
        val a = AppdRoutes.migrate(AppdRoutes.TAILSCALE.url, routePinned = false)
        val b = AppdRoutes.migrate(AppdRoutes.TAILSCALE.url, routePinned = false)
        assertEquals(a, b, "two reads of unchanged bytes must produce the same book")
        assertTrue(a.routes.all { it.addedAt == AppdRoutes.MIGRATED_AT })
    }
}
