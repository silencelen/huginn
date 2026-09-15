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
 * WHICH SURFACE is asking — a tmux session, or a headless chat.
 *
 * The difference is not cosmetic and it is not a preference: a session HAS a live
 * pane, and every prompt type Claude Code can put up is answerable there. A chat
 * has no pane at all, so there is nowhere to send its reader and the card has to
 * be answerable where it stands. One enum rather than two booleans at each call
 * site, because "is this a chat" and "may this card be answered here" are the same
 * question asked twice and they drifted apart once already.
 */
enum class PromptSurface {
    /** A tmux session: three faces, and a terminal that can answer anything. */
    SESSION,

    /** A headless run: one face, no pane, no `/answer` route. */
    CHAT,
}

/**
 * How a pending question is presented on the face being looked at.
 *
 * THE OWNER'S DECISION 23, verbatim in its consequences: a session's Conversation
 * and Overview stop rendering answerable cards and show a one-line bar that
 * deep-links to the Screen tab, because the cards were costing 290-330dp for a
 * five-option question (measured; `CardShell` is uncapped `fillMaxWidth` and every
 * `AnswerButton` was full width) and only some prompt types were handled from
 * them at all. The pane handles every type, so the steer is the honest surface.
 *
 * A CHAT KEEPS ITS CARD, compact. Not an exception to the rule — the opposite end
 * of the same one: the rule is "answer it where it can actually be answered", and
 * for a chat that is in place.
 */
enum class PromptPlacement {
    /** One line: "Claude is asking a question · Answer on Screen →". */
    LINK_TO_SCREEN,

    /** The card itself, capped and wrapped. Answering happens here. */
    INLINE_COMPACT,

    /** Nothing. The dialog is already on screen, drawn by Claude Code. */
    NONE;

    companion object {
        fun of(face: SessionFace, surface: PromptSurface): PromptPlacement = when {
            // THE SCREEN FACE FIRST, and before the surface is even consulted. The
            // terminal below IS the dialog; anything drawn over it is the bug this
            // gate was written for (see [PromptGate]).
            face == SessionFace.SCREEN -> NONE
            surface == PromptSurface.CHAT -> INLINE_COMPACT
            else -> LINK_TO_SCREEN
        }
    }
}

/**
 * Whether the question surface — the link bar on a session, the compact card in a
 * chat — belongs on screen right now.
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
 * Everywhere else there IS a surface, and since the owner's decision 23 it is no
 * longer the card: a session's Conversation and Overview show the one-line link
 * bar instead, which says the same fact in 40dp rather than 330 and hands the
 * reader to the pane that can answer every prompt type rather than to buttons
 * that handled some of them. [PromptPlacement] is where that choice is written
 * down; this gate stays the answer to the narrower question of whether ANY
 * question surface belongs on a face.
 *
 * The rule is stated for the face rather than for the client, so a shell that
 * grows a new one inherits the right answer.
 */
object PromptGate {

    /**
     * [hasQuestion] is "the pane reports a question" — either a readable prompt
     * or the degraded ask, since both render the same kind of card and both are
     * answerable in the terminal.
     */
    fun visible(hasQuestion: Boolean, face: SessionFace): Boolean =
        hasQuestion && PromptPlacement.of(face, PromptSurface.SESSION) != PromptPlacement.NONE

    /**
     * The dot on the session strip's Screen tab.
     *
     * The other half of the link bar, and the reason it can afford to be one line:
     * a bar that scrolls out of view takes the only sign that anything is waiting
     * with it, and the tab strip never scrolls. Shown on exactly the faces that
     * steer — [PromptPlacement.LINK_TO_SCREEN] — because on the Screen tab the
     * reader is already there and a dot pointing at the tab they are on is noise.
     */
    fun screenTabDot(hasQuestion: Boolean, face: SessionFace): Boolean =
        hasQuestion && PromptPlacement.of(face, PromptSurface.SESSION) == PromptPlacement.LINK_TO_SCREEN

    /** How much of a question a one-line bar can carry beside its own words. */
    const val GIST_MAX: Int = 60

    /**
     * The question, shortened to fit beside the bar's label.
     *
     * Whitespace collapsed first — a TUI-scraped question arrives with the
     * dialog's own line breaks in it, and those turn a one-line bar into three.
     * Cut at a word boundary when there is one in the last third, so the tail
     * reads as a trimmed phrase rather than a severed word.
     *
     * Null when there is nothing to show: no question, or a question that is only
     * whitespace (the degraded ask, whose text the scrape could not read). The bar
     * then shows its label alone, which is the whole fact anyway.
     */
    fun gist(question: String?, max: Int = GIST_MAX): String? {
        val flat = question?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (flat.isEmpty() || max <= 0) return null
        if (flat.length <= max) return flat
        val cut = flat.take(max)
        val space = cut.lastIndexOf(' ')
        val body = if (space > max * 2 / 3) cut.take(space) else cut
        return body.trimEnd().trimEnd(',', ';', ':', '.', '-') + "…"
    }

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
