package com.silencelen.huginn

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.RouteKind
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The upgrade from a store written before the route book: the one thing
 * [AppdRoutes] still does. A client that has been talking to an address keeps
 * talking to exactly that one, and an upgrade never adds an address the user did
 * not have.
 */
class AppdRoutesTest {

    @Test
    fun `normalize makes trailing slashes and whitespace irrelevant`() {
        val want = "http://192.168.1.20:8787"
        for (raw in listOf(want, "$want/", " $want ", "$want///")) {
            assertEquals(want, AppdRoutes.normalize(raw))
        }
    }

    /**
     * ⚠ AN UPGRADE NEVER ADDS AN ADDRESS. Builds before this one appended two
     * addresses compiled into the app to every migrated book, so an install that
     * pointed at its own server came up with two more routes to somebody else's
     * machine. The old code returns three pins here.
     */
    @Test
    fun `an upgrade keeps the stored address and adds nothing`() {
        for (stored in listOf("http://10.0.0.9:8787", "https://192.168.1.20:8787", "http://192.168.2.117:8787")) {
            val book = AppdRoutes.migrate(stored, routePinned = false)
            assertEquals(listOf(AppdRoutes.MIGRATED_ID), book.routes.map { it.id }, "stored=$stored")
            assertEquals(AppdRoutes.MIGRATED_ID, book.activeId, "stored=$stored")
        }
    }

    @Test
    fun `a stored address stays chosen and is named after its own host`() {
        val book = AppdRoutes.migrate("http://10.0.0.9:8787", routePinned = false)
        assertEquals("10.0.0.9:8787", book.routes.first().name)
        assertEquals(RouteKind.LAN, book.routes.first().kind)
        assertEquals("https://10.0.0.9:8787", book.activeUrl, "upgraded on the way in (TLS.md rule 6)")
    }

    /**
     * ⚠ SPELLING IS NOT IDENTITY. The old setter only trimmed, and the client
     * prepended `http://` to a bare address, so `192.168.1.20:8787` (and even
     * `HTTP://…`) were storable and worked. Every spelling becomes the same one
     * canonical pin.
     */
    @Test
    fun `a stored address spelled differently migrates to the same pin`() {
        for (stored in listOf(
            "192.168.1.20:8787",
            "HTTP://192.168.1.20:8787",
            "http://192.168.1.20:8787/",
            " http://192.168.1.20:8787 ",
        )) {
            val book = AppdRoutes.migrate(stored, routePinned = false)
            assertEquals(1, book.routes.size, "stored='$stored' -> ${book.routes.map { it.url }}")
            assertEquals("https://192.168.1.20:8787", book.activeUrl, "stored='$stored'")
        }
    }

    @Test
    fun `a migrated address is stored canonically, not verbatim`() {
        val book = AppdRoutes.migrate("10.0.0.9:8787/", routePinned = false)
        assertEquals("https://10.0.0.9:8787", book.activeUrl, "the scheme is spelled out (and upgraded) and the slash is gone")
        assertEquals(RouteKind.LAN, book.routes.first().kind)
    }

    @Test
    fun `a pinned route becomes autoSwitch off, which is the same refusal to move`() {
        val pinned = AppdRoutes.migrate("https://192.168.1.20:8787", routePinned = true)
        assertTrue(!pinned.autoSwitch)
        assertEquals(AppdRoutes.MIGRATED_ID, pinned.activeId, "the pinned route is still the active one")

        val auto = AppdRoutes.migrate("https://192.168.1.20:8787", routePinned = false)
        assertTrue(auto.autoSwitch)
    }

    /** A fresh install pins nothing: the first address the user saves becomes pin #1. */
    @Test
    fun `a fresh install migrates to an empty book`() {
        for (nothing in listOf(null, "", "   ")) {
            val book = AppdRoutes.migrate(nothing, routePinned = false)
            assertEquals(emptyList(), book.routes, "stored=${nothing?.let { "'$it'" }}")
            assertNull(book.activeId)
            assertEquals("", book.activeUrl, "and therefore no derived base URL")
        }
    }

    /**
     * ⚠ AN UPGRADE MUST NOT DELETE THE USER'S ADDRESS WITHOUT SAYING SO. The old
     * "Base URL" field was free text with no validation, so an address the guard
     * refuses could be stored. It is carried back as the dropped address, and
     * nothing becomes active in its place.
     */
    @Test
    fun `a refused legacy address is named, and nothing is adopted in its place`() {
        val refused = listOf(
            "http://192.168.1.20:8787/api",
            "http://user@10.0.0.1:8787",
        )
        for (url in refused) for (pinned in listOf(false, true)) {
            val book = AppdRoutes.migrate(url, routePinned = pinned)
            assertEquals(url, book.droppedUrl, "stored=$url pinned=$pinned")
            assertNull(book.activeId, "nothing is adopted in its place: $url")
            assertEquals("", book.activeUrl, "and therefore no derived base URL: $url")
            assertEquals(emptyList(), book.routes, "and nothing else is offered: $url")
        }
    }

    /**
     * Over https the name stops mattering to the bearer (the TLS proof decides),
     * so a plain-http NAME the user chose stays chosen — upgraded, never dialled
     * in the clear.
     */
    @Test
    fun `a plain-http hostname migrates as https and stays chosen`() {
        for (url in listOf("http://huginn.lan:8787", "http://huginn:8787", "http://203.0.113.9:8787")) {
            val book = AppdRoutes.migrate(url, routePinned = false)
            assertNull(book.droppedUrl, url)
            assertEquals("https://" + url.removePrefix("http://"), book.activeUrl)
        }
    }

    @Test
    fun `an address the guard allows is not reported as dropped`() {
        assertNull(AppdRoutes.migrate("http://10.0.0.9:8787", routePinned = false).droppedUrl)
        assertNull(AppdRoutes.migrate(null, routePinned = false).droppedUrl)
    }

    /** The migrated `addedAt` is zero on purpose — see [AppdRoutes.MIGRATED_AT]. */
    @Test
    fun `migration is a pure function of the stored bytes`() {
        val a = AppdRoutes.migrate("https://192.168.1.20:8787", routePinned = false)
        val b = AppdRoutes.migrate("https://192.168.1.20:8787", routePinned = false)
        assertEquals(a, b, "two reads of unchanged bytes must produce the same book")
        assertTrue(a.routes.all { it.addedAt == AppdRoutes.MIGRATED_AT })
    }
}
