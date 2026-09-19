package com.silencelen.huginn

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.PaneLease
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.TypingState
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.ui.AgentStream
import com.silencelen.huginn.ui.AttachBatch
import com.silencelen.huginn.ui.AttachChipState
import com.silencelen.huginn.ui.AttachmentSlots
import com.silencelen.huginn.ui.AttachmentText
import com.silencelen.huginn.ui.ClipboardImage
import com.silencelen.huginn.ui.PasteOutcome
import com.silencelen.huginn.ui.chipsFor
import com.silencelen.huginn.ui.composeMessage
import com.silencelen.huginn.ui.pastePlan
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.SelectionAction
import com.silencelen.huginn.ui.SelectionMode
import com.silencelen.huginn.ui.SelectionStaging
import com.silencelen.huginn.ui.applyAutoSwitch
import com.silencelen.huginn.ui.pageStillWanted
import com.silencelen.huginn.ui.SESSION_NAME
import com.silencelen.huginn.ui.detachWanted
import com.silencelen.huginn.ui.sessionGoneWords
import com.silencelen.huginn.widget.AskAttempt
import com.silencelen.huginn.ui.SendQueue
import com.silencelen.huginn.ui.StreamPicker
import com.silencelen.huginn.ui.fetchStreamAgents
import com.silencelen.huginn.ui.errorTextFor
import com.silencelen.huginn.ui.headroomPill
import com.silencelen.huginn.ui.mergeTranscriptPage
import com.silencelen.huginn.ui.saveQuickActions
import com.silencelen.huginn.ui.statusHeadroomOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --------------------------------------------------- the pane-size lease

    /**
     * THE PHONE'S HALF OF OWNER DECISION 52.
     *
     * The Screen tab polls the pane the whole time it is on display, and used to
     * report `?cols=&rows=` on every one of those polls — which took a lease over
     * the owner's real tmux window. With the desktop doing the same thing on the
     * same session the pane flapped 152x44 <-> 107x44 three times in ninety
     * seconds, nobody typing.
     *
     * `startScreenPolling` asks [PaneLease.poll] what to put on the wire, so the
     * rule is testable here: this view model is an `AndroidViewModel` and this
     * host has no device, so a pure delegate is the only way any of it is reached.
     */
    @Test
    fun `the screen poll claims the window only in live typing mode`() {
        // Screen tab open, reading. Geometry travels (the daemon still wants to
        // know what the phone can draw); the claim does not.
        val viewing = PaneLease.poll(cols = 60, rows = 30, liveView = false)
        assertEquals(60, viewing.cols)
        assertEquals(30, viewing.rows)
        assertFalse("watching a pane must not reshape somebody's terminal", viewing.live)

        // The keyboard is up and every keystroke is going into the pane. NOW the
        // phone's geometry is the one the pane should be wrapped for.
        val typing = PaneLease.poll(cols = 60, rows = 30, liveView = true)
        assertTrue("live typing is what entitles the phone to the window", typing.live)

        // Live mode entered before the first measurement: nothing honest to claim.
        assertFalse(PaneLease.poll(cols = null, rows = null, liveView = true).live)
    }

    /**
     * OPENING A SESSION TAKES NOTHING, AND CLOSING ONE OWES NOTHING BACK.
     *
     * The phone polls the pane from the moment a session opens — the Conversation
     * tab draws its question card off that poll — and the Screen tab may never be
     * opened at all. Two things follow, and the phone got both wrong: the poll on
     * open must carry no claim (no measurement has happened and nobody is typing),
     * and leaving must put no `DELETE /size` on the wire, because against the
     * pre-decision-52 daemon that release landed on whoever DID hold the window.
     * Closing a conversation could therefore unpin somebody else's live pane.
     */
    @Test
    fun `opening a session takes no lease, and leaving it releases nothing`() {
        // Session open, Conversation tab: polled, never measured, not live.
        val onOpen = PaneLease.poll(cols = null, rows = null, liveView = false)
        assertFalse("opening a session must claim nothing", onOpen.live)
        assertNull(onOpen.cols)

        // The Screen tab on display, measured, still not typing: still no claim.
        assertFalse(PaneLease.poll(cols = 60, rows = 30, liveView = false).live)

        // And the release rule the view model runs on the way out: nothing held,
        // nothing owed — so no request is built at all.
        assertNull("a session that was never leased owes no release",
            PaneLease.toRelease(held = null, wanted = null))
        assertEquals("what WAS leased is still handed back", "jtyper",
            PaneLease.toRelease(held = "jtyper", wanted = null))
    }

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

    // --------------------------------------- #73 a teardown tears down its own chat

    @Test
    fun `the outgoing chat's teardown does not blank the chat just opened`() {
        // openChat(B) runs, then the outgoing DisposableEffect(A) disposes on the
        // next vsync — 8-16 ms later, while a warm daemon GET is 1-2 ms. A
        // teardown keyed to no chat wiped B's page, its send flag and its live
        // stream, leaving an indefinite spinner.
        assertFalse(detachWanted("chat-A", "chat-B"))
        assertTrue(detachWanted("chat-B", "chat-B"))
        // Leaving the chat surface entirely is unconditional.
        assertTrue(detachWanted(null, "chat-B"))
        assertTrue(detachWanted(null, null))
    }

    // --------------------------- contract 1 + #85 what a session name is, and is not

    @Test
    fun `a session name may not contain a dot, and may contain a dash`() {
        // tmux silently rewrites '.' to '_', so a dotted name existed under a
        // name nothing could route to and every later call 404ed. The phone had
        // it exactly backwards: rename accepted dots, create refused dashes.
        assertFalse("tmux would rewrite this", SESSION_NAME.matches("web.api"))
        assertFalse(SESSION_NAME.matches("a.b.c"))
        assertTrue("dashes survive tmux untouched", SESSION_NAME.matches("web-api"))
        assertTrue(SESSION_NAME.matches("pctrooubleshoot"))
        assertTrue(SESSION_NAME.matches("_scratch"))
        assertTrue(SESSION_NAME.matches("j7"))

        assertFalse("a leading dash is not a filename", SESSION_NAME.matches("-lead"))
        assertFalse(SESSION_NAME.matches(""))
        assertFalse("upper case is folded before this is asked", SESSION_NAME.matches("Web"))
        assertTrue(SESSION_NAME.matches("a".repeat(50)))
        assertFalse(SESSION_NAME.matches("a".repeat(51)))
    }

    @Test
    fun `a 404 for a session still in the list is not a session that ended`() {
        val listed = listOf("pctrooubleshoot", "web.api")
        assertEquals("Session gone-one ended", sessionGoneWords("gone-one", listed))
        // Listed, and unreachable: saying it "ended" about a row the reader can
        // still see sends them looking for the wrong problem.
        assertTrue(sessionGoneWords("web.api", listed).contains("cannot address"))
        assertFalse(sessionGoneWords("web.api", listed).contains("ended"))
    }

    // ---------------------------------------- #72 a late page belongs to its session

    @Test
    fun `a history page arriving late belongs only to the session that asked`() {
        assertTrue(pageStillWanted("pctrooubleshoot", "pctrooubleshoot"))
        // The failure: A's "load earlier" comes back after the reader opened B,
        // and there is nothing in a TranscriptPage to say it is not B's. It was
        // welded above B's tail for the life of the view, and B's next "load
        // earlier" then asked for a byte offset into A's file.
        assertFalse(pageStillWanted("pctrooubleshoot", "opensession"))
        // Left the session screen altogether — backgrounding clears it too, and
        // coming back reloads the tail from scratch.
        assertFalse(pageStillWanted("pctrooubleshoot", null))
    }

    // ------------------------------------------ #80 transport failures in words

    @Test
    fun `a transport failure is told in household words, with no address in it`() {
        // Ktor 3.5.2 + OkHttp with HttpTimeout(connect = 8000) words it exactly
        // like this, and it flowed unscrubbed to the red banner on the Status tab
        // and to 62 toast call sites.
        val timeout = errorTextFor(
            RuntimeException(
                "Connect timeout has expired [url=http://192.168.2.117:8787/v1/status, " +
                    "connect_timeout=8000 ms]"
            )
        )
        assertFalse("the daemon's host must not reach the screen", timeout.contains("192.168.2.117"))
        assertFalse(timeout.contains("8787"))
        assertTrue("sentence case for a banner", timeout.first().isUpperCase())
        assertTrue(timeout.startsWith("Huginn did not answer in time"))

        // OkHttp does not word a refusal as "connection refused"; whatever
        // DeliveryCopy makes of it, the address must be gone.
        val refused = errorTextFor(RuntimeException("Failed to connect to /192.168.2.117:8787"))
        assertFalse(refused.contains("192.168.2.117"))
        assertFalse(refused.contains("8787"))

        val dns = errorTextFor(RuntimeException("Unable to resolve host \"huginn.tail1234.ts.net\""))
        assertFalse(dns.contains("huginn.tail1234.ts.net"))
    }

    @Test
    fun `an exception with no message still says something`() {
        assertTrue(errorTextFor(RuntimeException()).isNotBlank())
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

    // --------------------------------------------------- the quick actions

    /**
     * The editor the phone never had, and the guard that makes it safe to have.
     *
     * Two clients editing one set of host-owned templates is the ordinary case
     * here — a desktop window and a phone, both open on Settings — and the `rev`
     * is the only thing stopping the second Save from winning by being second.
     * It cannot be asserted on the view model (no Application, no device), so it
     * is asserted on the request the phone actually puts on the wire.
     */
    @Test
    fun `saving quick actions sends all four templates and the rev it opened on`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val client = HuginnClient(
            baseUrlProvider = { BASE },
            tokenProvider = { "t" },
            engine = MockEngine { request ->
                seen += request
                respond(
                    """{"rev":8,"explain":"E {selection}","execute":"X {selection}",""" +
                        """"askInNewChat":"A {selection}","quote":"About this:"}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", listOf("application/json")),
                )
            },
        )

        val back = saveQuickActions(
            client,
            QuickActions(
                rev = 7,
                explain = "E {selection}",
                execute = "X {selection}",
                askInNewChat = "A {selection}",
                quote = "About this:",
            ),
        )

        val sent = seen.single()
        assertEquals("/v1/quick-actions", sent.url.encodedPath)
        assertEquals("PATCH", sent.method.value)
        val body = (sent.body as io.ktor.http.content.TextContent).text
        // All four, every time: the editor holds all four, and "only what
        // changed" would need this to decide what changed.
        assertTrue(body, "\"explain\":\"E {selection}\"" in body)
        assertTrue(body, "\"execute\":\"X {selection}\"" in body)
        assertTrue(body, "\"askInNewChat\":\"A {selection}\"" in body)
        assertTrue(body, "\"quote\":\"About this:\"" in body)
        // The guard. Without it a stale copy overwrites a newer one in silence.
        assertTrue("the rev guard was dropped: $body", "\"rev\":7" in body)

        // And the daemon's answer comes back whole — the NEW rev especially,
        // because the editor keys its fields on it and would otherwise go on
        // saving against 7 forever.
        assertEquals(8, back.rev)
        assertEquals("About this:", back.quote)
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

    // ------------------------------------------------------ selection staging

    /**
     * A map-backed stand-in for the view model's draft book.
     *
     * The rules below belong to [SelectionStaging] precisely so they can be
     * reached: `HuginnViewModel` is an `AndroidViewModel` and nothing inside one
     * can be constructed on a host with no device.
     */
    private class Drafts {
        val map = mutableMapOf<String, String>()
        fun staging() = SelectionStaging(
            draftOf = { map[it].orEmpty() },
            setDraft = { k, v -> if (v.isEmpty()) map.remove(k) else map[k] = v },
            chatKeyOf = { HuginnViewModel.chatDraftKey(it) },
        )
    }

    private val actions = QuickActions(
        rev = 3,
        explain = "Explain this:\n\n{selection}",
        execute = "Run this:\n\n{selection}",
        askInNewChat = "About this:\n\n{selection}",
        quote = "",
    )

    @Test
    fun `staging into a composer appends and never clobbers what was typed`() {
        // THE STAGING CONTRACT. A half-typed message outranks anything arriving
        // into it; a verb that replaced the draft would destroy work that cannot
        // be recovered, and the reader pressed the verb, not Undo.
        val d = Drafts()
        val key = HuginnViewModel.sessionDraftKey("pctrooubleshoot")
        d.map[key] = "half a thought"
        d.staging().stage(SelectionAction.QUOTE, "alpha", actions, key)
        assertEquals("half a thought\n\n> alpha", d.map[key])

        // And twice over: a second verb joins the first rather than winning.
        d.staging().stage(SelectionAction.QUOTE, "beta", actions, key)
        assertEquals("half a thought\n\n> alpha\n\n> beta", d.map[key])
    }

    @Test
    fun `ask in a new chat stages into the NEW chat's draft, not the old one`() = runTest {
        // The whole bug this seam exists to pin: the composed text landing in the
        // composer the reader just left, in a chat they are navigating away from.
        val d = Drafts()
        val from = HuginnViewModel.sessionDraftKey("pctrooubleshoot")
        d.map[from] = "unsent"
        var opened: String? = null
        d.staging().askInNewChat(
            selection = "alpha",
            actions = actions,
            fallbackKey = from,
            create = { Chat(id = "c9", mode = "ask") },
            onOpened = { opened = it },
        )
        assertEquals("c9", opened)
        assertEquals("About this:\n\nalpha", d.map[HuginnViewModel.chatDraftKey("c9")])
        assertEquals("the source composer is untouched", "unsent", d.map[from])

        // A creation that FAILS does not lose the text — they selected it — so it
        // falls back into the composer they are actually looking at.
        val e = Drafts()
        e.map[from] = "unsent"
        e.staging().askInNewChat(
            selection = "alpha",
            actions = actions,
            fallbackKey = from,
            create = { throw IllegalStateException("offline") },
            onOpened = { opened = "must not open" },
        )
        assertEquals("unsent\n\nAbout this:\n\nalpha", e.map[from])
    }

    @Test
    fun `a daemon with no quick actions offers Quote alone`() {
        // The templates are the HOST's (quick-actions.json, appd 3.0.1+). Against a
        // daemon that owns none, three of the four verbs have no wording at all —
        // and Quote is the one verb whose text this client writes itself.
        assertEquals(
            listOf(SelectionAction.QUOTE),
            SelectionMode.begin("alpha").actions(null),
        )
        assertEquals(
            SelectionAction.entries.toList(),
            SelectionMode.begin("alpha").actions(actions),
        )
        // And a long-press that caught no words offers nothing, so the bar that
        // draws what it is given draws nothing either.
        assertTrue("a blank selection deserves no verbs", SelectionMode.begin("   ").actions(actions).isEmpty())
        assertTrue("and a dismissed one none at all", SelectionMode.NONE.actions(actions).isEmpty())
    }

    // ---------------------------------------------------------- the send queue

    @Test
    fun `a queued send says so, and a drained poll clears it`() {
        // The phone's half of the "my message just disappeared" report: a send into
        // a busy session is held by the daemon until the turn ends, and until this
        // the composer emptied with nothing anywhere to say why.
        val queued = SendKeysResult(ok = true, queued = 2, position = 2, delivered = false)
        val seeded = SendQueue.seed(queued)
        assertNotNull("a send that did not land seeds the line from its own answer", seeded)
        val note = SendQueue.note(seeded)
        assertNotNull(note)
        assertTrue("it must say what is happening: $note", note!!.startsWith("Queued"))
        assertTrue("and how many are waiting: $note", note.contains("2 waiting"))

        // DRAINED CLEARS IT — the half that rots. A poll that comes back empty must
        // remove the line, not settle on "(0 waiting)" under a working composer.
        assertNull(SendQueue.note(TypingState(queued = 0)))
        assertNull("a delivered send never showed one", SendQueue.seed(SendKeysResult(ok = true, delivered = true)))
    }

    /**
     * ⚠ THE OTHER WAY THE COMPOSER FALLS SILENT. appd 3.5.1 drops a human send it
     * already holds — or delivered within the last 30 seconds — and answers
     * `duplicate: true`. Nothing is queued for that press, so `landed` is true
     * and the seed used to be null: the composer emptied and said nothing, which
     * is the same screen as the message being lost. It is precisely the screen
     * that produced the three-copies P1, because the reader retypes.
     */
    @Test
    fun `a duplicate send is explained rather than passed over in silence`() {
        val dup = SendKeysResult(ok = true, queued = 0, position = 0, delivered = false, duplicate = true)
        assertTrue("nothing of it is waiting, which is why `landed` cannot be the gate", dup.landed)
        val seeded = SendQueue.seed(dup)
        assertNotNull("the phone seeds its composer line from the send's own answer", seeded)
        assertEquals("That message is already on its way", SendQueue.note(seeded))
        // And it is the daemon's next word, not this one, that has the last say.
        assertNull(SendQueue.note(TypingState(queued = 0)))
    }


    // ------------------------------------------------ #71 auto-switch ordering

    @Test
    fun `turning auto-switch on probes only after the new book is persisted`() = runTest {
        val order = mutableListOf<String>()
        var forced: Boolean? = null
        applyAutoSwitch(
            on = true,
            // Suspends exactly where the real one does: inside the DataStore
            // write, before _routeBook is republished.
            persist = { kotlinx.coroutines.yield(); order += "persist"; },
            resolve = { force -> order += "resolve"; forced = force },
        )
        assertEquals(listOf("persist", "resolve"), order)
        assertTrue("an unforced resolve on a fresh health map probes nothing", forced == true)
    }

    @Test
    fun `turning auto-switch off persists and probes nothing`() = runTest {
        val order = mutableListOf<String>()
        applyAutoSwitch(
            on = false,
            persist = { order += "persist" },
            resolve = { order += "resolve" },
        )
        assertEquals(listOf("persist"), order)
    }
}

/**
 * The phone's attachment slots.
 *
 * These had ZERO coverage before multi-attach — `takeAttachment`,
 * `whenAttachmentSettled` and the owner guard all lived as private methods on an
 * `AndroidViewModel`, which on a host with no device and no `/dev/kvm` cannot be
 * constructed at all. [AttachmentSlots] is a plain class for exactly that reason,
 * and every rule the view model used to hold is asserted here.
 */
class AttachmentSlotsTest {

    private val CHAT = "chat:1"
    private val SESS = "sess:jtyper"

    private fun AttachmentSlots.stageReady(owner: String, label: String, path: String): String {
        val id = stage(owner, label, image = true)!!
        ready(id, path, name = null, image = true, readable = true, bytes = 100)
        return id
    }

    @Test
    fun `markers come back in attach order, and the composed message is core's`() {
        val slots = AttachmentSlots()
        slots.stageReady(CHAT, "one", "/up/one.jpg")
        slots.stageReady(CHAT, "two", "/up/two.jpg")
        slots.stageReady(CHAT, "three", "/up/three.jpg")

        val taken = slots.take(CHAT)
        assertEquals("three files, three markers", 3, taken.markers.size)

        // The EXACT expression both phone send sites now use. It used to be
        // `text + "\n\n" + markerFor(att)`, written out twice, for one marker.
        val sent = composeMessage("look at these", taken.markers)
        assertEquals(
            listOf("/up/one.jpg", "/up/two.jpg", "/up/three.jpg"),
            AttachmentText.imagePaths(sent),
        )
        assertTrue("the old hand-rolled join is byte-identical for one", sent.startsWith("look at these\n\n"))
    }

    @Test
    fun `what failed is named, and what landed still sends`() {
        val slots = AttachmentSlots()
        slots.stageReady(CHAT, "ok1.png", "/up/ok1.jpg")
        val bad = slots.stage(CHAT, "big.zip", image = false)!!
        slots.fail(bad, "that type is not allowed")
        slots.stageReady(CHAT, "ok2.png", "/up/ok2.jpg")

        val taken = slots.take(CHAT)
        assertEquals(2, taken.markers.size)
        assertEquals(listOf("big.zip"), taken.failed)
        assertEquals(
            "1 of 3 attachments did not upload: big.zip — sent without it",
            AttachBatch.failureLine(taken.failed, 3),
        )
        // The slot version left a FAILED attachment staged, so it rode the NEXT
        // message instead of this one.
        assertTrue("taking clears them all", slots.items.value.isEmpty())
    }

    @Test
    fun `the cap is ten per composer`() {
        val slots = AttachmentSlots()
        repeat(10) { assertNotNull(slots.stage(CHAT, "f$it", image = true)) }
        assertNull("the eleventh is refused", slots.stage(CHAT, "f11", image = true))
        assertEquals(10, slots.countFor(CHAT))
        // Per composer, not globally: a full chat must not lock the session pane.
        assertNotNull(slots.stage(SESS, "s1", image = true))
        assertEquals(10L, AttachBatch.MAX_ITEMS.toLong())
    }

    @Test
    fun `one composer cannot take another's`() {
        // Stage in chat A, hop to the session before A's dispose runs, send —
        // the session's message used to carry A's photo.
        val slots = AttachmentSlots()
        slots.stageReady(CHAT, "one", "/up/one.jpg")
        assertTrue(slots.take(SESS).markers.isEmpty())
        assertEquals("and it is still staged where it belongs", 1, slots.countFor(CHAT))
    }

    @Test
    fun `settle waits for the WHOLE batch, not the first one to land`() = runTest {
        val slots = AttachmentSlots()
        val a = slots.stage(CHAT, "a", image = true)!!
        val b = slots.stage(CHAT, "b", image = true)!!
        val c = slots.stage(CHAT, "c", image = true)!!

        var settled = false
        val waiter = launch { slots.settle(CHAT); settled = true }
        runCurrent()
        assertFalse("nothing has landed yet", settled)

        slots.ready(a, "/up/a.jpg", null, image = true, readable = true, bytes = 1)
        runCurrent()
        assertFalse("one of three is not the batch", settled)

        slots.fail(b, "nope")
        runCurrent()
        assertFalse("a failure is settled, but c is still in flight", settled)

        slots.ready(c, "/up/c.jpg", null, image = true, readable = true, bytes = 1)
        runCurrent()
        assertTrue("the send goes once nothing is still uploading", settled)
        waiter.join()
    }

    @Test
    fun `chips are this composer's, in order, with the state the row draws`() {
        val slots = AttachmentSlots()
        slots.stageReady(CHAT, "one", "/up/one.jpg")
        val mid = slots.stage(CHAT, "two", image = false)!!
        slots.fail(mid, "refused")
        slots.stage(SESS, "elsewhere", image = true)

        val chips = chipsFor(slots.items.value, CHAT)
        assertEquals(listOf("one", "two"), chips.map { it.label })
        assertEquals(AttachChipState.READY, chips[0].state)
        assertEquals(AttachChipState.FAILED, chips[1].state)

        // Removing by id, not by index: uploads settle out of order.
        slots.remove(chips[0].id)
        assertEquals(listOf("two"), chipsFor(slots.items.value, CHAT).map { it.label })
    }
}

/**
 * The clipboard paste rule.
 *
 * `ClipboardManager` is one of the framework classes the unit-test android.jar
 * throws from on first call, so the READING sits behind `ImageClipboard` and only
 * the DECISION is asserted here — which is the half people report: "I copied a
 * picture and nothing happened" is either an empty clipboard or a full composer,
 * and a paste that says neither is indistinguishable from a broken button.
 */
class PastePlanTest {

    private val image = ClipboardImage("pasted.jpg", ByteArray(8))

    @Test
    fun `an empty clipboard is refused in words`() {
        val out = pastePlan(null, pending = 0)
        assertTrue(out is PasteOutcome.Refused)
        assertEquals("No image on the clipboard", (out as PasteOutcome.Refused).why)
    }

    @Test
    fun `a full composer is refused with the cap`() {
        val out = pastePlan(image, pending = AttachBatch.MAX_ITEMS)
        assertTrue(out is PasteOutcome.Refused)
        assertTrue((out as PasteOutcome.Refused).why.contains("10 attachments at a time"))
    }

    @Test
    fun `otherwise it goes, under its own name`() {
        val out = pastePlan(image, pending = 3)
        assertTrue(out is PasteOutcome.Attach)
        assertEquals("pasted.jpg", (out as PasteOutcome.Attach).name)
        assertEquals(8, out.jpeg.size)
    }

}

/**
 * THE ASK SHEET'S RETRY, which used to leave a chat behind on every press.
 *
 * The activity is Android; what one question does to the host across several
 * presses is not.
 */
class AskAttemptTest {

    private class Host {
        var created = 0
        var deleted = mutableListOf<String>()
        val queued = mutableListOf<Pair<String, String>>()
        fun create(): String { created++; return "chat-$created" }
    }

    @Test
    fun `three failed presses leave one chat on the host, not three`() = runTest {
        // The deterministic repro: 429 "too many concurrent runs (3)". The chat
        // exists and the daemon refused the MESSAGE, so the chat is kept and the
        // retry sends into it.
        val host = Host()
        val attempt = AskAttempt()
        var refuse = true
        suspend fun press(): Result<String> = runCatching {
            attempt.submit(
                create = { host.create() },
                queue = { id ->
                    if (refuse) throw HuginnClient.HuginnException(429, "too many concurrent runs (3)")
                    host.queued += id to "what is the fleet doing?"
                },
                discard = { id -> host.deleted += id },
            )
        }

        assertTrue(press().isFailure)
        assertTrue(press().isFailure)
        assertTrue(press().isFailure)
        assertEquals("one question, one chat", 1, host.created)

        refuse = false
        assertEquals("chat-1", press().getOrNull())
        assertEquals(1, host.queued.size)
        assertEquals("chat-1", host.queued.single().first)
        assertEquals(emptyList<String>(), host.deleted)
    }

    @Test
    fun `a send that never reached the host takes its fresh chat back`() = runTest {
        val host = Host()
        val attempt = AskAttempt()
        val first = runCatching {
            attempt.submit(
                create = { host.create() },
                queue = { throw java.io.IOException("unexpected end of stream") },
                discard = { id -> host.deleted += id },
            )
        }
        assertTrue(first.isFailure)
        assertEquals(listOf("chat-1"), host.deleted)

        // ...and the retry starts clean rather than sending into a chat that was
        // just deleted.
        val second = attempt.submit(
            create = { host.create() },
            queue = { id -> host.queued += id to "q" },
            discard = { id -> host.deleted += id },
        )
        assertEquals("chat-2", second)
        assertEquals(2, host.created)
    }

    @Test
    fun `a chat the host could not even create leaves nothing behind`() = runTest {
        val host = Host()
        val attempt = AskAttempt()
        val r = runCatching {
            attempt.submit(
                create = { throw java.io.IOException("no route to host") },
                queue = { },
                discard = { id -> host.deleted += id },
            )
        }
        assertTrue(r.isFailure)
        assertEquals(0, host.created)
        assertEquals(emptyList<String>(), host.deleted)
    }

    @Test
    fun `an ordinary send creates one chat and queues into it`() = runTest {
        val host = Host()
        val id = AskAttempt().submit(
            create = { host.create() },
            queue = { i -> host.queued += i to "q" },
            discard = { },
        )
        assertEquals("chat-1", id)
        assertEquals(1, host.queued.size)
    }
}
