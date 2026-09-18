package com.silencelen.huginn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NO SELECTABLE TEXT INSIDE AN OVERLAY — asserted against the source tree,
 * because the failure is a THROWN CRASH in code nothing here can host.
 *
 * A `Popup`, a `Dialog` and the tooltip half of a `TooltipArea` compose their
 * content in a SEPARATE LAYOUT ROOT, but they inherit composition locals from
 * where they were called. The transcript is wrapped in one `SelectionContainer`
 * (`SessionView`, `ChatView`), so `LocalSelectionRegistrar` is in scope for every
 * row — and for every overlay a row opens. A `Text` composed in that overlay
 * therefore REGISTERS ITSELF AS A SELECTABLE of the transcript's selection.
 *
 * The first press that starts a drag-selection makes `SelectionManager` sort the
 * registered selectables by position, which is `containerCoordinates
 * .localPositionOf(selectable)` — across two roots that share no ancestor:
 *
 *     java.lang.IllegalArgumentException: layouts are not part of the same hierarchy
 *       at androidx.compose.ui.node.NodeCoordinator.findCommonAncestor
 *       at androidx.compose.ui.node.NodeCoordinator.localPositionOf
 *       at …selection.SelectionRegistrarImpl.sort
 *       at …selection.SelectionManager.getSelectionLayout
 *       at …selection.SelectionManager.startSelection
 *
 * Compose's window exception handler turns that into an error dialog and the
 * client dies. It shipped in desktop 1.4.0 and was reported as "sometimes
 * crashes when trying to select text in a session" — sometimes, because the
 * overlay has to be UP when the press lands, and the timestamp tooltip needs
 * 400ms of hover to get there. Hovering a message for a moment and then dragging
 * is a completely ordinary way to select text, which is why it is not rare.
 *
 * THE RULE, stated so it cannot rot: every overlay content lambda in the client
 * sources — the `tooltip = { }` of a `TooltipArea`, and the trailing content
 * lambda of a raw `Popup`/`Dialog`/`DialogWindow` — must be exactly one
 * `DisableSelection { … }` block. Not "contains a DisableSelection somewhere":
 * the block has to open first and close last, so that everything the overlay
 * draws now AND everything added to it later is covered.
 *
 * ⚠ THE ANCHOR IS NOT THE CONTENT. A `TooltipArea`'s `content` is the row being
 * hovered — it lives in the transcript's own hierarchy and MUST stay selectable,
 * which is the entire point of the `SelectionContainer`. This only ever looks at
 * the `tooltip =` lambda, and getting that backwards would disable selecting the
 * transcript rather than fixing anything.
 *
 * WHY EVERY OVERLAY AND NOT ONLY THE TRANSCRIPT'S. Which overlays can be
 * composed under a `SelectionContainer` is a reachability question a source scan
 * cannot answer — `Menus.kt`'s `Popup` is the representation for EVERY context
 * menu in the app, including the transcript's, and `Tips.kt`'s `Tip` is provided
 * at the window root. So the rule is the blunt one, and it costs nothing: text in
 * a tooltip, a menu row or an image viewer's caption was never usefully
 * selectable, because the drag that would select it dismisses it.
 *
 * WHY A SOURCE SCAN AND NOT A COMPOSE TEST. There is no compose-ui-test in :ui or
 * :app-desktop, and the crash needs a real pointer press against a real window
 * with a popup already up — it was reproduced with Xvfb + xdotool, which is not
 * something a unit suite can hold. The source text IS the rule.
 */
class OverlaysDisableSelectionTest {

    /**
     * How many raw overlay sites the tree had when this gate was written. A
     * pattern that stops matching (a rename, a wrapper, a moved file) would
     * otherwise make this suite pass by scanning nothing — the same failure mode
     * [CapBeforeFillTest] guards with its file-count floor.
     */
    private val knownSites = 4

    // ------------------------------------------------------------------ sources

    /** Shipped Kotlin in the four modules. Test sources excluded: a fixture may
     *  legitimately name these constructs without drawing anything. */
    private fun sources(): List<File> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        return listOf("core", "ui", "app", "app-desktop")
            .map { File(root, "$it/src") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .filter { f -> f.path.split(File.separatorChar).none { it == "test" || it.endsWith("Test") } }
    }

    // ------------------------------------------------------------------ scanner
    //
    // Everything below works on a BLANKED copy of the file: comments and string
    // literals are replaced by spaces of the same length, so indices (and
    // therefore line numbers) still line up with the original while a brace in a
    // doc comment or a `"}"` in a string cannot throw the matcher off. This file
    // is full of prose about braces; without this it would fail on itself.

