package com.silencelen.huginn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The frame's answers to "how wide is this window".
 *
 * Every one of these fails as a LAYOUT rather than as an error: a list pane that
 * takes 76% of the window, a palette drawn wider than the window it is centred
 * in, a composer whose text field measures 35px between two buttons that would
 * not yield. None of them throws, none of them logs, and all of them look
 * deliberate in a screenshot — which is the whole argument for asserting the
 * arithmetic here instead.
 *
 * NOTE the kotlin.test argument order: (expected, actual, message). The reverse
 * of JUnit's, and already on this project's trap list.
 */
class ResponsiveTest {

    // ------------------------------------------------------------ the breakpoint

    @Test
    fun `compact is the phone's own breakpoint`() {
        // 700dp, the same number MainActivity asks — a desktop window narrowed to
        // a phone's width must get the phone's answer.
        assertEquals(700f, Responsive.COMPACT_BELOW_DP, "the two clients share one breakpoint")
        assertTrue(Responsive.compact(699f), "just under is compact")
        assertFalse(Responsive.compact(700f), "the breakpoint itself is NOT compact")
        assertFalse(Responsive.compact(1440f))
    }

    @Test
    fun `the shapes the audit measured are compact and the desk shapes are not`() {
        listOf(420f, 600f, 640f, 699f).forEach {
            assertTrue(Responsive.compact(it), "$it is a one-pane window")
        }
        listOf(768f, 900f, 1024f, 1280f, 1440f).forEach {
            assertFalse(Responsive.compact(it), "$it can hold both panes")
        }
    }

    // ------------------------------------------------------------- the list pane

    @Test
    fun `a wide window gets exactly the width that was dragged`() {
        // The whole point of persisting it: widening the window hands back the
        // number the reader chose, not a default and not a fraction.
        assertEquals(320f, Responsive.listWidth(persisted = 320f, windowDp = 1440f))
        assertEquals(480f, Responsive.listWidth(persisted = 480f, windowDp = 1440f))
    }

    @Test
    fun `a narrow window caps the pane at a fraction of itself`() {
        // 768x1024 portrait: the audit measured the list at 42% of the window with
        // the detail pane holding a composer whose placeholder wrapped four lines.
        assertEquals(322.56f, Responsive.listWidth(persisted = 560f, windowDp = 768f), 0.01f)
        // …and never below the readability floor, whatever the window does.
        assertEquals(
            Splitter.MIN,
            Responsive.listWidth(persisted = 560f, windowDp = 420f),
            "under the floor a list is three words and an ellipsis, so it stops",
        )
    }

    @Test
    fun `the cap never widens a pane`() {
        // A 220dp pane in a 3440 window is 220dp. The fraction is a CEILING.
        assertEquals(220f, Responsive.listWidth(persisted = 220f, windowDp = 3440f))
    }

    @Test
    fun `an out-of-bounds persisted width is still clamped`() {
        // The file on disk is editable, and a hand-typed 9000 must not reach the frame.
        assertEquals(Splitter.MAX, Responsive.listWidth(persisted = 9000f, windowDp = 3440f))
        assertEquals(Splitter.MIN, Responsive.listWidth(persisted = 10f, windowDp = 3440f))
    }

    // -------------------------------------------------------- what shuts the pane

    @Test
    fun `a wide window obeys the remembered collapse`() {
        assertFalse(Responsive.listCollapsed(persisted = false, compact = false, revealed = false))
        assertTrue(Responsive.listCollapsed(persisted = true, compact = false, revealed = false))
    }

    @Test
    fun `compact folds the pane away whatever was remembered`() {
        assertTrue(
            Responsive.listCollapsed(persisted = false, compact = true, revealed = false),
            "420dp of window left the detail pane 40dp wide — this is the fix",
        )
    }

