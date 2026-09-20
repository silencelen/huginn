package com.silencelen.huginn.desktop.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠ D-23. THE SEARCH FOUND THE ROW AND THE PANE NEVER WENT THERE.
 *
 * Searching settings for *awake* matched "Keep a window rotating" and opened
 * Usage & headroom at the TOP, with the matched row two full screens below the
 * fold. Every part of the journey already existed — the hit, the category, the
 * row id, the arrival mark — except the last one.
 *
 * The arithmetic is what can be wrong here, so it is pure and asserted: the row
 * and the pane both report ROOT coordinates, which move as the pane scrolls, so
 * the answer is a difference added to where the pane already is rather than an
 * absolute.
 */
class SettingsRevealTest {

    private fun reveal(mark: String?, at: Int = 0, max: Int = 10_000): Pair<SettingsReveal, MutableList<Int>> {
        val moves = mutableListOf<Int>()
        return SettingsReveal(
            mark = mark,
            here = { at },
            extent = { max },
            goTo = { moves += it },
        ) to moves
    }

    @Test
    fun `a row two screens down is scrolled to, less a margin`() {
        // Pane top at y=120; the row landed at y=1120, so it is 1000 below the
        // fold while the pane sits at 0.
        val want = SettingsReveal.scrollTarget(current = 0, paneTop = 120f, rowTop = 1120f, max = 10_000)
        assertEquals((1000 - SettingsReveal.MARGIN_DP).toInt(), want)
    }

    @Test
    fun `the offset is added to where the pane already is`() {
        // Same row, but the reader had already scrolled 400 down: root
        // coordinates have moved with them, so the sum must carry that.
        val want = SettingsReveal.scrollTarget(current = 400, paneTop = 120f, rowTop = 720f, max = 10_000)
        assertEquals(400 + 600 - SettingsReveal.MARGIN_DP.toInt(), want)
    }

    @Test
    fun `a row near the top does not scroll past zero`() {
        assertEquals(0, SettingsReveal.scrollTarget(current = 0, paneTop = 120f, rowTop = 130f, max = 10_000))
    }

    @Test
    fun `a row at the end of a short page stops at the end of it`() {
        assertEquals(300, SettingsReveal.scrollTarget(current = 0, paneTop = 0f, rowTop = 9_000f, max = 300))
        // A page with nothing to scroll must not be asked to scroll backwards.
        assertEquals(0, SettingsReveal.scrollTarget(current = 0, paneTop = 0f, rowTop = 9_000f, max = 0))
    }

    @Test
    fun `only the marked row is asked to report where it is`() {
        val (r, _) = reveal("usage.keep-awake")
        assertTrue(r.claims("usage.keep-awake"))
        assertFalse(r.claims("usage.plan"))
    }

    @Test
    fun `an ordinary open claims nothing`() {
        val (r, _) = reveal(null)
        assertFalse(r.claims("usage.plan"))
        assertFalse(r.claimsAny { true }, "a null mark is not every mark")
    }

    @Test
    fun `a section claims the rows it draws but this shell cannot reach`() {
        val (r, _) = reveal("usage.keep-awake")
        assertTrue(r.claimsAny { it != "usage.plan" })
        val (plan, _) = reveal("usage.plan")
        assertFalse(plan.claimsAny { it != "usage.plan" }, "the row the page draws itself is exact")
    }

    @Test
    fun `nothing moves before the pane has said where its top is`() {
        val (r, moves) = reveal("usage.keep-awake")
        assertFalse(r.target(900f), "no anchor yet")
        assertTrue(moves.isEmpty())
    }

    /**
     * `onGloballyPositioned` fires on every layout pass — a hover, a poll landing
     * a fresh summary, the reader's own wheel. A reveal that re-fired would drag
     * the pane back to the row every time they scrolled away from it.
     */
    @Test
    fun `one arrival moves the pane exactly once`() {
        val (r, moves) = reveal("usage.keep-awake")
        r.anchorPane(100f)
        assertTrue(r.target(1100f))
        assertFalse(r.target(1100f))
        assertFalse(r.target(1400f), "and not when a later pass measures it somewhere else")
        assertEquals(1, moves.size)
        assertEquals(1000 - SettingsReveal.MARGIN_DP.toInt(), moves.single())
    }
}
