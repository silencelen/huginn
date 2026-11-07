package com.silencelen.huginn

import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.ui.DashboardBook
import com.silencelen.huginn.ui.DashboardShelf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The last rollup each project answered with, and what replaces it.
 *
 * Both shells used to hold one dashboard and null it on every walk into a
 * project, then wait a poll tick: "Nothing to show yet." for five seconds over a
 * cluster drawn a moment ago. The book is what makes a reopen draw at once.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class DashboardBookTest {

    private fun dashboard(id: String, at: Long) = ProjectDashboard(
        project = ProjectRow(id = id, name = id, slug = id, kind = "software", status = "active"),
        generatedAt = at,
    )

    @Test
    fun `reopening a project shows the rollup it last answered with until a newer one lands`() {
        val book = DashboardBook()
        val a1 = dashboard("a", at = 100)
        assertTrue(book.adopt("a", a1), "the first answer always lands")
        assertTrue(book.adopt("b", dashboard("b", at = 100)))
        // Back to a: what it said is still there, not null for a tick.
        assertSame(a1, book.held("a"))
        // The refetch replaces it only when it has moved.
        assertFalse(book.adopt("a", dashboard("a", at = 100)), "the daemon re-sent the same rollup")
        assertSame(a1, book.held("a"))
        val a2 = dashboard("a", at = 105)
        assertTrue(book.adopt("a", a2))
        assertSame(a2, book.held("a"))
        assertNull(book.held("never-opened"))
        assertNull(book.held(null))
    }

    @Test
    fun `a project that is gone takes its rollup with it`() {
        val book = DashboardBook()
        book.adopt("a", dashboard("a", at = 1))
        book.adopt("b", dashboard("b", at = 1))
        book.retain(listOf("b"))
        assertNull(book.held("a"))
        assertEquals("b", book.held("b")?.project?.id)
        book.forget("b")
        assertNull(book.held("b"))
    }

    @Test
    fun `the stamp rule - same stamp is the same rollup, zero is no stamp, and it never goes backwards`() {
        assertTrue(DashboardBook.moved(null, dashboard("a", at = 100)))
        assertFalse(DashboardBook.moved(dashboard("a", at = 100), dashboard("a", at = 100)))
        assertTrue(DashboardBook.moved(dashboard("a", at = 100), dashboard("a", at = 101)))
        assertFalse(DashboardBook.moved(dashboard("a", at = 200), dashboard("a", at = 150)), "a poll that overtook its predecessor")
        // ⚠ ZERO IS NOT A STAMP: a daemon that does not fill it must never freeze the screen.
        assertTrue(DashboardBook.moved(dashboard("a", at = 0), dashboard("a", at = 0)))
        assertTrue(DashboardBook.moved(dashboard("a", at = 100), dashboard("a", at = 0)))
        // Stamps are per answer, not per cluster: another project always lands.
        assertTrue(DashboardBook.moved(dashboard("a", at = 100), dashboard("b", at = 100)))
    }

    // ------------------------------------------------------------- the shelf

    /** A shelf as a map of encoded files: what a directory would hold. */
    private class MemoryShelf : DashboardShelf {
        val files = mutableMapOf<String, String>()
        override fun load(): Map<String, ProjectDashboard> = files.values.mapNotNull { DashboardBook.decode(it) }.toMap()
        override fun save(id: String, dashboard: ProjectDashboard) { files[id] = DashboardBook.encode(id, dashboard) }
        override fun remove(id: String) { files.remove(id) }
    }

    /**
     * ⚠ "SEEMS TO HAVE NO CACHE" (R5, 2026-11-07). The book fixed the walk-back
     * and not the relaunch: every start began empty, and the first open of each
     * project read "Nothing to show yet." until the daemon's first rollup
     * landed. A new book over the same shelf is a relaunch, and it draws at once.
     */
    @Test
    fun `what a project last said survives a relaunch - a new book over the same shelf draws it at once`() {
        val shelf = MemoryShelf()
        assertTrue(DashboardBook(shelf).adopt("a", dashboard("a", at = 100)))

        val relaunched = DashboardBook(shelf)
        assertEquals(100L, relaunched.held("a")?.generatedAt, "drawn from the shelf, not null until the first fetch")
        assertNull(relaunched.held("never"), "the empty text is only for a project no run ever had a rollup for")
        // The refetch replaces it only when it has moved, and the shelf follows.
        assertFalse(relaunched.adopt("a", dashboard("a", at = 100)), "the daemon re-sent the same rollup")
        assertTrue(relaunched.adopt("a", dashboard("a", at = 101)))
        assertEquals(101L, DashboardBook(shelf).held("a")?.generatedAt)
    }

    @Test
    fun `forget and retain take the shelf's copy with them`() {
        val shelf = MemoryShelf()
        val book = DashboardBook(shelf)
        book.adopt("a", dashboard("a", at = 1))
        book.adopt("b", dashboard("b", at = 1))
        book.adopt("c", dashboard("c", at = 1))
        book.retain(listOf("a", "b"))
        assertEquals(setOf("a", "b"), shelf.files.keys)
        book.forget("a")
        assertEquals(setOf("b"), shelf.files.keys)
        assertEquals("b", DashboardBook(shelf).held("b")?.project?.id, "and a relaunch sees only what was kept")
    }

    @Test
    fun `a shelf that cannot be read or written is an empty book, never a crash`() {
        val broken = object : DashboardShelf {
            override fun load(): Map<String, ProjectDashboard> = throw IllegalStateException("disk gone")
            override fun save(id: String, dashboard: ProjectDashboard) = throw IllegalStateException("disk gone")
            override fun remove(id: String) = throw IllegalStateException("disk gone")
        }
        val book = DashboardBook(broken)
        assertNull(book.held("a"))
        assertTrue(book.adopt("a", dashboard("a", at = 5)), "the book still works in memory")
        assertEquals(5L, book.held("a")?.generatedAt)
        book.retain(emptyList())
        book.forget("a")
        assertNull(book.held("a"))
    }

    @Test
    fun `the shelf format round-trips, carries the id beside the rollup, and refuses what it cannot read`() {
        val text = DashboardBook.encode("proj-1", dashboard("proj-1", at = 42))
        val (id, back) = DashboardBook.decode(text) ?: error("did not decode: $text")
        assertEquals("proj-1", id)
        assertEquals(42L, back.generatedAt)
        assertEquals("proj-1", back.project?.id)
        assertNull(DashboardBook.decode("not json at all"))
        assertNull(DashboardBook.decode("{}"), "no id is no entry")
        assertNull(DashboardBook.decode("""{"id":"","dashboard":{}}"""), "an empty id is no entry")
        // A field this client does not know (a newer app shelved it) is skipped, not fatal.
        assertEquals("p", DashboardBook.decode("""{"id":"p","dashboard":{"generatedAt":7,"future":true}}""")?.first)
    }
}
