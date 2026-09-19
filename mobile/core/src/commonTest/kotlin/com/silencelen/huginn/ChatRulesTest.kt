package com.silencelen.huginn

import com.silencelen.huginn.ui.ChatRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT A CHAT IS CALLED IN A LIST.
 *
 * The client repeated the daemon's own words without reading them.
 *
 * ⚠ THE DAEMON CUTS A TITLE AT SIXTY CHARACTERS AND SAYS NOTHING ABOUT IT —
 * `humanizeUserText(text).slice(0, 60)` — so the chat header rendered "…give me
 * the url to download the latest huginn windows deskto" with clear space to
 * spare beside it. A hard cut with no mark reads as a rendering fault; an
 * ellipsis reads as a title.
 */
class ChatRulesTest {

    @Test
    fun `a title cut at the daemon's limit gets the mark it is missing`() {
        val cut = "give me the url to download the latest huginn windows deskto"
        assertEquals(ChatRules.DAEMON_TITLE_MAX, cut.length, "the fixture has to be the real shape")
        assertEquals("$cut…", ChatRules.title(cut))
    }

    @Test
    fun `a short title is left exactly as it is`() {
        assertEquals("restart the creative server", ChatRules.title("restart the creative server"))
    }

    @Test
    fun `a sixty-character sentence that ENDS is not marked as cut`() {
        // 60 characters is a length, not a proof. A message that finishes on a
        // full stop finished; adding an ellipsis there would invent a truncation.
        val whole = "whats the best way to calculate the area of an island.".padEnd(59, ' ').trim() + "."
        val sixty = whole.take(59).trimEnd() + "."
        assertEquals(sixty, ChatRules.title(sixty))
        assertTrue(!ChatRules.title(sixty)!!.endsWith("…"))
    }

    @Test
    fun `a title that already ends in an ellipsis is not given a second one`() {
        val already = "a".repeat(ChatRules.DAEMON_TITLE_MAX - 1) + "…"
        assertEquals(already, ChatRules.title(already))
    }
}
