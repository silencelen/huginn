package com.silencelen.huginn

import com.silencelen.huginn.ui.PromptChoices
import kotlin.test.Test
import kotlin.test.assertEquals

class PromptChoicesTest {

    @Test
    fun `an unchanged pane leaves local edits alone`() {
        val merged = PromptChoices.mergeBaseline(
            prev = setOf(1), next = setOf(1), chosen = setOf(1, 3),
        )
        assertEquals(setOf(1, 3), merged)
    }

    @Test
    fun `an external toggle-on arrives without stomping a local pick`() {
        val merged = PromptChoices.mergeBaseline(
            prev = setOf(1), next = setOf(1, 2), chosen = setOf(1, 4),
        )
        assertEquals(setOf(1, 2, 4), merged)
    }

    @Test
    fun `an external toggle-off removes even a locally-kept option`() {
        val merged = PromptChoices.mergeBaseline(
            prev = setOf(1, 2), next = setOf(2), chosen = setOf(1, 2, 3),
        )
        assertEquals(setOf(2, 3), merged)
    }

    @Test
    fun `simultaneous external on and off both apply`() {
        val merged = PromptChoices.mergeBaseline(
            prev = setOf(1), next = setOf(2), chosen = setOf(1, 4),
        )
        assertEquals(setOf(2, 4), merged)
    }
}
