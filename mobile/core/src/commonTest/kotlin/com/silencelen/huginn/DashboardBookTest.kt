package com.silencelen.huginn

import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.ui.DashboardBook
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
}
