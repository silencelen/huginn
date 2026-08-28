package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        assertEquals(Shortcut.VIEW_SCRATCHPADS, match(true, false, false, "P"))
        assertEquals(Shortcut.TOGGLE_PAD_PANEL, match(true, true, false, "P"))
    }

    @Test
    fun `the two page bindings are distinct`() {
        // The panel toggle sits on Shift beside the view it opens a sidebar for.
        // Getting them the same way round is the difference between "show me my
        // notes" and "take a third of this window away mid-sentence".
        assertNotEquals(
            match(true, false, false, "P"),
            match(true, true, false, "P"),
        )
        // A bare P is a letter somebody is typing, never a shortcut.
        assertNull(match(false, false, false, "P"))
        assertNull(match(false, false, true, "P"))
    }

    @Test
    fun `typing suppresses the bare keys, and Ctrl chords still work`() {
        // ⚠ THIS TEST USED TO BE A TAUTOLOGY. Two of its assertions read
        // `assertNull(match(...).takeIf { false })` — `takeIf { false }` is null
        // whatever match returns, so both passed against any behaviour at all,
        // including the Ctrl+digit leak below. Written out properly, the rule is
        // that a BARE key belongs to the field and a Ctrl chord does not.
        assertNull(match(false, false, false, "ESCAPE", typing = true), "Esc leaves the field before the view")
        assertNull(match(false, false, false, "F1", typing = true))
        assertEquals(
            Shortcut.PALETTE,
            match(true, false, false, "K", typing = true),
            "Ctrl+K is not a character; suppressing it would make the palette unreachable from a composer",
        )
        assertEquals(Shortcut.VIEW_SCRATCHPADS, match(true, false, false, "P", typing = true))
        assertEquals(Shortcut.TOGGLE_PAD_PANEL, match(true, true, false, "P", typing = true))
        assertEquals(Shortcut.LIST_NEXT, match(false, false, alt = true, key = "DOWN", typing = true))
        assertEquals(Shortcut.LIST_PREV, match(false, false, alt = true, key = "UP", typing = true))
    }

    @Test
    fun `Ctrl and a digit is the one chord that must not fire mid-sentence`() {
        // ⚠ LIVE-VERIFIED. On X11/AWT a Ctrl+letter chord produces a control code
        // no field will insert, but Ctrl+digit produces the printable DIGIT — so
        // Ctrl+1 switched to Chats and typed "1" into whatever had focus, and in
        // the page editor the autosave committed it a moment later. The character
        // half is swallowed by the window (see isChordDebris); this half is the
        // view switch declining to happen mid-sentence at all.
        assertNull(match(true, false, false, "1", typing = true))
        assertNull(match(true, false, false, "2", typing = true))
        assertNull(match(true, false, false, "3", typing = true))
        // …and it is still there the moment the cursor is not in a field.
        assertEquals(Shortcut.VIEW_CHATS, match(true, false, false, "1"))
        assertEquals(Shortcut.VIEW_SESSIONS, match(true, false, false, "2"))
        assertEquals(Shortcut.VIEW_STATUS, match(true, false, false, "3"))
    }

    @Test
    fun `a Ctrl chord's stray character is recognised as debris`() {
        // Ctrl+1 on X11 delivers the digit as its own event, after the key press.
        assertTrue(isChordDebris(ctrl = true, alt = false, codePoint = '1'.code))
        assertTrue(isChordDebris(ctrl = true, alt = false, codePoint = ','.code))
        // Ctrl+C arrives as 3 on the platforms that send anything at all, and no
        // field inserts a control code — nothing to swallow.
        assertFalse(isChordDebris(ctrl = true, alt = false, codePoint = 3))
        assertFalse(isChordDebris(ctrl = true, alt = false, codePoint = 0x7F))
        // ⚠ AltGr IS Ctrl+Alt on Windows and Linux, and the characters it makes
        // are the ones people on those layouts type with. Swallowing them would
        // make this window refuse half of a Polish or German keyboard.
        assertFalse(isChordDebris(ctrl = true, alt = true, codePoint = 'ę'.code))
        // AWT's CHAR_UNDEFINED. Not text, and not ours to eat — the same 0xFFFF
        // trap the terminal keys documents.
        assertFalse(isChordDebris(ctrl = true, alt = false, codePoint = 0xFFFF))
        // Ordinary typing is never debris.
        assertFalse(isChordDebris(ctrl = false, alt = false, codePoint = '1'.code))
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
        assertEquals(16, SHORTCUT_HELP.size)
        assertTrue(SHORTCUT_HELP.all { it.first.isNotBlank() && it.second.isNotBlank() })
        // The pointer half of the model. It is listed beside the keys because the
        // verb surface, the state legend and multi-select all live on the mouse,
        // and none of them is discoverable if nothing says so.
        assertEquals(6, POINTER_HELP.size)
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

    // -------------------------------------------------------- the list pane

    @Test
    fun `Ctrl B hides the list, and keeps working mid-sentence`() {
        assertEquals(Shortcut.TOGGLE_LIST, match(true, false, false, "B"))
        // Same argument as the brackets: this is the seam, and hiding the list to
        // read a wide transcript is a layout change you want without leaving the
        // composer. It is also safe there in a way Ctrl+digit is not — see below.
        assertEquals(Shortcut.TOGGLE_LIST, match(true, false, false, "B", typing = true))
        // A bare B is a letter somebody is typing, and Ctrl+Shift+B is nothing.
        assertNull(match(false, false, false, "B"))
        assertNull(match(true, true, false, "B"))
        assertNull(match(false, false, true, "B"))
    }

    @Test
    fun `the chord is actually reachable from a real key`() {
        // ⚠ A BINDING IN `match` WITH NO `keyName` ENTRY IS DEAD CODE that reads
        // like a working shortcut: the table would answer TOGGLE_LIST for "B" and
        // nothing would ever hand it a "B" to answer about. The one test that can
        // tell the difference is the round trip.
        assertEquals("B", keyName(androidx.compose.ui.input.key.Key.B))
        assertEquals(
            Shortcut.TOGGLE_LIST,
            keyName(androidx.compose.ui.input.key.Key.B)?.let { match(true, false, false, it) },
        )
    }

    @Test
    fun `Ctrl B leaves no character behind, which is why the letters never needed the digit fix`() {
        // ⚠ VERIFIED AGAINST THE TOOLKIT, not assumed. Compose Foundation 1.7.3's
        // desktop `isTypedEvent` requires the AWT KEY_TYPED char to be PRINTABLE —
        // it rejects `Character.isISOControl`, 0xFFFF and the SPECIALS block — so
        // the 0x02 that Ctrl+B delivers on X11 never becomes a CommitTextCommand at
        // all. That is the whole reason Ctrl+K and Ctrl+N have shipped for weeks
        // without leaking into the composer while Ctrl+1 typed "1" into the page
        // editor and the autosave committed it.
        //
        // So this asserts the NEGATIVE deliberately: swallowing control codes here
        // would be swallowing something no field was ever going to insert.
        assertFalse(isChordDebris(ctrl = true, alt = false, codePoint = 2), "Ctrl+B is STX")
        assertFalse(isChordDebris(ctrl = true, alt = false, codePoint = 11), "Ctrl+K is VT")
        // The printable ones are still ours to eat.
        assertTrue(isChordDebris(ctrl = true, alt = false, codePoint = '1'.code))
    }

    @Test
    fun `the palette offers the list toggle by name`() {
        // The chord is discoverable from the cheat sheet and the notch is
        // discoverable by looking at the seam; the palette is how somebody who
        // knows the WORD finds it without knowing either.
        val items = paletteItems(emptyList(), emptyList())
        val verb = items.filterIsInstance<PaletteItem.Verb>()
            .singleOrNull { it.shortcut == Shortcut.TOGGLE_LIST }
        assertTrue(verb != null, "the palette must offer it")
        assertTrue(filterPalette(items, "list").contains(verb), "and find it by the obvious word")
    }

    // ------------------------------------------------------------- palette

    private fun chat(id: String, title: String?) =
        Chat(id = id, title = title, mode = "ask", lastSnippet = "snippet")

    private fun session(name: String, title: String?) =
        Session(name = name, title = title, state = "idle")

    private fun pad(id: String, name: String, size: Int = 0) =
        Scratchpad(id = id, name = name, size = size)

    @Test
    fun `pages are offered by name, ahead of the conversations`() {
        // There are a handful of pages and hundreds of chats, and a page is looked
        // up BY NAME — which is the one thing the palette does better than the rail.
        val items = paletteItems(
            listOf(chat("c1", "Weather")),
            listOf(session("dev", null)),
            listOf(pad("p1", "Deploy notes", size = 40)),
        )
        val out = filterPalette(items, "deploy")
        assertEquals(1, out.size)
        val hit = out.first() as PaletteItem.OpenScratchpad
        assertEquals("p1", hit.id)
        assertTrue(hit.detail.contains("40 characters"), "a picker must be able to tell a written page from a blank")
    }

    @Test
    fun `an empty page says so rather than counting nothing`() {
        val items = paletteItems(emptyList(), emptyList(), listOf(pad("p1", "Blank")))
        val hit = filterPalette(items, "blank").first() as PaletteItem.OpenScratchpad
        assertTrue(hit.detail.contains("empty"), hit.detail)
    }

    @Test
    fun `no pages means no page rows, not an empty section`() {
        val items = paletteItems(listOf(chat("c1", "Weather")), emptyList())
        assertTrue(items.none { it is PaletteItem.OpenScratchpad })
    }

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
