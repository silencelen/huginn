package com.silencelen.huginn.ui


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The headroom form's NUMBER BOX, one keystroke at a time.
 *
 * The composable needs a window; the arithmetic that made four of these fields
 * untypable does not. Same arrangement as [HeadroomViewsTest].
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class HeadroomSettingsViewTest {

    private val high = HeadroomForm.range("stopPct")
    private val below = HeadroomForm.range("clearBelowPct")

    /** The four 50..100 rows are the ones this broke on. */
    @Test
    fun `the ranges this is about`() {
        assertEquals(50..100, high, "the HIGH_RANGE rows are where a prefix clamp bites")
        assertEquals(1..99, below)
    }

    @Test
    fun `typing 92 into a 50 to 100 field reaches 92, one keystroke at a time`() {
        // Select-all then type: the box goes "9" then "92". A clamped prefix set
        // the field to 50 on the first keystroke and to 100 on the second.
        val first = pctFieldEdit("9", high)
        assertEquals("9", first.text, "the box must hold what was typed")
        assertNull(first.commit, "9 is not a value in 50..100 — it is half of one")

        val second = pctFieldEdit("92", high)
        assertEquals("92", second.text)
        assertEquals(92, second.commit)
    }

    @Test
    fun `reaching 100 passes through two states that must not commit`() {
        assertNull(pctFieldEdit("1", high).commit)
        assertNull(pctFieldEdit("10", high).commit)
        assertEquals(100, pctFieldEdit("100", high).commit)
    }

    @Test
    fun `the box can be emptied`() {
        val e = pctFieldEdit("", high)
        assertEquals("", e.text, "an empty box is a state the reader passes through")
        assertNull(e.commit)
    }

    @Test
    fun `a below-range field still commits its single digits`() {
        assertEquals(9, pctFieldEdit("9", below).commit)
        assertEquals(92, pctFieldEdit("92", below).commit)
        assertNull(pctFieldEdit("0", below).commit, "0 is outside 1..99")
    }

    @Test
    fun `non-digits and overlong input never reach the setting`() {
        assertEquals("92", pctFieldEdit("9 2%", high).text)
        assertEquals(92, pctFieldEdit("9 2%", high).commit)
        assertEquals("100", pctFieldEdit("10000", high).text, "three digits is the whole range")
    }
}
