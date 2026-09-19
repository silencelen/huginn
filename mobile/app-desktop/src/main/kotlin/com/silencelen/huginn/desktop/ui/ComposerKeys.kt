package com.silencelen.huginn.desktop.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * What Tab means in a composer.
 *
 * ⚠ IT MEANT "TYPE A TAB CHARACTER", which is the one thing it must not mean
 * here. Pressing it three times in the chat composer put three tabs in the
 * message and left the focus ring exactly where it was — so from the composer
 * there was NO keyboard route to Send, to the attachment button, to the
 * suggestion chips or to the tab strip. That is not a small omission on a
 * desktop: it is the difference between a client somebody can drive without a
 * mouse and one they cannot.
 *
 * A text field that wants a literal tab is a code editor. This is a chat
 * composer whose own placeholder documents Shift+Enter for the one whitespace
 * case it needs, and whose Enter already means send. So Tab is focus, and the
 * literal keeps a chord (Ctrl+Tab) and a paste — neither of which any other
 * control in the window wanted.
 */
internal enum class TabMove {
    /** Forward, to Send and the rest of the frame. */
    NEXT,

    /** Backward — Shift+Tab, the same traversal in reverse. */
    PREVIOUS,

    /** A real `\t` in the draft. Ctrl+Tab only. */
    LITERAL,

    /** Not ours: let it through untouched. */
    NONE,
}

/**
 * The binding, as a pure function of the modifiers.
 *
 * ⚠ ALT AND META ARE LEFT ALONE. Alt+Tab is the window manager's on every
 * platform this ships to and Cmd+Tab is macOS's; swallowing either to insert a
 * character or to move a focus ring would take a system gesture away from the
 * desk, from inside a text box, with no way to get it back.
 */
internal fun tabMove(ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): TabMove = when {
    alt || meta -> TabMove.NONE
    ctrl -> TabMove.LITERAL
    shift -> TabMove.PREVIOUS
    else -> TabMove.NEXT
}

/**
 * Text spliced over whatever is selected, cursor after it.
 *
 * Shared by BOTH composers (the session's and the chat's) because the two blocks
 * were byte-identical, and a splice that differs between two boxes in one app is
 * the kind of divergence nobody notices until one of them corrupts a draft.
 *
 * ⚠ min/max, NOT start/end. A [TextRange] is DIRECTED: Shift+Left, Shift+Home,
 * Shift+Up and a right-to-left drag all produce `start > end`, and Compose's
 * legacy TextFieldValue path hands that to onValueChange unnormalised. Splicing
 * `substring(0, start) + "\n" + substring(end)` on a reversed range OVERLAPS
 * instead of replacing — "hello world" with "world" selected backwards became
 * "hello world\nworld", Shift+Home from the end doubled the whole draft, and the
 * result was written straight through onDraft to the drafts book.
 */
internal fun spliceIn(field: TextFieldValue, insert: String): TextFieldValue {
    val lo = field.selection.min
    val hi = field.selection.max
    val next = field.text.substring(0, lo) + insert + field.text.substring(hi)
    return TextFieldValue(next, TextRange(lo + insert.length))
}

/** Shift+Enter's newline. */
internal fun newlineIn(field: TextFieldValue): TextFieldValue = spliceIn(field, "\n")

/** Ctrl+Tab's literal tab — the only key that still types one. */
internal fun tabIn(field: TextFieldValue): TextFieldValue = spliceIn(field, "\t")
