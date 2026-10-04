package com.silencelen.huginn

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.RouteKind
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * What is LEFT of the built-in addresses: a seed and a migration.
 *
 * Everything else this file used to assert — `labelFor`, `candidates`, the whole
 * "Tailscale or Yggdrasil or Custom" vocabulary — went with the feature. Routes
 * are the owner's list now, so a label this app chose is not a fact about
 * anything. What survives is the one thing an upgrade turns on: that a phone
 * which has been talking to one of these addresses for a year keeps talking to
 * exactly that one, under a name it recognises — and, since Tailscale was
 * retired, that an install still pointed at the dead tailnet address is NOT
 * kept talking to it.
 */
class AppdRoutesTest {

    private val RETIRED_TAILNET = "http://100.97.198.90:8787"

    @Test
    fun `normalize makes trailing slashes and whitespace irrelevant`() {
        val want = "http://192.168.7.117:8787"
        for (raw in listOf(want, "$want/", " $want ", "$want///")) {
            assertEquals(want, AppdRoutes.normalize(raw))
        }
    }

    @Test
    fun `the built-ins are still recognised, by address, for migration`() {
        assertEquals(AppdRoutes.LAN, AppdRoutes.match("http://192.168.7.117:8787/"))
        assertEquals(AppdRoutes.YGGDRASIL, AppdRoutes.match("http://192.168.2.117:8787"))
        assertNull(AppdRoutes.match("http://10.0.0.9:8787"), "a hand-typed address is nobody's built-in")
        assertNull(AppdRoutes.match(RETIRED_TAILNET), "the retired tailnet address is nobody's built-in any more")
    }

