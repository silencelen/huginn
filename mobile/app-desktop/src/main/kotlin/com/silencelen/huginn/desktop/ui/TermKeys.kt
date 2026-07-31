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
        if (cp >= 0x20 && cp != 0x7F) {
            return Press.Text(String(Character.toChars(cp)))
        }
        return null
    }

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
