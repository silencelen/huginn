package com.silencelen.huginn.desktop

import com.silencelen.huginn.ui.PromptGate
import com.silencelen.huginn.ui.SessionFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This client's tabs in the form the shared rules reason about.
 *
 * The mapping is the ONLY desktop-specific part of "no question card on the
 * Screen tab", so it is also the only place that rule can be lost on this client
 * while the shared tests stay green — a tab wired to the wrong face draws the
 * card over the terminal again, and looks entirely correct doing it.
 *
 * NOTE the kotlin.test argument order: (expected, actual, message).
 */
class SessionTabFaceTest {

    @Test
    fun `every tab maps to its own face`() {
        assertEquals(SessionFace.CONVERSATION, SessionTab.CONVERSATION.face)
        assertEquals(SessionFace.SCREEN, SessionTab.SCREEN.face)
        assertEquals(SessionFace.OVERVIEW, SessionTab.OVERVIEW.face)
        // Nothing collapses onto one face: three tabs, three distinct answers, so
        // a tab added later cannot silently inherit another's rules.
        assertEquals(3, SessionTab.entries.map { it.face }.toSet().size)
    }

    @Test
    fun `the card is withheld on the screen tab and drawn on the other two`() {
        assertFalse(PromptGate.visible(hasQuestion = true, face = SessionTab.SCREEN.face))
        assertTrue(PromptGate.visible(hasQuestion = true, face = SessionTab.CONVERSATION.face))
        assertTrue(PromptGate.visible(hasQuestion = true, face = SessionTab.OVERVIEW.face))
    }

    @Test
    fun `this client and the phone agree about the screen`() {
        // The anti-drift property at its real boundary: the desktop arrives by
        // enum and the phone by tab index, and they must reach the same answer.
        assertEquals(SessionTab.SCREEN.face, SessionFace.ofTabIndex(1))
        assertEquals(SessionTab.CONVERSATION.face, SessionFace.ofTabIndex(0))
        assertEquals(SessionTab.OVERVIEW.face, SessionFace.ofTabIndex(2))
    }
}
