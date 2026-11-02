package com.silencelen.huginn.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decisions under a [WrappedMonoBlock]'s "↓ more" cue and its `onAtEnd`
 * (third scan 2026-11-02, F1). Pure, because the trap is invisible:
 * `ScrollState.maxValue` is `Int.MAX_VALUE` until the block has measured, and
 * a gate on Start that read "unmeasured" as "at the end" would be open for the
 * first frame — and forever on a card that never re-measures.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class PromptBlockTest {

    @Test
    fun `an unmeasured block is not at its end and shows no cue`() {
        assertFalse(PromptBlock.atEnd(0, Int.MAX_VALUE), "fail closed until the measure")
        assertFalse(PromptBlock.moreBelow(0, Int.MAX_VALUE), "nothing known yet, nothing claimed")
    }

    @Test
    fun `a block whose content fits is at its end with no cue`() {
        assertTrue(PromptBlock.atEnd(0, 0))
        assertFalse(PromptBlock.moreBelow(0, 0))
    }

    @Test
    fun `a taller block says more until it is scrolled to the max`() {
        assertFalse(PromptBlock.atEnd(0, 400))
        assertTrue(PromptBlock.moreBelow(0, 400))
        assertFalse(PromptBlock.atEnd(399, 400))
        assertTrue(PromptBlock.moreBelow(399, 400))
        assertTrue(PromptBlock.atEnd(400, 400))
        assertFalse(PromptBlock.moreBelow(400, 400))
        assertTrue(PromptBlock.atEnd(401, 400), "past the max is at the end")
        assertEquals("↓ more", PromptBlock.MORE)
    }
}
