package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.Session

/**
 * The keyboard model, as data.
 *
 * Kept apart from the window that dispatches it because a `when` block inside an
 * `onKeyEvent` lambda cannot be tested, and a keyboard model that nobody can
 * assert on is one that quietly loses a binding in a refactor. [match] is a pure
 * function of the modifiers and the key; Main.kt only maps its answer to an
 * action.
 */
enum class Shortcut {
    PALETTE,
    VIEW_CHATS,
    VIEW_SESSIONS,
    VIEW_STATUS,
    VIEW_SETTINGS,

    /**
     * The pages, full width. P for pages: the digits are taken and a fourth one
     * would suggest a rail position this does not have.
     */
    VIEW_SCRATCHPADS,

    /**
     * The page beside the conversation. Ctrl+Shift+P, next to the view it toggles
     * a panel of — the same pairing every editor uses for "the sidebar of the
     * thing Ctrl+P opens".
     */
    TOGGLE_PAD_PANEL,
    NEW_ASK,
    NEW_ACT,
    /** Palette-only — no key: a chat on whichever machine serves local AI. */
    NEW_LOCAL,
    LIST_PREV,
    LIST_NEXT,
    BACK,
    CHEATSHEET,
    HIDE_TO_TRAY,

    /**
     * The splitter, from the keyboard. A seam that can only be dragged is a seam
     * that cannot be adjusted by someone whose hands are on the keys — which, in an
     * app whose whole detail pane is a text composer, is most of the time. Bound to
     * the bracket keys because that is where every editor puts "resize the sidebar"
     * and muscle memory is worth more than a mnemonic.
     */
    SPLIT_NARROWER,
    SPLIT_WIDER,
    SPLIT_RESET,
}

/**
 * @param key an uppercase, platform-independent name — "K", "N", "1", "COMMA",
 *   "ESCAPE", "F1", "UP", "DOWN". The caller translates Compose's `Key` to this
 *   so the table is readable and this file needs no Compose import.
 * @param typing true when focus is in a text field. Almost everything is
 *   suppressed there — a shortcut that steals a keystroke mid-sentence is worse
 *   than a missing shortcut — but list navigation deliberately survives, because
 *   moving between chats without leaving the composer is the whole point of
 *   putting it on Alt rather than the bare arrows.
 */
fun match(
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
    key: String,
    typing: Boolean = false,
): Shortcut? {
    if (alt && !ctrl) {
        return when (key) {
            "UP" -> Shortcut.LIST_PREV
            "DOWN" -> Shortcut.LIST_NEXT
            else -> null
        }
    }
    if (!ctrl) {
        // Escape leaves a field before it leaves a view, so the shell only sees
        // it when nothing is being typed into.
        if (typing) return null
        return when (key) {
            "ESCAPE" -> Shortcut.BACK
            "F1" -> Shortcut.CHEATSHEET
            else -> null
        }
    }
    if (shift) {
        return when (key) {
            "H" -> Shortcut.HIDE_TO_TRAY
            "N" -> Shortcut.NEW_ACT
            "P" -> Shortcut.TOGGLE_PAD_PANEL
            else -> null
        }
    }
    // ⚠ CTRL+DIGIT IS NOT SAFE MID-SENTENCE, and it is the one Ctrl chord that is
    // not. On X11/AWT a Ctrl+letter chord produces a CONTROL code that no text
    // field will insert, but Ctrl+digit produces the printable digit — so the view
    // switch and a stray "1" in whatever field had focus happened together, and
    // the page editor's autosave then committed the corruption without anybody
    // pressing anything. The character half is swallowed by the window (see
    // [isChordDebris]); this half is the shortcut declining to fire mid-sentence
    // at all, because switching views out from under a half-typed message is not
    // what Ctrl+1 was reached for while typing.
    if (typing && key.length == 1 && key[0] in '0'..'9') return null
    return when (key) {
        "K" -> Shortcut.PALETTE
        "N" -> Shortcut.NEW_ASK
        "1" -> Shortcut.VIEW_CHATS
        "2" -> Shortcut.VIEW_SESSIONS
        "3" -> Shortcut.VIEW_STATUS
        "P" -> Shortcut.VIEW_SCRATCHPADS
        "COMMA" -> Shortcut.VIEW_SETTINGS
        "SLASH" -> Shortcut.CHEATSHEET
        // Deliberately NOT suppressed while typing, like the list arrows above:
        // resizing the pane you are reading is the one layout change you want
        // without leaving the composer, and no editor puts a bracket on Ctrl.
        "LBRACKET" -> Shortcut.SPLIT_NARROWER
        "RBRACKET" -> Shortcut.SPLIT_WIDER
        "BACKSLASH" -> Shortcut.SPLIT_RESET
        else -> null
    }
}

