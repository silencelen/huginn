package com.silencelen.huginn.ui

import com.silencelen.huginn.data.DegradedAsk
import com.silencelen.huginn.data.PanePrompt

/**
 * Which face of a session is being looked at.
 *
 * The two shells count their tabs differently — the desktop has an enum on its
 * controller, the phone a bare index off a `TabRow` — so a rule about "which tab
 * is showing" cannot be written against either of them without being written
 * twice, which is how the clients drift. This is the shell-neutral form both
 * normalise into; the difference between them stays a parameter.
 */
enum class SessionFace {
    /** The Claude transcript. The question card's home. */
    CONVERSATION,

    /** The live tmux pane — where the dialog itself is, drawn by Claude Code. */
    SCREEN,

    /** The whole run: the map, the spend, the plan. Nothing here answers anything. */
    OVERVIEW;

    companion object {
        /**
         * The phone's tab index, which is what its `TabRow` deals in.
         *
         * An unrecognised index is the CONVERSATION — both the tab the phone
         * opens on and the safer of the two mistakes: a face we cannot name is
         * not the Screen, and suppressing the one card a reader has to act on
         * is worse than drawing it somewhere harmless.
         */
        fun ofTabIndex(index: Int): SessionFace = when (index) {
            1 -> SCREEN
            2 -> OVERVIEW
            else -> CONVERSATION
        }
    }
}

/**
 * Whether the question surface — the prompt card, and the degraded ask card with
 * it — belongs on screen right now.
 *
 * ONE SUPPRESSION, and it is not a preference. A question the card cannot answer
 * from where it stands offers "Answer on the Screen tab" and sends the reader to
 * the live pane — and the card was then drawn over the very terminal they had
 * just been sent to use. The steering worked and then covered its own
 * destination; the owner's report was that the popup "blocks the screen we now
 * have to use".
 *
 * So: ON THE SCREEN FACE THE TERMINAL IS THE PROMPT. The dialog is right there,
 * drawn by Claude Code itself, with every part of a multi-part question
 * steppable in a way a card of buttons cannot drive. A second copy of it below
 * is redundant at best, and at worst it is the thing in the way.
 *
 * Everywhere else the card stays exactly as it was, OVERVIEW included and
 * deliberately: nothing is covered there and nothing else on that face can
 * answer, so a card is the only way to act on a question without going to find
 * it first. Only the desktop has anything to draw there — the phone's overview
 * tab holds no card at all — but the rule is stated for the face rather than for
 * the client, so a phone that grows one inherits the right answer.
 */
object PromptGate {

    /**
     * [hasQuestion] is "the pane reports a question" — either a readable prompt
     * or the degraded ask, since both render the same kind of card and both are
     * answerable in the terminal.
     */
    fun visible(hasQuestion: Boolean, face: SessionFace): Boolean =
        hasQuestion && face != SessionFace.SCREEN

    /**
     * A pane-only prompt that is really a MULTI-QUESTION AskUserQuestion the scrape
     * happened to read enough of to draw buttons — and therefore must NOT be offered
     * as tap-to-answer.
     *
     * When the hook sidecar is present the daemon already serves such a dialog as a
     * degraded multi-part [DegradedAsk]; this covers the case the sidecar is absent
     * or unparseable (jq missing, a hook write failure, a rename mid-question), where
     * the daemon falls back to serving question 1 of N as a fully answerable prompt.
     * A single digit there OVER-answers: the digit selects+advances and the Enter
     * confirms the NEXT question's default too (live-verified 2026-08-11), so one tap
     * silently answers two questions and every tap then 409s as the pane skids past.
     *
     * The tell is the dialog's tab strip: two or more [PanePrompt.headers] on a
     * prompt the host did NOT fuse ([PanePrompt.source] != "hook"). A fused prompt is
     * safe (the daemon split it correctly); a single-question dialog carries at most
     * one header. So the client steers a pane-only >=2-header prompt to the Screen
     * tab instead of tapping it — the same contract the fused path already honours.
     */
    fun paneOnlyMultiQuestion(prompt: PanePrompt): Boolean =
        prompt.source != "hook" && prompt.headers.size >= 2
}

/**
 * Re-presents a pane-only multi-question [PanePrompt] as the read-only, steer-to-
 * Screen degraded card — the exact surface the fused multi-part path already uses,
 * so both cases look and behave identically to the reader and neither offers a
 * misfiring answer button. See [PromptGate.paneOnlyMultiQuestion].
 */
fun PanePrompt.asMultiPartSteer(): DegradedAsk = DegradedAsk(
    question = question,
    options = options,
    multiSelect = multiSelect,
    questionIndex = questionIndex,
    questionCount = questionCount ?: headers.size,
    fingerprint = fingerprint,
    multiPart = true,
)
