@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.silencelen.huginn.desktop.ui

import java.awt.event.KeyEvent as AwtKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a physical key press becomes on the way to a live pane.
 *
 * There was no test file here at all, which is why the bug below shipped.
 *
 * These build Compose key events through Compose's own factory rather than from
 * real AWT events — the AWT-to-Compose bridge is internal, so a test cannot cross
 * it. Two consequences worth naming:
 *
 *   * The factory is itself `@InternalComposeUiApi`, opted into above. It is
 *     pinned to the exact compose-ui version, so a Compose bump may break this
 *     file. That is acceptable here because it breaks at COMPILE time, loudly,
 *     in a test — not silently in a live pane.
 *   * The code point a bare modifier carries is asserted from
 *     `java.awt.event.KeyEvent.CHAR_UNDEFINED` — a documented constant,
 *     referenced rather than copied — instead of being observed from a real key
 *     press. That a live pane really does receive junk was established
 *     separately, by typing into one.
 */
class TermKeysTest {

    /**
     * A key-down. The default code point is what AWT reports for a key with no
     * character — which is the whole subject of this file.
     */
    private fun down(
        key: Key,
        codePoint: Int = AwtKeyEvent.CHAR_UNDEFINED.code,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        codePoint = codePoint,
        isCtrlPressed = ctrl,
        isMetaPressed = false,
        isAltPressed = alt,
        isShiftPressed = shift,
        nativeEvent = null,
    )

    private fun ch(key: Key, c: Char, shift: Boolean = false) = down(key, c.code, shift = shift)

    private fun text(e: KeyEvent): String? = (TermKeys.of(e) as? TermKeys.Press.Text)?.text

    private fun named(e: KeyEvent): String? = (TermKeys.of(e) as? TermKeys.Press.Named)?.name

    // --------------------------------------------------------------- the bug

    @Test
    fun `a bare Shift sends nothing`() {
        // THE REGRESSION, at its source. AWT gives a modifier press a keyChar of
        // CHAR_UNDEFINED — 0xFFFF — and the mapper's printable test was
        // `cp >= 0x20`, which 0xFFFF passes. So holding Shift to reach a capital
        // put U+FFFF into the pane first, and the owner saw a junk glyph before
        // every shifted character in a live shell.
        assertNull(TermKeys.of(down(Key.ShiftLeft)))
        assertNull(TermKeys.of(down(Key.ShiftRight)))
    }

    @Test
    fun `no modifier is text, held alone`() {
        listOf(
            Key.ShiftLeft, Key.ShiftRight,
            Key.CtrlLeft, Key.CtrlRight,
            Key.AltLeft, Key.AltRight,
            Key.MetaLeft, Key.MetaRight,
            Key.CapsLock, Key.NumLock, Key.ScrollLock,
        ).forEach { k ->
            assertNull(TermKeys.of(down(k)), "$k alone must send nothing")
        }
    }

    @Test
    fun `a modifier held down while it repeats still sends nothing`() {
        // Auto-repeat is why this was not a one-glyph annoyance: a slow Shift on
        // the way to a capital sprayed junk, one per repeat.
        repeat(5) { assertNull(TermKeys.of(down(Key.ShiftLeft, shift = true))) }
    }

    @Test
    fun `a capital letter sends exactly one character`() {
        // The pair as it actually arrives: Shift down, then the letter with Shift
        // still held. Only the second is text, and it is text once.
        assertNull(TermKeys.of(down(Key.ShiftLeft, shift = true)))
        assertEquals("A", text(ch(Key.A, 'A', shift = true)))
    }

    @Test
    fun `a shifted symbol sends only the symbol`() {
        assertEquals("!", text(ch(Key.One, '!', shift = true)))
        assertEquals("~", text(ch(Key.Grave, '~', shift = true)))
        assertEquals("?", text(ch(Key.Slash, '?', shift = true)))
    }