/**
 * Whether a character the platform has just produced is the DEBRIS of a Ctrl
 * chord rather than something a person typed.
 *
 * ⚠ THE DIGIT THAT ENDED UP IN THE PAGE. A chord arrives as two events: the key
 * press, which [match] answers, and — on X11/AWT — a separate KEY_TYPED carrying
 * a character. For Ctrl+letter that character is a control code (Ctrl+C is 3) and
 * every text field ignores it, which is why nobody had seen this. For Ctrl+digit,
 * Ctrl+comma and friends it is the PRINTABLE character, and consuming the key
 * press does nothing to stop it: the second event goes straight to the focused
 * field. Ctrl+1 therefore switched to Chats AND typed "1" into whatever was
 * focused, and in the page editor the autosave committed it a moment later.
 *
 * Held while a Ctrl chord is down, no printable character belongs to anybody —
 * which is true of every platform's behaviour and is why this is safe to swallow
 * wholesale rather than key by key.
 *
 * @param alt excluded deliberately: Ctrl+Alt IS AltGr on Windows and Linux, and
 *   the characters it makes are the ones people on those layouts type with.
 * @param codePoint the character the event carried. AWT reports 0xFFFF
 *   (CHAR_UNDEFINED) for a key with no character, which is not text and is left
 *   alone — the same trap [TermKeys] documents.
 */
fun isChordDebris(ctrl: Boolean, alt: Boolean, codePoint: Int): Boolean {
    if (!ctrl || alt) return false
    if (codePoint < 0x20 || codePoint == 0x7F) return false
    if (codePoint == 0xFFFF) return false
    return true
}

/**
 * Compose's `Key` → the plain name [match] speaks. Only the keys the table can
 * use are named; everything else is null and falls through to the app, which is
 * why an unbound key never becomes a swallowed keystroke.
 */
fun keyName(key: androidx.compose.ui.input.key.Key): String? = when (key) {
    androidx.compose.ui.input.key.Key.K -> "K"
    androidx.compose.ui.input.key.Key.N -> "N"
    androidx.compose.ui.input.key.Key.H -> "H"
    androidx.compose.ui.input.key.Key.P -> "P"
    androidx.compose.ui.input.key.Key.One -> "1"
    androidx.compose.ui.input.key.Key.Two -> "2"
    androidx.compose.ui.input.key.Key.Three -> "3"
    androidx.compose.ui.input.key.Key.Comma -> "COMMA"
    androidx.compose.ui.input.key.Key.Slash -> "SLASH"
    androidx.compose.ui.input.key.Key.LeftBracket -> "LBRACKET"
    androidx.compose.ui.input.key.Key.RightBracket -> "RBRACKET"
    androidx.compose.ui.input.key.Key.Backslash -> "BACKSLASH"
    androidx.compose.ui.input.key.Key.Escape -> "ESCAPE"
    androidx.compose.ui.input.key.Key.F1 -> "F1"
    androidx.compose.ui.input.key.Key.DirectionUp -> "UP"
    androidx.compose.ui.input.key.Key.DirectionDown -> "DOWN"
    else -> null
}

/** One row of the cheat sheet, and the single source for what the app claims. */
val SHORTCUT_HELP: List<Pair<String, String>> = listOf(
    "Enter" to "Send the message you are typing",
    "Shift Enter" to "New line instead of sending",
    "Ctrl K" to "Find a chat or session",
    "Ctrl 1 / 2 / 3" to "Chats / Sessions / Status",
    "Ctrl ," to "Settings",
    "Ctrl N" to "New Ask chat",
    "Ctrl Shift N" to "New Act chat",
    "Ctrl P" to "Pages",
    "Ctrl Shift P" to "Show the open page beside this conversation",
    "Alt ↑ / ↓" to "Previous / next in the list (works while typing)",
    "Ctrl [ / ]" to "Narrower / wider list pane",
    "Ctrl \\" to "Reset the list pane (or double-click the seam)",
    "Esc" to "Back to the list",
    "Ctrl Shift H" to "Hide to the tray",
    "F1" to "This list",
)