    @Test
    fun `the notch still works while compact, and only for now`() {
        assertFalse(
            Responsive.listCollapsed(persisted = false, compact = true, revealed = true),
            "Ctrl+B on a narrow window must still show the list",
        )
        // And nothing it did leaks into the remembered answer: widen the window and
        // the persisted preference is the one that applies again.
        assertFalse(Responsive.listCollapsed(persisted = false, compact = false, revealed = true))
        assertTrue(Responsive.listCollapsed(persisted = true, compact = false, revealed = true))
    }

    // ---------------------------------------------------------------- the palette

    @Test
    fun `the palette keeps its measure while there is room for it`() {
        assertEquals(620f, Responsive.paletteWidth(1440f))
        assertEquals(620f, Responsive.paletteWidth(652f), "exactly 620 + the margin")
    }

    @Test
    fun `the palette never draws wider than its window`() {
        // The audit's 600x1000 shot: a flat 620dp card bleeding off BOTH edges.
        assertEquals(568f, Responsive.paletteWidth(600f))
        assertEquals(388f, Responsive.paletteWidth(420f))
        assertTrue(Responsive.paletteWidth(420f) < 420f, "it has to fit, that is the whole job")
    }

    @Test
    fun `an absurdly small window still gets a positive width`() {
        // Never zero and never negative: a Surface measured at -12dp throws.
        assertTrue(Responsive.paletteWidth(20f) > 0f)
    }

    @Test
    fun `the palette list grows with the window instead of clipping at a constant`() {
        assertTrue(
            Responsive.paletteListHeight(1000f) > Responsive.paletteListHeight(600f),
            "880px of unused window under a list clipped at a constant is the defect",
        )
        assertEquals(408f, Responsive.paletteListHeight(600f))
        assertEquals(120f, Responsive.paletteListHeight(200f), "…and never a sliver")
        assertEquals(560f, Responsive.paletteListHeight(4000f), "…nor a wall of rows")
    }

    // --------------------------------------------------------------- the composer

    @Test
    fun `the composer is full at desk widths and compact when snapped`() {
        assertEquals(ComposerLayout.FULL, ComposerLayout.of(1068f), "1440 window, list open")
        assertEquals(ComposerLayout.FULL, ComposerLayout.of(560f), "the breakpoint itself is full")
        assertEquals(ComposerLayout.COMPACT, ComposerLayout.of(559f))
        // The owner's own shapes: 600x1000 and 420x860 with the list collapsed.
        assertEquals(ComposerLayout.COMPACT, ComposerLayout.of(548f), "600 window less rail and padding")
        assertEquals(ComposerLayout.COMPACT, ComposerLayout.of(344f), "420 window, list collapsed")
    }

    @Test
    fun `the field never takes more than its share of the pane`() {
        // 420x860: 160dp of composer was 22% of the window for an EMPTY box.
        assertEquals(160f, Composer.fieldMaxHeight(860f), "a desk window keeps the old cap")
        assertEquals(120f, Composer.fieldMaxHeight(300f), "a short pane gets 40% of itself")
        assertTrue(
            Composer.fieldMaxHeight(300f) < Composer.FIELD_MAX_DP,
            "the constant cap is the half of the complaint a breakpoint does not fix",
        )
    }

    @Test
    fun `the field cap is never below the height of one line`() {
        assertEquals(
            Composer.FIELD_MIN_DP,
            Composer.fieldMaxHeight(60f),
            "a max under the min is a field that cannot draw its own first line",
        )
    }

    @Test
    fun `the keyboard lesson is dropped whole rather than clipped`() {
        val full = "Message…"
        val hint = "Enter to send · Shift+Enter for a new line"
        assertEquals("$full  ($hint)", Composer.placeholder(full, hint, ComposerLayout.FULL))
        assertEquals(
            full,
            Composer.placeholder(full, hint, ComposerLayout.COMPACT),
            "half a sentence about Shift+Enter teaches nothing; F1 still carries all of it",
        )
    }
}
