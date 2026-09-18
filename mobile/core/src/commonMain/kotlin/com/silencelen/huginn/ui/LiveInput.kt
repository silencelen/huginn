package com.silencelen.huginn.ui

/**
 * Typing straight into the pane, keystroke by keystroke, instead of composing in
 * a bubble and sending.
 *
 * There is no PTY on the phone and no InputConnection to a terminal, so the trick
 * is a hidden text field holding a single invisible sentinel character. Whatever
 * the keyboard does to that field is read as a diff against the sentinel and
 * translated into keystrokes for tmux, then the field snaps back to the sentinel.
 * The sentinel is the load-bearing part: with a truly empty field most IMEs
 * express backspace as nothing at all (there is no text to delete), so deleting
 * the sentinel is how backspace becomes visible here.
 */
object LiveInput {

    /** Zero-width space: present, deletable, and invisible in the field. */
    const val SENTINEL = "​"

    /**
     * What one field change means in keystrokes.
     *
     * @param insert the text to deliver, newlines INCLUDED. The daemon sends text
     *   by bracketed paste, so an interior newline lands in a Claude composer (or
     *   a shell line) as a newline and submits nothing — which is what collapsing
     *   them was trying to achieve and did not.
     * @param enter whether a Return belongs to this edit. At most one: a tail
     *   that both begins and ends with a newline still presses Return once, since
     *   delivering fewer Returns than the input implied cannot fire one at a modal
     *   and delivering more can.
     * @param enterFirst whether that Return goes BEFORE the text. An IME that
     *   commits "\nls" pressed Return and then typed; sending it the other way
     *   round submits whatever draft the pane was holding with `ls` stuck on the
     *   end. ⚠ A caller that reads only [enter] gets the old order, which is why
     *   [ops] exists.
     */
    data class Typed(
        val backspaces: Int,
        val insert: String,
        val enter: Boolean,
        val enterFirst: Boolean = false,
    ) {
        val isNothing: Boolean get() = backspaces == 0 && insert.isEmpty() && !enter

        /** This edit as ordered ops — the whole contract, in the order it happened. */
        fun ops(): List<Op> {
            val out = mutableListOf<Op>()
            if (backspaces > 0) out += Op.Key(List(backspaces) { "BSpace" })
            if (enter && enterFirst) out += Op.Key(listOf("Enter"))
            if (insert.isNotEmpty()) out += Op.Text(insert)
            if (enter && !enterFirst) out += Op.Key(listOf("Enter"))
            return out
        }
    }

    /** One thing to deliver to the pane, in order. */
    sealed interface Op {
        data class Text(val text: String) : Op
        data class Key(val keys: List<String>) : Op
    }

    /**
     * Coalesces queued keystrokes into the fewest requests that preserve order.
     *
     * This queue exists for two reasons and the second is the important one.
     * Fewer round trips make typing feel faster — a burst of six characters
     * becomes one request. But the original path launched an independent
     * coroutine per keystroke, and independent requests are not ordered: type
     * "ls" fast enough and the pane could receive "sl". A single drainer sending
     * merged ops sequentially makes ordering a property of the design instead of
     * a property of network luck.
     */
    fun merge(ops: List<Op>): List<Op> {
        val out = ArrayList<Op>(ops.size)
        for (op in ops) {
            val last = out.lastOrNull()
            if (op is Op.Text && last is Op.Text) {
                out[out.size - 1] = Op.Text(last.text + op.text)
            } else if (op is Op.Key && last is Op.Key) {
                out[out.size - 1] = Op.Key(last.keys + op.keys)
            } else {
                out.add(op)
            }
        }
        return out
    }

    /**
     * Diffs the field against the sentinel it was reset to.
     *
     * Pure and deliberately paranoid: an IME may rewrite the whole field (paste,
     * autocorrect, voice input), so this never assumes the change was a single
     * character.
     *
     * ⚠ NEWLINES ARE STRUCTURE, NOT A RETURN KEY. This used to delete every
     * newline in the tail and press Return once at the end, so a three-line
     * clipboard arrived as `git statusgit log --onelinels -la` and was SUBMITTED:
     * a command nobody wrote, run in a live shell. Only a newline at the very
     * start or the very end of the edit is a Return now; the ones in the middle
     * stay in the text, where bracketed paste delivers them without submitting.
     * `\r\n` and a bare `\r` are normalised first — the old collapse left carriage
     * returns in `send-keys -l` text, delivering the mid-text Return it claimed
     * to prevent.
     */
    fun diff(newValue: String): Typed {
        if (newValue == SENTINEL) return Typed(0, "", false)
        // The sentinel survived as a prefix: everything after it was typed.
        // Otherwise it is gone — backspace consumed it — and anything left is
        // text the IME put there in the same edit.
        val survived = newValue.startsWith(SENTINEL)
        val tail = normalizeNewlines(if (survived) newValue.removePrefix(SENTINEL) else newValue)
        val backspaces = if (survived) 0 else 1

        val trailing = tail.endsWith("\n")
        val body = if (trailing) tail.dropLast(1) else tail
        val leading = body.startsWith("\n")
        val insert = if (leading) body.drop(1) else body
        return Typed(
            backspaces = backspaces,
            insert = insert,
            enter = trailing || leading,
            // Only when there is text for it to come before; a bare newline is
            // just Return, and saying it came "first" would be noise.
            enterFirst = leading,
        )
    }

    private fun normalizeNewlines(s: String): String =
        if ('\r' !in s) s else s.replace("\r\n", "\n").replace('\r', '\n')
}
