package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AnswerValue
import com.silencelen.huginn.data.AskQuestionSpec
import com.silencelen.huginn.data.QuestionAnswers
import com.silencelen.huginn.data.SessionQuestion
import kotlinx.serialization.json.JsonObject

/**
 * The compact card's walk through an AskUserQuestion with several questions:
 * one question at a time ("1 of 2"), answers collected as the reader goes, the
 * whole set submitted together — because the hook answers the tool call once,
 * with every question in it (a half answer is refused by the daemon).
 *
 * Immutable: every move returns the next state, so the card's `remember` holds
 * exactly one value and a test can walk the same path the thumb does. The rules
 * that matter are the ones a screenshot would not show: a multi-select is a set
 * that may be empty, going back keeps what was chosen, and [body] exists only
 * once nothing is missing.
 */
data class AskStepper(
    val specs: List<AskQuestionSpec>,
    val index: Int = 0,
    val answers: Map<String, AnswerValue> = emptyMap(),
    /** A multi-select's ticks before its button is pressed: a draft, not an answer. */
    val drafts: Map<String, List<String>> = emptyMap(),
) {
    val count: Int get() = specs.size
    val current: AskQuestionSpec? get() = specs.getOrNull(index)
    val isLast: Boolean get() = index >= specs.size - 1
    val isFirst: Boolean get() = index == 0

    /** "1 of 2", or null for a single question, which needs no counter. */
    val position: String? get() = if (count > 1) "${index + 1} of $count" else null

    /** What the current question already holds, for drawing its state when stepping back. */
    val currentAnswer: AnswerValue? get() = current?.let { answers[it.question] }

    /** Answer the current question and move on; on the last one the state stays put, complete. */
    fun answer(value: AnswerValue): AskStepper {
        val q = current ?: return this
        val next = copy(answers = answers + (q.question to value))
        return if (isLast) next else next.copy(index = index + 1)
    }

    /** The current multi-select's ticks: the draft, else what was confirmed before stepping back. */
    val currentChosen: List<String>
        get() = current?.let { q -> drafts[q.question] ?: (answers[q.question] as? AnswerValue.Many)?.labels } ?: emptyList()

    /** Tick or untick one label of the current multi-select. A draft: [confirmMany] makes it the answer. */
    fun toggle(label: String): AskStepper {
        val q = current ?: return this
        val have = currentChosen
        val now = if (label in have) have - label else have + label
        return copy(drafts = drafts + (q.question to now))
    }

    /** Make the current multi-select's ticks (possibly none) its answer and move on. */
    fun confirmMany(): AskStepper = answer(AnswerValue.Many(currentChosen))

    fun back(): AskStepper = if (isFirst) this else copy(index = index - 1)

    /** Every question answered: the body may be sent. */
    val complete: Boolean get() = specs.isNotEmpty() && specs.all { answers.containsKey(it.question) }

    /** The wire body for [question], or null while something is missing. */
    fun body(question: SessionQuestion, response: String? = null): JsonObject? =
        if (complete) QuestionAnswers.askBody(question, answers, response) else null

    companion object {
        fun of(question: SessionQuestion): AskStepper = AskStepper(question.askQuestions())
    }
}
