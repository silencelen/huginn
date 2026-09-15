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
import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.ui.HeadroomRules
import com.silencelen.huginn.ui.PlanFormat
import com.silencelen.huginn.ui.StreamPicker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    // ⚠ ALIGNED TO THE DAEMON AT c356870 + the Wave 1 fix round on 2026-09-15,
    // not captured: appd 3.0.0 is not deployed yet. They were re-derived FROM
    // THE DAEMON SOURCE rather than from `design/w1-headroom.md`, which is what
    // the first pass was written from and why fourteen fields drifted — every
    // `at` on `/v1/headroom` is milliseconds, `agents[].id` is the BARE hex,
    // `depth` and `status` can be null, `settings` is always present, and the
    // refresh vocabulary is `refreshed`/`active_skipped`/… and never `ok`.
    // RE-CAPTURE LIVE AFTER DEPLOY; until then these prove the client can decode
    // what the daemon's code emits, not that a running daemon emits it.

    @Test
    fun `a plan names the account its bars belong to`() {
        val p = json.decodeFromString<Plan>(fixture("plan.json"))
        assertTrue("limits must decode", p.limits.isNotEmpty())
        assertTrue("fetchedAt is `planCache.at = Date.now()`: millis", p.fetchedAt!! > 1_000_000_000_000L)
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
        val laddered = h.sessions.first { it.ladder?.to == "opus" }
        assertTrue("a stalled session must decode", h.sessions.any { it.stalled })
        assertNotNull("STOP-FABLE is armed", h.sentinels["STOP-FABLE"])
        assertEquals("one spawn held by the gate", 1, h.held.size)
        assertNotNull("the arbiter's reasoning is displayed verbatim", h.arbiter)
        // The client's defaults must MIRROR the daemon's, or a settings form
        // opens showing thresholds the daemon is not using.
        assertEquals(92, h.settings!!.ladderPct)
        assertEquals(85, h.settings!!.headsUpPct)
        assertEquals(listOf("fable", "opus", "sonnet"), h.settings!!.ladder)
        assertEquals("claude-fable-5-1", h.settings!!.defaultModel)
        assertTrue("{pct}" in h.settings!!.headsUpText)

        // ⚠ EVERY CLOCK ON THIS ROUTE IS MILLISECONDS. The fixture used to carry
        // seconds beside the daemon's millis with no field-name tell, so the
        // contract test agreed with itself and with nothing else.
        assertTrue("serverTime is millis", h.serverTime > 1_000_000_000_000L)
        assertTrue("ladder.at is millis", laddered.ladder!!.at > 1_000_000_000_000L)
        assertTrue("headsUpAt is millis", laddered.headsUpAt!! > 1_000_000_000_000L)
        assertTrue("held since is millis", h.held.single().since > 1_000_000_000_000L)

        // The stall record: the clock Claude printed and the daemon's refusal.
        val stalled = h.sessions.first { it.stalled }
        val stall = stalled.stall
        assertNotNull("a stalled session carries its whole record", stall)
        assertEquals("weekly_fable", stall!!.window)
        assertTrue("stall.resetsAt is millis", stall.resetsAt!! > 1_000_000_000_000L)
        assertEquals("auto-resume is off for this session", stall.why)
        assertTrue("the limit sentence carries its own clock", stall.text!!.contains("resets 10:10pm"))
        assertEquals(
            "stopped: the daemon's own reason, not our guess at one",
            "auto-resume is off for this session",
            HeadroomRules.sessionMark(
                com.silencelen.huginn.data.SessionHeadroom(
                    family = stalled.family, autoResume = stalled.autoResume, stalled = true,
                ),
                null, 1_789_460_000_000L, stall = stall,
            ),
        )
        // Always present, both halves null when nothing was seen.
        assertNotNull("nativeSwitch is always on the row", laddered.nativeSwitch)

        // The arbiter's last action, by the names the daemon actually writes.
        val last = h.arbiter!!["lastAction"]!!.jsonObject
        assertEquals("ladder_down", last["type"]!!.jsonPrimitive.content)
        assertEquals("w1kcore", last["name"]!!.jsonPrimitive.content)
        assertEquals("opus", last["to"]!!.jsonPrimitive.content)

        val reset = h.resets.single()
        assertEquals("session", reset.window)
        assertTrue("the DUE instant stays ISO", reset.resetsAt!!.startsWith("2026-"))
        assertTrue("and the observation clock is millis", reset.at!! > 1_000_000_000_000L)
    }

    @Test
    fun `an idle daemon's headroom decodes with everything empty`() {
        val h = json.decodeFromString<Headroom>(fixture("headroom-idle.json"))
        assertEquals("ok", h.mode)
        assertTrue(h.sessions.isEmpty())
        assertTrue(h.held.isEmpty())
        assertTrue(h.resets.isEmpty())
        // ⚠ `settings` is ALWAYS emitted — `headroomPayload` has no branch that
        // omits it. The fixture used to leave it out and this line used to say so.
        assertNotNull("settings ride every answer", h.settings)
        assertEquals("claude-fable-5-1", h.settings!!.defaultModel)
        assertNull("an unarmed sentinel is null, not absent", h.sentinels["STOP"])
    }

    /**
     * ⚠ THE DECODE THAT KILLED THE WHOLE RESPONSE, and the reason there is now a
     * watch fixture at all: `normalizeHeadroomState` seeds `lastResumeAt: null`,
     * so on every host that had never resumed anything — every fresh install —
     * the explicit null failed the `/v1/watch` decode outright, taking the watch
     * loop, every notification decision and the headroom toasts with it.
     */
    @Test
    fun `the watch digest decodes both a null lastResumeAt and a zero`() {
        val never = json.decodeFromString<Watch>(fixture("watch-never-resumed.json"))
        assertNull("nothing has ever resumed; that is not the epoch", never.headroom!!.lastResumeAt)
        assertEquals("ok", never.headroom!!.mode)

        val live = json.decodeFromString<Watch>(fixture("watch.json"))
        assertEquals(java.lang.Long.valueOf(0L), live.headroom!!.lastResumeAt)
        assertEquals(listOf("promptprobe"), live.headroom!!.stalled)
        assertEquals("opus", live.headroom!!.laddered["andrev"])
        // ⚠ ISO here, unlike `/v1/headroom`'s millisecond `stall.resetsAt`: the
        // digest converts it so the notification never has to guess a unit.
        assertTrue(live.headroom!!.stalls["promptprobe"]!!.startsWith("2026-"))
        assertEquals(listOf("STOP-FABLE"), live.headroom!!.sentinels)
        assertEquals(java.lang.Long.valueOf(214L), live.pushesSent)
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
            "a session with no Claude session gets a NULL cell",
            list.sessions.any { it.headroom == null },
        )
        // ⚠ ON THE RAW JSON, because a model default cannot tell an absent key
        // from a null one and the drift is exactly about presence: `pendingSends`
        // is always a number and `headroom` is always a key (null when the
        // session has no Claude session id yet).
        val rows = json.parseToJsonElement(fixture("sessions.json"))
            .jsonObject["sessions"]!!.jsonArray.map { it.jsonObject }
        rows.forEach {
            assertTrue("every row carries pendingSends", "pendingSends" in it)
            assertTrue("every row carries a headroom key", "headroom" in it)
        }
    }

    @Test
    fun `agents with all=1 carry the run id the picker groups on`() {
        val info = json.decodeFromString<AgentsInfo>(fixture("agents-all.json"))
        assertEquals(5, info.agents.size)
        assertEquals(2, info.agents.count { it.workflowId != null })

        // ⚠ THE BARE HEX. `/agents` strips the `agent-` prefix and the transcript
        // route takes it either way; a fixture carrying the prefixed form let the
        // contract test agree with a client that 400'd on every chip.
        assertTrue(
            "the list emits bare ids: ${info.agents.map { it.id }}",
            info.agents.none { it.id.startsWith("agent-") },
        )

        // The two nulls a contract test has to carry, because they are the two
        // that used to fail the decode of the WHOLE list.
        val orphan = info.agents.first { it.status == "orphan" }
        assertNull("an agent with no .meta.json has no depth", orphan.depth)
        assertNull("and no type", orphan.agentType)
        assertTrue("a cold agent has no status word at all", info.agents.any { it.status == null })
        assertTrue("a failed agent is a real row", info.agents.any { it.status == "failed" })

        val items = StreamPicker.items(info.agents, 1_789_460_000L)
        assertEquals("main", items.first().key)
        assertEquals("one run header for the two members", 1, items.count { it.header })
        assertEquals("keys must be unique", items.size, items.map { it.key }.toSet().size)
        assertTrue(
            "the picker addresses an agent by the id it was given",
            items.any { it.agentId == "af7ca864cee1939de" },
        )
    }

    @Test
    fun `an agent's own transcript is an ordinary TranscriptPage`() {
        val p = json.decodeFromString<TranscriptPage>(fixture("agent-transcript.json"))
        assertTrue("expected events", p.events.isNotEmpty())
        assertTrue("nextOffset drives the agent cursor", p.nextOffset > 0)
        assertEquals("Opus 5", p.modelDisplay)
        // The route echoes the id it VALIDATED, which is the prefixed form even
        // when the bare one was asked for — so a page can be attributed to the
        // chip it came from without the caller re-deriving anything.
        assertEquals("agent-3f9c1a", p.agentId)
        assertEquals("wf_01H9ZKQT", p.workflowId)
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
        // ⚠ THE DAEMON'S WORDS. `ok` and `invalid_grant` are emitted by nothing —
        // `lib/oauth-refresh.js` writes `refreshed`, `not_needed`,
        // `active_skipped`, `refresh_token_expired`, `known_dead_refresh_token`,
        // `lock_busy`, `refresh_failed` and the rest.
        val refreshVocab = setOf(
            "refreshed", "not_needed", "active_skipped", "no_refresh_token", "not_refreshable",
            "refresh_token_expired", "known_dead_refresh_token", "account_on_hold",
            "lock_busy", "lock_timeout", "lock_error", "lock_compromised",
            "refresh_failed", "no_such_profile",
        )
        saved.accounts.forEach {
            assertTrue(
                "unknown refresh word '${it.refresh?.lastStatus}'",
                it.refresh?.lastStatus in refreshVocab,
            )
        }
        assertEquals("refresh_token_expired", expired.refresh?.lastStatus)
        assertNotNull("set once by an invalid_grant and carried forward", expired.refresh?.deadAt)
        assertNotNull("expiry drives the Refresh button", expired.expiresAt)
        assertNotNull("past this, only a re-login helps", expired.refreshTokenExpiresAt)
        // Every refresh clock is millis, in the same object as the millisecond
        // `expiresAt` it is computed from (nextAt = expiresAt − skew).
        saved.accounts.mapNotNull { it.refresh?.lastAt }.forEach {
            assertTrue("refresh.lastAt is millis", it > 1_000_000_000_000L)
        }
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
