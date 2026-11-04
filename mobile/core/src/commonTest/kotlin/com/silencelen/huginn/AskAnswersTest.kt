package com.silencelen.huginn

import com.silencelen.huginn.data.AskQuestion
import com.silencelen.huginn.ui.AskAnswers
import com.silencelen.huginn.ui.WordFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An AskUserQuestion's answers, read from the result Claude Code writes for the
 * model. Every result below is verbatim from a 2.1.296 – 2.1.316 transcript on
 * one host (the questions shortened where noted).
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AskAnswersTest {

    private val sizeShape = listOf(
        AskQuestion(question = "Which size?", header = "Size"),
        AskQuestion(question = "Which shape?", header = "Shape"),
    )

    private val READ = "Read the answers carefully — they may request clarification, changes, or that you not " +
        "proceed — and follow what they actually say."

    @Test
    fun `typed answers come back against their questions`() {
        val r = "The user answered: \"Which size?\"=\"extra wide\", \"Which shape?\"=\"hexagon\". $READ"
        assertEquals(
            listOf(AskAnswers.Answer("Which size?", "extra wide"), AskAnswers.Answer("Which shape?", "hexagon")),
            AskAnswers.parse(r, sizeShape),
        )
        assertEquals(listOf("Size → extra wide", "Shape → hexagon"), AskAnswers.outcome(r, sizeShape, ok = true)?.lines)
        assertEquals("Answered", AskAnswers.outcome(r, sizeShape, ok = true)?.heading)
    }

    @Test
    fun `chosen options come back the same way`() {
        val r = "Your questions have been answered: \"How should clients verify the certificate?\"=\"Token-bound pin " +
            "(Recommended)\". You can now continue with these answers in mind."
        assertEquals(
            listOf(AskAnswers.Answer("How should clients verify the certificate?", "Token-bound pin (Recommended)")),
            AskAnswers.parse(r),
        )
    }

    @Test
    fun `a multi-select answer is one answer, commas and all`() {
        val q = listOf(AskQuestion(question = "Which colour?", header = "Colour"), AskQuestion(question = "Which fruits?", header = "Fruits"))
        val r = "The user answered: \"Which colour?\"=\"teal, actually (typed from the app)\", \"Which fruits?\"=\"Apple, Pear, ripe\". $READ"
        assertEquals(listOf("Colour → teal, actually (typed from the app)", "Fruits → Apple, Pear, ripe"), AskAnswers.outcome(r, q, true)?.lines)
        // With the card's questions gone (an orphan), the boundary is still found.
        assertEquals(
            listOf(AskAnswers.Answer("Which colour?", "teal, actually (typed from the app)"), AskAnswers.Answer("Which fruits?", "Apple, Pear, ripe")),
            AskAnswers.parse(r),
        )
    }

    @Test
    fun `quotes inside a question or an answer do not end it`() {
        val q = listOf(AskQuestion(question = "Ship \"fast\" or \"safe\"?", header = "Pace"), AskQuestion(question = "Then?", header = "Next"))
        val r = "The user answered: \"Ship \"fast\" or \"safe\"?\"=\"\"safe\", obviously\", \"Then?\"=\"tag it\". $READ"
        assertEquals(listOf("Pace → \"safe\", obviously", "Next → tag it"), AskAnswers.outcome(r, q, true)?.lines)
    }

    @Test
    fun `a preview block and notes are not part of the answer`() {
        val r = "Your questions have been answered: \"How should the dashboards be structured?\"=\"Home + domains " +
            "(Recommended)\" selected preview:\n┌ Homelab · Compute ┐\n│ FIRING 7 │ notes: keep it small. You can now " +
            "continue with these answers in mind."
        assertEquals(
            listOf(AskAnswers.Answer("How should the dashboards be structured?", "Home + domains (Recommended)")),
            AskAnswers.parse(r),
        )
        // Notes alone, no option: the notes are the answer.
        val notesOnly = "The user answered: \"Which size?\"=(no option selected) notes: whatever fits the box. $READ"
        assertEquals(listOf("Size → whatever fits the box"), AskAnswers.outcome(notesOnly, sizeShape, true)?.lines)
    }

    @Test
    fun `a question the daemon clipped still finds its header`() {
        val long = "This is a deliberately long question used to test how the Huginn question card behaves when the " +
            "card has to scroll. It goes on for a few sentences so that it wraps across several lines on a phone " +
            "screen, then it asks: which of the following made-up options should the imaginary test pipeline take next?"
        val card = listOf(AskQuestion(question = long.take(240) + "…", header = "Long test"))
        val r = "The user answered: \"$long\"=\"keep it\". $READ"
        assertEquals(listOf("Long test → keep it"), AskAnswers.outcome(r, card, true)?.lines)
        // No card at all: the question is the label, cut at a word.
        val label = AskAnswers.outcome(r, emptyList(), true)!!.lines.single()
        assertEquals("This is a deliberately long… → keep it", label)
    }

    @Test
    fun `a result the daemon clipped keeps what it has`() {
        val clipped = "The user answered: \"Which size?\"=\"extra wide\", \"Which shape?\"=\"a hexagon with a very lo…"
        assertEquals(
            listOf(AskAnswers.Answer("Which size?", "extra wide"), AskAnswers.Answer("Which shape?", "a hexagon with a very lo…")),
            AskAnswers.parse(clipped, sizeShape),
        )
    }

    @Test
    fun `the other endings are read too`() {
        val idle = "No answer came in time.\n\nBefore going idle the user had selected: \"Which size?\"=\"Small\"."
        assertTrue(AskAnswers.isAnswer(idle))
        assertEquals(listOf(AskAnswers.Answer("Which size?", "Small")), AskAnswers.parse(idle, sizeShape))
        val free = "The user responded: just pick the one that ships tonight"
        assertEquals(AskAnswers.Outcome(true, listOf("just pick the one that ships tonight")), AskAnswers.outcome(free, sizeShape, true))
        assertEquals(AskAnswers.Outcome(false, emptyList()), AskAnswers.outcome("The user did not answer the questions.", sizeShape, true))
    }

    @Test
    fun `a refusal is not answered, and its instruction to the model is not shown`() {
        val refusal = "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a " +
            "file edit, the new_string was NOT written to the file). STOP what you are doing and wait for the user " +
            "to tell you how to proceed."
        val o = AskAnswers.outcome(refusal, sizeShape, ok = false)!!
        assertEquals("Not answered", o.heading)
        assertEquals(emptyList(), o.lines)
        // A failure that says why keeps its first sentence.
        assertEquals(
            listOf("No answer after 60 seconds, so nothing was run."),
            AskAnswers.outcome("No answer after 60 seconds, so nothing was run.", sizeShape, ok = false)?.lines,
        )
        assertNull(AskAnswers.outcome("  ", sizeShape, ok = true), "no result yet: the card is still waiting")
    }

    /**
     * ⚠ ANCHORED. An orphan result is handed to an open question on this alone,
     * so a Read of a file QUOTING these sentences — this one — must not pass.
     */
    @Test
    fun `only Claude Code's own sentences are answers`() {
        assertTrue(AskAnswers.isAnswer("The user answered: \"Q\"=\"A\". $READ"))
        assertTrue(AskAnswers.isAnswer("Your questions have been answered: \"Q\"=\"A\". You can now continue with these answers in mind."))
        assertFalse(AskAnswers.isAnswer("    12\t        \"The user answered: \",\n    13\t        \"So far they answered: \","))
        assertFalse(AskAnswers.isAnswer("1\tval r = \"The user answered: \\\"Which size?\\\"\""))
        assertFalse(AskAnswers.isAnswer("File does not exist."))
        assertFalse(AskAnswers.isAnswer(null))
    }

    @Test
    fun `a line is cut at a word, never inside one`() {
        assertEquals("dragon fruit…", WordFit.cut("dragon fruit please", 14))
        assertEquals("dragon fruit please", WordFit.cut("dragon fruit please", 19), "it fits")
        assertEquals("dragon fruit please…", WordFit.cut("dragon fruit please, sliced thin", 21), "the comma goes with the cut")
        assertEquals("supercalifragilis…", WordFit.cut("supercalifragilisticexpialidocious", 18), "one long word: cut it anyway")
        // `fit` with any rule: the longest word prefix that the rule takes.
        assertEquals("a b…", WordFit.fit("a b c d") { it.length <= 4 })
        assertEquals("", WordFit.fit("") { false })
    }
}