    @Test
    fun `nothing that is not a character is ever sent as one`() {
        // The modifier table covers the common source of CHAR_UNDEFINED; this
        // covers the rest. A dead key, a media key, anything the platform has no
        // character for reports the same way, and none of it is text.
        mapOf(
            "CHAR_UNDEFINED" to AwtKeyEvent.CHAR_UNDEFINED.code,
            "U+FFFE noncharacter" to 0xFFFE,
            "U+1FFFF noncharacter" to 0x1FFFF,
            "lone high surrogate" to 0xD800,
            "lone low surrogate" to 0xDFFF,
            "above the last code point" to 0x110000,
        ).forEach { (what, cp) ->
            val got = text(down(Key.Unknown, cp))
            assertNull(got, "$what must not be sent as text, got ${got?.let { "'$it'" }}")
        }
    }

    @Test
    fun `an astral character still goes through whole`() {
        // The noncharacter guard masks off the low bits of every plane, so it has
        // to not catch ordinary supplementary characters on the way past.
        assertEquals("🚀", text(down(Key.Unknown, 0x1F680)))
    }

    // ------------------------------------------------------- ordinary typing

    @Test
    fun `plain letters and digits are text`() {
        assertEquals("a", text(ch(Key.A, 'a')))
        assertEquals("z", text(ch(Key.Z, 'z')))
        assertEquals("7", text(ch(Key.Seven, '7')))
        assertEquals(" ", text(ch(Key.Spacebar, ' ')))
    }

    @Test
    fun `control codes are not smuggled through as text`() {
        // Below 0x20 is a control byte the daemon would have to guess about, and
        // the named table already covers the ones that mean something.
        assertNull(text(down(Key.Unknown, 0x03)))
        assertNull(text(down(Key.Unknown, 0x1B)))
        assertNull(text(down(Key.Unknown, 0x7F)))
    }

    // ------------------------------------------------------------ named keys

    @Test
    fun `the named keys a TUI needs`() {
        assertEquals("Enter", named(down(Key.Enter)))
        assertEquals("Escape", named(down(Key.Escape)))
        assertEquals("BSpace", named(down(Key.Backspace)))
        assertEquals("Up", named(down(Key.DirectionUp)))
        assertEquals("Down", named(down(Key.DirectionDown)))
        assertEquals("Left", named(down(Key.DirectionLeft)))
        assertEquals("Right", named(down(Key.DirectionRight)))
        assertEquals("Home", named(down(Key.MoveHome)))
        assertEquals("End", named(down(Key.MoveEnd)))
        assertEquals("PPage", named(down(Key.PageUp)))
        assertEquals("NPage", named(down(Key.PageDown)))
        assertEquals("DC", named(down(Key.Delete)))
        assertEquals("IC", named(down(Key.Insert)))
    }

    @Test
    fun `Shift+Tab is BTab`() {
        // It is what cycles Claude Code's permission mode backwards; sending "Tab"
        // for both means the owner can only ever go forwards.
        assertEquals("Tab", named(down(Key.Tab)))
        assertEquals("BTab", named(down(Key.Tab, shift = true)))
    }

    @Test
    fun `Ctrl and a letter is a control name, not a character`() {
        assertEquals("C-c", named(ch(Key.C, 'c').let { down(Key.C, 'c'.code, ctrl = true) }))
        // Ctrl+C arrives with a code point of 3 on some platforms and 'c' on
        // others; both must land on the same name.
        assertEquals("C-c", named(down(Key.C, 0x03, ctrl = true)))
        assertEquals("C-d", named(down(Key.D, 'd'.code, ctrl = true)))
    }