    /**
     * The seed carries the built-in's own label as the pin's NAME and a stable
     * id, so a rename later ("the mesh") does not lose which pin is active.
     */
    @Test
    fun `a seeded pin is named after the built-in and badged from its address`() {
        val l = AppdRoutes.seed(AppdRoutes.LAN, now = 0)
        assertEquals("lan", l.id)
        assertEquals("LAN", l.name)
        assertEquals(RouteKind.LAN, l.kind)

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
    fun `an install on the LAN address migrates to LAN as pin one`() {
        val book = AppdRoutes.migrate(AppdRoutes.LAN.url, routePinned = false)
        assertEquals(listOf("lan", "yggdrasil"), book.routes.map { it.id })
        assertEquals(listOf("LAN", "Yggdrasil"), book.routes.map { it.name })
        assertEquals("lan", book.activeId)
        assertEquals(AppdRoutes.LAN.url, book.activeUrl)
        assertTrue(book.autoSwitch)
    }

    @Test
    fun `an install on the mesh address migrates to Yggdrasil as pin one`() {
        val book = AppdRoutes.migrate(AppdRoutes.YGGDRASIL.url, routePinned = false)
        assertEquals(listOf("yggdrasil", "lan"), book.routes.map { it.id })
        assertEquals("yggdrasil", book.activeId)
        assertEquals(0, book.routes.first().order)
        assertEquals(1, book.routes.last().order)
    }

    /**
     * ⚠ THE ONE ROW THAT CHANGED BEHAVIOUR ON PURPOSE. The tailnet address was
     * pin #1 for every install that upgraded from 2.x, and it stopped answering
     * on 2026-09-30. Carrying it forward as the ACTIVE route would bring such an
     * install up "connected" to nothing, with autoSwitch consulted only after
     * three failures on a poll loop that runs while the window is visible. So it
     * is treated exactly like a refused address: named, not chosen, and the live
     * seeds offered for the resolver to pick from.
     */
    @Test
    fun `an install on the retired tailnet address is not carried forward`() {
        for (pinned in listOf(false, true)) {
            val book = AppdRoutes.migrate(RETIRED_TAILNET, routePinned = pinned)
            assertEquals(RETIRED_TAILNET, book.droppedUrl, "named, so the person can see what went: pinned=$pinned")
            assertNull(book.activeId, "and not chosen: pinned=$pinned")
            assertEquals("", book.activeUrl)
            assertEquals(listOf("lan", "yggdrasil"), book.routes.map { it.id }, "the live seeds are offered")
        }
        assertTrue(AppdRoutes.RETIRED.all { AppdRoutes.match(it) == null }, "a retired address must never also be a seed")
    }

    @Test
    fun `a hand-typed address stays chosen and is named after its own host`() {
        val book = AppdRoutes.migrate("http://10.0.0.9:8787", routePinned = false)
        assertEquals(listOf(AppdRoutes.MIGRATED_ID, "lan", "yggdrasil"), book.routes.map { it.id })
        assertEquals("10.0.0.9:8787", book.routes.first().name)
        assertEquals(RouteKind.LAN, book.routes.first().kind)
        assertEquals(AppdRoutes.MIGRATED_ID, book.activeId)
        assertEquals("http://10.0.0.9:8787", book.activeUrl)
    }

    @Test
    fun `a trailing slash in the stored address does not produce a third pin`() {
        val book = AppdRoutes.migrate("${AppdRoutes.LAN.url}/", routePinned = false)
        assertEquals(2, book.routes.size, "normalized before matching: ${book.routes.map { it.url }}")
        assertEquals("lan", book.activeId)
    }

    /**
     * ⚠ SPELLING IS NOT IDENTITY. The old setter only trimmed, and `HuginnClient`
     * prepends `http://` to a bare address, so `192.168.2.117:8787` (and even
     * `HTTP://…`) were storable and worked. `migrate` matched them with a trim-
     * and-slash normalize, missed, and kept the address AGAIN beside the built-in
     * it already names: three pins, two of them one daemon — probed twice, two
     * rows in the health strip that both answer, and an edit to repair it refused
     * as a duplicate.
     */
    @Test
    fun `a stored address spelled differently is still the built-in it names`() {
        for (stored in listOf(
            "192.168.2.117:8787",
            "HTTP://192.168.2.117:8787",
            "http://192.168.2.117:8787/",
            " http://192.168.2.117:8787 ",
        )) {
            val book = AppdRoutes.migrate(stored, routePinned = false)
            assertEquals(2, book.routes.size, "stored='$stored' -> ${book.routes.map { it.url }}")
            assertEquals("yggdrasil", book.activeId, "stored='$stored'")
            assertEquals(AppdRoutes.YGGDRASIL.url, book.activeUrl, "stored='$stored'")
        }
    }

    @Test
    fun `a migrated address is stored canonically, not verbatim`() {
        val book = AppdRoutes.migrate("10.0.0.9:8787/", routePinned = false)
        assertEquals("http://10.0.0.9:8787", book.activeUrl, "the scheme is spelled out and the slash is gone")
        assertEquals(RouteKind.LAN, book.routes.first().kind)
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
            assertEquals(listOf("lan", "yggdrasil"), book.routes.map { it.id },
                "the built-ins are still offered, they are just not chosen: $url")
        }
    }

    @Test
    fun `an address the guard allows is not reported as dropped`() {
        assertNull(AppdRoutes.migrate("http://10.0.0.9:8787", routePinned = false).droppedUrl)
        assertNull(AppdRoutes.migrate(AppdRoutes.LAN.url, routePinned = false).droppedUrl)
        assertNull(AppdRoutes.migrate(null, routePinned = false).droppedUrl)
    }

    /** The migrated `addedAt` is zero on purpose — see [AppdRoutes.MIGRATED_AT]. */
    @Test
    fun `migration is a pure function of the stored bytes`() {
        val a = AppdRoutes.migrate(AppdRoutes.LAN.url, routePinned = false)
        val b = AppdRoutes.migrate(AppdRoutes.LAN.url, routePinned = false)
        assertEquals(a, b, "two reads of unchanged bytes must produce the same book")
        assertTrue(a.routes.all { it.addedAt == AppdRoutes.MIGRATED_AT })
    }
}
