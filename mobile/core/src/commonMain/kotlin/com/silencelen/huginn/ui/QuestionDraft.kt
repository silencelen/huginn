package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AnswerValue
import com.silencelen.huginn.data.SessionQuestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The working state of a [QuestionCard] — the stepper's collected answers and the
 * free text a reader has typed but not yet sent — lifted OUT of the card's own
 * `remember` so it survives the card leaving composition.
 *
 * WHY THIS EXISTS: the card kept all of this in `remember(id, …)`, which is
 * discarded the instant the card leaves the tree — and switching to the Screen
 * tab, or to another chat and back, does exactly that. A reader who had answered
 * one part of a two-part question, or typed half of an "Other…" / a "No, tell
 * Claude" message, lost it on the trip. Held in the shell's long-lived view model
 * and keyed by the request id, it outlives the trip. (Pure view toggles like
 * "read the plan" / "more fields" stay local to the card — losing a fold on
 * navigation is not losing an answer.)
 *
 * Immutable, like [AskStepper]: every edit returns the next value, so a test can
 * replay a reader's path and the hoist is a plain value swap.
 */
data class QuestionDraft(
    /** The multi-part walk: the answers collected so far, the step, the multi-select ticks. */
    val stepper: AskStepper,
    /** The ask's free text: a text/number question, or the "Other…" box. */
    val typed: String = "",
    /** The "Other…" box is open on the current ask step. */
    val otherOpen: Boolean = false,
    /** A permission's / plan's "tell Claude…" box is open. */
    val noOpen: Boolean = false,
    /** A permission's deny message, or a plan's feedback words. */
    val words: String = "",
) {
    /**
     * Answer the current ask question and move on, clearing the per-step scratch
     * (the free box resets for the next question, exactly as the old `remember`
     * keyed on the step did). The collected answers ride along in [stepper].
     */
    fun answerCurrent(value: AnswerValue): QuestionDraft =
        copy(stepper = stepper.answer(value), typed = "", otherOpen = false)

    /** Make the current multi-select's ticks the answer and move on. */
    fun confirmMany(): QuestionDraft =
        copy(stepper = stepper.confirmMany(), typed = "", otherOpen = false)

    /** Tick or untick one label of the current multi-select (a draft, not yet an answer). */
    fun toggle(label: String): QuestionDraft =
        copy(stepper = stepper.toggle(label))

    /** Step back a question, clearing the per-step scratch; the earlier answers stay. */
    fun back(): QuestionDraft =
        copy(stepper = stepper.back(), typed = "", otherOpen = false)

    companion object {
        fun of(q: SessionQuestion): QuestionDraft = QuestionDraft(stepper = AskStepper.of(q))
    }
}

/**
 * Every half-answered card, by request id, held ABOVE any one session's screen.
 *
 * The shell owns one for its whole life (the phone's view model, the desktop's
 * app store). The desktop first kept the drafts on its per-session controller,
 * which the session view builds and drops with the session it shows: a draft
 * then survived a Screen-tab flip but not a switch to another session and back.
 * Request ids are unique across sessions, so one map serves every session.
 */
class QuestionDraftBook {
    private val _drafts = MutableStateFlow<Map<String, QuestionDraft>>(emptyMap())
    val drafts: StateFlow<Map<String, QuestionDraft>> = _drafts.asStateFlow()

    /** The card reports its evolving draft here; held until the question is answered. */
    fun set(id: String, draft: QuestionDraft) {
        if (id.isNotEmpty()) _drafts.value = _drafts.value + (id to draft)
    }

    /** Answered, or the request is gone or changed: the draft has nothing left to answer. */
    fun clear(id: String) {
        if (_drafts.value.containsKey(id)) _drafts.value = _drafts.value - id
    }
}
