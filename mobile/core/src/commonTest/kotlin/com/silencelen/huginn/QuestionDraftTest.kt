package com.silencelen.huginn

import com.silencelen.huginn.data.AnswerValue
import com.silencelen.huginn.data.SessionQuestion
import com.silencelen.huginn.data.SessionQuestions
import com.silencelen.huginn.ui.QuestionDraft
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hoisted [QuestionDraft] — the card's working state held in the view model
 * so a half answer survives the card leaving the tree (owner, 2026-10-26). The
 * card used to keep this in `remember`, which a tab/chat switch discards; the
 * value here is what the view model carries across that gap, so these pin that a
 * two-part walk, a typed "Other…" and a stepped-back answer all ride along in ONE
 * immutable value.
 */
class QuestionDraftTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun ask(): SessionQuestion = json.decodeFromString<SessionQuestions>(
        """
        {"questions":[{"id":"q-1","kind":"ask","toolName":"AskUserQuestion",
          "input":{"questions":[
            {"question":"Which colour?","header":"Colour","multiSelect":false,
             "options":[{"label":"Red"},{"label":"Blue"}]},
            {"question":"Which fruits?","header":"Fruit","multiSelect":true,
             "options":[{"label":"Apple"},{"label":"Pear, ripe"},{"label":"Plum"}]}]},
          "fingerprint":"abc","since":1,"bound":true}]}
        """.trimIndent(),
    ).questions[0]

    @Test
    fun `a fresh draft is a walk at the start with nothing typed`() {
        val d = QuestionDraft.of(ask())
        assertEquals(0, d.stepper.index)
        assertEquals(2, d.stepper.count)
        assertEquals("", d.typed)
        assertEquals("", d.words)
        assertFalse(d.otherOpen)
        assertFalse(d.noOpen)
    }

    @Test
    fun `a half-answered two-part walk rides along in one value`() {
        // Answer Q1, then start typing an "Other…" for Q2 — the exact half the
        // card's old `remember` dropped on a tab or chat switch.
        var d = QuestionDraft.of(ask())
        d = d.answerCurrent(AnswerValue.One("Blue"))           // Q1 answered, advance
        assertEquals(1, d.stepper.index)
        d = d.copy(otherOpen = true, typed = "kumquat")        // mid-typing on Q2
        // This is the value the view model holds across a navigation away and back:
        assertEquals("Blue", (d.stepper.answers["Which colour?"] as AnswerValue.One).label)
        assertEquals("kumquat", d.typed)
        assertTrue(d.otherOpen)
    }

    @Test
    fun `advancing clears the per-step scratch but keeps earlier answers`() {
        var d = QuestionDraft.of(ask()).copy(typed = "x", otherOpen = true)
        d = d.answerCurrent(AnswerValue.One("Red"))
        assertEquals("", d.typed, "the free box resets for the next question")
        assertFalse(d.otherOpen)
        assertEquals(AnswerValue.One("Red"), d.stepper.answers["Which colour?"])
    }

    @Test
    fun `stepping back keeps the collected answers and clears scratch`() {
        var d = QuestionDraft.of(ask()).answerCurrent(AnswerValue.One("Red"))
        d = d.copy(typed = "half")
        d = d.back()
        assertEquals(0, d.stepper.index)
        assertEquals("", d.typed)
        assertEquals(AnswerValue.One("Red"), d.stepper.answers["Which colour?"], "the earlier answer survives a back")
    }

    @Test
    fun `a multi-select draft collects ticks and completes the whole walk`() {
        var d = QuestionDraft.of(ask())
        d = d.answerCurrent(AnswerValue.One("Blue"))           // Q1
        d = d.toggle("Apple").toggle("Plum")                   // Q2 ticks (a draft, not an answer)
        assertFalse(d.stepper.complete, "ticks are not an answer")
        assertEquals(listOf("Apple", "Plum"), d.stepper.currentChosen)
        d = d.confirmMany()
        assertTrue(d.stepper.complete)
        assertNotNull(d.stepper.body(ask()), "a completed walk builds a body")
    }
}
