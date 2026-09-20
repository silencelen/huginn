package com.silencelen.huginn

import com.silencelen.huginn.ui.PanePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠⚠ P-14. THE ROW SHOWED A PROMPT NOBODY TYPED.
 *
 * `rv_phone_2`'s row read `❯ run sleep 10 in the background then say doneB`.
 * It is Claude Code's own dim inline SUGGESTION offered back from history, drawn
 * by the app in normal weight and indistinguishable from a real queued prompt.
 *
 * The daemon cannot strip it on this path — the list's capture has no `-e` and
 * `previewLines` strips ANSI before a preview line exists — so the client uses
 * the structural tell `pane.js` already owns.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class PanePreviewTest {

    /** The pane writes the prompt as `❯` + U+00A0, not `❯` + a space. */
    private val NBSP = ' '

    @Test
    fun `the ghost suggestion is marked as the composer, not as output`() {
        val rows = PanePreview.rows(
            listOf(
                "Done — the file is written.",
                "❯${NBSP}run sleep 10 in the background then say doneB",
            ),
        )
        assertEquals(2, rows.size, "it is kept — a line that vanishes takes its explanation with it")
        assertFalse(rows[0].hint, "the assistant's own line is output")
        assertTrue(rows[1].hint, "the composer line is not something the session did")
    }

    /**
     * ⚠ THE GAP IS A NON-BREAKING SPACE, and the whole prompt test turns on it.
     * Kotlin's `Char.isWhitespace()` covers U+00A0 (it is
     * `isWhitespace || isSpaceChar`) where Java's own `Character.isWhitespace`
     * does NOT — a distinction one import away from mattering. The premise is
     * pinned here so a change in it is a failing test rather than a preview that
     * silently stops marking anything.
     */
    @Test
    fun `the gap after the chevron may be a non-breaking space`() {
        assertTrue(NBSP.isWhitespace(), "Kotlin's answer for U+00A0 — the rule does not rely on it")
        assertTrue(PanePreview.isComposerLine("❯${NBSP}end session"))
        assertTrue(PanePreview.isComposerLine("❯ end session"))
        assertTrue(PanePreview.isComposerLine("  ❯  end session"))
    }

    @Test
    fun `ordinary output is never mistaken for a prompt`() {
        for (line in listOf(
            "Reading src/main.kt",
            "  4 files changed",
            ">>> python repl output",
            ">not a quote, no gap after the mark",
            "",
        )) {
            assertFalse(PanePreview.isComposerLine(line), line)
        }
    }

    /** `>` with a gap is how the TUI draws a prompt on a terminal without the glyph. */
    @Test
    fun `the plain chevron counts too, which is what pane_js matches`() {
        assertTrue(PanePreview.isComposerLine("> run the thing"))
    }

    @Test
    fun `the text behind the chevron is available without it`() {
        assertEquals(
            "run sleep 10 in the background then say doneB",
            PanePreview.composerText("❯${NBSP}run sleep 10 in the background then say doneB "),
        )
        assertEquals("end session", PanePreview.composerText("  ❯  end session  "))
    }

    @Test
    fun `a preview with nothing in it stays empty`() {
        assertEquals(emptyList(), PanePreview.rows(emptyList()))
    }
}
