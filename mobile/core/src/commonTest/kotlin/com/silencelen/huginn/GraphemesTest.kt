package com.silencelen.huginn

import com.silencelen.huginn.ui.Graphemes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cluster rules live typing counts Backspaces by. Each case is a sequence a
 * phone keyboard can produce, written as escapes because most of them are
 * invisible or ambiguous on screen.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class GraphemesTest {

    private fun clusters(s: String): List<String> =
        Graphemes.boundaries(s).zipWithNext { a, b -> s.substring(a, b) }

    @Test
    fun `plain text is a cluster per character`() {
        assertEquals(listOf("a", "b", "c"), clusters("abc"))
    }

    @Test
    fun `an empty string has no clusters`() {
        assertEquals(listOf(0), Graphemes.boundaries(""))
        assertEquals(0, Graphemes.lastStart(""))
    }

    @Test
    fun `a combining mark stays with its letter`() {
        assertEquals(listOf("é", "x"), clusters("éx"))
        assertEquals(0, Graphemes.lastStart("é"))
    }

    @Test
    fun `an emoji sequence is one cluster`() {
        for (seq in listOf(
            "👍🏽", // thumbs up, medium tone
            "1️⃣", // keycap 1
            "❤️‍🔥", // heart on fire
            "👨‍👩‍👧", // family
            "🧑🏽‍💻", // technologist, medium tone
            "🐈‍⬛", // black cat
            "🏴󠁧󠁢󠁥󠁮󠁧󠁿", // England
        )) {
            assertEquals(listOf(seq), clusters(seq), "split: ${seq.length} units")
        }
    }

    @Test
    fun `regional indicators pair into flags`() {
        val us = "🇺🇸"
        val gb = "🇬🇧"
        assertEquals(listOf(us, gb), clusters(us + gb))
        assertEquals(listOf(us, "🇬"), clusters(us + "🇬"))
    }

    @Test
    fun `a ZWJ joins pictographs, not letters`() {
        assertEquals(listOf("a‍", "b"), clusters("a‍b"))
    }

    @Test
    fun `CR LF is one, and a control stands alone`() {
        assertEquals(listOf("\r\n", "x"), clusters("\r\nx"))
        // The live field's sentinel: what is typed after it never joins it.
        assertEquals(listOf("​", "́"), clusters("​́"))
    }

    @Test
    fun `a lone surrogate is a cluster of its own`() {
        assertEquals(listOf("\uD83D", "x"), clusters("\uD83Dx"))
    }
}
