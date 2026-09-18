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

private const val ESC = '\u001B'
private const val BEL = '\u0007'

/**
 * A row of `capture-pane -e` as the person on the other end SEES it.
 *
 * ⚠ :core LOST ITS STRIP AND NOBODY NOTICED. v1 had `Ansi.strip`; the cell grid
 * replaced the renderer (see [TerminalGrid]) and only the palette stayed, while
 * `server/appd/lib/pane.js` still documents its own `stripAnsi` as "Mirrors the
 * client's Ansi.strip". Everything in this file that treats a row as TEXT — the
 * clipboard, the wrap arithmetic, the link scan — was reading SGR bytes as
 * characters. [Screen.lines] stays raw, because the grid painter needs the
 * styling; this is for the copy paths only.
 *
 * @param text the row with CSI and OSC sequences removed. The visible label of
 *   an OSC 8 hyperlink survives as ordinary text, which is what it is.
 * @param links the URI targets of any OSC 8 hyperlinks on the row. Claude Code
 *   emits these around file paths and doc links, and the target is frequently
 *   NOT in the visible text at all — "Security guide" is a link to
 *   code.claude.com and says so nowhere on screen.
 */
internal data class Unescaped(val text: String, val links: List<String>)

internal fun unescape(line: String): Unescaped {
    if (ESC !in line) return Unescaped(line, emptyList())
    val out = StringBuilder(line.length)
    var links: MutableList<String>? = null
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c != ESC) {
            out.append(c)
            i++
            continue
        }
        // A row that ends mid-escape is a torn capture, not text.
        if (i + 1 >= line.length) break
        when (line[i + 1]) {
            // CSI: SGR colour, cursor moves, erases. Ends at the first final byte.
            '[' -> {
                var j = i + 2
                while (j < line.length && line[j] !in '@'..'~') j++
                i = if (j < line.length) j + 1 else line.length
            }
            // OSC: title changes and OSC 8 hyperlinks. Terminated by BEL or by ST
            // (ESC backslash) — tmux emits ST, which is why a scan that only knew
            // about BEL ate the rest of the row.
            ']' -> {
                var j = i + 2
                while (j < line.length && line[j] != BEL && line[j] != ESC) j++
                osc8Target(line.substring(i + 2, j))?.let {
                    (links ?: mutableListOf<String>().also { l -> links = l }).add(it)
                }
                i = if (j < line.length && line[j] == BEL) j + 1 else j
            }
            // Two-character escapes, ST included; consumed whole.
            else -> i += 2
        }
    }
    return Unescaped(out.toString(), links ?: emptyList())
}

/** Escapes out. The half of [unescape] most callers want. */
internal fun stripAnsi(line: String): String = unescape(line).text

/**
 * `8;id=zaxmda;https://…` → the URI. The closing `8;;` carries none and is not a
 * link; anything that is not http(s) is not one either.
 */
private fun osc8Target(body: String): String? {
    if (!body.startsWith("8;")) return null
    val uri = body.split(';', limit = 3).getOrNull(2)?.trim().orEmpty()
    return uri.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

/**
 * Rows that reached the pane's width, rejoined into the lines they were before.
 *
 * ⚠ MEASURED IN COLUMNS, on the STRIPPED row. `line.length` counts UTF-16 units
 * of a row that is mostly escape bytes, which is wrong in both directions: SGR
 * made a two-column row read as full (6 of 25 rows on a live pane) and weld
 * itself to the next one, manufacturing links out of two unrelated rows; a wide
 * glyph made a genuinely wrapped row read as short, so it was never rejoined and
 * the URL that wrapped stayed in pieces. [TerminalGrid.charWidth] is the same
 * wcwidth rule the painter lays the grid out with, so the two agree by
 * construction.
 */
internal fun logicalLines(lines: List<String>, width: Int): List<String> {
    val plain = lines.map { stripAnsi(it) }
    if (width <= 0) return plain
    val out = mutableListOf<String>()
    val buf = StringBuilder()
    for (line in plain) {
        buf.append(line)
        // Narrower than the pane means the writer ended it. Only a row that filled
        // every column can have been continued.
        if (displayWidth(line) < width) {
            out.add(buf.toString())
            buf.clear()
        }
    }
    if (buf.isNotEmpty()) out.add(buf.toString())
    return out
}

/** Columns this text occupies in a terminal — not characters, not code points. */
private fun displayWidth(s: String): Int {
    var w = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += if (cp > 0xFFFF) 2 else 1
        w += TerminalGrid.charWidth(cp)
    }
    return w
}

// ⚠ ESC IS IN THE NEGATED CLASS, and not only because the scan now runs on
// stripped text: a regex that treats an escape byte as an ordinary URL
// character is how a coloured URL came back with its SGR tail attached.
private val URL_RE = Regex("""https?://[^\s<>"'`\u001B]+""")

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
    // ⚠ THE OSC 8 TARGET FIRST, because it is the one thing a reader could not
    // have copied off the pane by hand: the visible text of a hyperlink is a
    // LABEL ("Security guide") and the URI lives only in the escape. Not trimmed
    // of trailing punctuation the way scraped text is — the escape delimits the
    // URI exactly, so there is nothing to guess at.
    val hyperlinked = screen.lines.flatMap { unescape(it).links }
    val scraped = logicalLines(screen.lines, screen.width)
        .flatMap { line -> URL_RE.findAll(line).map { it.value } }
        .map { it.trimEnd { c -> c in TRAILING } }
    return (hyperlinked + scraped)
        // A bare scheme is what a truncated pane leaves behind, and copying it
        // would look like it worked.
        .filter { it.length > "https://".length }
        .distinct()
}

/**
 * The visible pane as text.
 *
 * Trailing blank rows go, because a terminal is a fixed grid and the empty bottom
 * of it is not content. Trailing spaces on each row go for the same reason. The
 * escape sequences go because they were never characters — see [unescape]; a row
 * that is nothing BUT styling is therefore blank, and a screen of them has
 * nothing to copy.
 *
 * Nothing else is touched: no reflowing, no rejoining.
 */
fun screenText(screen: Screen?): String {
    if (screen == null) return ""
    return screen.lines
        .map { stripAnsi(it).trimEnd() }
        .dropLastWhile { it.isEmpty() }
        .joinToString("\n")
}

/** Whether there is anything worth offering to copy. */
fun hasCopyableText(screen: Screen?): Boolean = screenText(screen).isNotBlank()
