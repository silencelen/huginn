package com.silencelen.huginn

import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.MAX_TRANSCRIPT_EVENTS
import com.silencelen.huginn.ui.prependTranscriptPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The conversation view is the full history; the tail is only where it opens.
 * Prepending an older page is how the rest of it arrives.
 */
class HistoryPagingTest {

    private fun ev(seq: Int, text: String) =
        TranscriptEvent(seq = seq, kind = "user", text = text)

    private fun page(vararg texts: String, windowStart: Long = 0, model: String? = null) =
        TranscriptPage(
            events = texts.mapIndexed { i, t -> ev(i, t) },
            windowStart = windowStart,
            model = model,
        )

    @Test
    fun `older events land in front, and seqs stay unique`() {
        val current = page("third", "fourth", windowStart = 900)
        val older = page("first", "second", windowStart = 400)

        val merged = prependTranscriptPage(current, older)

        assertEquals(listOf("first", "second", "third", "fourth"), merged.events.map { it.text })
        assertEquals(merged.events.size, merged.events.map { it.seq }.toSet().size,
            "a duplicate key throws in LazyColumn and takes the whole view with it")
    }

    @Test
    fun `the handle moves to the older page, so the next read goes further back`() {
        val merged = prependTranscriptPage(page("b", windowStart = 900), page("a", windowStart = 400))
        assertEquals(400L, merged.windowStart)
        assertTrue(merged.truncated, "there is still conversation above byte 400")
    }

    @Test
    fun `reaching the start of the file stops offering more`() {
        val merged = prependTranscriptPage(page("b", windowStart = 900), page("a", windowStart = 0))
        assertEquals(0L, merged.windowStart)
        assertFalse(merged.truncated, "byte 0 is the beginning; there is nothing above it")
    }

    @Test
    fun `session-level fields stay with the CURRENT page`() {
        // An older page reports the model and mode as they were back then.
        // Letting them win would make scrolling up rewrite the header.
        val current = page("now", windowStart = 900, model = "claude-opus-5")
        val older = page("then", windowStart = 400, model = "claude-haiku-4-5")
        assertEquals("claude-opus-5", prependTranscriptPage(current, older).model)
    }

    @Test
    fun `history the reader asked for is never trimmed away`() {
        // The live merge caps the retained window and drops from the FRONT, which
        // is exactly where loaded history lands. Prepending must not inherit that.
        val current = TranscriptPage(
            events = (0 until MAX_TRANSCRIPT_EVENTS).map { ev(it, "live $it") },
            windowStart = 5_000,
        )
        val older = page("the oldest thing loaded", windowStart = 100)

        val merged = prependTranscriptPage(current, older)

        assertEquals("the oldest thing loaded", merged.events.first().text)
        assertEquals(MAX_TRANSCRIPT_EVENTS + 1, merged.events.size)
    }
}
