package com.silencelen.huginn.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ⚠ D-27. THE SESSION ROW SHOWED A RULE.
 *
 * `rv-desktop`'s row read `ctx 6% ————————————————…` — the whole of what the
 * list said that session was doing. The daemon's preview is the pane's last few
 * lines verbatim (`capture-pane -p`), and a Claude pane that has just drawn a
 * separator hands one back; the picker was `preview.firstOrNull()`, so the
 * separator won.
 *
 * Nothing is being hidden: the Screen tab renders every line the host sent. This
 * decides which ONE line goes on a row, and a rule is the one answer that cannot
 * be an answer.
 */
class PreviewSnippetTest {

    @Test
    fun `a rule is stepped over for the line that follows it`() {
        assertEquals(
            "Running the tests",
            previewSnippet(listOf("————————————————", "Running the tests")),
        )
    }

    @Test
    fun `every rule glyph the panes actually draw`() {
        for (rule in listOf("────────", "━━━━", "════", "╌╌╌╌", "┄┄┄", "┈┈┈", "--------", "– – –", "____", "====", "· · ·", "•••", "* * *")) {
            assertEquals(
                "after",
                previewSnippet(listOf(rule, "after")),
                "'$rule' is furniture, not a report",
            )
        }
    }

    @Test
    fun `blank and whitespace-only lines are stepped over too`() {
        assertEquals("real output", previewSnippet(listOf("", "   ", "\t", "real output")))
    }

    @Test
    fun `the first line wins when it says something`() {
        assertEquals("Reading AppStore.kt", previewSnippet(listOf("Reading AppStore.kt", "────")))
    }

    @Test
    fun `the chosen line is trimmed, as the row always did`() {
        assertEquals("busy", previewSnippet(listOf("   busy   ")))
    }

    @Test
    fun `nothing but furniture draws no snippet at all`() {
        assertNull(previewSnippet(listOf("————", "", "  ", "════")))
        assertNull(previewSnippet(emptyList()))
    }

    /**
     * ⚠ THE SET IS DELIBERATELY SHORT. A gate that grows to cover every lonely
     * punctuation mark starts eating real output, and the composer line in
     * particular MUST survive — `PanePreview.isComposerLine` is what draws it as
     * a draft rather than as something the session did (P-14), and it can only
     * do that if this hands it over.
     */
    @Test
    fun `a prompt, a quote and a root shell are output, not furniture`() {
        assertEquals("❯ run the tests", previewSnippet(listOf("❯ run the tests")))
        assertEquals("> quoted", previewSnippet(listOf("> quoted")))
        assertEquals("#", previewSnippet(listOf("#")))
    }

    @Test
    fun `a line that merely CONTAINS dashes is not a rule`() {
        assertEquals("-- done --", previewSnippet(listOf("-- done --")))
        assertEquals("edge-case-1", previewSnippet(listOf("edge-case-1")))
    }
}
