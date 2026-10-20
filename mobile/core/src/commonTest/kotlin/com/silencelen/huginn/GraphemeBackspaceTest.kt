package com.silencelen.huginn

import com.silencelen.huginn.ui.LiveInput
import com.silencelen.huginn.ui.LocalEcho
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deleting an emoji in live typing.
 *
 * The hidden field keeps what was typed until it has been quiet for a moment, so
 * a Backspace pressed right after an emoji is read against text that still holds
 * it. The keyboard removes the whole emoji; the diff counted the UTF-16 units it
 * took — two for 😀, four for 👍🏽, eight for a family — and every Backspace past
 * the first erased a character in the pane that nobody had deleted.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class GraphemeBackspaceTest {

    private val S = LiveInput.SENTINEL

    private val grinning = "😀"
    private val thumbsUpMediumTone = "👍🏽"
    private val flagUs = "🇺🇸"
    private val flagGb = "🇬🇧"
    private val keycapOne = "1️⃣"
    private val redHeart = "❤️"
    private val family = "👨‍👩‍👧"
    private val flagEngland =
        "🏴󠁧󠁢󠁥󠁮󠁧󠁿"

    @Test
    fun `deleting an emoji just typed is one backspace`() {
        val t = LiveInput.diff(S + "hi" + grinning, S + "hi")
        assertEquals(
            listOf<LiveInput.Op>(LiveInput.Op.Key(listOf("BSpace"))),
            t.ops(),
            "a second Backspace takes the 'i' typed before it",
        )
    }

    @Test
    fun `a skin tone, a flag, a keycap, a heart and a family are one backspace each`() {
        val named = mapOf(
            "thumbs up, medium tone" to thumbsUpMediumTone,
            "US flag" to flagUs,
            "keycap 1" to keycapOne,
            "red heart" to redHeart,
            "family" to family,
            "England flag" to flagEngland,
        )
        for ((name, emoji) in named) {
            assertEquals(1, LiveInput.diff(S + "ok" + emoji, S + "ok").backspaces, "deleting the $name")
        }
    }

    @Test
    fun `only what was deleted is counted`() {
        assertEquals(2, LiveInput.diff(S + "ok" + thumbsUpMediumTone + "x", S + "ok").backspaces)
        assertEquals(2, LiveInput.diff(S + flagUs + flagGb, S).backspaces, "two flags")
        assertEquals(4, LiveInput.diff(S + "ok" + grinning, "").backspaces, "the sentinel, o, k and the emoji")
    }

    /**
     * é as e + U+0301. One Backspace takes the whole é in the pane, so a keyboard
     * that removed only the accent means: delete it, type the e again.
     */
    @Test
    fun `a keyboard that drops only the accent retypes the letter under it`() {
        val t = LiveInput.diff(S + "café", S + "cafe")
        assertEquals(1, t.backspaces)
        assertEquals("e", t.insert)
    }

    @Test
    fun `typing after an emoji is still only the new text`() {
        val t = LiveInput.diff(S + grinning, S + grinning + thumbsUpMediumTone)
        assertEquals(0, t.backspaces)
        assertEquals(thumbsUpMediumTone, t.insert)
    }

    @Test
    fun `the echo loses a whole emoji per backspace`() {
        var e = LocalEcho.typed(LocalEcho.Echo(), "hi$grinning")
        e = LocalEcho.backspace(e)
        assertEquals("hi", e.text, "half an emoji was left in the echo")
        e = LocalEcho.backspace(LocalEcho.typed(e, family))
        assertEquals("hi", e.text)
    }
}
