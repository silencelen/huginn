package com.silencelen.huginn

import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.PromptHeader
import com.silencelen.huginn.data.PromptOption
import com.silencelen.huginn.ui.PromptGate
import com.silencelen.huginn.ui.SessionFace
import com.silencelen.huginn.ui.asMultiPartSteer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `the overview keeps the card`() {
        // Nothing is covered there and nothing else on that face can answer, so
        // suppressing it would only make a question harder to find.
        assertTrue(PromptGate.visible(hasQuestion = true, face = SessionFace.OVERVIEW))
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
