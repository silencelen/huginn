package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.AskData
import com.silencelen.huginn.data.AskQuestion
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.theme.HuginnTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠ THE ANSWERED QUESTION READ AS CLAUDE CODE'S SENTENCE TO THE MODEL
 * (2026-11-03): the desktop's card said `Answered · The user answered: "Which
 * size?"="extra wide", "Which shape?"="hexagon". Read the answers carefully —
 * they may request c` — the result's first 120 characters, cut mid-word — and
 * the phone, which read the answer in a later page than the question, printed
 * the same sentence as a one-line monospace row under a card still "waiting".
 * The results below are verbatim from a Claude Code 2.1.316 transcript.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class AskAnsweredRowTest {

    private val questions = listOf(
        AskQuestion(question = "Which size?", header = "Size", options = listOf("Small", "Medium", "Large")),
        AskQuestion(question = "Which shape?", header = "Shape", options = listOf("Circle", "Square")),
    )

    private val answered = "The user answered: \"Which size?\"=\"extra wide\", \"Which shape?\"=\"hexagon\". " +
        "Read the answers carefully — they may request clarification, changes, or that you not proceed — " +
        "and follow what they actually say."

    private fun ask(result: String? = null, qs: List<AskQuestion> = questions) = TranscriptEvent(
        seq = 2, kind = "tool", name = "AskUserQuestion", detail = qs.first().question, input = "",
        ask = AskData(qs), result = result, ok = if (result == null) null else true,
    )

    private fun ComposeUiTest.rows(events: List<TranscriptEvent>, width: Dp = 420.dp) {
        setContent {
            HuginnTheme(darkTheme = true, rootSurface = true) {
                // ⚠ A Box between the root Surface and the width: a Surface hands
                // its minimum size down, and `width()` cannot shrink below it —
                // without this every row was laid out 1000 px wide.
                Box {
                    Column(Modifier.width(width)) {
                        TranscriptGroups.group(events).forEach { TranscriptRowItem(it, onCopy = {}) }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.shown(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.texts(): List<String> =
        onAllNodesWithText("", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    @Test
    fun `an answered card shows each answer against its question, not the sentence`() = runComposeUiTest {
        rows(listOf(ask(answered)))
        assertEquals(1, shown("Size → extra wide"))
        assertEquals(1, shown("Shape → hexagon"))
        assertEquals(0, shown("The user answered", substring = true), "the model's sentence is not for the reader")
        assertEquals(0, shown("Read the answers", substring = true))
        assertEquals(0, shown("Waiting for your answer", substring = true))
    }

    @Test
    fun `an answer read after its question still lands on the card`() = runComposeUiTest {
        val orphan = TranscriptEvent(seq = 4, kind = "tool_result", result = answered, ok = true)
        rows(listOf(ask(), TranscriptEvent(seq = 3, kind = "thinking", text = "waiting"), orphan))
        assertEquals(1, shown("Size → extra wide"))
        assertEquals(1, shown("Shape → hexagon"))
        assertEquals(0, shown("The user answered", substring = true))
        assertEquals(0, shown("Waiting for your answer", substring = true), "the card is answered now")
    }

    @Test
    fun `an answer whose question scrolled off still reads as answers`() = runComposeUiTest {
        // A cold open that starts between the two records: no card to complete.
        rows(listOf(TranscriptEvent(seq = 1, kind = "tool_result", result = answered, ok = true)))
        assertEquals(1, shown("Which size? → extra wide"))
        assertEquals(1, shown("Which shape? → hexagon"))
        assertEquals(0, shown("The user answered", substring = true))
    }

    @Test
    fun `a long question is answered under its header, and the daemon's clipped copy still matches`() = runComposeUiTest {
        // Verbatim (the phone's test, 2026-11-03). The daemon clips a question
        // to 240 characters and an ellipsis in the card's data; the result
        // carries it whole.
        val long = "This is a deliberately long question used to test how the Huginn question card behaves when the " +
            "card has to scroll. It goes on for a few sentences so that it wraps across several lines on a phone " +
            "screen, then it asks: which of the following made-up options should the imaginary test pipeline take next?"
        val clipped = long.take(240) + "…"
        val result = "The user answered: \"$long\"=\"keep it\". Read the answers carefully — they may request " +
            "clarification, changes, or that you not proceed — and follow what they actually say."
        rows(listOf(ask(result, listOf(AskQuestion(question = clipped, header = "Long test")))))
        assertEquals(1, shown("Long test → keep it"))
        assertEquals(0, shown("The user answered", substring = true))
    }

    @Test
    fun `a line too long for the width is cut at a word, never inside one`() = runComposeUiTest {
        val fruit = listOf(AskQuestion(question = "Which fruit?", header = "Fruit"))
        val answer = "dragon fruit please, sliced thin and laid out on the blue plate by the window"
        val full = "Fruit → $answer"
        rows(
            listOf(ask("The user answered: \"Which fruit?\"=\"$answer\". Read the answers carefully.", fruit)),
            width = 180.dp,
        )
        val line = texts().single { it.startsWith("Fruit → ") }
        assertTrue(line.endsWith("…"), "cut, and says so: '$line'")
        val kept = line.removeSuffix("…")
        assertTrue(kept.length < full.length && full.startsWith(kept), "a prefix of the line: '$line'")
        val next = full[kept.length]
        assertTrue(next == ' ' || next == ',', "cut at a word boundary, not inside a word: '$line' (next '$next')")
    }

    @Test
    fun `a refused question says it was not answered`() = runComposeUiTest {
        val refusal = "The user doesn't want to proceed with this tool use. The tool use was rejected " +
            "(eg. if it was a file edit, the new_string was NOT written to the file). STOP what you are doing " +
            "and wait for the user to tell you how to proceed."
        rows(listOf(ask().copy(result = refusal, ok = false)))
        assertEquals(1, shown("Not answered"))
        assertEquals(0, shown("Answered", substring = false), "never 'Answered' for a refusal")
        assertEquals(0, shown("STOP what you are doing", substring = true), "the instruction is the model's")
    }
}