    private fun blank(src: String): String {
        val out = src.toCharArray()
        var i = 0
        fun blankTo(end: Int, from: Int) {
            for (k in from until minOf(end, out.size)) if (out[k] != '\n') out[k] = ' '
        }
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> {
                    val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                    blankTo(end, i); i = end
                }
                src.startsWith("/*", i) -> {
                    val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                    blankTo(end, i); i = end
                }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    blankTo(end, i); i = end
                }
                src[i] == '"' || src[i] == '\'' -> {
                    val quote = src[i]
                    var j = i + 1
                    while (j < src.length && src[j] != quote) {
                        if (src[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, src.length)
                    blankTo(end, i); i = end
                }
                else -> i++
            }
        }
        return String(out)
    }

    /** Index just past the `)` that closes the `(` at [open], or -1. */
    private fun closeParen(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i + 1 }
            }
        }
        return -1
    }

    /** Index of the `}` that closes the `{` at [open], or -1. */
    private fun closeBrace(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private fun firstCodeIndex(s: String, from: Int, until: Int): Int {
        var i = from
        while (i < until && s[i].isWhitespace()) i++
        return i
    }

    /** One overlay's content lambda: where its body starts and ends. */
    private data class Site(val file: File, val what: String, val bodyStart: Int, val bodyEnd: Int)

    private val trailingLambdaOverlay = Regex("""(?<![A-Za-z0-9_.])(Popup|Dialog|DialogWindow)\s*\(""")
    private val tooltipOverlay = Regex("""(?<![A-Za-z0-9_.])TooltipArea\s*\(""")

    private fun sites(file: File, s: String): List<Site> {
        val found = mutableListOf<Site>()
        // Popup(…) { content }  ·  Dialog(…) { content }
        for (m in trailingLambdaOverlay.findAll(s)) {
            val afterCall = closeParen(s, s.indexOf('(', m.range.first))
            if (afterCall < 0) continue
            val brace = firstCodeIndex(s, afterCall, s.length)
            if (brace >= s.length || s[brace] != '{') continue
            val end = closeBrace(s, brace)
            if (end < 0) continue
            found += Site(file, m.groupValues[1], brace + 1, end)
        }
        // TooltipArea(tooltip = { content }, …) — the TOOLTIP half only; the
        // `content` half is the hovered row and stays selectable.
        for (m in tooltipOverlay.findAll(s)) {
            val open = s.indexOf('(', m.range.first)
            val afterCall = closeParen(s, open)
            if (afterCall < 0) continue
            val named = s.indexOf("tooltip", open).takeIf { it in (open + 1) until afterCall } ?: continue
            val brace = s.indexOf('{', named).takeIf { it in named until afterCall } ?: continue
            val end = closeBrace(s, brace)
            if (end < 0) continue
            found += Site(file, "TooltipArea tooltip", brace + 1, end)
        }
        return found
    }

    private fun scan(): List<Pair<Site, String?>> = sources().flatMap { file ->
        val s = blank(file.readText())
        sites(file, s).map { site ->
            val first = firstCodeIndex(s, site.bodyStart, site.bodyEnd)
            val fault: String? = when {
                !s.startsWith("DisableSelection", first) ->
                    "does not open with DisableSelection"
                else -> {
                    val brace = s.indexOf('{', first)
                    val end = if (brace in first until site.bodyEnd) closeBrace(s, brace) else -1
                    when {
                        end < 0 -> "DisableSelection has no block"
                        firstCodeIndex(s, end + 1, site.bodyEnd) < site.bodyEnd ->
                            "draws something after the DisableSelection block"
                        else -> null
                    }
                }
            }
            site to fault
        }
    }

    private fun line(file: File, index: Int) =
        file.readText().take(index).count { it == '\n' } + 1

    // -------------------------------------------------------------------- gates

    @Test
    fun `the source tree is scanned at all`() {
        assertTrue(sources().size > 100, "only ${sources().size} Kotlin files found — the root is wrong")
    }

    @Test
    fun `every overlay in the tree is still found`() {
        val found = scan()
        assertTrue(
            found.size >= knownSites,
            "found ${found.size} overlay content lambdas, expected at least $knownSites — " +
                "the patterns have stopped matching and this gate is scanning nothing",
        )
    }

    @Test
    fun `no overlay composes selectable text inside a SelectionContainer`() {
        val offences = scan().filter { it.second != null }.map { (site, fault) ->
            "${site.file.path}:${line(site.file, site.bodyStart)}  ${site.what} $fault"
        }
        assertTrue(
            offences.isEmpty(),
            "an overlay's content is a SEPARATE layout root: a Text in it registers with the " +
                "transcript's selection registrar and the next press-drag throws \"layouts are not " +
                "part of the same hierarchy\". Wrap the whole content lambda in DisableSelection:\n" +
                offences.joinToString("\n"),
        )
    }
}
