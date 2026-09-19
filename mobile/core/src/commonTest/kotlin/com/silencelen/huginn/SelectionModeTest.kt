package com.silencelen.huginn

import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.ui.SelectionAction
import com.silencelen.huginn.ui.SelectionBarItem
import com.silencelen.huginn.ui.SelectionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The selecting state, hoisted out of the phone's long-press gesture.
 *
 * It is here rather than in a lambda beside the gesture because "is this
 * selection worth offering anything for" is a DECISION, and one both clients have
 * to answer the same way — the desktop asks it of a right-click and the phone of
 * a long-press, and a bar that appears over an empty selection is the version of
 * this feature that gets turned off.
 */
class SelectionModeTest {

    private val hostActions = QuickActions(
        rev = 1,
        explain = "Explain this, briefly:\n\n{selection}",
        execute = "Run this and show me the output:\n\n{selection}",
        askInNewChat = "{selection}\n\nWhat is going on here?",
        quote = "",
    )

    @Test
    fun `nothing is selected until something is`() {
        assertFalse(SelectionMode.NONE.active)
        assertEquals("", SelectionMode.NONE.text)
        assertEquals(emptyList(), SelectionMode.NONE.actions(hostActions))
    }

    @Test
    fun `an active selection offers every verb the host has wording for`() {
        val mode = SelectionMode.begin("ls -la")
        assertTrue(mode.active)
        assertEquals(
            listOf(
                SelectionAction.EXPLAIN,
                SelectionAction.EXECUTE,
                SelectionAction.QUOTE,
                SelectionAction.ASK_IN_NEW_CHAT,
            ),
            mode.actions(hostActions),
        )
        // The older-daemon probe, arriving here in the only form the bar sees it.
        assertEquals(listOf(SelectionAction.QUOTE), mode.actions(null))
    }

    @Test
    fun `an active mode over a blank selection offers nothing`() {
        // A long-press that caught no words still enters selection mode — the
        // toolkit decides that, not us — and the strip must be empty rather than
        // offering four verbs over nothing.
        assertEquals(emptyList(), SelectionMode.begin("   ").actions(hostActions))
        assertEquals(emptyList(), SelectionMode.begin("").actions(hostActions))
    }

    @Test
    fun `dragging the handles re-asks the question, and dismiss ends it`() {
        val started = SelectionMode.begin("ls")
        val widened = started.select("ls -la")
        assertEquals("ls -la", widened.text)
        assertTrue(widened.active)
        assertEquals(SelectionMode.NONE, widened.dismiss())
        assertEquals(emptyList(), widened.dismiss().actions(hostActions))
    }

    /**
     * THE BAR ALWAYS ENDS WITH A WAY OUT.
     *
     * The verbs sit in a horizontal scroll, so on a narrow phone the last of them
     * is already off the right edge — which is where the dismiss used to be. It
     * is an X pinned outside that scroll now, and it is part of the LIST rather
     * than a fixture of the composable precisely so this can assert it: a bar
     * that can be raised and not put down is the version that gets turned off.
     */
    @Test
    fun `the bar offers the verbs, then Copy, then a way out`() {
        val items = SelectionMode.begin("ls -la").barItems(hostActions)
        assertEquals(
            listOf(
                SelectionBarItem.Verb(SelectionAction.EXPLAIN),
                SelectionBarItem.Verb(SelectionAction.EXECUTE),
                SelectionBarItem.Verb(SelectionAction.QUOTE),
                SelectionBarItem.Verb(SelectionAction.ASK_IN_NEW_CHAT),
                SelectionBarItem.Copy,
                SelectionBarItem.Cancel,
            ),
            items,
        )
        assertEquals(SelectionBarItem.Cancel, items.last(), "the way out is last, at the trailing end")
    }

    @Test
    fun `an older daemon still gets a way out`() {
        // One verb offered, not four — and the cancel is not attached to the
        // count. A bar with a single Quote in it is still a bar to get out of.
        val items = SelectionMode.begin("ls -la").barItems(null)
        assertEquals(
            listOf(SelectionBarItem.Verb(SelectionAction.QUOTE), SelectionBarItem.Copy, SelectionBarItem.Cancel),
            items,
        )
    }

    @Test
    fun `no bar means no way out to draw`() {
        // The strip is not drawn at all over a blank or run-away selection, so a
        // lone X floating over nothing would be a control with no subject.
        assertEquals(emptyList(), SelectionMode.begin("   ").barItems(hostActions))
        assertEquals(emptyList(), SelectionMode.NONE.barItems(hostActions))
        assertTrue(SelectionMode.begin("ls").barItems(hostActions).isNotEmpty())
    }

    @Test
    fun `the way out is the dismiss path, not a fifth verb`() {
        // What the X maps to, stated where it can be checked: it ends the
        // selection outright — the same NONE that Back produces — rather than
        // staging text like every other item in the bar.
        val mode = SelectionMode.begin("ls -la", "Yesterday 21:40")
        val cancel = mode.barItems(hostActions).last()
        assertTrue(cancel is SelectionBarItem.Cancel)
        assertEquals(SelectionMode.NONE, mode.dismiss())
        assertEquals(emptyList(), mode.dismiss().barItems(hostActions), "and the bar is gone with it")
    }

    /**
     * The phone's timestamp reveal rides here, in words rather than as a number:
     * formatting one needs a clock and a zone, and `:core` deliberately has
     * neither. The bar draws the line only when this is non-blank, which is how a
     * row the daemon sent no `ts` for gets no line at all instead of an empty one.
     */
    @Test
    fun `the selection carries when the row was written, and survives the handles moving`() {
        assertEquals("", SelectionMode.begin("ls").at, "no stamp offered, no line drawn")
        assertEquals("", SelectionMode.NONE.at)
        val stamped = SelectionMode.begin("ls -la", "Yesterday 21:40")
        assertEquals("Yesterday 21:40", stamped.at)
        // Widening the selection is the same row at the same time.
        assertEquals("Yesterday 21:40", stamped.select("ls -la /tmp").at)
        assertEquals("", stamped.dismiss().at)
    }
}
