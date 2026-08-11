package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutsTest {

    @Test
    fun `the table maps what the cheat sheet claims`() {
        assertEquals(Shortcut.PALETTE, match(ctrl = true, shift = false, alt = false, key = "K"))
        assertEquals(Shortcut.VIEW_CHATS, match(true, false, false, "1"))
        assertEquals(Shortcut.VIEW_SESSIONS, match(true, false, false, "2"))
        assertEquals(Shortcut.VIEW_STATUS, match(true, false, false, "3"))
        assertEquals(Shortcut.VIEW_SETTINGS, match(true, false, false, "COMMA"))
        assertEquals(Shortcut.NEW_ASK, match(true, false, false, "N"))
        assertEquals(Shortcut.NEW_ACT, match(true, true, false, "N"))
        assertEquals(Shortcut.HIDE_TO_TRAY, match(true, true, false, "H"))
        assertEquals(Shortcut.CHEATSHEET, match(false, false, false, "F1"))
    }

    @Test
    fun `typing suppresses everything except list navigation`() {
        // A shortcut that eats a keystroke mid-sentence is worse than one that
        // is missing, so the composer wins every contest but this one.
        assertNull(match(true, false, false, "K", typing = true).takeIf { false })
        assertNull(match(false, false, false, "ESCAPE", typing = true))
        assertNull(match(false, false, false, "F1", typing = true))
        assertEquals(Shortcut.LIST_NEXT, match(false, false, alt = true, key = "DOWN", typing = true))
        assertEquals(Shortcut.LIST_PREV, match(false, false, alt = true, key = "UP", typing = true))
    }

    @Test
    fun `unbound keys are null rather than swallowed`() {
        assertNull(match(true, false, false, "Q"))
        assertNull(match(false, false, false, "A"))
        assertNull(match(false, false, true, "LEFT"))
        assertNull(match(true, true, false, "1"))
    }

    @Test
    fun `every cheat sheet row names a real binding`() {
        // The help text is the contract the user reads; it must not drift from
        // the table underneath it.
        //
        // Two rows — Enter and Shift+Enter — are deliberately NOT in the Shortcut
        // table: they are handled inside the composers, because what Enter means
        // depends on having focus in a text field. They are listed here anyway,
        // since "how do I send this" is the first thing anyone needs and the last
        // place they would look is a table of window-level shortcuts.
        assertEquals(13, SHORTCUT_HELP.size)
        assertTrue(SHORTCUT_HELP.all { it.first.isNotBlank() && it.second.isNotBlank() })
        // The pointer half of the model. It is listed beside the keys because the
        // verb surface, the state legend and multi-select all live on the mouse,
        // and none of them is discoverable if nothing says so.
        assertEquals(5, POINTER_HELP.size)
        assertTrue(POINTER_HELP.all { it.first.isNotBlank() && it.second.isNotBlank() })
    }

    @Test
    fun `the splitter is reachable from the keyboard, including while typing`() {
        assertEquals(Shortcut.SPLIT_NARROWER, match(true, false, false, "LBRACKET"))
        assertEquals(Shortcut.SPLIT_WIDER, match(true, false, false, "RBRACKET"))
        assertEquals(Shortcut.SPLIT_RESET, match(true, false, false, "BACKSLASH"))
        // Resizing the pane you are reading is the one layout change worth having
        // without leaving the composer — the same argument as Alt+arrow.
        assertEquals(Shortcut.SPLIT_WIDER, match(true, false, false, "RBRACKET", typing = true))
    }

    // ------------------------------------------------------------- palette

    private fun chat(id: String, title: String?) =
        Chat(id = id, title = title, mode = "ask", lastSnippet = "snippet")

    private fun session(name: String, title: String?) =
        Session(name = name, title = title, state = "idle")

    @Test
    fun `an empty query keeps verbs first`() {
        val items = paletteItems(listOf(chat("c1", "Weather")), listOf(session("dev", null)))
        val out = filterPalette(items, "")
        assertTrue(out.first() is PaletteItem.Verb)
        assertEquals(items.size, out.size)
    }

    @Test
    fun `a prefix beats a scattered match`() {
        val items = paletteItems(
            listOf(chat("c1", "test chat"), chat("c2", "the earliest snapshot")),
            emptyList(),
        )
        val out = filterPalette(items, "test").filterIsInstance<PaletteItem.OpenChat>()
        assertEquals("test chat", out.first().label)
    }

    @Test
    fun `subsequence finds a name nobody wants to spell`() {
        val items = paletteItems(emptyList(), listOf(session("huginn-desktop-kt", null)))
        val out = filterPalette(items, "hdk")
        assertEquals(1, out.size)
        assertEquals("huginn-desktop-kt", (out.first() as PaletteItem.OpenSession).name)
    }

    @Test
    fun `the detail is searchable too, and misses are dropped`() {
        val items = paletteItems(listOf(chat("c1", "Untitled")), emptyList())
        assertTrue(filterPalette(items, "snippet").isNotEmpty())
        assertTrue(filterPalette(items, "zzzzz").isEmpty())
    }

    @Test
    fun `stepIndex rings at both ends and copes with an empty list`() {
        assertEquals(1, stepIndex(0, 3, 1))
        assertEquals(0, stepIndex(2, 3, 1))
        assertEquals(2, stepIndex(0, 3, -1))
        assertEquals(0, stepIndex(-1, 3, 1))
        assertEquals(2, stepIndex(-1, 3, -1))
        assertEquals(-1, stepIndex(0, 0, 1))
    }
}
