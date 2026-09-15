package com.silencelen.huginn

import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.ChatList
import com.silencelen.huginn.data.DeviceList
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.RoundList
import com.silencelen.huginn.data.SavedAccounts
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.SessionList
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.HeadroomRules
import com.silencelen.huginn.ui.PlanFormat
import com.silencelen.huginn.ui.StreamPicker
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes real huginn-appd 2.0.0 responses.
 *
 * The fixtures in `src/test/resources` were captured from the live daemon on
 * 2026-07-27 and then scrubbed: every key and every value whose shape matters
 * (numbers, booleans, enum-like strings) is untouched, and free text is replaced
 * with a placeholder, so no session content lives in the repo.
 *
 * This is the only automated check that the app and the daemon still agree on
 * the wire format. A renamed server field is otherwise invisible until the app
 * silently shows an empty screen on a phone this host cannot run.
 */
class ApiContractTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `sessions decode with the v2 fields the list depends on`() {
        val list = json.decodeFromString<SessionList>(fixture("sessions.json"))
        assertTrue("expected at least one live session", list.sessions.isNotEmpty())
        val s = list.sessions.first()
        assertTrue("name must survive", s.name.isNotBlank())
        // Geometry drives the resize request; 0 would mean the field was renamed.
        assertTrue("cols must decode", s.cols > 0)
        assertTrue("rows must decode", s.rows > 0)
        assertNotNull("windowSize must decode", s.windowSize)
        assertTrue("activityAt must decode", s.activityAt > 0)
        // At least one session on the capture host had a recorded Claude state.
        assertTrue(
            "no session carried a state; the hook mapping may have broken",
            list.sessions.any { it.state != null },
        )
        assertTrue(
            "no session carried a transcript; the session view would be empty",
            list.sessions.any { it.hasTranscript },
        )
    }

    @Test
    fun `a screen decodes with geometry, hash and cursor`() {
        val s = json.decodeFromString<Screen>(fixture("screen.json"))
        assertTrue(s.width > 0)
        assertTrue(s.height > 0)
        assertEquals("one captured line per pane row", s.height, s.lines.size)
        assertNotNull("hash drives long polling", s.hash)
        assertTrue(s.cursorX >= 0 && s.cursorY >= 0)
    }

    @Test
    fun `a transcript decodes into the event kinds the renderer handles`() {
        val p = json.decodeFromString<TranscriptPage>(fixture("transcript.json"))
        assertTrue("expected events", p.events.isNotEmpty())
        assertTrue("nextOffset drives tailing", p.nextOffset > 0)
        val known = setOf("user", "assistant", "thinking", "tool", "tool_result", "system")
        p.events.forEach {
            assertTrue("unhandled event kind '${it.kind}'", it.kind in known)
        }
        // A tool event must carry its name, else the card renders blank.
        p.events.filter { it.kind == "tool" }.forEach {
            assertTrue("tool event without a name", !it.name.isNullOrBlank())
        }
    }

    @Test
    fun `status decodes the fields the status screen shows`() {
        val s = json.decodeFromString<Status>(fixture("status.json"))
        assertNotNull(s.host)
        assertNotNull(s.claude)
        assertNotNull(s.mempalace)
        assertNotNull(s.disk)
        assertTrue(s.uptimeSec > 0)
        assertTrue(s.cores > 0)
        assertEquals(3, s.load.size)
    }

    @Test
    fun `the chat list decodes even when empty`() {
        val c = json.decodeFromString<ChatList>(fixture("chats.json"))
        assertNotNull(c.chats)
    }

    @Test
    fun `an unknown future field does not fail the decode`() {
        // Forward compatibility: a newer daemon must not break an older app.
        val s = json.decodeFromString<Screen>("""{"width":80,"height":24,"somethingNew":true}""")
        assertEquals(80, s.width)
    }
    // ---------------------------------------------------------- Rounds + Devices
    //
    // ⚠ CAPTURED 2026-08-25, because until then this file's fixtures predated both
    // features. Its own KDoc says this is "the only automated check that the app
    // and the daemon still agree on the wire format" — and the two newest features,
    // the ones most likely to have a field renamed, were the two it did not cover.

    @Test
    fun `rounds decode with the fields the list and the report depend on`() {
        val list = json.decodeFromString<RoundList>(fixture("rounds.json"))
        assertTrue("expected at least one round", list.rounds.isNotEmpty())
        val r = list.rounds.first()
        assertTrue("title must survive", r.title.isNotBlank())
        // The daemon renders the cadence; the clients never re-derive it, so an
        // empty one here is a blank line under every row.
        assertTrue("cadence must decode", r.cadence.isNotBlank())
        assertNotNull("schedule must decode", r.schedule)
        assertTrue("nextRunAt drives the countdown", r.nextRunAt != null && r.nextRunAt!! > 0)

        val run = r.lastRun
        assertNotNull("a round that has run must decode its lastRun", run)
        assertTrue("status must decode", run!!.status.isNotBlank())
        assertTrue("the headline IS the notification", run.headline.isNotBlank())
        assertTrue("at is epoch SECONDS", run.at > 0)
        // Added 2026-08-25; a rename would silently take the row back to showing
        // the capped count as if it were the whole story.
        assertTrue("itemsTotal must decode", run.itemsTotal >= run.items.size)
        // Added 2026-08-25; a rename means Mark done stops sticking, with no error.
        assertNotNull("acknowledgedAt must decode", run.acknowledgedAt)
        run.items.firstOrNull()?.let {
            assertTrue("an item's next step is what Carry on is built from", it.suggest.isNotBlank())
        }
    }

    @Test
    fun `devices decode with the scope fields the controls depend on`() {
        val list = json.decodeFromString<DeviceList>(fixture("devices.json"))
        assertTrue("expected at least one device", list.devices.isNotEmpty())
        val d = list.devices.first()
        assertTrue("name must survive", d.name.isNotBlank())
        assertTrue("platform must decode", d.platform.isNotBlank())
        assertTrue("scope must decode", d.scope.isNotBlank())
        // ⚠ effectiveScope is what enables "Act here", not the enrolled scope. A
        // rename would leave the button reading the wrong field, which is the one
        // place on this screen where being wrong widens what a machine will do.
        assertTrue("effectiveScope must decode", d.effectiveScope.isNotBlank())
        assertEquals(
            "every device must report an effectiveScope",
            list.devices.size,
            list.devices.count { it.effectiveScope.isNotBlank() },
        )
    }

    // ------------------------------------------------- headroom (3.0.0)
    //
    // ⚠ AUTHORED FROM THE WAVE 1 CONTRACT on 2026-09-15, not captured: appd
    // 3.0.0 does not exist yet. Every fixture below is hand-written from the
    // example JSON in `design/w1-headroom.md` and MUST be re-captured from the
    // live daemon once 3.0.0 deploys. Until then these prove the client can
    // decode what the contract promises — not that the daemon sends it.

    @Test
    fun `a plan names the account its bars belong to`() {
        val p = json.decodeFromString<Plan>(fixture("plan.json"))
        assertTrue("limits must decode", p.limits.isNotEmpty())
        assertNotNull("the whole point: whose usage this is", p.account)
        assertTrue("an email is what a person recognises", !p.account!!.email.isNullOrBlank())
        assertEquals("max_20x", p.account!!.subscriptionType)
        assertTrue("the caption is built from it", PlanFormat.accountCaption(p).contains("max_20x"))
    }

    @Test
    fun `a plan from an older daemon leaves the account null`() {
        // 2.85.0 has no `account` block. The bars must still draw, captioned
        // with the fallback rather than with the last account anyone looked at.
        val p = json.decodeFromString<Plan>(fixture("plan-legacy.json"))
        assertTrue("limits must still decode", p.limits.isNotEmpty())
        assertNull("no identity on the wire", p.account)
        assertEquals("signed-in account", PlanFormat.accountCaption(p))
    }

    @Test
    fun `headroom decodes the whole picture the Status pane draws`() {
        val h = json.decodeFromString<Headroom>(fixture("headroom.json"))
        assertEquals("red", h.mode)
        assertEquals("weekly_fable", h.worst?.window)
        assertEquals(92.0, h.worst!!.percent, 0.001)
        assertTrue("a laddered session must decode", h.sessions.any { it.ladder?.to == "opus" })
        assertTrue("a stalled session must decode", h.sessions.any { it.stalled })
        assertNotNull("STOP-FABLE is armed", h.sentinels["STOP-FABLE"])
        assertEquals("one spawn held by the gate", 1, h.held.size)
        assertNotNull("the arbiter's reasoning is displayed verbatim", h.arbiter)
        // The client's defaults must MIRROR the daemon's, or a settings form
        // opens showing thresholds the daemon is not using.
        assertEquals(92, h.settings!!.ladderPct)
        assertEquals(85, h.settings!!.headsUpPct)
        assertEquals(listOf("fable", "opus", "sonnet"), h.settings!!.ladder)
    }

    @Test
    fun `an idle daemon's headroom decodes with everything empty`() {
        val h = json.decodeFromString<Headroom>(fixture("headroom-idle.json"))
        assertEquals("ok", h.mode)
        assertTrue(h.sessions.isEmpty())
        assertTrue(h.held.isEmpty())
        assertNull("settings are only sent when asked for", h.settings)
        assertNull("an unarmed sentinel is null, not absent", h.sentinels["STOP"])
    }

    @Test
    fun `status carries the pill's reading`() {
        val s = json.decodeFromString<Status>(fixture("status.json"))
        val hr = s.headroom
        assertNotNull("the pill is built from this alone", hr)
        assertEquals("red", hr!!.mode)
        assertEquals(listOf("STOP-FABLE"), hr.sentinels)
        assertEquals(2, hr.paused)
        assertEquals(
            "Fable 92% · resets 2d",
            HeadroomRules.pillText(hr, 1_789_460_000_000L),
        )
    }

    @Test
    fun `status from an older daemon hides the pill instead of showing zero`() {
        val s = json.decodeFromString<Status>(fixture("status-legacy.json"))
        assertNotNull("the rest of the screen must still decode", s.host)
        assertNull("no block means no pill", s.headroom)
        assertNull(HeadroomRules.pillText(s.headroom, 1_789_460_000_000L))
    }

    @Test
    fun `session rows carry the headroom cell and the queue depth`() {
        val list = json.decodeFromString<SessionList>(fixture("sessions.json"))
        val laddered = list.sessions.first { it.headroom?.ladder != null }
        assertEquals("opus", laddered.headroom!!.ladder)
        val stalled = list.sessions.first { it.headroom?.stalled == true }
        assertFalse("auto-resume off must survive", stalled.headroom!!.autoResume)
        assertEquals(
            "stopped at the usage limit",
            HeadroomRules.sessionMark(stalled.headroom, null, 1_789_460_000_000L),
        )
        assertTrue("a queued send must decode", list.sessions.any { it.pendingSends > 0 })
        assertTrue(
            "a session with no Claude session gets no cell at all",
            list.sessions.any { it.headroom == null },
        )
    }

    @Test
    fun `agents with all=1 carry the run id the picker groups on`() {
        val info = json.decodeFromString<AgentsInfo>(fixture("agents-all.json"))
        assertEquals(3, info.agents.size)
        assertEquals(2, info.agents.count { it.workflowId != null })
        assertTrue("a status word must decode", info.agents.all { !it.status.isNullOrBlank() })
        assertTrue("agentType must decode", info.agents.all { !it.agentType.isNullOrBlank() })

        val items = StreamPicker.items(info.agents, 1_789_460_000L)
        assertEquals("main", items.first().key)
        assertEquals("one run header for the two members", 1, items.count { it.header })
        assertEquals("keys must be unique", items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun `an agent's own transcript is an ordinary TranscriptPage`() {
        val p = json.decodeFromString<TranscriptPage>(fixture("agent-transcript.json"))
        assertTrue("expected events", p.events.isNotEmpty())
        assertTrue("nextOffset drives the agent cursor", p.nextOffset > 0)
        assertEquals("Opus 5", p.modelDisplay)
        val known = setOf("user", "assistant", "thinking", "tool", "tool_result", "system")
        p.events.forEach { assertTrue("unhandled event kind '${it.kind}'", it.kind in known) }
    }

    @Test
    fun `a limit stall decodes as an assistant record carrying its api error`() {
        val p = json.decodeFromString<TranscriptPage>(fixture("transcript-limit.json"))
        val last = p.events.last()
        assertEquals("the record IS an assistant record", "assistant", last.kind)
        assertEquals("only this field makes it a limit notice", 429, last.apiError)
        assertTrue("every earlier event is ordinary", p.events.dropLast(1).all { it.apiError == null })
    }

    @Test
    fun `saved accounts speak the daemon's freshness vocabulary`() {
        val saved = json.decodeFromString<SavedAccounts>(fixture("accounts.json"))
        assertTrue("expected saved logins", saved.accounts.isNotEmpty())
        val words = setOf("fresh", "expiring", "expired", "unrefreshable")
        saved.accounts.forEach {
            assertTrue("unknown freshness word '${it.freshness}'", it.freshness in words)
        }
        val expired = saved.accounts.first { it.freshness == "expired" }
        assertEquals("invalid_grant", expired.refresh?.lastStatus)
        assertNotNull("expiry drives the Refresh button", expired.expiresAt)
        assertNotNull("past this, only a re-login helps", expired.refreshTokenExpiresAt)
    }

    @Test
    fun `a send result decodes from both the queued daemon and the old one`() {
        val queued = json.decodeFromString<SendKeysResult>(
            """{"ok":true,"queued":1,"position":1,"delivered":false}""",
        )
        assertEquals(1, queued.position)
        assertFalse("still waiting for the turn to end", queued.landed)

        // ⚠ The whole compat question for this route: `sendKeys` used to return
        // Unit and the daemon answered `{"ok":true}`. That must still decode,
        // and must not read as "queued forever".
        val old = json.decodeFromString<SendKeysResult>("""{"ok":true}""")
        assertTrue(old.ok)
        assertTrue("no queue means it landed outright", old.landed)
    }
}
