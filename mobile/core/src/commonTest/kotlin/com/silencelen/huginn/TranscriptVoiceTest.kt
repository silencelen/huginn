package com.silencelen.huginn

import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.TranscriptVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠⚠ THE WRAP-UP PHRASE IS NOT THE READER'S WORDS (P-34/D-28).
 *
 * `POST /v1/sessions/:name/soft-end` types the phrase into the pane, so it lands
 * in the transcript as an ordinary typed prompt with nothing structural on it.
 * Both shells drew it as a right-aligned user bubble — on an ARCHIVED transcript,
 * where it is the only account of how the session ended.
 *
 * The rule this must not break is the one that makes the match safe at all: the
 * phrase is the HOST's (a deployment setting, published on `/v1/status`), so a
 * daemon that does not send one classifies nothing.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class TranscriptVoiceTest {

    private val phrase =
        "Finish outstanding items, commit your work, and prepare to end the session."

    private fun user(text: String) = TranscriptEvent(seq = 1, kind = "user", text = text)

    @Test
    fun `the daemon's own phrase is recognised`() {
        assertTrue(TranscriptVoice.isWrapUp(user(phrase), phrase))
    }

    @Test
    fun `whitespace the pane picked up does not break the match`() {
        assertTrue(TranscriptVoice.isWrapUp(user("  $phrase "), phrase))
        assertTrue(
            TranscriptVoice.isWrapUp(user(phrase.replace(", ", ",\n")), phrase),
            "a paste that wrapped is the same sentence",
        )
    }

    @Test
    fun `a daemon that sends no phrase classifies nothing`() {
        // The safe direction, and the one an older daemon takes: an unrecognised
        // wrap-up is a bubble, exactly as it always was.
        assertFalse(TranscriptVoice.isWrapUp(user(phrase), null))
        assertFalse(TranscriptVoice.isWrapUp(user(phrase), ""))
        assertFalse(TranscriptVoice.isWrapUp(user(phrase), "   "))
    }

    @Test
    fun `a deployment that changed the phrase is matched on ITS phrase`() {
        val custom = "Wind it up please."
        assertTrue(TranscriptVoice.isWrapUp(user(custom), custom))
        assertFalse(
            TranscriptVoice.isWrapUp(user(phrase), custom),
            "the host's wording wins — a client literal would be the copy that drifts",
        )
    }

    @Test
    fun `only a user row can be one`() {
        for (kind in listOf("assistant", "system", "thinking", "tool", "command")) {
            assertFalse(
                TranscriptVoice.isWrapUp(TranscriptEvent(seq = 1, kind = kind, text = phrase), phrase),
                "$kind is already attributed to somebody",
            )
        }
    }

    @Test
    fun `a person who types the phrase themselves gets a note, and that is the right trade`() {
        // Stated rather than hidden: the match is textual because the record
        // carries nothing else, so a reader who types the exact sentence is
        // attributed to huginn. The alternative is the bubble this fixes.
        assertTrue(TranscriptVoice.isWrapUp(user(phrase), phrase))
        assertFalse(
            TranscriptVoice.isWrapUp(user("$phrase Also commit the docs."), phrase),
            "anything the reader added makes it theirs again",
        )
    }

    @Test
    fun `the note says who asked and keeps what was asked`() {
        val note = TranscriptVoice.wrapUpNote(phrase)
        assertTrue(note.startsWith(TranscriptVoice.WRAP_UP_LEAD), note)
        assertTrue(note.contains(phrase), "the instruction the model acted on stays readable: $note")
        assertEquals(TranscriptVoice.WRAP_UP_LEAD, TranscriptVoice.wrapUpNote("   "))
    }
}
