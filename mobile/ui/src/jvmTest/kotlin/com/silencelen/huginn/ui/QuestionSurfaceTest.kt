package com.silencelen.huginn.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the two question surfaces DRAW — the session's one-line link bar, and the
 * chat's compact card.
 *
 * Both are decided by pure functions beside the composables, for the reason every
 * other view rule in this module is: the composables cannot be asserted without a
 * window, and the mistakes are never in the pixels. The card's whole complaint was
 * a width cap that was silently defeated by modifier ORDER — a card that renders
 * perfectly and is exactly as wrong as before, which no screenshot would catch.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class QuestionSurfaceTest {

    // ---------------------------------------------------------- the link bar

    @Test
    fun `a pending question draws the bar, and nothing pending draws none`() {
        val bar = questionLinkFace("Pick a database", PromptPlacement.LINK_TO_SCREEN, wide = false)
        assertNotNull(bar, "a session with a question waiting gets the bar")
        assertEquals(QUESTION_LINK_LABEL, bar!!.label)

        // NULL IS "NOTHING IS PENDING". The bar is placed unconditionally by both
        // shells, so this is the only thing standing between an idle session and a
        // permanent claim that Claude is asking something.
        assertNull(questionLinkFace(null, PromptPlacement.LINK_TO_SCREEN, wide = true))
    }

    @Test
    fun `a pending question with no readable text still gets a bar`() {
        // BLANK IS NOT NULL, and both are real: a plan approval carries no question
        // of its own and a degraded ask is one the pane scrape could not read.
        // Suppressing those is how a plan approval went invisible for weeks.
        val bar = questionLinkFace("", PromptPlacement.LINK_TO_SCREEN, wide = true)
        assertNotNull(bar)
        assertNull(bar!!.detail, "there is nothing to preview, so nothing is previewed")
    }

    @Test
    fun `the preview is a wide-layout affordance only`() {
        val q = "Which migration strategy should tonight's deploy take"
        assertNull(
            questionLinkFace(q, PromptPlacement.LINK_TO_SCREEN, wide = false)!!.detail,
            "the label is already 48 characters; a gist beside it on a narrow phone " +
                "is three words and an ellipsis, which looks like the question and is not",
        )
        assertEquals(q, questionLinkFace(q, PromptPlacement.LINK_TO_SCREEN, wide = true)!!.detail)
    }

    @Test
    fun `the bar draws on no other placement`() {
        // The Screen face's silence and the chat's compact card are somebody else's
        // job, and a bar that followed the reader to the terminal is the exact bug
        // PromptGate was written for.
        listOf(PromptPlacement.NONE, PromptPlacement.INLINE_COMPACT).forEach { p ->
            assertNull(questionLinkFace("Pick a database", p, wide = true), "$p")
        }
    }

    // ------------------------------------------------------- the compact card

    @Test
    fun `the compact card caps its width and shows four lines collapsed`() {
        val compact = promptCardMetrics(compact = true)
        assertEquals(560.dp, compact.maxWidth, "the reading measure the chat card is capped at")
        assertEquals(4, compact.questionMaxLines, "collapsed, with a `more` expander past it")
        assertTrue(compact.wrapOptions, "buttons sized to their labels, in a wrapping row")
    }

    @Test
    fun `the uncapped shape is still expressible, and is not the chat's`() {
        // The old full-bleed card: five full-width buttons and an uncapped shell,
        // measured at 290-330dp for a five-option question at a 768x1024 window.
        // Kept as a shape, no longer chosen by a session (which draws the bar) or
        // by a chat (which draws the compact one).
        val full = promptCardMetrics(compact = false)
        assertEquals(Dp.Unspecified, full.maxWidth)
        assertEquals(Int.MAX_VALUE, full.questionMaxLines)
        assertTrue(!full.wrapOptions)
    }

    @Test
    fun `the two shapes disagree about every measurement`() {
        // Belt and braces on a parameter object: a `compact` that returned the same
        // three values would compile, pass a width test on its own, and change
        // nothing on screen.
        val a = promptCardMetrics(compact = true)
        val b = promptCardMetrics(compact = false)
        assertTrue(a.maxWidth != b.maxWidth)
        assertTrue(a.questionMaxLines != b.questionMaxLines)
        assertTrue(a.wrapOptions != b.wrapOptions)
    }
}
