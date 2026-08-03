package com.silencelen.huginn.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * A physical key press turned into something the daemon's `/keys` route accepts.
 *
 * This is the one part of live typing the phone cannot share. Android has no
 * physical key identity for a terminal, so it holds an invisible field containing
 * a zero-width sentinel and reads what the IME did to it. A desktop has real key
 * events, so it says what it means — but the OUTPUT contract is identical
 * (`LiveInput.Op.Text` for characters, `LiveInput.Op.Key` for tmux key names, in
 * order), which is what lets both clients drive the same pane.
 *
 * Names are tmux `send-keys` names and the daemon validates them, so an unmapped
 * key must produce null rather than a guess.
 */
object TermKeys {

    /** What a key press means to a terminal, or null for "nothing to send". */
    sealed interface Press {
        /** Ordinary typed characters. */
        data class Text(val text: String) : Press

        /** A named key, or a control sequence like `C-c`. */
        data class Named(val name: String) : Press
    }

    fun of(e: KeyEvent): Press? {
        named(e)?.let { return Press.Named(it) }

        // A modifier pressed on its own is not text, and saying so has to come
        // before the code-point branch below.
        //
        // THE BUG THIS FIXES. AWT reports a bare Shift/Ctrl/Alt press with a
        // keyChar of CHAR_UNDEFINED — 0xFFFF — and Compose hands that straight
        // through as `utf16CodePoint`. 0xFFFF is greater than 0x20, so it used to
        // pass the printable test and reach the pane as a character. Every capital
        // letter and every shifted symbol therefore arrived as a junk glyph
        // followed by the real one: typing "ABC" put "<FFFD>A<FFFD>B<FFFD>C" into
        // a live shell.
        if (e.key in MODIFIERS) return null

        // Control combinations before characters: Ctrl+C arrives with a code point
        // of 3 on some platforms and 'c' on others, and only one of those is a
        // character anybody meant to type.
        if (e.isCtrlPressed) {
            val letter = LETTERS[e.key]
            return if (letter != null) Press.Named("C-$letter") else null
        }

        // A printable code point, and nothing else. Control codes (below 0x20) and
        // DEL are already covered by the named table above; letting them through
        // here would send a raw byte the daemon would have to guess about.
        val cp = e.utf16CodePoint
        if (typable(cp)) {
            return Press.Text(String(Character.toChars(cp)))
        }
        return null
    }

    /**
     * Whether a code point is something a person meant to type.
     *
     * The modifier table catches the common source of 0xFFFF, but it is not the
     * only one — a dead key, a media key, or anything else the platform has no
     * character for reports the same way. So this refuses everything that is not
     * real text rather than trusting the key identity to have covered it.
     */
    private fun typable(cp: Int): Boolean {
        if (cp < 0x20 || cp == 0x7F) return false
        if (!Character.isValidCodePoint(cp)) return false
        // U+xFFFE and U+xFFFF are noncharacters in every plane, and AWT's
        // CHAR_UNDEFINED is the BMP's. None of them is text.
        if ((cp and 0xFFFE) == 0xFFFE) return false
        // A lone surrogate is half a character; sending one emits invalid UTF-8.
        if (cp in 0xD800..0xDFFF) return false
        return true
    }

    /**
     * Keys that only ever qualify another key.
     *
     * Held down, each of these repeats — so a slow Shift while reaching for a
     * capital used to spray junk into the pane, not just prefix one character.
     */
    private val MODIFIERS: Set<Key> = setOf(
        Key.ShiftLeft, Key.ShiftRight,
        Key.CtrlLeft, Key.CtrlRight,
        Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight,
        Key.CapsLock, Key.NumLock, Key.ScrollLock,
        Key.Function,
    )

    private fun named(e: KeyEvent): String? = when (e.key) {
        Key.Enter, Key.NumPadEnter -> "Enter"
        Key.Backspace -> "BSpace"
        // Shift+Tab is BTab, which is how a TUI walks a form backwards. Sending
        // "Tab" for both means the owner can never go back a field.
        Key.Tab -> if (e.isShiftPressed) "BTab" else "Tab"
        Key.Escape -> "Escape"
        Key.DirectionUp -> "Up"
        Key.DirectionDown -> "Down"
        Key.DirectionLeft -> "Left"
        Key.DirectionRight -> "Right"
        Key.MoveHome -> "Home"
        Key.MoveEnd -> "End"
        Key.PageUp -> "PPage"
        Key.PageDown -> "NPage"
        Key.Delete -> "DC"
        Key.Insert -> "IC"
        else -> null
    }

    private val LETTERS: Map<Key, String> = buildMap {
        val keys = listOf(
            Key.A to "a", Key.B to "b", Key.C to "c", Key.D to "d", Key.E to "e",
            Key.F to "f", Key.G to "g", Key.H to "h", Key.I to "i", Key.J to "j",
            Key.K to "k", Key.L to "l", Key.M to "m", Key.N to "n", Key.O to "o",
            Key.P to "p", Key.Q to "q", Key.R to "r", Key.S to "s", Key.T to "t",
            Key.U to "u", Key.V to "v", Key.W to "w", Key.X to "x", Key.Y to "y",
            Key.Z to "z",
        )
        keys.forEach { (k, v) -> put(k, v) }
    }
}
