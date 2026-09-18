package com.silencelen.huginn.ui.settings

import com.silencelen.huginn.data.RouteBook
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
