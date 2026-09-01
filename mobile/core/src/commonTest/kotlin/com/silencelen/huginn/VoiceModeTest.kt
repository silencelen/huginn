package com.silencelen.huginn

import com.silencelen.huginn.ui.Speakable
import com.silencelen.huginn.ui.VoiceLoop
import com.silencelen.huginn.ui.VoiceLoop.Effect
import com.silencelen.huginn.ui.VoiceLoop.Event
import com.silencelen.huginn.ui.VoiceLoop.State
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The conversation loop, exercised without a microphone or a speaker — which is
 * the point of it being a reducer: the engines are per-OEM quicksand, but the
 * decisions about what happens next are the same everywhere and live here.
 */
class VoiceLoopTest {

    @Test
    fun `the happy loop - listen, send, speak, listen again`() {
        var s = VoiceLoop.reduce(State.Idle, Event.Tap)
        assertEquals(State.Listening(), s.state)
        assertEquals(listOf(Effect.StartListening), s.effects)

        s = VoiceLoop.reduce(s.state, Event.Heard("check the build status"))
        assertEquals(State.Thinking, s.state)
        assertEquals(listOf(Effect.SendMessage("check the build status")), s.effects)

        s = VoiceLoop.reduce(s.state, Event.AnswerReady("The build is green."))
        assertEquals(State.Speaking, s.state)
        assertEquals(listOf(Effect.Speak("The build is green.")), s.effects)

        s = VoiceLoop.reduce(s.state, Event.SpokenAll)
        assertEquals(State.Listening(), s.state)
        assertEquals(listOf(Effect.StartListening), s.effects)
    }

    @Test
    fun `a tap while speaking interrupts and listens`() {
        val s = VoiceLoop.reduce(State.Speaking, Event.Tap)
        assertEquals(State.Listening(), s.state)
        assertEquals(listOf(Effect.StopSpeaking, Effect.StartListening), s.effects)
    }

    @Test
    fun `a tap while listening cancels to rest`() {
        val s = VoiceLoop.reduce(State.Listening("half a sent"), Event.Tap)
        assertEquals(State.Idle, s.state)
        assertEquals(listOf(Effect.StopListening), s.effects)
    }

    @Test
    fun `a tap while thinking changes nothing - the message is already sent`() {
        val s = VoiceLoop.reduce(State.Thinking, Event.Tap)
        assertEquals(State.Thinking, s.state)
        assertTrue(s.effects.isEmpty())
    }

    @Test
    fun `hearing silence retries rather than sending an empty message`() {
        val s = VoiceLoop.reduce(State.Listening(), Event.Heard("   "))
        assertEquals(State.Listening(), s.state)
        assertEquals(listOf(Effect.StartListening), s.effects)
        assertFalse(s.effects.any { it is Effect.SendMessage })
    }

    @Test
    fun `partials update the hypothesis without effects`() {
        val s = VoiceLoop.reduce(State.Listening("che"), Event.Partial("check the"))
        assertEquals(State.Listening("check the"), s.state)
        assertTrue(s.effects.isEmpty())
    }

    @Test
    fun `a silence timeout keeps listening, a hard mic failure stops`() {
        val retry = VoiceLoop.reduce(State.Listening(), Event.MicFailed(transient = true))
        assertEquals(State.Listening(), retry.state)
        assertEquals(listOf(Effect.StartListening), retry.effects)

        val stop = VoiceLoop.reduce(State.Listening(), Event.MicFailed(transient = false))
        assertEquals(State.Idle, stop.state)
        assertEquals(listOf(Effect.StopListening), stop.effects)
    }

    @Test
    fun `an empty answer skips speaking and goes back to listening`() {
        val s = VoiceLoop.reduce(State.Thinking, Event.AnswerReady(""))
        assertEquals(State.Listening(), s.state)
        assertFalse(s.effects.any { it is Effect.Speak })
    }

    @Test
    fun `stale events in the wrong state are ignored`() {
        // A late recognizer result after the user already cancelled must not send.
        assertTrue(VoiceLoop.reduce(State.Idle, Event.Heard("late words")).effects.isEmpty())
        // A late TTS completion after an interrupt must not restart listening twice.
        assertTrue(VoiceLoop.reduce(State.Listening(), Event.SpokenAll).effects.isEmpty())
    }

