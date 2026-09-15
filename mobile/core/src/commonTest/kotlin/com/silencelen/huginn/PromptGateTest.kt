package com.silencelen.huginn

import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.PromptHeader
import com.silencelen.huginn.data.PromptOption
import com.silencelen.huginn.ui.PromptGate
import com.silencelen.huginn.ui.PromptPlacement
import com.silencelen.huginn.ui.PromptSurface
import com.silencelen.huginn.ui.SessionFace
import com.silencelen.huginn.ui.asMultiPartSteer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a question card may be drawn.
 *
 * The failure this rule exists for is invisible to a screenshot of the card
 * itself, because the card renders perfectly — it just renders ON TOP OF the
 * terminal the reader was sent to in order to answer. So the cases below are
 * mostly about what must NOT appear, and the last one is about the two shells
 * reaching the same answer from their two different ideas of a tab.
 */
class PromptGateTest {

    @Test
    fun `a question on the conversation draws its card`() {
        assertTrue(PromptGate.visible(hasQuestion = true, face = SessionFace.CONVERSATION))
    }

    @Test
    fun `the same question on the screen draws nothing`() {
        // THE RULE. The pane below is the dialog; a card over it is the bug.
        assertFalse(PromptGate.visible(hasQuestion = true, face = SessionFace.SCREEN))
    }

    @Test
    fun `the overview keeps a question surface`() {
        // Nothing is covered there and nothing else on that face can answer, so
        // suppressing it would only make a question harder to find. Since the
        // owner's decision 23 that surface is the link bar rather than the card —
        // this gate still answers the narrower "is there one at all".
        assertTrue(PromptGate.visible(hasQuestion = true, face = SessionFace.OVERVIEW))
    }

    // ------------------------------------------------------------ placement

    @Test
    fun `a session steers and a chat answers in place`() {
        // THE MATRIX. A session has a pane and every prompt type is answerable
        // there, so its Conversation and Overview hand the reader over. A chat has
        // no pane at all — there is nowhere to send anybody — so its card stays,
        // compact. The Screen face draws nothing on either, first and before the
        // surface is even consulted: the terminal below IS the dialog.
        assertEquals(
            PromptPlacement.LINK_TO_SCREEN,
            PromptPlacement.of(SessionFace.CONVERSATION, PromptSurface.SESSION),
        )
        assertEquals(
            PromptPlacement.LINK_TO_SCREEN,
            PromptPlacement.of(SessionFace.OVERVIEW, PromptSurface.SESSION),
        )
        assertEquals(
            PromptPlacement.NONE,
            PromptPlacement.of(SessionFace.SCREEN, PromptSurface.SESSION),
        )
        assertEquals(
            PromptPlacement.INLINE_COMPACT,
            PromptPlacement.of(SessionFace.CONVERSATION, PromptSurface.CHAT),
        )
        assertEquals(
            PromptPlacement.INLINE_COMPACT,
            PromptPlacement.of(SessionFace.OVERVIEW, PromptSurface.CHAT),
        )
        // Even asked about a face a chat does not have, the Screen rule wins —
        // a chat can never be told to draw over a terminal it does not own.
        assertEquals(
            PromptPlacement.NONE,
            PromptPlacement.of(SessionFace.SCREEN, PromptSurface.CHAT),
        )
    }

    @Test
    fun `the screen tab carries a dot only while the question is elsewhere`() {
        // The link bar's other half: a bar scrolls out of view, a tab strip never
        // does. On the Screen face the reader is already there and a dot pointing
        // at the tab they are on is noise.
        assertTrue(PromptGate.screenTabDot(hasQuestion = true, face = SessionFace.CONVERSATION))
        assertTrue(PromptGate.screenTabDot(hasQuestion = true, face = SessionFace.OVERVIEW))
        assertFalse(PromptGate.screenTabDot(hasQuestion = true, face = SessionFace.SCREEN))
        SessionFace.entries.forEach { face ->
            assertFalse(PromptGate.screenTabDot(hasQuestion = false, face = face), "$face")
        }
    }