    @Test
    fun `tmux keeps its own prefix while Live is on`() {
        // ⚠ THE APP NOW BINDS Ctrl+B (hide the list pane), and `C-b` is tmux's
        // prefix in most setups — the one chord a person at a live pane reaches for
        // first. The pane wins, and it wins by ARRANGEMENT rather than by a rule
        // anybody wrote: the Screen tab's `onPreviewKeyEvent` runs before the
        // window's `onKeyEvent`, so a key this function claims never reaches the
        // shortcut table at all. The same is already true of Ctrl+K and Ctrl+N.
        //
        // What that makes fragile is exactly this line. Drop B from the letter
        // table and Ctrl+B silently starts collapsing the list pane instead of
        // opening a tmux command prompt, with nothing on screen looking wrong.
        assertEquals("C-b", named(down(Key.B, 'b'.code, ctrl = true)))
        assertEquals("C-b", named(down(Key.B, 0x02, ctrl = true)), "and however the platform spells it")
    }

    @Test
    fun `Ctrl with something unmapped sends nothing rather than guessing`() {
        assertNull(TermKeys.of(down(Key.F5, ctrl = true)))
        assertNull(TermKeys.of(down(Key.One, '1'.code, ctrl = true)))
    }

    @Test
    fun `a named key still wins while Ctrl is held`() {
        // Ctrl+Enter is the composer's send gesture, not a character, and the
        // named table is consulted first so it stays Enter rather than becoming
        // nothing.
        assertEquals("Enter", named(down(Key.Enter, ctrl = true)))
    }

    // ------------------------------------------------- the daemon's contract

    @Test
    fun `every name this can produce is one the daemon accepts`() {
        // The daemon validates names and rejects the whole REQUEST on one bad one
        // — and the client coalesces a burst of keystrokes into one request, so an
        // unaccepted name does not fail alone, it takes every keystroke batched
        // with it down too. This is the daemon's set, copied deliberately: if the
        // two ever drift, this test is what says so.
        val produced = buildList {
            listOf(
                Key.Enter, Key.NumPadEnter, Key.Backspace, Key.Tab, Key.Escape,
                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown, Key.Delete, Key.Insert,
            ).forEach { k -> named(down(k))?.let { add(it) } }
            add(named(down(Key.Tab, shift = true))!!)
            LETTER_KEYS.forEach { (k, c) -> named(down(k, c.code, ctrl = true))?.let { add(it) } }
        }

        assertTrue(produced.size >= 40, "expected the full mapped set, got ${produced.size}")
        produced.forEach {
            assertTrue(daemonAccepts(it), "the daemon would reject '$it' — and the whole batch with it")
        }
    }

    @Test
    fun `the key bar's own chips are names the daemon accepts too`() {
        // These are hard-coded in SessionView's KeyBar rather than produced by
        // this mapper, so they are a second, independent way to break the same
        // contract.
        listOf(
            "Escape", "Tab", "BTab", "Up", "Down", "Left", "Right", "Enter",
            "C-c", "C-d", "C-l", "C-r", "PPage", "NPage",
        ).forEach {
            assertTrue(daemonAccepts(it), "key bar chip '$it' would be rejected")
        }
    }

    private companion object {
        /** `NAMED_KEYS` in huginn-appd.js, and the regexes beside it. */
        val ACCEPTED = setOf(
            "Enter", "Escape", "Tab", "BTab", "Space", "BSpace", "DC", "IC",
            "Up", "Down", "Left", "Right", "Home", "End", "PPage", "NPage",
        )

        fun daemonAccepts(k: String) = k in ACCEPTED ||
            Regex("^C-[a-z]$").matches(k) ||
            Regex("^M-[a-z]$").matches(k) ||
            Regex("^F([1-9]|1[0-2])$").matches(k)

        val LETTER_KEYS = listOf(
            Key.A to 'a', Key.B to 'b', Key.C to 'c', Key.D to 'd', Key.E to 'e',
            Key.F to 'f', Key.G to 'g', Key.H to 'h', Key.I to 'i', Key.J to 'j',
            Key.K to 'k', Key.L to 'l', Key.M to 'm', Key.N to 'n', Key.O to 'o',
            Key.P to 'p', Key.Q to 'q', Key.R to 'r', Key.S to 's', Key.T to 't',
            Key.U to 'u', Key.V to 'v', Key.W to 'w', Key.X to 'x', Key.Y to 'y',
            Key.Z to 'z',
        )
    }
}
