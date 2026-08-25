package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Screen

/**
 * Getting text back OUT of a terminal pane.
 *
 * The screen view could render a session and offered no way to take anything from
 * it. The case that proved it: a 450-character OAuth URL on a headless box, hard-
 * wrapped across five rows — a thing nobody can retype, and which a text-selection
 * gesture would have handed back as five fragments with newlines in them.
 *
 * So there are two operations, and the difference between them is deliberate:
 *
 *   [screenText] copies WHAT IS THERE. No reflowing, no cleverness — a terminal
 *   draws in columns and a copy that silently rejoined its rows would corrupt
 *   every table, tree and progress bar on screen.
 *
 *   [linksOn] copies what a wrapped URL MEANT. A URL split across rows is not
 *   information a person can use, so this one place undoes the wrap.
 *
 * The unwrap is exact rather than a heuristic: [Screen.width] is the pane's real
 * column count, and a row that reaches it is one the terminal broke, not one the
 * writer ended. Guessing the width from the longest line would have been wrong
 * exactly when the screen holds one long line, which is this case.
 */

/** Rows that reached the pane's width, rejoined into the lines they were before. */
internal fun logicalLines(lines: List<String>, width: Int): List<String> {
    if (width <= 0) return lines
    val out = mutableListOf<String>()
    val buf = StringBuilder()
    for (line in lines) {
        buf.append(line)
        // Shorter than the pane means the writer ended it. Only a row that filled
        // every column can have been continued.
        if (line.length < width) {
            out.add(buf.toString())
            buf.clear()
        }
    }
    if (buf.isNotEmpty()) out.add(buf.toString())
    return out
}

private val URL_RE = Regex("""https?://[^\s<>"'`]+""")

/** Punctuation that ends a sentence rather than a URL. */
private const val TRAILING = ".,;:!?)]}>\"'"

/**
 * Every link visible on this screen, wrap undone, in the order they appear.
 *
 * Deduplicated: a pane often shows the same URL twice — once where it was printed
 * and again in a status line — and offering the same link twice is a choice with
 * no answer.
 */
fun linksOn(screen: Screen?): List<String> {
    if (screen == null) return emptyList()
    return logicalLines(screen.lines, screen.width)
        .flatMap { line -> URL_RE.findAll(line).map { it.value } }
        .map { it.trimEnd { c -> c in TRAILING } }
        // A bare scheme is what a truncated pane leaves behind, and copying it
        // would look like it worked.
        .filter { it.length > "https://".length }
        .distinct()
}

/**
 * The visible pane as text.
 *
 * Trailing blank rows go, because a terminal is a fixed grid and the empty bottom
 * of it is not content. Trailing spaces on each row go for the same reason.
 * Nothing else is touched.
 */
fun screenText(screen: Screen?): String {
    if (screen == null) return ""
    return screen.lines
        .map { it.trimEnd() }
        .dropLastWhile { it.isEmpty() }
        .joinToString("\n")
}

/** Whether there is anything worth offering to copy. */
fun hasCopyableText(screen: Screen?): Boolean = screenText(screen).isNotBlank()