    @Test
    fun `the gist is one line, cut at a word, and never invented`() {
        // A TUI-scraped question arrives with the dialog's own line breaks in it,
        // and those turn a one-line bar into three.
        assertEquals("Pick a database and a cache", PromptGate.gist("Pick a database\n  and a cache"))
        // Null and blank both mean "nothing to preview" — a degraded ask is pending
        // with text the scrape could not read, and the bar's own sentence is then
        // the whole fact.
        assertNull(PromptGate.gist(null))
        assertNull(PromptGate.gist("   \n  "))

        val long = "Which of these seventeen migration strategies should the deploy take tonight"
        val cut = PromptGate.gist(long)
        assertNotNull(cut)
        assertTrue(cut!!.length <= PromptGate.GIST_MAX + 1, "over budget: $cut")
        assertTrue(cut.endsWith("…"), cut)
        // Cut at a word boundary, so the tail reads as a trimmed phrase rather
        // than a severed word.
        assertTrue(!cut.dropLast(1).endsWith(" "), "no trailing space before the ellipsis: $cut")
        assertTrue(long.startsWith(cut.dropLast(1)), "the preview must be the question's own words")
    }

    @Test
    fun `no question draws nothing on any face`() {
        SessionFace.entries.forEach { face ->
            assertFalse(PromptGate.visible(hasQuestion = false, face = face), "$face")
        }
    }

    @Test
    fun `the phone's tab indices are the faces its strip shows`() {
        assertEquals(SessionFace.CONVERSATION, SessionFace.ofTabIndex(0))
        assertEquals(SessionFace.SCREEN, SessionFace.ofTabIndex(1))
        assertEquals(SessionFace.OVERVIEW, SessionFace.ofTabIndex(2))
    }

    @Test
    fun `an index off the end of the strip is never read as the screen`() {
        // A saved index from an older build, or one that outlived a tab: it may
        // draw a card it did not need to, but it may not suppress one.
        listOf(-1, 3, 99).forEach { i ->
            assertEquals(SessionFace.CONVERSATION, SessionFace.ofTabIndex(i), "index $i")
            assertTrue(PromptGate.visible(hasQuestion = true, face = SessionFace.ofTabIndex(i)), "index $i")
        }
    }

    @Test
    fun `a pane-only multi-question prompt is steered, not tap-answered`() {
        // The over-answer trap: no fused sidecar, but the pane scrape read a
        // two-tab dialog. A single digit here answers question 1 AND confirms
        // question 2's default, so the client must NOT offer answer buttons.
        val paneOnlyTwoQ = PanePrompt(
            question = "Pick a database",
            options = listOf(PromptOption(number = 1, label = "Postgres")),
            source = null,
            headers = listOf(PromptHeader("Database"), PromptHeader("Cache")),
        )
        assertTrue(PromptGate.paneOnlyMultiQuestion(paneOnlyTwoQ))

        // A single-question dialog carries at most one header — safe to tap.
        assertFalse(
            PromptGate.paneOnlyMultiQuestion(
                paneOnlyTwoQ.copy(headers = listOf(PromptHeader("Database"))),
            ),
        )
        // A FUSED prompt was split correctly by the daemon — its buttons are safe
        // even with sibling questions present.
        assertFalse(
            PromptGate.paneOnlyMultiQuestion(paneOnlyTwoQ.copy(source = "hook")),
        )
        // No tab strip at all (an ordinary permission/plan dialog) — tap away.
        assertFalse(PromptGate.paneOnlyMultiQuestion(PanePrompt(question = "Proceed?")))
    }

    @Test
    fun `the steer card is read-only and points at the Screen tab`() {
        val prompt = PanePrompt(
            question = "Pick a database",
            options = listOf(PromptOption(number = 1, label = "Postgres")),
            fingerprint = "fp-1",
            headers = listOf(PromptHeader("Database"), PromptHeader("Cache")),
        )
        val steer = prompt.asMultiPartSteer()
        assertTrue(steer.multiPart, "a multi-part ask renders read-only + a Screen-tab steer")
        assertEquals("Pick a database", steer.question)
        assertEquals("fp-1", steer.fingerprint)
        assertEquals(2, steer.questionCount)
    }

    @Test
    fun `the phone's screen tab and the desktop's reach the same answer`() {
        // The anti-drift property, and the only reason this lives in core: the
        // phone arrives by index and the desktop by enum, and the two clients
        // must not disagree about whether a question is on screen.
        val byIndex = PromptGate.visible(hasQuestion = true, face = SessionFace.ofTabIndex(1))
        val byFace = PromptGate.visible(hasQuestion = true, face = SessionFace.SCREEN)
        assertEquals(byFace, byIndex)
        assertFalse(byIndex)
    }
}
