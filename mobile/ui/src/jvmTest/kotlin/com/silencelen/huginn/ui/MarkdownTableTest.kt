package com.silencelen.huginn.ui

import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A TABLE SCROLLS SIDEWAYS INSIDE ITSELF, NEVER BY DRAGGING THE PAGE.
 *
 * `:core` now parses GFM pipe tables (3.5.1, after the walk found the
 * Conversation tab drawing an answer's table as raw `| # | Item |` pipes while
 * the Screen tab beside it drew a box table). Parsing one is only half the fix:
 * a table is the one block Claude writes that has no honest narrow form, so the
 * grid is allowed to be wider than the phone — and the thing that must NOT
 * happen is the transcript taking that width, which drags every message on the
 * screen sideways to read one table.
 *
 * WHY A SOURCE GREP for the container: there is no compose-ui-test in this
 * module and the failure is a measured width. The source text IS the rule — the
 * same reasoning as [DisclosureHeightOnlyTest] and CapBeforeFillTest.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class MarkdownTableTest {

    private fun source(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val f = File(root, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/MarkdownText.kt")
        assertTrue(f.isFile, "MarkdownText.kt not found at ${f.absolutePath}")
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue(text.length > 5_000, "MarkdownText.kt read as ${text.length} chars — wrong file")
        return text
    }

    @Test
    fun `the renderer has a branch for a table at all`() {
        assertTrue(
            source().contains("is MdBlock.Table ->"),
            "a parsed table with no branch here is a crash, not a missing feature",
        )
    }

    @Test
    fun `the table composable scrolls inside its own container`() {
        val text = source()
        val start = text.indexOf("private fun TableGrid(")
        assertTrue(start > 0, "TableGrid is gone — this gate is now scanning for nothing")
        // Up to the next top-level declaration: the body, and nothing after it.
        val end = text.indexOf("\nprivate val TABLE_COL_MIN", start)
        assertTrue(end > start, "cannot find the end of TableGrid")
        val body = text.substring(start, end)
        assertTrue(
            body.contains("horizontalScroll("),
            "the grid must carry its own sideways scroll, or the transcript carries it",
        )
        // And every child inside it is width-set: `fillMaxWidth` under infinite
        // incoming constraints has nothing to fill, which is what the divider did.
        val inner = body.substringAfter("horizontalScroll(")
        assertTrue(
            !inner.contains("fillMaxWidth()"),
            "a fillMaxWidth child inside a horizontal scroll has no width to take",
        )
    }

    @Test
    fun `a column is as wide as its longest cell, within bounds`() {
        assertEquals(44.dp, tableColumnWidth(0), "an empty column still has to be tappable-wide")
        assertEquals(44.dp, tableColumnWidth(1))
        assertEquals(210.dp, tableColumnWidth(400), "a paragraph in a cell wraps rather than running off")
        val mid = tableColumnWidth(20)
        assertTrue(mid > 44.dp && mid < 210.dp, "a 20-character column sits between the bounds, was $mid")
        assertTrue(tableColumnWidth(30) > mid, "a longer cell is never a narrower column")
    }

    @Test
    fun `a column takes the longest cell anywhere in it, not just the header's`() {
        val rows = listOf(
            listOf(androidx.compose.ui.text.AnnotatedString("#"), androidx.compose.ui.text.AnnotatedString("Item")),
            listOf(
                androidx.compose.ui.text.AnnotatedString("1"),
                androidx.compose.ui.text.AnnotatedString("Storage cleanup, tier 1 only"),
            ),
        )
        val widths = columnWidths(rows)
        assertEquals(2, widths.size)
        assertEquals(tableColumnWidth(1), widths[0], "a one-character column stays narrow")
        assertEquals(tableColumnWidth(28), widths[1], "the body row is what sets this one")
    }
}
