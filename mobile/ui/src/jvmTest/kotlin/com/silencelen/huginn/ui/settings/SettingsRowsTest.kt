package com.silencelen.huginn.ui.settings

import com.silencelen.huginn.data.PinnedRoute
import com.silencelen.huginn.data.RouteBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE ROUTE FORM'S RULES, asserted without a window.
 *
 * The composables in `SettingsRows.kt` cannot be driven headless, so the
 * decisions the route form actually makes live beside them as pure functions —
 * the same arrangement `HeadroomViewsTest` describes, and for the same reason:
 * the mistakes were never in the pixels.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SettingsRowsTest {

    private val now = 1_700_000_000_000L

    // ------------------------------------------------- #D13 the address line

    /**
     * ⚠ THE ADDRESS SHARED A ROW WITH FIVE CONTROLS AND LOST. "in use"/"Use",
     * two reorder arrows and Edit all sat beside a `weight(1f)` column at
     * `maxLines = 1`, so a perfectly ordinary pin rendered as
     * `http://192.168.2.117:8…` — the PORT, which is the half that says whether
     * this is even the daemon's address, was the first thing cut. And the
     * witness clause joined onto the same line went with it, so the row lost
     * "last reached" entirely at exactly the moment somebody was reading the
     * list to find out which route still works.
     */
    @Test
    fun `the address keeps its port, and its witness, on its own line`() {
        val h = com.silencelen.huginn.data.RouteHealth(lastOkAt = now - 240_000)
        assertEquals(
            "http://192.168.2.117:8787 · last reached 4m ago",
            routeAddressLine("http://192.168.2.117:8787", h, now),
        )
        assertEquals(
            "http://192.168.2.117:8787",
            routeAddressLine("http://192.168.2.117:8787", null, now),
            "no probe yet says nothing rather than guessing",
        )
    }

    @Test
    fun `an address too long for two lines loses its MIDDLE, never its port`() {
        val long = "http://a-very-long-magicdns-hostname-for-exactly-one-machine.tailnet-1234abcd.ts.net:8787"
        val out = middleElide(long)
        assertTrue(out.length <= ROUTE_URL_MAX, "still $out")
        assertTrue(out.endsWith(":8787"), "the port is the half that identifies the daemon: $out")
        assertTrue(out.startsWith("http://"), "and the scheme is the half that says whether it is plain: $out")
        assertTrue("…" in out, "something has to say it was cut: $out")
        assertEquals(long, middleElide(long, long.length), "an address that fits is left alone")
    }

    /**
     * The layout half, asserted against the source because there is no
     * compose-ui-test here — the same arrangement `DisclosureHeightOnlyTest`
     * uses. What can be checked is that the address is no longer emitted INSIDE
     * the row that carries the controls.
     */
    @Test
    fun `the address is drawn after the controls, not beside them`() {
        val f = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").isFile }
            ?.let { java.io.File(it, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/settings/SettingsRows.kt") }
        assertTrue(f != null && f.isFile, "SettingsRows.kt not found from ${java.io.File("").absolutePath}")
        val body = f!!.readText().substringAfter("private fun RouteRow(").substringBefore("private fun RouteForm(")
        assertTrue(body.length > 1_000, "RouteRow read as ${body.length} chars — wrong slice")
        val edit = body.indexOf("actions.move(route.id, 1)")
        val address = body.indexOf("routeAddressLine(")
        assertTrue(edit > 0 && address > 0, "the gate lost its subject: controls=$edit address=$address")
        assertTrue(
            address > edit,
            "the address is back inside the controls row, where it gets one line and an ellipsis",
        )
        assertTrue("maxLines = 2" in body, "the address line must be allowed a second line")
    }

    private fun book(): RouteBook = RouteBook()
        .add("Tailscale", "http://100.64.0.1:8787", now, id = "r1")
        .add("Yggdrasil", "http://192.168.2.117:8787", now, id = "r2")

    /**
     * A shell exactly as fire-and-forget as both of ours were: every action
     * captures the book AS IT STOOD WHEN THE ACTION WAS CALLED and applies it
     * later, wholesale. Two actions from one Save therefore race, and the
     * second's write — computed on the pre-edit book — lands last and wins.
     *
     * Not a caricature: it is what `HuginnViewModel.editRoutes` and
     * `AppStore.editRoutes` did before the Mutex, transcribed.
     */
    private class LosingShell(var book: RouteBook) {
        private val pending = mutableListOf<() -> RouteBook>()
        var ops = 0

        fun actions(): RouteListActions = RouteListActions(
            rename = { id, name -> enqueue { it.rename(id, name) } },
            setUrl = { id, url -> enqueue { it.setUrl(id, url) } },
            editBoth = { id, name, url -> enqueue { it.rename(id, name).setUrl(id, url) } },
        )

        private fun enqueue(edit: (RouteBook) -> RouteBook) {
            ops++
            val seen = book
            pending += { runCatching { edit(seen) }.getOrDefault(book) }
        }

        /** Every queued write lands, last one wins — the losing order. */
        fun settle() {
            for (p in pending) book = p()
            pending.clear()
        }
    }

    // ------------------------------------------------- #64 one Save, one op

    @Test
    fun `a name and an address changed in one Save both survive`() {
        val shell = LosingShell(book())
        routeFormSave(
            shell.actions(), "r1",
            was = "Tailscale", wasUrl = "http://100.64.0.1:8787",
            name = "the mesh", url = "http://100.64.0.9:8787",
        )
        shell.settle()
        val r = shell.book.routes.first { it.id == "r1" }
        // The rename is the casualty when this is two writes: the URL write was
        // computed on the book as it stood BEFORE the rename, and lands last.
        assertEquals("the mesh", r.name, "the new name must survive a same-Save address change")
        assertEquals("http://100.64.0.9:8787", r.url, "and so must the new address")
    }

    @Test
    fun `one Save is one book operation`() {
        val shell = LosingShell(book())
        routeFormSave(
            shell.actions(), "r1",
            was = "Tailscale", wasUrl = "http://100.64.0.1:8787",
            name = "the mesh", url = "http://100.64.0.9:8787",
        )
        assertEquals(1, shell.ops, "two mutations for one Save is the race itself")
    }

    @Test
    fun `a Save that changes nothing writes nothing`() {
        val shell = LosingShell(book())
        routeFormSave(
            shell.actions(), "r1",
            was = "Tailscale", wasUrl = "http://100.64.0.1:8787",
            name = "Tailscale", url = "http://100.64.0.1:8787",
        )
        assertEquals(0, shell.ops)
    }

    @Test
    fun `renaming alone still renames`() {
        val shell = LosingShell(book())
        routeFormSave(
            shell.actions(), "r1",
            was = "Tailscale", wasUrl = "http://100.64.0.1:8787",
            name = "the mesh", url = "http://100.64.0.1:8787",
        )
        shell.settle()
        assertEquals("the mesh", shell.book.routes.first { it.id == "r1" }.name)
    }

    /** With no shell wiring, the combined edit still reaches the two old verbs. */
    @Test
    fun `edit falls back to rename then setUrl for a shell that has not wired it`() {
        val seen = mutableListOf<String>()
        val actions = RouteListActions(
            rename = { id, n -> seen += "rename:$id:$n" },
            setUrl = { id, u -> seen += "setUrl:$id:$u" },
        )
        actions.edit("r1", "the mesh", "http://100.64.0.9:8787")
        assertEquals(listOf("rename:r1:the mesh", "setUrl:r1:http://100.64.0.9:8787"), seen)
    }

    // ------------------------------------------- #81 the refusal, before the shell

    @Test
    fun `an address the guard refuses is refused by the form, not by closing it`() {
        val b = book()
        refuses(routeFormRefusal(b, null, "huginn.example.com:8787"))
        refuses(routeFormRefusal(b, null, "http://huginn.local:8787/v1"))
        refuses(routeFormRefusal(b, null, "example.com"))
        refuses(routeFormRefusal(b, null, "   "))
    }

    @Test
    fun `an address already pinned is refused by the form`() {
        val b = book()
        assertEquals(RouteBook.DUPLICATE, routeFormRefusal(b, null, "http://100.64.0.1:8787"))
        // ...but a route is never a duplicate of itself.
        assertNull(routeFormRefusal(b, "r1", "http://100.64.0.1:8787"))
        assertEquals(RouteBook.DUPLICATE, routeFormRefusal(b, "r2", "http://100.64.0.1:8787"))
    }

    @Test
    fun `a full book refuses a ninth, but not an edit of one of the eight`() {
        var b = RouteBook()
        for (i in 1..RouteBook.MAX_PINS) b = b.add("r$i", "http://10.0.0.$i:8787", now, id = "r$i")
        assertEquals(RouteBook.FULL, routeFormRefusal(b, null, "http://10.0.1.1:8787"))
        assertNull(routeFormRefusal(b, "r1", "http://10.0.1.1:8787"))
    }

    @Test
    fun `a good address is not refused`() {
        assertNull(routeFormRefusal(book(), null, "http://100.64.0.9:8787"))
        assertNull(routeFormRefusal(book(), "r1", "http://100.64.0.9:8787"))
        // The same address as typed, normalised, is still its own route.
        assertNull(routeFormRefusal(book(), "r1", "http://100.64.0.1:8787/"))
    }

    private fun refuses(s: String?) =
        assertTrue(!s.isNullOrBlank(), "a refused address must come back with a sentence")

    // ------------------------------------------- the candidate that is OFFERED

    /**
     * ⚠ OFFERED, NEVER TAKEN. `RouteResolver.Choice.Stay.Candidate` exists
     * because moving to a plain-http address NOBODY TYPED hands that host the
     * daemon's bearer in cleartext — and the two addresses an upgrade seeds are
     * hard-coded literals in a public repo. The resolver has always refused the
     * move; until now no shell said so, which made the refusal indistinguishable
     * from nothing having answered at all.
     *
     * The sentence says "answered", not "is better": the only fact there is
     * about this address is that huginn replied on it.
     */
    @Test
    fun `the offer names the route and asks`() {
        val candidate = PinnedRoute(id = "yggdrasil", name = "Yggdrasil", url = "http://192.168.2.117:8787")
        assertEquals("Yggdrasil answered — use it?", routeCandidateOffer(candidate))
    }

    /** A seeded pin with no name falls back to its address, never to a blank. */
    @Test
    fun `an unnamed candidate is named by its address`() {
        val candidate = PinnedRoute(id = "x", name = "", url = "http://192.168.2.117:8787")
        assertEquals("http://192.168.2.117:8787 answered — use it?", routeCandidateOffer(candidate))
    }

    /**
     * ⚠ ITS OWN VERB. Adopting an offer is not the same gesture as choosing
     * between addresses the owner already trusts: [RouteListActions.activate]
     * moves between known pins, and this one GRANTS TRUST. A shell that has not
     * wired it offers nothing and adopts nothing, which is the behaviour every
     * client had before the case existed.
     */
    @Test
    fun `a shell that has not wired the offer adopts nothing`() {
        var activated: String? = null
        val actions = RouteListActions(activate = { activated = it })
        actions.useCandidate("yggdrasil")
        assertNull(activated, "the default must not quietly fall through to activate")
    }

    @Test
    fun `a wired shell adopts exactly the offered route`() {
        var used: String? = null
        val actions = RouteListActions(useCandidate = { used = it })
        actions.useCandidate("yggdrasil")
        assertEquals("yggdrasil", used)
    }
}
