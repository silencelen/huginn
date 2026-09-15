package com.silencelen.huginn

import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.AgentStream
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.errorTextFor
import com.silencelen.huginn.ui.headroomPill
import com.silencelen.huginn.ui.mergeTranscriptPage
import com.silencelen.huginn.ui.statusHeadroomOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's own headroom rules — the ones that cannot live in `:core` because
 * they are about this client's state, and cannot live in the view model's tests
 * because there are none: `HuginnViewModel` is an `AndroidViewModel` and this
 * host has no device and no `/dev/kvm`, so nothing here may touch an Application.
 *
 * Everything below is therefore reached the way `reattachPlan` already is — as a
 * pure function or a plain class the view model delegates to. That is not a
 * workaround; it is what makes these three rules testable at all, and each of
 * them is a bug that has a name.
 */
class HuginnViewModelTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    // ------------------------------------------------------------ the pill

    /**
     * NULL IS THE IMPORTANT ANSWER.
     *
     * The view model holds `headroom` as a nullable and the top bar draws the
     * pill from it unconditionally, so "no answer yet" and "this daemon has no
     * headroom subsystem" both have to reach the bar as *nothing at all*. A pill
     * drawn at 0 % in either case is a claim that somebody looked and found
     * plenty, which is the one thing that surface must never say by accident.
     */
    @Test
    fun `headroom decodes into a pill, and no answer draws none`() {
        val h = json.decodeFromString<Headroom>(fixture("headroom.json"))
        val summary = statusHeadroomOf(h)
        assertNotNull("the full answer must fold into the pill's shape", summary)
        assertEquals("red", summary!!.mode)
        assertEquals(92.0, summary.worstPercent!!, 0.001)
        assertEquals("Current week (Fable)", summary.worstLabel)
        // Armed sentinels only: STOP is present-as-null and must not be counted,
        // or the pill warns about a sentinel nobody armed.
        assertEquals(listOf("STOP-FABLE"), summary.sentinels)
        assertEquals("one spawn is being held by the gate", 1, summary.paused)

        // Before the reset in the fixture, so the countdown is a real one.
        val now = 1_789_460_000_000L
        val face = headroomPill(summary, now)
        assertNotNull("a red window must draw a pill", face)
        assertTrue("the pill must name the window", face!!.text.contains("Fable"))
        assertTrue("the pill must carry the number", face.text.contains("92"))

        // The two shapes of "nothing to say", both of which reach the bar.
        assertNull("an older daemon has no headroom at all", statusHeadroomOf(null))
        assertNull("and must therefore draw no pill", headroomPill(null, now))
        // Answered, but nothing read yet: still no pill.
        assertNull(statusHeadroomOf(Headroom(mode = "ok", worst = null)))
    }

    // -------------------------------------------------- the second cursor pair

    private fun page(
        events: List<TranscriptEvent>,
        nextOffset: Long,
        windowStart: Long,
        sessionId: String? = "s1",
    ) = TranscriptPage(
        events = events,
        nextOffset = nextOffset,
        windowStart = windowStart,
        claudeSessionId = sessionId,
    )

    private fun ev(seq: Int, text: String) =
        TranscriptEvent(seq = seq, kind = "assistant", text = text)

    /**
     * There is no such thing as one cursor for two files.
     *
     * The main pair are byte positions in the session's own `.jsonl`; the agent
     * pair are byte positions in one agent's. Carrying the first agent's offset
     * into the second would tail it from a number that is meaningless there and,
     * on a long session, past the end of the file — and carrying its PAGE over
     * would merge two agents' work into one conversation with no mark to say
     * where one ended. Meanwhile the main page must keep merging underneath:
     * reading an agent is looking more closely at a session that is still going,
     * not leaving it.
     */
    @Test
    fun `switching streams resets the agent cursor pair while the main page keeps merging`() {
        val stream = AgentStream()

        assertTrue("picking an agent is a move", stream.select("agent-aaa"))
        stream.land(page(listOf(ev(1, "first")), nextOffset = 900, windowStart = 100))
        assertEquals(900L, stream.offset)
        assertEquals(100L, stream.historyStart)
        assertEquals(1, stream.page!!.events.size)

        // A tail read below the first page must NOT move the history handle.
        stream.land(page(listOf(ev(2, "second")), nextOffset = 1400, windowStart = 900))
        assertEquals(1400L, stream.offset)
        assertEquals("the history handle belongs to the FIRST page", 100L, stream.historyStart)
        assertEquals(2, stream.page!!.events.size)

        // The main page, ticking underneath the whole time.
        var main = mergeTranscriptPage(null, page(listOf(ev(1, "main one")), 500, 0))
        assertTrue(stream.select("agent-bbb"))
        main = mergeTranscriptPage(main, page(listOf(ev(2, "main two")), 700, 500))

        assertEquals("agent-bbb", stream.selected)
        assertNull("the previous agent's tail offset is meaningless here", stream.offset)
        assertNull("and so is its history handle", stream.historyStart)
        assertNull("its page must be dropped, never merged into the next", stream.page)
        assertEquals("the main page kept merging across the switch", 2, main!!.events.size)

        // Back to Main drops the agent page rather than leaving it behind.
        assertTrue(stream.select(null))
        assertNull(stream.selected)
        assertNull(stream.page)

        // A 404 is the one expected failure and it is about the HOST, not the
        // agent: a daemon older than 3.0.0 has no such route at all.
        stream.select("agent-ccc")
        stream.fail(404, "not found")
        assertEquals(HuginnViewModel.STREAMS_UNSUPPORTED, stream.note)
        stream.select("agent-ddd")
        assertEquals(
            "a route the host does not have outlives any one selection",
            HuginnViewModel.STREAMS_UNSUPPORTED,
            stream.note,
        )
    }

    // ------------------------------------------------------------ the 409

    /**
     * The daemon's own sentence, verbatim.
     *
     * `POST /v1/accounts/:slug/activate` answers 409 with the entire reason —
     * "<email> cannot be switched to: its login expired on <date> — sign in
     * again" — which is the whole of what the reader pressed Use to find out. The
     * desktop replaced that with "could not switch" and threw it away; this
     * client must never learn to. Only 401 is substituted, because
     * "Unauthorized" tells nobody what to do about it.
     */
    @Test
    fun `the daemon's 409 reason reaches the toast verbatim`() {
        val reason = "owner@example.com cannot be switched to: its login expired on " +
            "2026-08-30 — sign in again"
        assertEquals(reason, errorTextFor(HuginnClient.HuginnException(409, reason)))

        // Every other code too — a 400 from the headroom settings PATCH names the
        // rule it refused, and the form shows that rather than "invalid".
        assertEquals(
            "headsUpPct must be below ladderPct",
            errorTextFor(HuginnClient.HuginnException(400, "headsUpPct must be below ladderPct")),
        )

        // The one substitution, and it is a real improvement on the wire text.
        assertEquals(
            "Rejected by huginn: check the token in Settings",
            errorTextFor(HuginnClient.HuginnException(401, "Unauthorized")),
        )
    }
}
