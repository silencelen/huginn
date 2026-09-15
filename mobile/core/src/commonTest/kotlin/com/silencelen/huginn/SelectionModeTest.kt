package com.silencelen.huginn

import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.ui.SelectionAction
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
}
