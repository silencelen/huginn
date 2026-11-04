package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ProjectPeer
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.theme.HuginnTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠ THE CLOSED /context CARD READ "Context usage   ## Context Usage"
 * (2026-11-03, the desktop): the preview beside the label was the body's first
 * line, raw — a Markdown heading that only repeats the label. The bodies below
 * are the two shapes Claude Code 2.1.316 records `/context` in, verbatim but
 * shortened: the Markdown twin (an isMeta user record) and the terminal table
 * (`system`/`local_command`, the ANSI already stripped by the daemon).
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class FoldPreviewRowTest {

    private val markdown = "## Context Usage\n\n**Model:** claude-opus-5-5  \n**Tokens:** 45k / 1m (4%)\n\n" +
        "### Estimated usage by category\n\n| Category | Tokens | Percentage |\n|----------|--------|------------|\n" +
        "| System prompt | 2.9k | 0.3% |\n| Messages | 16.2k | 1.6% |"

    private val terminal = "Context Usage\n" +
        "⛀ ⛁ ⛁ ⛁ ⛀ ⛀ ⛀ ⛁ ⛁ ⛁ ⛁ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶   Opus 5.5\n" +
        "⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶   claude-opus-5-5\n" +
        "⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶   45k/1m tokens (4%)\n" +
        "⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶ ⛶   ⛁ System prompt: 2.9k tokens (0.3%)"

    private fun context(body: String) =
        TranscriptEvent(seq = 7, kind = "system", text = "Context usage", body = body, fold = "context")

    private fun ComposeUiTest.row(ev: TranscriptEvent) {
        setContent {
            HuginnTheme(darkTheme = true, rootSurface = true) {
                Box { Box(Modifier.width(900.dp)) { TranscriptEventItem(ev, onCopy = {}) } }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.shown(text: String, substring: Boolean = true) =
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `the closed context card previews the total, not the heading`() = runComposeUiTest {
        row(context(markdown))
        assertEquals(1, shown("Context usage   45k / 1m tokens · 4%", substring = false), "label, then the figure")
        assertEquals(0, shown("##"), "no heading marks on a one-line preview")
        assertEquals(0, shown("Context Usage"), "and not the heading repeating the label")
    }

    @Test
    fun `the terminal copy of the table previews the same figure`() = runComposeUiTest {
        row(context(terminal))
        assertEquals(1, shown("Context usage   45k / 1m tokens · 4%", substring = false))
        assertEquals(0, shown("⛶"), "no grid glyphs in a preview")
    }

    @Test
    fun `any folded report previews its first line as words, not as markup`() = runComposeUiTest {
        row(
            TranscriptEvent(
                seq = 8, kind = "system", text = "Message from Explore: Findings", peer = ProjectPeer(name = "Explore"),
                body = "## Findings\n\n**Three** files changed.", fold = "peer",
            ),
        )
        assertEquals(1, shown("Message from Explore   Findings", substring = false))
        assertEquals(0, shown("##"))
    }
}