    @Test
    fun `speaks this turn's answer, never the prior one, in a chat with history`() {
        // The reported bug: open a chat that already has replies, say the first
        // thing — and voice mode reads the PREVIOUS answer aloud, one behind. The
        // window is state=Thinking, sending not yet flipped, no streaming text, and
        // the ambient "latest answer" still holding the prior reply.
        val prior = "The build passed."
        // Recorded when the turn is SENT (the fix). The bug recorded nothing, so
        // the baseline was null and the gate below fired on the prior reply.
        val baseline = VoiceLoop.baselineForTurn(prior)

        // In that window the prior reply must NOT be spoken.
        assertEquals(
            null,
            VoiceLoop.answerToSpeak(
                State.Thinking, current = prior, alreadySpoken = baseline,
                sending = false, streaming = null,
            ),
            "the prior turn's answer must not be spoken before the new one arrives",
        )

        // This turn's real answer arrives → speak THAT, exactly once.
        val fresh = "And now the tests pass too."
        assertEquals(
            fresh,
            VoiceLoop.answerToSpeak(
                State.Thinking, current = fresh, alreadySpoken = baseline,
                sending = false, streaming = null,
            ),
        )
        assertEquals(
            null,
            VoiceLoop.answerToSpeak(
                State.Thinking, current = fresh, alreadySpoken = fresh,
                sending = false, streaming = null,
            ),
            "once spoken, the same answer is not read a second time",
        )
    }

    @Test
    fun `nothing is spoken while the turn is still in flight`() {
        // Sending, or still streaming, or not yet in Thinking: the answer is not
        // final, so the loop stays quiet.
        assertEquals(
            null,
            VoiceLoop.answerToSpeak(State.Thinking, "partial", null, sending = true, streaming = null),
        )
        assertEquals(
            null,
            VoiceLoop.answerToSpeak(State.Thinking, "partial", null, sending = false, streaming = "still going"),
        )
        assertEquals(
            null,
            VoiceLoop.answerToSpeak(State.Listening(), "done", null, sending = false, streaming = null),
        )
    }
}

/** Markdown is for eyes; this is what the ears get. */
class SpeakableTest {

    @Test
    fun `code blocks become a notice, not a recitation`() {
        val out = Speakable.render("Run this:\n```bash\nnpm test && npm run build\n```\nThen check.")
        assertFalse(out.contains("npm"))
        assertTrue(out.contains("Code omitted."))
        assertTrue(out.contains("Then check."))
    }

    @Test
    fun `links speak their text, not their targets`() {
        assertEquals(
            "See the build page for details.",
            Speakable.render("See [the build page](https://ci.example.com/run/9) for details."),
        )
    }

    @Test
    fun `inline code keeps its content without the ticks`() {
        assertEquals("Run npm test first.", Speakable.render("Run `npm test` first."))
    }

    @Test
    fun `headings and emphasis lose their marks`() {
        assertEquals("Status\nAll good, really.", Speakable.render("## Status\n**All good**, _really_."))
    }

    @Test
    fun `tables are dropped entirely`() {
        val out = Speakable.render("Results:\n| a | b |\n|---|---|\n| 1 | 2 |\nDone.")
        assertFalse(out.contains("|"))
        assertTrue(out.contains("Done."))
    }

    @Test
    fun `bullets read as sentences`() {
        assertEquals("first thing\nsecond thing", Speakable.render("- first thing\n- second thing"))
    }

    @Test
    fun `a very long answer is cut at a sentence with a pointer to the screen`() {
        val long = (1..200).joinToString(" ") { "Sentence number $it ends here." }
        val out = Speakable.render(long)
        assertTrue(out.length <= Speakable.MAX_CHARS + 40)
        assertTrue(out.endsWith("The rest is on screen."))
        assertTrue(out.substringBefore(" The rest").endsWith("."))
    }

    @Test
    fun `plain text passes through untouched`() {
        assertEquals("Deployed, and the tests pass.", Speakable.render("Deployed, and the tests pass."))
    }
}
