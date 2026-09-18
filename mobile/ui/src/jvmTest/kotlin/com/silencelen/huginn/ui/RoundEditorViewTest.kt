package com.silencelen.huginn.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The monthly Round's "Day of the month" field, typed rather than pasted.
 *
 * The composable needs a window; the sequence that lost a date does not — and
 * the sequence is the whole defect. Same arrangement as [HeadroomViewsTest].
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class RoundEditorViewTest {

    /** Types [keys] into the field one character at a time, as a keyboard does. */
    private fun type(keys: String, from: String = "", dates: Set<Int> = emptySet()): DatesEdit {
        var text = from
        var set = dates
        for (c in keys) {
            val e = monthDatesEdit(text + c, set)
            text = e.text
            set = e.dates
        }
        return DatesEdit(text, set)
    }

    @Test
    fun `typing the placeholder ends with the placeholder`() {
        // "1, 15" from empty. The separator used to be eaten on the keystroke
        // after it, and "115" — a state this passes through — blanked the box.
        val end = type("1, 15")
        assertEquals("1, 15", end.text, "the box must keep what was typed into it")
        assertEquals(setOf(1, 15), end.dates)
    }

    @Test
    fun `editing an existing multi-date Round does not drop a date`() {
        // "1, 15" → "1, 16": backspace the 5, type a 6. Re-deriving the text from
        // the parsed set rewrote "1, 1" to "1", so the 6 landed on it and the
        // Round saved [16] — a valid set, so Save stayed enabled.
        val mid = monthDatesEdit("1, 1", setOf(1, 15))
        assertEquals("1, 1", mid.text)
        assertEquals(setOf(1), mid.dates)

        val end = monthDatesEdit("1, 16", mid.dates)
        assertEquals("1, 16", end.text)
        assertEquals(setOf(1, 16), end.dates)
    }

    @Test
    fun `an out-of-range intermediate keeps the schedule it had`() {
        val e = monthDatesEdit("115", setOf(1, 15))
        assertEquals("115", e.text, "the digits stay on screen")
        assertEquals(setOf(1, 15), e.dates, "a transient 115 must not wipe the schedule")
    }

    @Test
    fun `an empty box does clear the schedule, because Save refuses that`() {
        assertEquals(emptySet(), monthDatesEdit("", setOf(1, 15)).dates)
        assertEquals(emptySet(), monthDatesEdit("  ", setOf(1, 15)).dates)
    }

    @Test
    fun `pasting the whole string still works`() {
        val e = monthDatesEdit("1, 15, 28", emptySet())
        assertEquals(setOf(1, 15, 28), e.dates)
    }

    @Test
    fun `letters never reach the box and the field is bounded`() {
        assertEquals("115", monthDatesEdit("1x1y5", emptySet()).text)
        assertEquals(64, monthDatesEdit("1, ".repeat(40), emptySet()).text.length)
    }

    @Test
    fun `the canonical spelling is what the field is seeded and reset with`() {
        assertEquals("1, 15", monthDatesText(setOf(15, 1)))
        assertEquals("", monthDatesText(emptySet()))
    }
}
