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
     * A field that is known to have been resting at the sentinel.
     *
     * ⚠ ONLY SAFE WHEN NOTHING CAN BE IN FLIGHT — the two-argument [diff] is
     * the one a live IME talks to, and the reason is written on it. This
     * overload is the resting case: a first edit, and the tests that describe
     * one.
     */
    fun diff(newValue: String): Typed = diff(SENTINEL, newValue)

    /**
     * Diffs the field against THE TEXT WE LAST SAW IN IT, not against a constant.
     *
     * ⚠⚠ THE SENTINEL IS A RESTING VALUE, NOT A BASELINE, AND CONFUSING THE TWO
     * DUPLICATED EVERY FAST BURST. The field used to be snapped back to [SENTINEL]
     * inside `onValueChange` and every change diffed against that constant, so a
     * second IME change that arrived before the snap-back had round-tripped
     * through recomposition saw the CUMULATIVE buffer and re-emitted everything
     * typed so far. Five characters delivered in one burst arrived as fifteen —
     * 1+2+3+4+5, the triangular number that named the bug — straight into a live
     * Claude Code prompt. Diffing against the last observed text makes a
     * mid-burst change mean exactly the characters it added, whatever the field
     * still holds and whenever the reset lands.
     *
     * Pure and deliberately paranoid: an IME may rewrite the whole field (paste,
     * autocorrect, voice input), so this never assumes the change was a single
     * character. Anything [previous] had past the shared prefix was deleted, and
     * it is reported as that many backspaces — including the sentinel itself,
     * which is how a backspace at the very start of an empty field becomes
     * visible at all.
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
    fun diff(previous: String, current: String): Typed {
        if (current == previous) return Typed(0, "", false)
        val shared = sharedPrefix(previous, current)
        val backspaces = previous.length - shared
        val tail = normalizeNewlines(current.substring(shared))

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

    /**
     * How much of [a] and [b] is the same run of characters from the start.
     *
     * ⚠ NEVER SPLITS A SURROGATE PAIR. Half a code point is not a keystroke, and
     * an emoji typed into the pane would otherwise be reported as one backspace
     * and a lone low surrogate.
     */
    private fun sharedPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        if (i in 1..(a.length - 1) && a[i - 1].isHighSurrogate()) i--
        return i
    }

    private fun normalizeNewlines(s: String): String =
        if ('\r' !in s) s else s.replace("\r\n", "\n").replace('\r', '\n')
}
