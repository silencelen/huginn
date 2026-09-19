package com.silencelen.huginn.desktop.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TAB IS FOCUS, AND IT WAS A CHARACTER.
 *
 * ⚠⚠ Pressing Tab in the composer inserted `\t` and left the focus ring where it
 * was — three presses, three tabs in the message, ring unmoved. From the box a
 * person types in there was therefore NO keyboard route to the Send button, the
 * attachment clip, the suggestion chips or the tab strip: on a desktop client
 * that is not a rough edge, it is the difference between an app somebody can
 * drive without a mouse and one they cannot.
 *
 * A text field that wants a literal tab is a code editor. This is a chat
 * composer whose own placeholder documents Shift+Enter for the one whitespace
 * case it needs and whose Enter already sends. So the literal keeps a chord.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ComposerKeysTest {

    @Test
    fun `bare Tab moves forward and Shift Tab moves back`() {
        assertEquals(TabMove.NEXT, tabMove(ctrl = false, shift = false, alt = false, meta = false))
        assertEquals(TabMove.PREVIOUS, tabMove(ctrl = false, shift = true, alt = false, meta = false))
    }

    @Test
    fun `the literal tab keeps one chord, and only one`() {
        assertEquals(TabMove.LITERAL, tabMove(ctrl = true, shift = false, alt = false, meta = false))
        assertEquals(
            TabMove.LITERAL, tabMove(ctrl = true, shift = true, alt = false, meta = false),
            "Ctrl+Shift+Tab is still the literal: the modifier that asked for a character is present",
        )
    }

    /**
     * ⚠ ALT+TAB IS THE WINDOW MANAGER'S and Cmd+Tab is macOS's. Swallowing either
     * from inside a text box — to insert a character or to move a ring — takes a
     * system gesture away from the desk with no way to get it back.
     */
    @Test
    fun `alt and meta are never ours`() {
        assertEquals(TabMove.NONE, tabMove(ctrl = false, shift = false, alt = true, meta = false))
        assertEquals(TabMove.NONE, tabMove(ctrl = false, shift = false, alt = false, meta = true))
        assertEquals(TabMove.NONE, tabMove(ctrl = true, shift = false, alt = true, meta = false))
        assertEquals(TabMove.NONE, tabMove(ctrl = false, shift = true, alt = false, meta = true))
    }

    /**
     * The splice is [newlineIn]'s, unchanged — see its KDoc for why min/max and
     * not start/end. A tab has to go through the same one, or the two insertions
     * in one composer would disagree about a reversed selection.
     */
    @Test
    fun `a literal tab is spliced like a newline is`() {
        assertEquals("a\tb", tabIn(TextFieldValue("ab", TextRange(1))).text)
        assertEquals(TextRange(2), tabIn(TextFieldValue("ab", TextRange(1))).selection)
        // A REVERSED range: Shift+Home from the end hands `start > end` through
        // unnormalised, and an overlapping splice doubled the draft.
        val backwards = TextFieldValue("rebuild the index", TextRange(17, 0))
        assertEquals("\t", tabIn(backwards).text, "the selection is replaced, not wrapped")
        assertEquals("\n", newlineIn(backwards).text)
    }

    /**
     * And BOTH composers do it. There are two — the chat's and the session's —
     * and the bug was reported against one of them; a fix in one box while the
     * other still types tabs is the drift `ComposerFrame` exists to prevent.
     */
    @Test
    fun `both composers hand Tab to the focus manager`() {
        listOf("ChatView.kt", "SessionView.kt").forEach { name ->
            val src = File("src/main/kotlin/com/silencelen/huginn/desktop/ui/$name").readText()
            assertTrue(src.length > 10_000, "$name read as ${src.length} chars — wrong file")
            val handler = src.substringAfter("e.key == Key.Enter -> { submit(); true }")
                .substringBefore("else -> false")
            assertTrue(handler.length in 1..3_000, "$name: the composer key handler was not found")
            assertTrue(
                "e.key == Key.Tab" in handler,
                "$name must claim Tab, or the field inserts one and the ring never moves",
            )
            assertTrue(
                "moveFocus(FocusDirection.Next)" in handler &&
                    "moveFocus(FocusDirection.Previous)" in handler,
                "$name: Tab and Shift+Tab must both move the focus ring:\n$handler",
            )
            assertTrue("tabIn(field)" in handler, "$name: Ctrl+Tab must still type a real tab")
        }
    }
}
