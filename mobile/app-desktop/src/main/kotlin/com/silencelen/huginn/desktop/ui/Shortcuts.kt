package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.Chat
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
    NEW_ASK,
    NEW_ACT,
    LIST_PREV,
    LIST_NEXT,
    BACK,
    CHEATSHEET,
    HIDE_TO_TRAY,
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
            else -> null
        }
    }
    return when (key) {
        "K" -> Shortcut.PALETTE
        "N" -> Shortcut.NEW_ASK
        "1" -> Shortcut.VIEW_CHATS
        "2" -> Shortcut.VIEW_SESSIONS
        "3" -> Shortcut.VIEW_STATUS
        "COMMA" -> Shortcut.VIEW_SETTINGS
        "SLASH" -> Shortcut.CHEATSHEET
        else -> null
    }
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
    androidx.compose.ui.input.key.Key.One -> "1"
    androidx.compose.ui.input.key.Key.Two -> "2"
    androidx.compose.ui.input.key.Key.Three -> "3"
    androidx.compose.ui.input.key.Key.Comma -> "COMMA"
    androidx.compose.ui.input.key.Key.Slash -> "SLASH"
    androidx.compose.ui.input.key.Key.Escape -> "ESCAPE"
    androidx.compose.ui.input.key.Key.F1 -> "F1"
    androidx.compose.ui.input.key.Key.DirectionUp -> "UP"
    androidx.compose.ui.input.key.Key.DirectionDown -> "DOWN"
    else -> null
}

/** One row of the cheat sheet, and the single source for what the app claims. */
val SHORTCUT_HELP: List<Pair<String, String>> = listOf(
    "Ctrl K" to "Find a chat or session",
    "Ctrl 1 / 2 / 3" to "Chats / Sessions / Status",
    "Ctrl ," to "Settings",
    "Ctrl N" to "New Ask chat",
    "Ctrl Shift N" to "New Act chat",
    "Alt ↑ / ↓" to "Previous / next in the list (works while typing)",
    "Esc" to "Back to the list",
    "Ctrl Shift H" to "Hide to the tray",
    "F1" to "This list",
)

// ------------------------------------------------------------------- palette

/** What the palette can offer: something to open, or something to do. */
sealed interface PaletteItem {
    val label: String
    val detail: String

    data class OpenChat(val id: String, override val label: String, override val detail: String) : PaletteItem
    data class OpenSession(val name: String, override val label: String, override val detail: String) : PaletteItem
    data class Verb(val shortcut: Shortcut, override val label: String, override val detail: String) : PaletteItem
}

private val VERBS = listOf(
    PaletteItem.Verb(Shortcut.NEW_ASK, "New Ask chat", "reasoning, memory and reads"),
    PaletteItem.Verb(Shortcut.NEW_ACT, "New Act chat", "can run commands and change files"),
    PaletteItem.Verb(Shortcut.VIEW_STATUS, "Status", "host, plan and usage"),
    PaletteItem.Verb(Shortcut.VIEW_SETTINGS, "Settings", "server, accounts, diagnostics"),
)

fun paletteItems(chats: List<Chat>, sessions: List<Session>): List<PaletteItem> =
    VERBS +
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
