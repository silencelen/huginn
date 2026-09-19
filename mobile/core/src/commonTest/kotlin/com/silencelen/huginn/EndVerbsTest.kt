package com.silencelen.huginn

import com.silencelen.huginn.ui.EndVerbs
import com.silencelen.huginn.ui.VerbTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two ways a session ends, and the words for them.
 *
 * Pinned here rather than in either shell's suite because the POINT of moving
 * them into `:core` is that the desktop's right-click menu, the phone's session
 * row and the phone's detail overflow all say the same thing. Asserting them in
 * one client's tests would let the other drift and stay green, which is what the
 * five separate literals this replaces already did.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class EndVerbsTest {

    @Test
    fun `the verbs say what happens, not how it is done`() {
        assertEquals("Wrap up", EndVerbs.SOFT)
        assertEquals("Kill session", EndVerbs.HARD)
        assertEquals("Wrap up", EndVerbs.soft(1))
        assertEquals("Kill session", EndVerbs.hard(1))
    }

    @Test
    fun `the old words are gone, both of them`() {
        // The pair that was picked wrongly: "Wind down" and "End session" read as
        // two spellings of one thing, sitting one row apart in the same menu.
        val words = listOf(EndVerbs.SOFT, EndVerbs.HARD, EndVerbs.soft(3), EndVerbs.hard(3))
        for (w in words) {
            assertFalse(w.contains("Wind down"), "the old soft verb is still being drawn: $w")
            assertFalse(w == "End session", "the old hard verb is still being drawn: $w")
        }
    }

    @Test
    fun `a multi-selection says how many it addresses`() {
        // Same rule the delete verbs follow: a row reading "Wrap up" that
        // messages four sessions is the worst version of this feature.
        assertEquals("Wrap up 2 sessions", EndVerbs.soft(2))
        assertEquals("Kill 2 sessions", EndVerbs.hard(2))
        assertEquals("Wrap up 11 sessions", EndVerbs.soft(11))
        assertEquals("Kill 11 sessions", EndVerbs.hard(11))
    }

    @Test
    fun `a count of one or none is the single wording, never "1 sessions"`() {
        // 0 reaches here only from an empty selection, which no menu builds — but
        // the plural of a number it did not check is the kind of text that ships.
        assertEquals(EndVerbs.SOFT, EndVerbs.soft(0))
        assertEquals(EndVerbs.HARD, EndVerbs.hard(0))
    }

    @Test
    fun `neither verb carries an ellipsis, because both open the same kind of dialog`() {
        // The mark means "a dialog follows" and both of these do, so it would be
        // on both or neither — and the hard end has never carried one. Rename… /
        // Archive… / Delete… keep it.
        assertFalse(EndVerbs.SOFT.endsWith("…"))
        assertFalse(EndVerbs.HARD.endsWith("…"))
    }

    @Test
    fun `the two ending verbs are the only users of the two reds`() {
        // The tone is pure so a menu's claim about itself can be asserted; the
        // COLOUR is :ui's. What matters here is that there are three of them —
        // the boolean this replaced could not say "ends something, loses
        // nothing", which is why the wrap-up used to be drawn as a plain row.
        assertEquals(3, VerbTone.entries.size)
        assertTrue(VerbTone.SOFT != VerbTone.DESTRUCTIVE)
        assertEquals(VerbTone.PLAIN, VerbTone.entries.first(), "plain is the default a row falls back to")
    }
}
