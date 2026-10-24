package com.silencelen.huginn.ui

import com.silencelen.huginn.data.FastMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fast chip's words and the note after a queued `/fast` toggle. Both shells
 * print these; one wording for one control is the house rule, so the sentence
 * lives here once and is pinned here once. The desktop said "fast mode on ·
 * after this turn" while the phone said "Fast mode on for alex after this turn".
 */
class FastWordsTest {

    @Test
    fun theChipNamesTheStateNeverTheMechanics() {
        assertEquals("fast on", fastWords(FastMode(on = true, available = true)))
        assertEquals("fast off", fastWords(FastMode(on = false, available = true)))
        assertEquals("fast unavailable", fastWords(FastMode(on = true, available = false, reason = "Fast mode requires usage credits")))
    }

    @Test
    fun theQueuedNoteIsOneSentenceForBothShells() {
        assertEquals("Fast mode on · after this turn", fastQueuedWords(on = true))
        assertEquals("Fast mode off · after this turn", fastQueuedWords(on = false))
    }
}
