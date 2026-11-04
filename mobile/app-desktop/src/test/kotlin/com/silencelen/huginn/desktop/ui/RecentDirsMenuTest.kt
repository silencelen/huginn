package com.silencelen.huginn.desktop.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.silencelen.huginn.data.RecentDirs
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The New session dialog's Recent dropdown, as a reader sees it.
 *
 * ⚠ THE BUG (desktop 2.10.0, 2026-11-03): two recents under the same long prefix
 * were drawn as the same row — `/root/projects/apv2/assets/src/voice/…` twice —
 * because the menu cut every path at its END, which is exactly the part that
 * tells two directories apart. The rule for the label is [DirLabel] (and its own
 * test); this is the dialog drawing it: no row cut off, the two rows different,
 * each still ending in its own last segment, and the whole path on hover.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class RecentDirsMenuTest {

    // Placeholder paths, long enough to be shortened even in a menu as wide as
    // the dialog's field, and identical up to their last segment.
    private val a = "/root/projects/example/assets/src/voice/models/pretrained/checkpoints/aspen-large"
    private val b = "/root/projects/example/assets/src/voice/models/pretrained/checkpoints/aspen-small"
    private val recent = RecentDirs(dirs = listOf(a, b, "/root/projects/osid"), default = "/root/netplan")

    /** One path row of the open menu: its words, and whether they were drawn cut off. */
    private class Drawn(val text: String, val cut: Boolean)

    private fun ComposeUiTest.pathRows(): List<Drawn> {
        val out = mutableListOf<Drawn>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { t ->
                val s = t.text
                if (s.startsWith("/") || s.startsWith("…")) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    // Cut = drawn narrower than its one-line width (an end ellipsis
                    // Skia does not report as one), or wrapped.
                    val cut = layouts.any { l ->
                        l.lineCount > 1 || l.size.width + 1 < l.multiParagraph.intrinsics.maxIntrinsicWidth
                    }
                    out += Drawn(s, cut)
                }
            }
            n.children.forEach(::walk)
        }
        onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun ComposeUiTest.openMenu() {
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                NewSessionDialog(taken = emptySet(), onDismiss = {}, onConfirm = { _, _ -> }, recent = recent)
            }
        }
        waitForIdle()
        onNodeWithText("Recent").performClick()
        waitForIdle()
    }

    @Test
    fun `two recents that differ only at the end are told apart and none is cut off`() = runComposeUiTest {
        openMenu()
        val rows = pathRows()
        assertEquals(4, rows.size, "the default and the three recents: ${rows.map { it.text }}")
        val cut = rows.filter { it.cut }.map { it.text }
        assertTrue(cut.isEmpty(), "rows drawn cut off at the end, losing what tells them apart: $cut")
        val ra = rows.single { it.text.endsWith("/aspen-large") }.text
        val rb = rows.single { it.text.endsWith("/aspen-small") }.text
        assertNotEquals(ra, rb)
        // The short ones are drawn whole.
        assertTrue(rows.any { it.text == "/root/projects/osid" }, rows.map { it.text }.toString())
        assertTrue(rows.any { it.text == "/root/netplan" }, rows.map { it.text }.toString())
    }

    @Test
    fun `a shortened row carries the whole path on hover`() = runComposeUiTest {
        openMenu()
        val shown = pathRows().single { it.text.endsWith("/aspen-large") }.text
        assertNotEquals(a, shown, "the long path was not shortened at all")
        assertEquals(0, onAllNodesWithText(a).fetchSemanticsNodes().size, "the whole path is on screen before any hover")
        onNodeWithText(shown).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(1, onAllNodesWithText(a).fetchSemanticsNodes().size, "hovering the row did not show the whole path")
    }
}
