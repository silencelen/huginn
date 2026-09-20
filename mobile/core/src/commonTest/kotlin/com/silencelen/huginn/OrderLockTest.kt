package com.silencelen.huginn

import com.silencelen.huginn.ui.OrderLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * ⚠⚠ P-02 (high). A tap on row 2's "Kill session" raised
 * "Wrap up **rv-desktop-1**?" — a different verb on a different session —
 * because the list re-sorted by `activityAt` between the menu opening and the
 * item being pressed. Three mis-targets in five minutes on the phone, and the
 * desktop walker wrapped up the phone walker's project lead the same way.
 *
 * Every case here is about the same sentence: while something is open over the
 * list, what the reader can SEE is what they get.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class OrderLockTest {

    private fun keys(rows: List<String>) = rows

    @Test
    fun `unlocked, the server's order is the order`() {
        val incoming = listOf("a", "b", "c")
        val out = OrderLock.order(listOf("c", "b", "a"), incoming, frozen = false) { it }
        assertSame(incoming, out, "no gesture is open, so nothing is held — and nothing is copied either")
    }

    /**
     * THE BUG, EXACTLY. The reader is looking at `docs-reviewer` second and has
     * its menu open; the daemon re-sorts and `rv-desktop-1` becomes second. The
     * menu must still belong to the row it was opened on.
     */
    @Test
    fun `locked, a re-sort under an open menu changes nothing on screen`() {
        val shown = listOf("lead", "docs-reviewer", "rv-desktop-1")
        val resorted = listOf("rv-desktop-1", "lead", "docs-reviewer")
        assertEquals(shown, OrderLock.order(shown, resorted, frozen = true) { it })
    }

    @Test
    fun `a row that appears while the menu is open goes to the end`() {
        // There is no position a brand-new row could take that the reader has
        // already seen, so it takes the only honest one.
        val shown = listOf("a", "b")
        val incoming = listOf("new", "b", "a")
        assertEquals(listOf("a", "b", "new"), OrderLock.order(shown, incoming, frozen = true) { it })
    }

    @Test
    fun `several new rows keep the server's order among themselves`() {
        val shown = listOf("a")
        val incoming = listOf("n2", "a", "n1")
        assertEquals(listOf("a", "n2", "n1"), OrderLock.order(shown, incoming, frozen = true) { it })
    }

    /**
     * ⚠ THE CONTENTS ARE NOT FROZEN, only the ORDER. A session that ended while
     * a dialog was open is gone, and a list that kept drawing it would offer a
     * verb that cannot do anything. The confirm dialog names its target, so the
     * reader is told either way.
     */
    @Test
    fun `a row that ends while the menu is open is not kept alive`() {
        val shown = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), OrderLock.order(shown, listOf("c", "a"), frozen = true) { it })
    }

    @Test
    fun `nothing on screen yet means nothing to hold to`() {
        val incoming = listOf("a", "b")
        assertEquals(incoming, OrderLock.order(emptyList(), incoming, frozen = true) { it })
        assertEquals(emptyList(), OrderLock.order(listOf("a"), emptyList<String>(), frozen = true) { it })
    }

    /**
     * ⚠ THE LOCK IS NOT A CACHE. Releasing it must hand back the server's order
     * immediately — a lock that outlived the gesture would be a list that had
     * quietly stopped sorting, which is a worse bug than the one it fixes
     * because nothing about it looks wrong.
     */
    @Test
    fun `releasing the lock restores the server's order at once`() {
        val shown = listOf("a", "b", "c")
        val resorted = listOf("c", "b", "a")
        assertEquals(shown, OrderLock.order(shown, resorted, frozen = true) { it })
        assertEquals(resorted, OrderLock.order(shown, resorted, frozen = false) { it })
    }

    @Test
    fun `identity is the key, never the index`() {
        // Rows carry more than their name, and the same session re-fetched is a
        // different object with different fields. Ranking on the key is what
        // makes "the row I opened" survive that.
        data class Row(val name: String, val activityAt: Long)
        val shown = listOf("a", "b")
        val incoming = listOf(Row("b", 200), Row("a", 100))
        assertEquals(
            listOf("a", "b"),
            OrderLock.order(shown, incoming, frozen = true) { it.name }.map { it.name },
        )
    }

    @Test
    fun `keysOf records what was drawn`() {
        data class Row(val name: String)
        assertEquals(listOf("x", "y"), OrderLock.keysOf(listOf(Row("x"), Row("y"))) { it.name })
    }

    /** A duplicate key cannot rank twice, and must not drop a row either. */
    @Test
    fun `a repeated key in the remembered order does not lose a row`() {
        val shown = listOf("a", "a", "b")
        assertEquals(listOf("a", "b"), OrderLock.order(shown, listOf("b", "a"), frozen = true) { it })
    }
}
