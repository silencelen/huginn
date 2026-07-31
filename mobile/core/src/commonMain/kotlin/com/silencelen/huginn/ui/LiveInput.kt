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

    /** What one field change means in keystrokes. */
    data class Typed(
        val backspaces: Int,
        val insert: String,
        val enter: Boolean,
    ) {
        val isNothing: Boolean get() = backspaces == 0 && insert.isEmpty() && !enter
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
     * character. A newline anywhere means Enter, sent AFTER the text before it —
     * the order a terminal expects.
     */
    fun diff(newValue: String): Typed {
        if (newValue == SENTINEL) return Typed(0, "", false)
        // The sentinel survived as a prefix: everything after it was typed.
        if (newValue.startsWith(SENTINEL)) {
            val tail = newValue.removePrefix(SENTINEL)
            val enter = tail.contains('\n')
            return Typed(0, tail.replace("\n", ""), enter)
        }
        // The sentinel is gone: backspace consumed it, and anything left besides
        // is text the IME put there in the same edit.
        val enter = newValue.contains('\n')
        return Typed(1, newValue.replace("\n", ""), enter)
    }
}
