package com.silencelen.huginn

import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.ChatList
import com.silencelen.huginn.data.AppList
import com.silencelen.huginn.data.AppRefusal
import com.silencelen.huginn.data.DeviceList
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectDetail
import com.silencelen.huginn.data.ProjectList
import com.silencelen.huginn.data.ProjectManifest
import com.silencelen.huginn.data.ProjectRefusal
import com.silencelen.huginn.data.RoundList
import com.silencelen.huginn.data.SavedAccounts
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.SessionList
import com.silencelen.huginn.data.SpawnResult
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.ui.AppRules
import com.silencelen.huginn.ui.HeadroomRules
import com.silencelen.huginn.ui.PlanFormat
import com.silencelen.huginn.ui.ProjectRules
import com.silencelen.huginn.ui.StreamPicker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        // ⚠ THE FIELD NAME IS THE CONTRACT. appd 3.0.5 sends the counter's epoch
        // beside the tally; get the spelling wrong and the phone silently keeps
        // comparing counts across a host-side restart — which is exactly the bug
        // that produced "1274 of 916 pushes arrived". See PushTally.
        assertEquals("pe_01HQZ8K4", live.pushEpoch)
        // And a pre-3.0.5 daemon sends none. Null, not "", so the reconciliation
        // can tell "no epoch known" from "a new epoch called nothing".
        assertNull("an older daemon has no epoch to send", never.pushEpoch)
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

        // Two of the five are still writing; the other three — failed, done and
        // the orphan — fold behind the pill rather than crowding the strip.
        val now = 1_789_460_000L
        val items = StreamPicker.items(info.agents, now)
        assertEquals("main", items.first().key)
        assertEquals("only the live members earn a header", 1, items.count { it.header })
        assertEquals("keys must be unique", items.size, items.map { it.key }.toSet().size)
        assertEquals("the settled three are counted, not dropped", 3, items.single { it.overflow }.count)
        assertTrue("nothing settled has a chip of its own", items.none { it.agentId == "c0d4e8" })

        val open = StreamPicker.items(info.agents, now, expanded = true)
        assertEquals("keys stay unique across the fold", open.size, open.map { it.key }.toSet().size)
        assertTrue(
            "the picker addresses an agent by the id it was given",
            open.any { it.agentId == "af7ca864cee1939de" },
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

    // -------------------------------------------------- projects + apps (Wave 3)
    //
    // ⚠⚠ THESE FIXTURES ARE THE DAEMON'S OWN OUTPUT, NOT A CONTRACT WRITTEN DOWN.
    // Every one of them was generated by running `server/appd/lib/projects.js`
    // and `lib/apps.js` — projectRow, joinMembers, dashboardMemberRow,
    // aggregateDashboard, memberRow, parseManifest, seedApps, appRow,
    // approvalCard — over the inputs those modules' own tests use
    // (test/projects.test.js, test/routes-projects.test.js,
    // test/apps.test.js, test/routes-apps.test.js). So a field renamed on
    // the daemon side changes these files, and the assertions below go red rather
    // than a phone going blank.
    //
    // ⚠⚠ THERE ARE TWO NAMES PER SESSION AND THEY ARE NOT INTERCHANGEABLE.
    // `name` is the TMUX name (`statusflap-db`), which is how every other route
    // on this daemon addresses a session. `claudeName` is the PEER name
    // (`statusflap/db`), which is what `--name` was given and what a peer's
    // SendMessage takes. A client that showed one where the other was meant is a
    // client whose Message control addresses nothing.
    //
    // ⚠⚠ EVERY CLOCK ON THESE TWO ROUTES IS EPOCH SECONDS, and it is asserted
    // from both directions. The headroom routes are milliseconds, the session
    // routes are seconds, and there is no field-name tell between them — which is
    // exactly how a fixture once carried seconds beside the daemon's millis and
    // the contract test agreed with itself and with nothing else.

    @Test
    fun `the project list decodes as ROWS with the daemon's own counts`() {
        val list = json.decodeFromString<ProjectList>(fixture("projects.json"))
        assertEquals("this is the ?all=1 answer, so the archived one is in it", 3, list.projects.size)
        assertEquals("the cap rides the wire so a client can say what it is", 64, list.max)

        // ⚠ THE LIST ROUTE CARRIES NO MEMBERSHIP. It is rows — counts the daemon
        // summed across three registries — and a client that expected a member
        // array here would draw every cluster as empty.
        val proposed = list.projects.first { it.id.endsWith("a1") }
        assertEquals("LoRa sensor stick", proposed.name)
        assertEquals("lora-stick", proposed.slug)
        assertEquals("hardware", proposed.kind)
        assertEquals("proposed", proposed.status)
        assertEquals(0, proposed.memberCount)
        assertEquals("lora-stick-lead", proposed.lead?.name)
        assertEquals("lora-stick/lead", proposed.lead?.claudeName)
        assertEquals(java.lang.Boolean.TRUE, proposed.lead?.present)
        assertEquals(2, proposed.manifestRev)
        assertEquals(
            "two sessions: docs writes the README, fw brings up the radio",
            proposed.manifestSummary,
        )
        assertTrue("a proposed row with a rev is a card", ProjectRules.hasProposal(proposed))

        // ⚠ THE COUNTS EXCLUDE THE LEAD, and they are the daemon's. Four members
        // on the record; the ended one's `claude -p` row is never a member, so
        // three are alive; one is busy and one needs a person.
        val active = list.projects.first { it.id.endsWith("b2") }
        assertEquals("active", active.status)
        assertEquals(4, active.memberCount)
        assertEquals(3, active.alive)
        assertEquals(1, active.busy)
        assertEquals(1, active.waiting)
        assertEquals("1 of 3 working · 1 needs you · 1 not running", ProjectRules.rollupWords(active))
        assertTrue("the untagged block is the injection signal and is carried", active.untaggedSeen)
        assertNotNull("said on the row, because only a person can fix it", ProjectRules.manifestCaution(active))
        assertFalse("an ACTIVE project is never re-proposed", ProjectRules.hasProposal(active))

        // ⚠ ARCHIVED IS TERMINAL, and the daemon says WHY in its own words.
        val over = list.projects.first { it.id.endsWith("c3") }
        assertEquals("archived", over.status)
        assertFalse(ProjectRules.live(over))
        assertEquals("the lead session is gone", over.endedReason)
        // Live first, then by name: the row you tapped is the row that is there.
        assertEquals(
            listOf("lora-stick", "statusflap", "auvik-lab"),
            ProjectRules.orderedProjects(list.projects).map { it.slug },
        )

        // ⚠ ON THE RAW JSON, because a model default cannot tell an absent key
        // from a null one, and the drift is about presence: every row carries all
        // of these, even when three of them are null.
        val rows = json.parseToJsonElement(fixture("projects.json"))
            .jsonObject["projects"]!!.jsonArray.map { it.jsonObject }
        listOf("id", "name", "slug", "kind", "status", "cwd", "memberCount", "alive", "busy", "waiting",
            "lead", "manifestRev", "spawnedRev", "manifestSummary", "untaggedSeen", "endedReason",
            "createdAt", "updatedAt", "rev")
            .forEach { key -> rows.forEach { assertTrue("every project row carries $key", key in it) } }

        // ⚠ THE REV THAT WAS CARRIED OUT, BESIDE THE ONE BEING PROPOSED.
        // `manifestRev > spawnedRev` is "this proposal is still waiting for an
        // answer", which a list could otherwise only learn by GETting every
        // project — and without it a row redrawn from a stale notification offers
        // Spawn on a cluster that is already running.
        val proposal = list.projects.first { it.slug == "lora-stick" }
        assertEquals(2, proposal.manifestRev)
        assertEquals(0, proposal.spawnedRev)
        assertFalse("rev 2 has never been spawned", ProjectRules.alreadySpawned(proposal))
        val running = list.projects.first { it.slug == "statusflap" }
        assertEquals(1, running.manifestRev)
        assertEquals(1, running.spawnedRev)
        assertTrue("rev 1 is already running", ProjectRules.alreadySpawned(running))

        // Seconds, not millis. Both directions: too small for a millisecond clock,
        // too large to be anything but an epoch.
        list.projects.forEach {
            assertTrue("createdAt is epoch SECONDS", it.createdAt in 1_000_000_000..9_999_999_999)
        }
    }

    @Test
    fun `one project decodes as the record, the row and the live join together`() {
        val text = fixture("project-detail.json")
        // ⚠ AN ENVELOPE, NOT A SPREAD RECORD, AND IT IS ONE DECODE. The route used
        // to spread the project into the top level; the day a project grew a field
        // called `row` or `live` that spread would have overwritten the daemon's
        // own with nothing to see in the diff.
        val detail = json.decodeFromString<ProjectDetail>(text)
        val p = detail.project

        assertEquals(
            "three keys, and nothing of the record beside them",
            setOf("project", "row", "live"),
            json.parseToJsonElement(text).jsonObject.keys,
        )
        assertEquals("Status page flap", p.name)
        assertEquals("statusflap", p.slug)
        assertEquals("active", p.status)
        assertTrue("the brief is the whole first message the lead got", p.brief.contains("flaps"))
        assertEquals("/root/netplan/status-page", p.cwd)
        assertEquals(4, p.rev)

        // The membership record: names in BOTH grammars, and how each was started.
        assertEquals(4, p.members.size)
        val db = p.members.first { it.role == "db" }
        assertEquals("statusflap-db", db.name)
        assertEquals("statusflap/db", db.claudeName)
        assertEquals("opus", db.model)
        assertEquals("high", db.effort)
        assertEquals("act", db.mode)
        assertEquals("profile the checks table", db.firstPrompt)

        // ⚠ THE MANIFEST IS STRUCTURED. The daemon parsed the lead's fenced block;
        // the card renders the result and never re-reads a fence.
        val m = p.manifest!!
        assertEquals(1, m.rev)
        assertEquals("four sessions: db, web, probe and docs", m.summary)
        assertEquals(listOf("db", "web", "probe", "docs"), m.sessions.map { it.role })
        assertEquals("opus · high effort · act mode", ProjectRules.sessionWords(m.sessions.first()))
        assertEquals("the host's own defaults", ProjectRules.sessionWords(m.sessions[1]))
        assertTrue("this rev is already running", ProjectRules.alreadySpawned(m))

        // ⚠⚠ THE TAG IS NOT ON THE WIRE AT ALL ANY MORE. It is the lead's
        // anti-injection secret, stripped by [publicProject] at every send site;
        // a body that carried it would publish it to every client on this port,
        // after which a planted `huginn-project` block spawns twelve sessions
        // with prompts a stranger wrote. Swept across every fixture below.
        assertFalse(
            "the daemon strips it",
            "tag" in json.parseToJsonElement(text).jsonObject["project"]!!
                .jsonObject["manifest"]!!.jsonObject,
        )

        assertEquals("the row rides along, already summed", 3, detail.row!!.alive)
        assertEquals("the lead and its four members", 5, detail.live.size)

        // ⚠ THE LIVE JOIN IS BY SESSION ID. `sid-db`'s native row carries a `tmux`
        // label pointing at a DIFFERENT session and a stranger's row claims
        // `statusflap-db` — a join by that field reports the stranger's state on a
        // dashboard that looks entirely plausible.
        val liveDb = detail.live.first { it.role == "db" }
        assertEquals("busy", liveDb.status)
        assertEquals("statusflap/db", liveDb.nativeName)
        assertEquals("running", ProjectRules.stateWord(liveDb))

        // `waiting` + waitingFor is the needs-you, and it says what for.
        val liveWeb = detail.live.first { it.role == "web" }
        assertEquals("waiting", liveWeb.status)
        assertEquals("input needed", liveWeb.waitingFor)
        assertTrue(liveWeb.needsYou)
        assertEquals("needs you — input needed", ProjectRules.memberWords(liveWeb))

        // ⚠ AN UNKNOWN NATIVE WORD IS NULL, NOT A GUESS — and the title hook's
        // own `idle` is what is left to draw the row with.
        val liveProbe = detail.live.first { it.role == "probe" }
        assertNull("a word this daemon has never seen is not mapped onto busy", liveProbe.status)
        assertEquals("idle", ProjectRules.stateWord(liveProbe))

        // ⚠ A `claude -p` THAT REGISTERED FOR FOUR SECONDS IS NEVER A MEMBER: the
        // ended docs row joins to nothing, so it is neither alive nor marked.
        val liveDocs = detail.live.first { it.role == "docs" }
        assertFalse("entrypoint sdk-cli is never joined", liveDocs.alive)
        assertNotNull(liveDocs.endedAt)
        assertNull("an ended member keeps its row and loses its mark", ProjectRules.stateWord(liveDocs))
        assertEquals("ended", ProjectRules.memberWords(liveDocs))

        // ⚠ NEEDS-YOU FIRST, THEN THE LEAD. That order is the screen's reason to
        // exist; role breaks every tie so the rows hold still between polls.
        assertEquals(
            listOf("web", "lead", "db", "probe", "docs"),
            ProjectRules.ordered(detail.live).map { it.role },
        )

        val liveRows = json.parseToJsonElement(text).jsonObject["live"]!!.jsonArray.map { it.jsonObject }
        listOf("role", "name", "claudeName", "sessionId", "present", "alive", "status", "waitingFor",
            "needsYou", "state", "stateSince", "pendingSends", "headroom", "title", "lead")
            .forEach { key -> liveRows.forEach { assertTrue("every live row carries $key", key in it) } }
    }

    @Test
    fun `a dashboard decodes its totals, its pace and its members`() {
        val d = json.decodeFromString<ProjectDashboard>(fixture("project-dashboard.json"))
        assertEquals("Status page flap", d.project?.name)
        assertEquals("the lead and its four members", 5, d.members.size)
        // ⚠ `generatedAt`, NOT `updatedAt`: when this poll was answered, which is
        // the only honest thing to say about a rollup of five transcripts.
        assertTrue("generatedAt is epoch SECONDS", d.generatedAt in 1_000_000_000..9_999_999_999)

        val db = d.members.first { it.role == "db" }
        assertEquals("statusflap/db", db.claudeName)
        assertEquals("statusflap-db", db.name)
        // The headroom cell is the one number a twelve-session cluster needs.
        assertEquals("fable", db.headroom?.family)
        assertEquals("opus", db.headroom?.ladder)
        assertEquals(14, db.turns)
        assertEquals(41_000L, db.tokens.input)
        assertEquals(2.6, db.estCostUsd!!, 0.001)
        // ⚠ THE AGENT COUNT AND NO IDS. An agent id is scoped to the session that
        // spawned it, so a project-wide id would address nothing from here.
        assertEquals(2, db.agentCount)

        val web = d.members.first { it.role == "web" }
        assertTrue("a stalled member must decode", web.headroom!!.stalled)
        assertFalse("auto-resume off must survive", web.headroom!!.autoResume)
        assertNull("a member the daemon has not walked has no cost", web.estCostUsd)
        assertEquals(2, web.pendingSends)

        // The project-wide sums, which is what makes the shared StatsHeader usable.
        assertEquals(31, d.totals?.turns)
        assertEquals(4.18, d.totals!!.estCost!!.usd, 0.001)
        assertEquals(listOf("claude-fable-5-1", "claude-opus-5"), d.totals!!.models)
        // ⚠ WALL TIME IS A SPAN, NOT A SUM. Five sessions running for two hours
        // each took two hours, not ten.
        assertEquals(7_200_000L, d.totals!!.wallMs)

        // ⚠ TOKENS PER MINUTE, OVER A 10- OR 60-MINUTE WINDOW — the members'
        // per-minute rates added, which is still per minute. The aggregate briefly
        // spelled these `tokensPer10m`, which reads as "tokens per 10 minutes" and
        // is wrong by a factor of ten against the very line that renders it
        // ("N tokens/min over 10m").
        assertEquals(1800L, d.rate!!.tokensPerMin10)
        assertEquals(1400L, d.rate!!.tokensPerMin60)
        assertTrue(d.rate!!.activeRecently)
        val rawRate = json.parseToJsonElement(fixture("project-dashboard.json")).jsonObject["rate"]!!.jsonObject
        assertTrue("the per-minute spelling", "tokensPerMin10" in rawRate)
        assertFalse("never the per-window lie", "tokensPer10m" in rawRate)

        // The rollup on the dashboard is the SAME sentence the list row carries,
        // and it comes off the daemon's own row rather than being recounted here.
        assertEquals(
            "1 of 3 working · 1 needs you · 1 not running",
            ProjectRules.rollupWords(d.project!!),
        )
    }

    @Test
    fun `a spawn that partly failed is a 200 with ok false, and says which role`() {
        // ⚠⚠ THE FAIL-FIRST CASE. Spawning is a loop over tmux: the third role
        // failing does not un-spawn the first two, so the daemon carries on and
        // answers HTTP 200 with `ok:false`. A client that read the status code as
        // the verdict would report a working cluster as a failure.
        val r = json.decodeFromString<SpawnResult>(fixture("spawn-result.json"))
        assertFalse("not ok — one of them did not happen", r.ok)
        assertEquals(listOf("db", "web"), r.spawned.map { it.role })
        assertEquals(1, r.failed.size)
        assertEquals("probe", r.failed.first().role)
        // ⚠ THE DAEMON'S OWN SENTENCE. "duplicate session: statusflap-probe"
        // tells somebody what to do; a client's summary of it does not.
        assertEquals("duplicate session: statusflap-probe", r.failed.first().reason)
        assertEquals("2 of 3 started · 1 failed", ProjectRules.spawnWords(r))
        assertEquals(
            listOf("probe — duplicate session: statusflap-probe"),
            ProjectRules.spawnFailures(r),
        )
        // The peer names, which is what the lead is told to address them by.
        assertEquals(listOf("statusflap/db", "statusflap/web"), ProjectRules.spawnedNames(r))
        // The project rides along, so the card can redraw without a second call.
        assertEquals("active", r.project?.status)

        val raw = json.parseToJsonElement(fixture("spawn-result.json")).jsonObject
        listOf("ok", "spawned", "failed", "project").forEach {
            assertTrue("a spawn answer carries $it", it in raw)
        }
        // ⚠ THE FAILURE IS KEYED BY ROLE, NOT BY NAME. A role that failed has no
        // tmux session to name, which is exactly why it failed.
        val failed = raw["failed"]!!.jsonArray.single().jsonObject
        assertTrue("role" in failed && "reason" in failed)
    }

    /**
     * ⚠ THREE REFUSALS SHARE ONE STATUS AND THEY HAVE THREE DIFFERENT FIXES.
     *
     * `POST /v1/projects` answers 409 for a working directory Claude Code has not
     * been trusted in, for a slug another project already holds, and for a tmux
     * session already wearing the lead's name — trust the directory, pick another
     * name, or go and end that session. A client cannot tell them apart from a
     * sentence, so the daemon sends `reason` as the discriminator beside the
     * sentence a person reads, and a sheet marks the field the fix belongs under.
     *
     * ⚠ NULLABLE, BECAUSE AN OLDER DAEMON SENDS NO REASON AT ALL. The sentence is
     * still the whole fix, so an unknown refusal must stay showable rather than
     * decode into a guess.
     */
    @Test
    fun `a create refusal carries the reason beside the sentence`() {
        val untrusted = json.decodeFromString<ProjectRefusal>(
            """{"error":"/srv/x has not been trusted in Claude Code yet - open it once with """ +
                """`claude` there and accept the folder-trust question, then create the project",""" +
                """"reason":"untrusted-cwd"}""",
        )
        assertEquals("untrusted-cwd", untrusted.reason)
        assertTrue("the sentence is the fix and must survive", untrusted.error!!.contains("folder-trust"))

        val slugTaken = json.decodeFromString<ProjectRefusal>(
            """{"error":"there is already a project with that slug","reason":"slug-taken"}""",
        )
        assertEquals("slug-taken", slugTaken.reason)

        val nameTaken = json.decodeFromString<ProjectRefusal>(
            """{"error":"a tmux session called 'stick-lead' already exists","reason":"name-taken"}""",
        )
        assertEquals("name-taken", nameTaken.reason)

        // A daemon older than the discriminator: the sentence, and no branch.
        val older = json.decodeFromString<ProjectRefusal>("""{"error":"that slug is taken"}""")
        assertNull("an unknown refusal is not guessed at", older.reason)
    }

    /**
     * ⚠⚠ THE MANIFEST TAG IS NOT ON THIS PORT, ON ANY ROUTE.
     *
     * It is minted per project, lives in exactly two places — the lead's system
     * prompt and the daemon's store — and is the ENTIRE control that keeps a
     * `huginn-project` block found in a log, a page or a file the lead happened to
     * READ from being acted on as the lead's own proposal. Seven send sites hand
     * back a project record, and the daemon strips it at every one of them
     * ([publicProject]); a body that carried it would publish it to every client
     * on this port and, through them, to anything that can read one, after which a
     * planted block spawns twelve sessions with attacker-written first prompts.
     *
     * Swept over EVERY fixture rather than the ones that obviously carry a
     * project, because the leak this guards against is a route nobody thought of
     * as a project route. And asserted on the MODEL too: a field for it is a field
     * something eventually renders.
     */
    @Test
    fun `no fixture body carries the manifest tag, and no model has a field for it`() {
        val dir = File(javaClass.classLoader!!.getResource("projects.json")!!.toURI()).parentFile!!
        val fixtures = dir.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }
        assertTrue("the fixtures must be enumerable, or this sweeps nothing", fixtures.size >= 20)
        fixtures.forEach { f ->
            val found = mutableListOf<String>()
            fun walk(el: JsonElement, path: String) {
                when (el) {
                    is JsonObject -> el.forEach { (k, v) ->
                        if (k == "tag") found += "$path.$k"
                        walk(v, "$path.$k")
                    }
                    is JsonArray -> el.forEachIndexed { i, v -> walk(v, "$path[$i]") }
                    else -> Unit
                }
            }
            walk(json.parseToJsonElement(f.readText()), f.name)
            assertEquals("a body publishes the lead's anti-injection secret", emptyList<String>(), found)
        }
        val fields = ProjectManifest.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }
        }
        assertFalse("nowhere to put it if one ever arrived", "tag" in fields)
    }

    /**
     * ⚠⚠ THE FIXTURE IS THE CONTRACT, WRITTEN OUT BY HAND. The daemon half of
     * `/v1/apps` is being built alongside this one, so there is no shared file to
     * generate from and no round trip that would catch a disagreement — every
     * field name here is a LITERAL taken from the agreed contract, and a drift on
     * either side shows up as a failure at merge rather than as an empty row on a
     * phone.
     */
    @Test
    fun `apps decode with the registry, the icon claim and the row with no verdict`() {
        val list = json.decodeFromString<AppList>(fixture("apps.json"))
        assertEquals(4, list.apps.size)
        val nowMs = 1_789_460_000_000L

        // The registry's own facts, which an editor needs and a row does not.
        assertEquals(32, list.max)
        assertFalse("the retrofit is still outstanding on this host", list.retrofitApplied)
        assertEquals(
            "the addresses the rows were measured against",
            listOf("192.168.7.31", "100.97.198.90"),
            list.clientAddresses,
        )
        // ⚠ THE CLIENT'S COPY OF THE VOCABULARY IS THE ONE THAT DECIDES what a
        // chip says, so it must BE the daemon's list rather than resemble it.
        assertEquals(listOf("dashboard", "tool", "docs", "lab", "other"), list.kinds)
        assertEquals("the client mirrors the daemon's kinds", AppRules.KINDS, list.kinds)
        assertEquals("and the picker offers the daemon's, not the mirror", list.kinds, AppRules.kindChoices(list))
        list.apps.forEach {
            val k = it.kind
            assertTrue("${it.id}'s kind is one the daemon can store", k != null && k in list.kinds)
        }

        val armap = list.apps.first { it.id == "armap" }
        assertEquals("http://huginn:8088/", armap.url)
        assertEquals("armap.service", armap.unit)
        assertEquals(3, armap.version)
        assertTrue("this row has a favicon, so the row will ask for it", armap.icon)
        assertEquals(
            "up from the host · checked 1m ago",
            AppRules.reachabilityWords(armap.up, armap.lastProbeAt, nowMs),
        )
        assertEquals("12 ms · HTTP 200", AppRules.probeDetail(armap))
        assertNull("the seeded rows are legal addresses", AppRules.urlProblem(armap.url))
        assertEquals(AppRules.DeviceReach.REACHABLE, AppRules.deviceReach(armap))
        assertFalse("nothing wrong, nothing to disclose", AppRules.failing(armap))

        // ⚠⚠ UP, AND STILL UNREACHABLE FROM HERE — the whole reason the second
        // axis exists (decision 54). A 403 page is UP: something answered. It
        // answered on the tailnet address and refused the connection on the LAN
        // one, so the row is up AND needs retrofit, and both have to survive.
        val jtyper = list.apps.first { it.id == "jtyper" }
        assertEquals(java.lang.Integer.valueOf(403), jtyper.httpStatus)
        assertEquals(AppRules.Reach.UP, AppRules.reach(jtyper))
        assertEquals(AppRules.DeviceReach.RETROFIT, AppRules.deviceReach(jtyper))
        assertTrue("a failing row has something to open", AppRules.failing(jtyper))
        assertFalse("no favicon cached, so the tile is drawn", jtyper.icon)
        assertEquals("J", AppRules.iconInitial(jtyper))
        // ⚠ LITERALS, not a count. These are lines somebody pastes into a root
        // shell to rebind a unit, and a drifted port or a reordered pair would
        // sail through an assertion about length.
        // ⚠ THE `#` LINES ARE PART OF THE FIX, not decoration this side may
        // strip: they are what says which MACHINE the next three run on, and the
        // firewall half runs on a different one.
        assertEquals(
            listOf(
                "# on huginn — bind the unit to 0.0.0.0",
                "systemctl edit jtyper-trainer.service   # ExecStart: bind 0.0.0.0 instead of the tailnet address",
                "systemctl restart jtyper-trainer",
                "ss -ltnp | grep 8091",
                "# on heimdall — allow the port in /etc/pve/firewall/117.fw",
                "IN ACCEPT -source 192.168.2.131 -p tcp -dport 8091 -log nolog",
            ),
            AppRules.fixLines(jtyper),
        )
        assertEquals(
            "192.168.7.31:8091 — connection refused",
            AppRules.addressWords(jtyper.reachable.addresses.first { !it.ok }),
        )

        // Accepted the connection and never answered: down, with the elapsed time
        // still reported, because "failed in 2000 ms" is a different story from
        // "failed in 1 ms".
        val board = list.apps.first { it.id == "board" }
        assertEquals(AppRules.Reach.DOWN, AppRules.reach(board))
        assertEquals(java.lang.Integer.valueOf(2000), board.latencyMs)
        assertNull("nothing answered, so there is no status to report", board.httpStatus)
        // ⚠⚠ A NULL DEVICE VERDICT WITH A REASON. On this daemon `ok = null`
        // means the PROBE SET WAS EMPTY — no usable bind address and no tailnet
        // address to try — so "not checked yet" on its own would leave the reader
        // waiting for a check that is never going to run. The row is not failing,
        // and it still has something to open.
        assertNull(board.reachable.ok)
        assertEquals("no address to probe yet", AppRules.reachNote(board))
        assertFalse("nothing is accused", AppRules.failing(board))
        assertTrue("but there is an answer to read", AppRules.expandable(board))
        assertEquals("not \"Why\" — nothing did anything wrong", "Details", AppRules.expandVerb(board))

        // ⚠⚠ THE THIRD STATE, AND THE WHOLE POINT OF THE FIXTURE CARRYING IT. The
        // daemon keeps probe state in memory, so after every restart EVERY row
        // looks like this. It is not a row that is down.
        val btc = list.apps.first { it.id == "btc15m" }
        assertNull("no verdict yet", btc.up)
        assertEquals("never probed is 0, not null and never 1970", 0L, btc.lastProbeAt)
        assertEquals("not checked yet", AppRules.reachabilityWords(btc.up, btc.lastProbeAt, nowMs))
        assertFalse(
            "an unchecked app must never be accused",
            AppRules.reachabilityWords(btc.up, btc.lastProbeAt, nowMs).contains("not answering"),
        )
        assertNull("and the device verdict is unchecked too, not failing", btc.reachable.ok)
        assertFalse(AppRules.failing(btc))
        assertFalse("no note either, so nothing to open", AppRules.expandable(btc))
        assertEquals("the daemon sends \"\" for none, never null", "", btc.unit)
        assertNull("which the row reads as absent", AppRules.subtitle(btc).takeIf { it.contains("service") })
        // Said ONCE, not twice, on a row where neither question has an answer.
        assertEquals("not checked yet", AppRules.rowWords(btc, nowMs))

        // The page's one transition line.
        assertEquals(
            "Retrofit not applied · 1 of 4 needs it · your devices arrive on 192.168.7.31, 100.97.198.90",
            AppRules.retrofitNote(list),
        )

        // ⚠ RAW-JSON PRESENCE, for the reason the project rows have it: a model
        // default cannot tell an absent key from a null one, and `up` is always a
        // key — a renamed one would read as an unchecked app forever. `icon` and
        // `reachable` are the two new ones, and both default to the quiet answer,
        // which is exactly the failure a default hides.
        val rows = json.parseToJsonElement(fixture("apps.json"))
            .jsonObject["apps"]!!.jsonArray.map { it.jsonObject }
        listOf("id", "name", "url", "kind", "notes", "unit", "addedAt", "version", "up", "lastProbeAt",
            "latencyMs", "httpStatus", "icon", "reachable")
            .forEach { key -> rows.forEach { assertTrue("every app carries $key", key in it) } }
        rows.forEach {
            val r = it["reachable"]!!.jsonObject
            listOf("ok", "checkedAt", "addresses", "fix", "note")
                .forEach { key -> assertTrue("every reachability carries $key", key in r) }
        }
        // ⚠ EVERY STAMP ON THIS ROUTE IS EPOCH SECONDS — `addedAt`, `lastProbeAt`
        // and `reachable.checkedAt` alike. The time words take seconds already;
        // one of these multiplied by 1000 renders as a date in the year 58000 and
        // one divided renders as 1970, and neither throws.
        list.apps.forEach {
            assertTrue("addedAt is epoch SECONDS", it.addedAt in 1_000_000_000..9_999_999_999)
            assertTrue(
                "lastProbeAt is epoch SECONDS or 0",
                it.lastProbeAt == 0L || it.lastProbeAt in 1_000_000_000..9_999_999_999,
            )
            assertTrue(
                "checkedAt is epoch SECONDS or 0",
                it.reachable.checkedAt == 0L || it.reachable.checkedAt in 1_000_000_000..9_999_999_999,
            )
        }
        // And there is no `reachableFrom` or `probeIntervalMs` on this body any
        // more: the first is answered per row by `reachable`, the second was
        // never read by a client.
        val body = json.parseToJsonElement(fixture("apps.json")).jsonObject
        assertFalse("reachableFrom" in body)
        assertFalse("probeIntervalMs" in body)
    }

    /**
     * ⚠⚠ THE 422 BODY, WHICH IS AN ANSWER AND NOT AN ERROR (decision 54).
     *
     * The daemon refuses an add until the app answers on every address clients
     * arrive on, and the refusal carries the evidence and the fix. Decoded wrong
     * this becomes one flattened exception message, the form empties, and the
     * person is told "422" about an address they typed correctly.
     */
    @Test
    fun `a refused add decodes as a refusal carrying its addresses and its fix`() {
        val refusal = json.decodeFromString<AppRefusal>(fixture("app-422.json"))
        assertEquals(
            "boardserver answers here but not on the addresses your devices arrive from",
            refusal.error,
        )
        val r = refusal.reachable
        assertNotNull("the prerequisite that failed comes with it", r)
        assertEquals(java.lang.Boolean.FALSE, r!!.ok)
        assertEquals(3, r.addresses.size)
        assertEquals(
            "it answered on the loopback and nowhere a device is",
            listOf(true, false, false),
            r.addresses.map { it.ok },
        )
        assertEquals("connection refused", r.addresses[1].error)
        assertEquals(
            listOf(
                "# on huginn — bind the unit to 0.0.0.0",
                "systemctl edit boardserver.service   # ExecStart: bind 0.0.0.0 instead of 127.0.0.1",
                "systemctl restart boardserver",
                "ss -ltnp | grep 8092",
            ),
            r.fix,
        )
        // And the form keeps every character while it shows them.
        val typed = com.silencelen.huginn.data.AppForm(
            name = "Board view",
            url = "http://huginn:8092/",
            kind = "tool",
            unit = "boardserver.service",
        )
        val kept = typed.refused(
            com.silencelen.huginn.data.AppCreate(app = null, refusal = refusal.error, reachable = r),
        )
        assertEquals("http://huginn:8092/", kept.url)
        assertEquals(r.fix, kept.fix)
        assertTrue("and it is still sendable once the lines have been run", kept.sendable)
    }

    /**
     * ⚠ THE ONE-RELEASE FALLBACK, against the body the LIVE daemon still serves
     * (decision 56). `consoles.json` is appd 3.5's `/v1/consoles` verbatim; the
     * rows land as apps with no icon and no device verdict, which is exactly what
     * is true of a daemon that cannot answer either question.
     */
    @Test
    fun `the pre-rename body still fills the Apps page`() {
        val legacy = json.parseToJsonElement(fixture("consoles.json")).jsonObject
        assertTrue("the old body is keyed `consoles`", "consoles" in legacy)
        assertFalse("and carries none of the new list fields", "retrofitApplied" in legacy)
        val rows = legacy["consoles"]!!.jsonArray.map { it.jsonObject }
        rows.forEach {
            assertFalse("no row claims an icon", "icon" in it)
            assertFalse("and none carries a reachability verdict", "reachable" in it)
        }
        // The translation itself is asserted on the wire in AppClientTest; this
        // pins the SHAPE it has to translate, so a daemon that quietly reshaped
        // the alias is caught here rather than by an empty page.
        assertTrue("the marker fact the fallback reads retrofitApplied from", "approval" in legacy)
        assertTrue("applied" in legacy["approval"]!!.jsonObject)
    }

    /**
     * ⚠ A PEER MESSAGE IS A SYSTEM NOTE, NOT A USER BUBBLE (decision 50).
     *
     * Today's transcript reader knows nothing about `<cross-session-message`, so a
     * teammate session's message renders as though the OWNER had typed it — the
     * D-G failure, on a surface where the sessions talk constantly. The chosen
     * shape degrades safely: an older client that has never heard of `peer` still
     * draws a system note, because the `kind` alone already says what it is.
     */
    @Test
    fun `a peer message decodes as a system note that knows who sent it`() {
        val p = json.decodeFromString<TranscriptPage>(
            """{"events":[{"seq":7,"kind":"system","ts":1789459400,
               "text":"lora-stick/docs: README.md is written.",
               "peer":{"name":"lora-stick/docs","sessionId":"0123abcd-0000-4000-8000-00000000d004",
                       "pid":41207}}],
               "nextOffset":812}""",
        )
        val e = p.events.single()
        assertEquals("system", e.kind)
        // ⚠ LABEL FROM origin.name, NOT FROM THE RENDERED PREVIEW: the @handle
        // form slugifies the slash (`lora-stick/docs` → `@lora-stick-docs`), so
        // the text cannot be trusted to carry the addressable name.
        assertEquals("lora-stick/docs", e.peer?.name)
        assertNotNull("the sessionId is what a tap would open", e.peer?.sessionId)
        // ⚠ THE PID IS THE KERNEL-VERIFIED ONE, and the daemon sends it because it
        // is all a native peer record actually proves — the address of record is a
        // unix socket path, and the sessionId beside it is the daemon's own lookup
        // of that pid, which can come back null.
        assertEquals(java.lang.Integer.valueOf(41_207), e.peer?.pid)

        // And an ordinary system event still has none, rather than an empty one.
        val plain = json.decodeFromString<TranscriptPage>(
            """{"events":[{"seq":8,"kind":"system","text":"Compacted."}],"nextOffset":900}""",
        )
        assertNull("no peer block means no peer", plain.events.single().peer)
    }
}
