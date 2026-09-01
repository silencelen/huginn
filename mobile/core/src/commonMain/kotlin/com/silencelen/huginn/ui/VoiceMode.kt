package com.silencelen.huginn.ui

/**
 * The pure heart of voice mode: a conversation loop as a state machine.
 *
 * Voice mode is LISTEN → THINK → SPEAK → LISTEN, hands-free, until told to stop.
 * Everything device-fragile (the recognizer, the TTS engine) lives behind
 * effects; this reducer only decides what happens next, which makes the loop —
 * the part that would otherwise be a tangle of callbacks racing each other —
 * testable on the JVM. The rule that matters most: a TAP always means "you,
 * stop" — stop speaking, stop listening, hand control back — because an
 * assistant that cannot be interrupted by touch is an assistant shouting over
 * you.
 */
object VoiceLoop {

    sealed interface State {
        /** Mic off, waiting for the user. */
        data object Idle : State

        /** Recognizer running; [partial] is the live hypothesis. */
        data class Listening(val partial: String = "") : State

        /** The heard text has been sent; huginn is answering. */
        data object Thinking : State

        /** TTS is reading the answer aloud. */
        data object Speaking : State
    }

    sealed interface Event {
        /** The mic button (or the sheet body) was tapped. */
        data object Tap : Event

        /** The recognizer produced a final utterance. */
        data class Heard(val text: String) : Event

        /** The recognizer updated its live hypothesis. */
        data class Partial(val text: String) : Event

        /** The turn finished and this is the answer's text. */
        data class AnswerReady(val text: String) : Event

        /** TTS finished reading. */
        data object SpokenAll : Event

        /** The recognizer gave up (silence, no match, engine error). */
        data class MicFailed(val transient: Boolean) : Event
    }

    sealed interface Effect {
        data object StartListening : Effect
        data object StopListening : Effect
        data class SendMessage(val text: String) : Effect
        data class Speak(val text: String) : Effect
        data object StopSpeaking : Effect
        data object Close : Effect
    }

    data class Step(val state: State, val effects: List<Effect> = emptyList())

    fun reduce(state: State, event: Event): Step = when (event) {
        Event.Tap -> when (state) {
            // Tapping while it speaks means "enough" — cut in and listen.
            State.Speaking -> Step(State.Listening(), listOf(Effect.StopSpeaking, Effect.StartListening))
            // Tapping while listening cancels the utterance, back to rest.
            is State.Listening -> Step(State.Idle, listOf(Effect.StopListening))
            // Tapping at rest starts the loop.
            State.Idle -> Step(State.Listening(), listOf(Effect.StartListening))
            // Tapping while it thinks changes nothing: the message is already
            // sent, and pretending a tap could unsend it would be a lie.
            State.Thinking -> Step(State.Thinking)
        }

        is Event.Partial -> when (state) {
            is State.Listening -> Step(State.Listening(event.text))
            else -> Step(state)
        }

        is Event.Heard -> when (state) {
            is State.Listening ->
                if (event.text.isBlank()) Step(State.Listening(), listOf(Effect.StartListening))
                else Step(State.Thinking, listOf(Effect.SendMessage(event.text)))
            else -> Step(state)
        }

        is Event.AnswerReady -> when (state) {
            State.Thinking ->
                if (event.text.isBlank()) Step(State.Listening(), listOf(Effect.StartListening))
                else Step(State.Speaking, listOf(Effect.Speak(event.text)))
            else -> Step(state)
        }

        Event.SpokenAll -> when (state) {
            // The loop: an answer read out hands the floor back to the user.
            State.Speaking -> Step(State.Listening(), listOf(Effect.StartListening))
            else -> Step(state)
        }

        is Event.MicFailed -> when (state) {
            is State.Listening ->
                // Silence timeouts are ordinary (the user is reading, thinking,
                // drinking coffee): retry. A non-transient failure stops the
                // loop rather than retrying into the same wall forever.
                if (event.transient) Step(State.Listening(), listOf(Effect.StartListening))
                else Step(State.Idle, listOf(Effect.StopListening))
            else -> Step(state)
        }
    }

    /**
     * The baseline to record the moment a turn is SENT: the chat's answer as it
     * stands right then, which belongs to a PRIOR turn.
     *
     * A chat opened on top of history already carries its last reply in the
     * ambient "latest answer" the instant a new turn begins — and the turn
     * boundary ([answerToSpeak]) fires in the window after the loop enters
     * Thinking but before the send has flipped `sending` true. Without a baseline
     * that window read the prior reply as this turn's answer and spoke it aloud,
     * one behind, every first turn in any chat with history. Recording the prior
     * answer here is what makes the loop WAIT for a genuinely new one.
     */
    fun baselineForTurn(current: String?): String? = current

    /**
     * The answer to speak at the turn boundary, or null to keep waiting.
     *
     * [current] is the chat's latest answer text — ambient, so it already holds
     * the PRIOR turn's reply the moment a new turn is sent. [alreadySpoken] is the
     * answer the loop has accounted for: the prior reply captured by
     * [baselineForTurn] when the turn was sent, then each answer once it has been
     * read out. The loop speaks only a non-blank [current] that differs from it —
     * so the prior reply is never read before the user has spoken, and no reply is
     * read twice.
     */
    fun answerToSpeak(
        state: State,
        current: String?,
        alreadySpoken: String?,
        sending: Boolean,
        streaming: String?,
    ): String? =
        if (state == State.Thinking && !sending && streaming == null &&
            !current.isNullOrBlank() && current != alreadySpoken
        ) {
            current
        } else {
            null
        }
}

/**
 * Turns an assistant answer into something worth hearing.
 *
 * Markdown is for eyes. Read aloud verbatim, a fenced code block is a minute of
 * "backtick backtick backtick const equals..." — so code becomes a short notice,
 * link targets vanish in favour of their text, and heading/emphasis marks drop.
 * Pure, because the difference between a usable voice mode and an unusable one
 * is mostly this function, and it must be testable without ears.
 */
object Speakable {

    private val FENCE = Regex("```[a-zA-Z0-9_-]*\\n[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]{1,80})`")
    private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
    private val HEADING = Regex("(?m)^#{1,6}\\s+")
    private val EMPHASIS = Regex("(\\*\\*|__|\\*|_)(?=\\S)([\\s\\S]*?)(?<=\\S)\\1")
    private val TABLE_ROW = Regex("(?m)^\\|.*\\|\\s*$")
    private val BULLET = Regex("(?m)^\\s*[-*+]\\s+")

    /** TTS engines choke on very long utterances; keep it to a spoken minute or so. */
    const val MAX_CHARS = 1400

    fun render(markdown: String): String {
        var s = markdown
        s = FENCE.replace(s) { "\nCode omitted.\n" }
        s = TABLE_ROW.replace(s, "")
        s = LINK.replace(s) { it.groupValues[1] }
        s = INLINE_CODE.replace(s) { it.groupValues[1] }
        s = HEADING.replace(s, "")
        s = EMPHASIS.replace(s) { it.groupValues[2] }
        s = BULLET.replace(s, "")
        s = s.replace(Regex("\\n{3,}"), "\n\n").trim()
        if (s.length > MAX_CHARS) {
            // Cut at a sentence boundary where one offers itself.
            val cut = s.lastIndexOf('.', MAX_CHARS).takeIf { it > MAX_CHARS / 2 } ?: MAX_CHARS
            s = s.substring(0, cut + 1) + " The rest is on screen."
        }
        return s
    }
}