/** What the pointer can do that no key can. Shown beside the keyboard model. */
val POINTER_HELP: List<Pair<String, String>> = listOf(
    "Right-click" to "Open, rename, interrupt, delete — every verb, one menu",
    "Ctrl click" to "Add a row to the selection",
    "Shift click" to "Select everything between",
    "Hover a dot" to "What the state is, and how long it has been that way",
    "Double-click seam" to "Reset the list pane width",
)

// ------------------------------------------------------------------- palette

/** What the palette can offer: something to open, or something to do. */
sealed interface PaletteItem {
    val label: String
    val detail: String

    data class OpenChat(val id: String, override val label: String, override val detail: String) : PaletteItem
    data class OpenSession(val name: String, override val label: String, override val detail: String) : PaletteItem
    data class OpenScratchpad(val id: String, override val label: String, override val detail: String) : PaletteItem
    data class Verb(val shortcut: Shortcut, override val label: String, override val detail: String) : PaletteItem
}

private val VERBS = listOf(
    PaletteItem.Verb(Shortcut.NEW_ASK, "New Ask chat", "reasoning, memory and reads"),
    PaletteItem.Verb(Shortcut.NEW_ACT, "New Act chat", "can run commands and change files"),
    PaletteItem.Verb(Shortcut.NEW_LOCAL, "New local chat", "a serving machine's model answers — never Claude"),
    PaletteItem.Verb(Shortcut.VIEW_SCRATCHPADS, "Pages", "your own pages, and what a message can carry"),
    PaletteItem.Verb(Shortcut.TOGGLE_PAD_PANEL, "Page beside this conversation", "show or hide the side panel"),
    PaletteItem.Verb(Shortcut.VIEW_STATUS, "Status", "host, plan and usage"),
    PaletteItem.Verb(Shortcut.VIEW_SETTINGS, "Settings", "server, accounts, diagnostics"),
)

fun paletteItems(
    chats: List<Chat>,
    sessions: List<Session>,
    pads: List<Scratchpad> = emptyList(),
): List<PaletteItem> =
    VERBS +
        // Pages before the conversations: there are a handful of them and hundreds
        // of chats, and a page is looked up BY NAME, which is the one thing the
        // palette is better at than the rail.
        pads.map {
            PaletteItem.OpenScratchpad(
                it.id,
                it.label(),
                listOfNotNull("page", if (it.size <= 0) "empty" else "${it.size} characters")
                    .joinToString(" · "),
            )
        } +
        sessions.map {
            PaletteItem.OpenSession(
                it.name,
                it.title ?: it.name,
                listOfNotNull("session", it.name.takeIf { n -> n != (it.title ?: n) }, it.state)
                    .joinToString(" · "),
            )
        } +
        chats.map {
            PaletteItem.OpenChat(
                it.id,
                it.title ?: "Untitled",
                listOfNotNull("chat", it.mode, it.lastSnippet?.take(60)).joinToString(" · "),
            )
        }

/**
 * Subsequence match on the label, then the detail — typing "hdk" finds
 * "huginn-desktop-kt" without spelling it. Ranked so that a prefix beats a
 * scattered match and a shorter label beats a longer one; an empty query keeps
 * the natural order, which puts the verbs first.
 */
fun filterPalette(items: List<PaletteItem>, query: String): List<PaletteItem> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return items
    return items
        .mapNotNull { item ->
            val label = item.label.lowercase()
            val score = when {
                label.startsWith(q) -> 0
                label.contains(q) -> 1
                subsequence(label, q) -> 2
                item.detail.lowercase().contains(q) -> 3
                else -> return@mapNotNull null
            }
            item to (score * 1000 + item.label.length)
        }
        .sortedBy { it.second }
        .map { it.first }
}

private fun subsequence(haystack: String, needle: String): Boolean {
    var i = 0
    for (c in haystack) {
        if (i < needle.length && c == needle[i]) i++
        if (i == needle.length) return true
    }
    return i == needle.length
}

/** Wraps at both ends: the list is a ring, because stopping dead at the bottom
 *  of a two-item list is a worse answer than coming back to the top. */
fun stepIndex(current: Int, size: Int, delta: Int): Int {
    if (size <= 0) return -1
    if (current < 0) return if (delta > 0) 0 else size - 1
    return ((current + delta) % size + size) % size
}

/** A page with no name is not possible, but a decoded one with a blank is. */
private fun Scratchpad.label(): String = name.ifBlank { "Untitled page" }
