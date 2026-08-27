package com.silencelen.huginn.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The escalation handoff both clients write. The two things worth breaking a
 * build over: the cap keeps the TAIL (recent context is what Claude needs),
 * and the draft ends mid-sentence so the person has something to finish —
 * a complete-looking draft gets sent unread.
 */
class EscalationTest {

    @Test
    fun theDraftCarriesTheModelTheRolesAndAnOpenEnding() {
        val d = Escalation.draft(
            "Qwen3 4B - PRESTIGE",
            listOf("User" to "what is a monad", "Assistant" to "a monoid in the category of endofunctors"),
        )
        assertTrue("Qwen3 4B - PRESTIGE" in d)
        assertTrue("User: what is a monad" in d)
        assertTrue("Assistant: a monoid" in d)
        assertTrue(d.endsWith("Pick this up from here: "), "open-ended, so the person must write the ask")
    }

    @Test
    fun aLongConversationKeepsItsTailNotItsHead() {
        val turns = (1..400).map { "User" to "turn number $it with some padding text to grow the body" }
        val d = Escalation.draft("Qwen3 8B - DATATREEX", turns)
        assertTrue("turn number 400" in d, "the newest turn survives")
        assertTrue("turn number 1 " !in d, "the oldest is what the cap spends")
        assertTrue("…" in d, "truncation is visible, never silent")
    }
}
