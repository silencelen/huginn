package com.silencelen.huginn

import com.silencelen.huginn.ui.PromptDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The display copy of a Claude-written prompt on an approval card (security
 * scan 2026-11-02, F2): what it makes visible, and that it leaves plain text
 * alone.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class PromptDisplayTest {

    @Test
    fun `plain text, newlines included, comes through unchanged`() {
        val p = "Audit the LoRa node firmware.\nStart with power.\n\nThen the radio."
        assertEquals(p, PromptDisplay.visible(p))
        assertEquals("one two  three", PromptDisplay.visible("one two  three"), "one or two spaces are kept as they are")
        assertEquals("a\tb", PromptDisplay.visible("a\tb"), "a single tab is layout, not a trick")
    }

    @Test
    fun `a run of three or more spaces or tabs collapses to one visible gap`() {
        val hidden = "Tidy the README." + " ".repeat(200) + "rm -rf /srv/x"
        assertEquals("Tidy the README.${PromptDisplay.GAP}rm -rf /srv/x", PromptDisplay.visible(hidden))
        assertEquals("a${PromptDisplay.GAP}b", PromptDisplay.visible("a   b"), "three is the threshold")
        assertEquals("a${PromptDisplay.GAP}b", PromptDisplay.visible("a \t b"), "spaces and tabs count as one run")
        assertEquals("${PromptDisplay.GAP}x", PromptDisplay.visible("\t\t\tx"), "at the start of a line too")
        assertEquals("x${PromptDisplay.GAP}", PromptDisplay.visible("x     "), "and at the end")
        assertEquals("a\n${PromptDisplay.GAP}b", PromptDisplay.visible("a\n    b"), "a newline ends a run; indentation past two is still a gap")
    }

    @Test
    fun `control characters are shown as their code point`() {
        assertEquals("a⟨U+001B⟩[2Jb", PromptDisplay.visible("a\u001B[2Jb"), "an escape sequence cannot clear the block")
        assertEquals("a⟨U+000D⟩b", PromptDisplay.visible("a\rb"), "a bare carriage return cannot overwrite the line")
        assertEquals("a⟨U+0000⟩b", PromptDisplay.visible("a\u0000b"))
        assertEquals("a⟨U+007F⟩b", PromptDisplay.visible("a\u007Fb"))
        assertEquals("a⟨U+0008⟩b", PromptDisplay.visible("a\bb"), "a backspace draws, it does not erase")
    }

    @Test
    fun `bidi overrides, isolates and zero-width marks are shown, not obeyed`() {
        assertEquals("rm ⟨U+202E⟩fr- x", PromptDisplay.visible("rm ‮fr- x"), "the right-to-left override, the classic")
        assertEquals("⟨U+2066⟩a⟨U+2069⟩", PromptDisplay.visible("⁦a⁩"), "isolates")
        assertEquals("⟨U+202A⟩⟨U+202B⟩⟨U+202C⟩⟨U+202D⟩", PromptDisplay.visible("‪‫‬‭"), "every embedding and override")
        assertEquals("a⟨U+200B⟩b⟨U+200F⟩c", PromptDisplay.visible("a​b‏c"), "zero-width space and the RLM")
        assertEquals("⟨U+FEFF⟩x", PromptDisplay.visible("﻿x"), "the byte-order mark")
        assertTrue(PromptDisplay.isHidden('‍'), "the zero-width joiner is in the range")
        assertFalse(PromptDisplay.isHidden('é'), "an accented letter is a letter")
        assertFalse(PromptDisplay.isHidden('\n'))
        assertFalse(PromptDisplay.isHidden('\t'))
    }

    @Test
    fun `the counts are of the prompt as SENT, not the display copy`() {
        val hidden = "Tidy the README." + " ".repeat(200) + "rm -rf /srv/work/lora"
        assertEquals(PromptDisplay.Counts(1, 237), PromptDisplay.counts(hidden), "the 200 spaces are counted even though the copy shows one gap")
        assertEquals("1 line · 237 characters", PromptDisplay.countsLine(hidden))
        assertEquals(PromptDisplay.Counts(2, 47), PromptDisplay.counts("Audit the LoRa node firmware.\nStart with power."))
        assertEquals("2 lines · 47 characters", PromptDisplay.countsLine("Audit the LoRa node firmware.\nStart with power."))
        assertEquals(PromptDisplay.Counts(1, 2), PromptDisplay.counts("a\n"), "a trailing newline ends the line, it does not start an empty one")
        assertEquals(PromptDisplay.Counts(0, 0), PromptDisplay.counts(""))
        assertEquals("1 line · 1 character", PromptDisplay.countsLine("x"))
        assertEquals(PromptDisplay.Counts(1, 1), PromptDisplay.counts("😀"), "one emoji is one character, not two code units")
    }
}
