package com.silencelen.huginn

import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.ui.AgentStream
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.StreamPicker
import com.silencelen.huginn.ui.fetchStreamAgents
import com.silencelen.huginn.ui.errorTextFor
import com.silencelen.huginn.ui.headroomPill
import com.silencelen.huginn.ui.mergeTranscriptPage
import com.silencelen.huginn.ui.statusHeadroomOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
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
    // ----------------------------------------------------------- the strip

    private companion object {
        const val STRIP_NOW = 1_789_460_000L
        const val BASE = "http://appd.test"
    }

    private fun streamAgent(
        id: String,
        active: Boolean = true,
        updatedAt: Long = STRIP_NOW - 20,
        status: String? = null,
        workflowId: String? = null,
    ) = AgentRun(id = id, task = "t $id", active = active, updatedAt = updatedAt, status = status, workflowId = workflowId)

    /**
     * The ARGUMENT is the behaviour.
     *
     * The strip's chips are the live agents only, which would not need `?all=1`.
     * The `…` pill would: it folds the SETTLED agents away rather than dropping
     * them, and the route's default 45-minute window hides exactly the older runs
     * somebody unfolds it to read — while the count on the pill goes on claiming
     * they are there. The view model cannot be built without an Application, so
     * the poll's one argument is held to here, on the recorded request.
     */
    @Test
    fun `the phone's strip poll asks for every agent`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val client = HuginnClient(
            baseUrlProvider = { BASE },
            tokenProvider = { "t" },
            engine = MockEngine { request ->
                seen += request
                respond(
                    """{"agents":[],"active":0,"serverTime":$STRIP_NOW}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", listOf("application/json")),
                )
            },
        )

        fetchStreamAgents(client, "jtyper")

        val asked = seen.single().url.toString().removePrefix(BASE)
        assertTrue("the folded list must be whole: $asked", "all=1" in asked)
        assertTrue(asked, asked.startsWith("/v1/sessions/jtyper/agents"))
    }

    /**
     * The pick and the fold live on the thing that owns them.
     *
     * Both are arguments to the shared picker rule, and a render site that pairs
     * the rows with one and forgets the other is wrong in a way nothing shows:
     * read an agent, watch it finish, and the transcript under you is replaced by
     * nothing.
     */
    @Test
    fun `the phone's strip keeps the stream being read and folds the rest`() {
        val agents = listOf(
            streamAgent("live", status = "running"),
            streamAgent("read", status = "done"),
            streamAgent("gone", updatedAt = STRIP_NOW - 4000),
        )
        val s = AgentStream()

        // Nothing picked: one live chip and a pill standing for the other two.
        assertEquals(
            listOf("main", "agent:live", "more"),
            s.items(agents, STRIP_NOW).map { it.key },
        )
        assertEquals(2, s.items(agents, STRIP_NOW).single { it.overflow }.count)

        s.select("read")
        val held = s.items(agents, STRIP_NOW)
        assertEquals(listOf("main", "agent:live", "agent:read", "more"), held.map { it.key })
        assertTrue("held open, not alive", held.single { it.agentId == "read" }.finished)

        s.toggleExpanded()
        val open = s.items(agents, STRIP_NOW)
        assertTrue("the pill is how a settled agent is reached", open.any { it.agentId == "gone" })
        assertEquals("held open and unfolded is still one row", 1, open.count { it.agentId == "read" })
        assertEquals("a duplicate key is a crash", open.size, open.map { it.key }.toSet().size)

        // Leaving the session drops both: the next one must not open unfolded on
        // somebody else's forty settled agents.
        s.reset()
        assertNull(s.selected)
        val shut = s.items(agents, STRIP_NOW)
        assertEquals(listOf("main", "agent:live", StreamPicker.OVERFLOW_KEY), shut.map { it.key })
        assertEquals("…", shut.single { it.overflow }.label)
    }

}
