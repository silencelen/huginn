package com.silencelen.huginn

import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.ChatList
import com.silencelen.huginn.data.ConsoleList
import com.silencelen.huginn.data.DeviceList
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectList
import com.silencelen.huginn.data.RoundList
import com.silencelen.huginn.data.SavedAccounts
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.SessionList
import com.silencelen.huginn.data.SpawnResult
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.Watch
import com.silencelen.huginn.ui.ConsoleRules
import com.silencelen.huginn.ui.HeadroomRules
import com.silencelen.huginn.ui.PlanFormat
import com.silencelen.huginn.ui.ProjectRules
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

    // --------------------------------------------- projects + consoles (Wave 3)
    //
    // ⚠ ALIGNED TO THE CONTRACT, NOT CAPTURED. `/v1/projects` and `/v1/consoles`
    // both 404 on the live daemon: these routes are being built on two other
    // branches right now. So these fixtures are the WIRE CONTRACT WRITTEN DOWN —
    // the grammar this client will decode, pinned as literal JSON so that the
    // moment the daemon lands and disagrees, the disagreement is a red test here
    // instead of an empty screen on a phone. RE-CAPTURE LIVE AFTER DEPLOY.
    //
    // ⚠⚠ EVERY CLOCK ON THESE TWO ROUTES IS EPOCH SECONDS, and it is asserted
    // from both directions. The headroom routes are milliseconds, the session
    // routes are seconds, and there is no field-name tell between them — which is
    // exactly how a fixture once carried seconds beside the daemon's millis and
    // the contract test agreed with itself and with nothing else.

    @Test
    fun `projects decode with the tree and the manifest fields`() {
        val list = json.decodeFromString<ProjectList>(fixture("projects.json"))
        assertEquals(2, list.projects.size)

        val proposed = list.projects.first()
        assertEquals("LoRa sensor stick", proposed.name)
        assertEquals("/root/netplan/dev-ledger/lora-stick", proposed.cwd)
        // The peer name is the `--name <slug>/<role>` form; it is what SendMessage
        // addresses and what a peer message is labelled with.
        assertEquals("lora-stick/lead", proposed.lead?.name)
        assertNotNull("the lead's Claude session id", proposed.lead?.sessionId)
        assertTrue("a proposal with no members yet", proposed.members.isEmpty())
        assertEquals(2, proposed.manifest?.rev)
        assertTrue("the card leads with this", !proposed.manifest?.summary.isNullOrBlank())
        assertTrue("and renders this as text", proposed.manifest!!.text!!.contains("sessions:"))
        assertTrue(ProjectRules.hasProposal(proposed))

        val active = list.projects[1]
        assertEquals(4, active.members.size)
        val web = active.members.first { it.role == "web" }
        assertEquals("statusflap/web", web.name)
        assertEquals("attention", web.state)
        assertEquals(java.lang.Boolean.TRUE, web.needsYou)
        assertEquals(java.lang.Integer.valueOf(2), web.pendingSends)

        // ⚠ THE UNKNOWN WORD, DECODED AND THEN DROPPED. A newer daemon inventing a
        // fifth state must not fail this list, and must not be drawn as idle.
        val probe = active.members.first { it.role == "probe" }
        assertEquals("teleporting", probe.state)
        assertNull("an unknown state is no state", ProjectRules.stateWord(probe))
        assertNull("and it does not invent a needsYou", probe.needsYou)
        assertNull("nor a queue depth", probe.pendingSends)

        val docs = active.members.first { it.role == "docs" }
        assertNotNull("an ended member keeps its row", docs.endedAt)
        assertNull("but loses its mark", ProjectRules.stateWord(docs))

        assertEquals("1 of 3 working · 1 needs you · 1 ended", ProjectRules.rollupWords(active.members))

        // ⚠ ON THE RAW JSON, because a model default cannot tell an absent key
        // from a null one, and the drift is about presence: every member row
        // carries all seven, even when three of them are null.
        val rows = json.parseToJsonElement(fixture("projects.json"))
            .jsonObject["projects"]!!.jsonArray[1].jsonObject["members"]!!.jsonArray.map { it.jsonObject }
        listOf("name", "role", "sessionId", "spawnedAt", "state", "needsYou", "pendingSends", "lastActivityTs")
            .forEach { key -> rows.forEach { assertTrue("every member carries $key", key in it) } }

        // Seconds, not millis. Both directions: too small for a millisecond clock,
        // too large to be anything but an epoch.
        list.projects.forEach {
            assertTrue("createdAt is epoch SECONDS", it.createdAt in 1_000_000_000..9_999_999_999)
        }
    }

    @Test
    fun `a dashboard decodes its totals and its members`() {
        val d = json.decodeFromString<ProjectDashboard>(fixture("project-dashboard.json"))
        assertEquals("Status page flap", d.project?.name)
        assertEquals(3, d.members.size)
        assertTrue("updatedAt is epoch SECONDS", d.updatedAt in 1_000_000_000..9_999_999_999)

        val db = d.members.first { it.role == "db" }
        // The headroom cell is the one number a twelve-session cluster needs.
        assertEquals("fable", db.headroom?.family)
        assertEquals("opus", db.headroom?.ladder)
        assertEquals(2, db.streams.size)
        assertEquals("one agent still writing", 1, db.streams.count { it.active })
        // ⚠ THE BARE HEX, as `/agents` emits it — a prefixed id here would be a
        // fixture agreeing with a client that 400s on every chip.
        assertTrue("agent ids are bare", db.streams.none { it.id.startsWith("agent-") })
        assertEquals(14, db.totals?.turns)
        assertEquals(92, db.totals?.toolCalls)

        val web = d.members.first { it.role == "web" }
        assertTrue("a stalled member must decode", web.headroom!!.stalled)
        assertFalse("auto-resume off must survive", web.headroom!!.autoResume)
        assertNull("a member the daemon has not walked has no totals", web.totals)

        // The project-wide sums, which is what makes the shared StatsHeader usable.
        assertEquals(31, d.totals?.turns)
        assertEquals(4.18, d.totals!!.estCost!!.usd, 0.001)
        assertEquals(listOf("claude-fable-5-1", "claude-opus-5"), d.totals!!.models)
        assertTrue("the pace card needs this", d.rate!!.activeRecently)

        // The rollup on the dashboard is the SAME sentence the list row carries.
        assertEquals(
            "1 of 3 working · 1 needs you",
            ProjectRules.rollupWords(d.members.map { it.asMember() }),
        )
    }

    @Test
    fun `a spawn result decodes as partial rather than as a failure`() {
        val r = json.decodeFromString<SpawnResult>(fixture("spawn-result.json"))
        assertEquals(3, r.results.size)
        assertEquals(2, r.results.count { it.ok })
        val failed = r.results.first { !it.ok }
        assertEquals("statusflap/docs", failed.name)
        // ⚠ THE DAEMON'S OWN SENTENCE. "a session called statusflap-docs already
        // exists" tells somebody what to do; a client's summary of it does not.
        assertEquals("a session called statusflap-docs already exists", failed.error)
        assertEquals("2 of 3 started · 1 failed", ProjectRules.spawnWords(r))
    }

    @Test
    fun `consoles decode with the registry, the approval and the row with no verdict`() {
        val list = json.decodeFromString<ConsoleList>(fixture("consoles.json"))
        assertEquals(4, list.consoles.size)
        val nowMs = 1_789_460_000_000L

        // The registry's own facts, which an editor needs and a row does not.
        assertEquals(32, list.max)
        assertEquals("host", list.reachableFrom)
        assertEquals(300_000L, list.probeIntervalMs)
        // ⚠ THE CLIENT'S COPY OF THE VOCABULARY IS THE ONE THAT DECIDES what a
        // chip says, so it must BE the daemon's list rather than resemble it.
        assertEquals(listOf("dashboard", "tool", "docs", "lab", "other"), list.kinds)
        assertEquals("the client mirrors the daemon's kinds", ConsoleRules.KINDS, list.kinds)

        val armap = list.consoles.first { it.id == "armap" }
        assertEquals("http://huginn:8088/", armap.url)
        assertEquals("host", armap.reachableFrom)
        assertEquals(3, armap.version)
        assertEquals(
            "up from the host · checked 1m ago",
            ConsoleRules.reachabilityWords(armap.up, armap.lastProbeAt, nowMs),
        )
        assertEquals("12 ms · HTTP 200", ConsoleRules.probeDetail(armap))
        assertNull("the seeded rows are legal addresses", ConsoleRules.urlProblem(armap.url))

        // A 403 page is UP: something answered.
        val jtyper = list.consoles.first { it.id == "jtyper" }
        assertEquals(java.lang.Integer.valueOf(403), jtyper.httpStatus)
        assertEquals(ConsoleRules.Reach.UP, ConsoleRules.reach(jtyper))

        // A kind outside the daemon's five lands in `other`, not in a blank chip.
        val board = list.consoles.first { it.id == "board" }
        assertEquals(ConsoleRules.Reach.DOWN, ConsoleRules.reach(board))
        assertEquals("hardware", board.kind)
        assertEquals("other", ConsoleRules.kindWords(board.kind))

        // ⚠⚠ THE THIRD STATE, AND THE WHOLE POINT OF THE FIXTURE CARRYING IT. The
        // daemon keeps probe state in memory, so after every restart EVERY row
        // looks like this. It is not a row that is down.
        val btc = list.consoles.first { it.id == "btc15m" }
        assertNull("no verdict yet", btc.up)
        assertEquals("never probed is 0, not null and never 1970", 0L, btc.lastProbeAt)
        assertEquals("not checked yet", ConsoleRules.reachabilityWords(btc.up, btc.lastProbeAt, nowMs))
        assertFalse(
            "an unchecked console must never be accused",
            ConsoleRules.reachabilityWords(btc.up, btc.lastProbeAt, nowMs).contains("not answering"),
        )

        // ⚠ THE APPROVAL IS LIST-LEVEL: one job covering every row, one card.
        val approval = list.approval
        assertNotNull("the registry carries its own approval", approval)
        assertFalse("not applied on this host yet", approval!!.applied)
        assertEquals("owner", approval.runBy)
        assertNotNull("the marker file is what `applied` is read from", approval.markerPath)
        assertEquals(2, ConsoleRules.approvalSteps(approval).size)
        assertEquals("every command across both steps", 7, ConsoleRules.approvalCommands(approval).size)
        assertTrue(
            "the firewall step names heimdall's own file",
            ConsoleRules.approvalSteps(approval).any { it.file == "/etc/pve/firewall/117.fw" },
        )
        assertTrue(
            "and says it runs somewhere else",
            ConsoleRules.approvalSteps(approval).any { it.where == "heimdall" },
        )

        // ⚠ RAW-JSON PRESENCE, for the reason the member rows have it: a model
        // default cannot tell an absent key from a null one, and `up` is always a
        // key — a renamed one would read as an unchecked console forever.
        val rows = json.parseToJsonElement(fixture("consoles.json"))
            .jsonObject["consoles"]!!.jsonArray.map { it.jsonObject }
        listOf("id", "name", "url", "kind", "notes", "addedAt", "version", "up", "lastProbeAt",
            "latencyMs", "httpStatus", "reachableFrom")
            .forEach { key -> rows.forEach { assertTrue("every console carries $key", key in it) } }
        list.consoles.forEach {
            assertTrue("addedAt is epoch SECONDS", it.addedAt in 1_000_000_000..9_999_999_999)
        }
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
               "peer":{"name":"lora-stick/docs","sessionId":"0123abcd-0000-4000-8000-00000000d004"}}],
               "nextOffset":812}""",
        )
        val e = p.events.single()
        assertEquals("system", e.kind)
        // ⚠ LABEL FROM origin.name, NOT FROM THE RENDERED PREVIEW: the @handle
        // form slugifies the slash (`lora-stick/docs` → `@lora-stick-docs`), so
        // the text cannot be trusted to carry the addressable name.
        assertEquals("lora-stick/docs", e.peer?.name)
        assertNotNull("the sessionId is what a tap would open", e.peer?.sessionId)

        // And an ordinary system event still has none, rather than an empty one.
        val plain = json.decodeFromString<TranscriptPage>(
            """{"events":[{"seq":8,"kind":"system","text":"Compacted."}],"nextOffset":900}""",
        )
        assertNull("no peer block means no peer", plain.events.single().peer)
    }
}
