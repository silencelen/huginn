package com.silencelen.huginn

import com.silencelen.huginn.data.AccountRefreshed
import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.SavedAccounts
import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.SessionList
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.data.UndoResult
import com.silencelen.huginn.data.Watch
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 3.0.0 wire additions, decoded BOTH ways round.
 *
 * `ApiContractTest` in `:app` checks these shapes against captured fixtures; this
 * checks the other half of the same promise, which fixtures cannot: that a
 * response from a daemon that has never heard of them still decodes, and decodes
 * to the OLD behaviour rather than to a confident zero. Every case below is
 * therefore a pair — the new field present, and the same object without it.
 */
class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `a session row carries its headroom cell and its queue depth`() {
        val list = json.decodeFromString<SessionList>(
            """
            {"sessions":[
              {"name":"w1","cols":80,"rows":24,
               "headroom":{"family":"fable","ladder":"opus","autoResume":false,"stalled":true},
               "pendingSends":2},
              {"name":"legacy","cols":80,"rows":24}
            ]}
            """.trimIndent(),
        )
        val live = list.sessions[0]
        assertEquals("opus", live.headroom?.ladder)
        assertEquals("fable", live.headroom?.family)
        assertFalse(live.headroom!!.autoResume)
        assertTrue(live.headroom!!.stalled)
        assertEquals(2, live.pendingSends)

        val old = list.sessions[1]
        assertNull(old.headroom, "no cell is not an idle cell: the mark must be hidden")
        assertEquals(0, old.pendingSends, "a daemon with no queue really had nothing waiting")
    }

    @Test
    fun `status carries the host's quick-action templates, or nothing at all`() {
        // The whole point of serving these: one copy of the wording, on the host,
        // so the phone and the desktop put the SAME text in the composer. A
        // client that carried its own copy would drift the moment either was
        // edited, which is the bug `softEndPhrase` was moved onto the wire for.
        val live = json.decodeFromString<Status>(
            """
            {"host":"huginn","sessions":2,
             "quickActions":{"rev":3,
               "explain":"Explain this, briefly:\n\n{selection}",
               "execute":"Run this and show me the output:\n\n{selection}",
               "askInNewChat":"{selection}\n\nWhat is going on here?",
               "quote":""}}
            """.trimIndent(),
        )
        val qa = assertNotNull(live.quickActions)
        assertEquals(3, qa.rev)
        assertEquals("Explain this, briefly:\n\n{selection}", qa.explain)
        assertEquals("Run this and show me the output:\n\n{selection}", qa.execute)
        assertEquals("{selection}\n\nWhat is going on here?", qa.askInNewChat)
        assertEquals("", qa.quote, "the quote lead-in is empty by default — the frame is the client's")
    }

    @Test
    fun `a daemon that has never heard of quick actions leaves them null`() {
        // NULL rather than a defaulted object, and the distinction is the whole
        // feature probe: a 3.0.x daemon owns no templates, so the selection menu
        // may offer only the one verb whose text this client writes itself
        // (Quote) and Settings must not show an editor for a file that does not
        // exist. An empty-string default would read as "the host says nothing",
        // which is a different and wrong answer.
        val old = json.decodeFromString<Status>("""{"host":"huginn","sessions":0}""")
        assertNull(old.quickActions, "no templates is not four blank templates")
    }

    @Test
    fun `an agent row carries the run id the picker groups on`() {
        val info = json.decodeFromString<AgentsInfo>(
            """
            {"agents":[
              {"id":"agent-a1","workflow":"wf_01","workflowId":"wf_01","agentType":"workflow-subagent",
               "status":"running","depth":1,"active":true,"updatedAt":1789460000},
              {"id":"agent-a2","workflow":null,"active":false,"updatedAt":1789459000}
            ],"active":1,"serverTime":1789460001}
            """.trimIndent(),
        )
        val fresh = info.agents[0]
        assertEquals("wf_01", fresh.workflowId)
        assertEquals("workflow-subagent", fresh.agentType)
        assertEquals("running", fresh.status)
        assertEquals(1, fresh.depth)

        val old = info.agents[1]
        assertNull(old.workflowId, "2.85.0 sends `workflow` only; the picker stays flat")
        assertNull(old.agentType)
        assertNull(old.depth, "a depth nobody reported is not depth zero")
    }

    /**
     * ⚠ THE DECODE THAT KILLED THE WHOLE RESPONSE.
     *
     * `normalizeHeadroomState` seeds `lastResumeAt: null` and the digest passed
     * it through, so every `/v1/watch` on a daemon that had never resumed
     * anything — i.e. every fresh install — threw on the explicit null and took
     * the watch loop, the notification decisions and the headroom toasts with
     * it. `explicitNulls = false` is an ENCODE option and does nothing here, and
     * there is no `coerceInputValues` in this tree. Only a nullable field fixes
     * it, and it stays nullable after the daemon starts sending 0.
     */
    @Test
    fun `a watch digest decodes an explicit null lastResumeAt as well as a zero`() {
        val never = json.decodeFromString<Watch>(
            """{"hash":"a","serverTime":1789460000,
                 "headroom":{"mode":"ok","lastResumeAt":null,"lastLadderAt":0}}""",
        )
        assertNull(never.headroom?.lastResumeAt, "never resumed is not resumed at the epoch")

        val zero = json.decodeFromString<Watch>(
            """{"hash":"a","serverTime":1789460000,
                 "headroom":{"mode":"ok","lastResumeAt":0,"lastLadderAt":0}}""",
        )
        assertEquals(0L, zero.headroom?.lastResumeAt, "and the fixed daemon's 0 still decodes")
    }

    /**
     * The same class of break one route over: an agent with no `.meta.json` —
     * the deliberate `orphan` row — gets an explicit `null` depth, and a cold
     * direct agent gets a null status. Either one used to fail the decode of the
     * WHOLE list, which is the stream picker and the work sheet at once.
     */
    @Test
    fun `an orphan agent decodes with a null depth and a null status`() {
        val info = json.decodeFromString<AgentsInfo>(
            """{"agents":[
                 {"id":"af7ca864cee1939de","workflow":null,"workflowId":null,"agentType":null,
                  "status":null,"depth":null,"active":false,"updatedAt":1789459000}
               ],"active":0,"serverTime":1789460000}""",
        )
        val a = info.agents.single()
        assertNull(a.depth)
        assertNull(a.status)
        assertEquals(
            "af7ca864cee1939de", a.id,
            "the list emits the BARE hex; nothing here may re-mint an id it did not make",
        )
    }

    @Test
    fun `an agent page names the stream it was read from`() {
        val agent = json.decodeFromString<TranscriptPage>(
            """{"events":[],"nextOffset":4096,"agentId":"agent-3f9c1a","workflowId":"wf_01H9ZKQT"}""",
        )
        assertEquals("agent-3f9c1a", agent.agentId, "the route echoes the id it validated")
        assertEquals("wf_01H9ZKQT", agent.workflowId)

        val own = json.decodeFromString<TranscriptPage>("""{"events":[],"nextOffset":4096}""")
        assertNull(own.agentId, "a session's own page belongs to no agent")
        assertNull(own.workflowId)
    }

    @Test
    fun `a saved account carries its token freshness and last refresh`() {
        val saved = json.decodeFromString<SavedAccounts>(
            """
            {"accounts":[
              {"slug":"one","email":"a@example.com","freshness":"expired","expiresAt":1789000000000,
               "refreshTokenExpiresAt":1791000000000,
               "refresh":{"lastAt":1789459000,"lastStatus":"invalid_grant","nextAt":1789462600}},
              {"slug":"two","email":"b@example.com"}
            ]}
            """.trimIndent(),
        )
        assertEquals("expired", saved.accounts[0].freshness)
        assertEquals("invalid_grant", saved.accounts[0].refresh?.lastStatus)
        assertEquals(1_789_462_600L, saved.accounts[0].refresh?.nextAt)
        assertNull(saved.accounts[1].freshness, "say nothing rather than guess `fresh`")
        assertNull(saved.accounts[1].refresh)
    }

    @Test
    fun `a limit event decodes its api error and an ordinary one does not`() {
        val page = json.decodeFromString<TranscriptPage>(
            """
            {"events":[
              {"seq":1,"kind":"assistant","text":"a normal reply"},
              {"seq":2,"kind":"assistant","apiError":429,
               "text":"You've hit your session limit · resets 10:10pm (America/Los_Angeles)"}
            ],"nextOffset":4096}
            """.trimIndent(),
        )
        assertNull(page.events[0].apiError)
        assertEquals(429, page.events[1].apiError)
        assertEquals("assistant", page.events[1].kind, "the record IS an assistant record; only the row differs")
    }

    @Test
    fun `a watch digest carries the headroom facts an alert turns on`() {
        val w = json.decodeFromString<Watch>(
            """
            {"hash":"abc","changed":true,"serverTime":1789460000,
             "headroom":{"mode":"red","stalled":["w1","w2"],"lastResumeAt":1789450000,
                         "lastLadderAt":1789455000,"sentinels":["STOP-FABLE"]}}
            """.trimIndent(),
        )
        assertEquals("red", w.headroom?.mode)
        assertEquals(listOf("w1", "w2"), w.headroom?.stalled)
        assertEquals(listOf("STOP-FABLE"), w.headroom?.sentinels)
        assertNull(json.decodeFromString<Watch>("""{"hash":"abc"}""").headroom)
    }

    @Test
    fun `a send result decodes both the queued shape and an old daemon's ok`() {
        val queued = json.decodeFromString<SendKeysResult>(
            """{"ok":true,"queued":1,"position":1,"delivered":false}""",
        )
        assertEquals(1, queued.position)
        assertFalse(queued.landed, "queued behind a running turn is not landed")

        val old = json.decodeFromString<SendKeysResult>("""{"ok":true}""")
        assertTrue(old.ok)
        assertEquals(0, old.queued)
        assertTrue(old.landed, "a daemon with no queue delivered it outright")
        assertFalse(old.duplicate, "and nothing about it was a duplicate")

        // appd 3.5.1: the send the daemon recognised as one it already has.
        val dup = json.decodeFromString<SendKeysResult>("""{"ok":true,"duplicate":true}""")
        assertTrue(dup.duplicate)
        assertTrue(dup.landed, "nothing of it is queued — which is why the seed cannot read `landed`")
    }

    /**
     * The stall record, which is where the two sentences the card needs live:
     * the clock Claude Code printed and the daemon's reason for not resuming.
     */
    @Test
    fun `a stalled session carries its stall record and its native switch`() {
        val h = json.decodeFromString<Headroom>(
            """{"mode":"red","serverTime":1789460000000,
                 "sessions":[
                   {"name":"btclab","claudeSessionId":"cs-2","family":"fable","ladder":null,
                    "autoResume":false,"stalled":true,"headsUpAt":null,
                    "stall":{"at":1789459000000,"window":"weekly_fable",
                             "resetsAt":1789471800000,"resetsAtSource":"endpoint",
                             "resumedAt":null,"how":null,"attempts":0,"nativeArmed":false,
                             "why":"auto-resume is off for this session",
                             "text":"You've hit your usage limit · resets 10:10pm (America/Los_Angeles)"},
                    "nativeSwitch":{"seenAt":null,"to":null}}
                 ]}""",
        )
        val s = h.sessions.single()
        val stall = s.stall
        assertNotNull(stall)
        assertEquals("weekly_fable", stall.window)
        assertEquals(1_789_471_800_000L, stall.resetsAt, "MILLISECONDS on this route, not an ISO string")
        assertEquals("auto-resume is off for this session", stall.why)
        assertNull(stall.resumedAt)
        assertEquals(0, stall.attempts)
        assertNotNull(s.nativeSwitch, "always present; both halves null when nothing was seen")
        assertNull(s.nativeSwitch!!.to)

        // And a session with no stall at all still decodes, which is the ordinary case.
        val quiet = json.decodeFromString<Headroom>(
            """{"mode":"ok","sessions":[{"name":"w1","stalled":false}]}""",
        )
        assertNull(quiet.sessions.single().stall)
    }

    @Test
    fun `headroom carries the resets a stalled session is waiting for`() {
        val h = json.decodeFromString<Headroom>(
            """{"mode":"ok","serverTime":1789460000000,
                 "resets":[{"slug":"owner-max","window":"session",
                            "resetsAt":"2026-09-15T05:30:00+00:00","percent":4.0,
                            "seenAt":1789452100000,"at":1789452100000}]}""",
        )
        val r = h.resets.single()
        assertEquals("session", r.window)
        assertEquals("2026-09-15T05:30:00+00:00", r.resetsAt, "the DUE instant stays ISO")
        assertEquals(1_789_452_100_000L, r.at, "and the observation clocks are millis")
        assertTrue(
            json.decodeFromString<Headroom>("""{"mode":"ok"}""").resets.isEmpty(),
            "a daemon that sends none reads as none, not as a failed decode",
        )
    }

    @Test
    fun `an undo says whether it applied or only queued`() {
        val applied = json.decodeFromString<UndoResult>(
            """{"ok":true,"applied":true,"queued":false,"to":"fable","delivery":"confirmed"}""",
        )
        assertTrue(applied.applied)
        assertEquals("fable", applied.to)

        // ⚠ THE CASE `ok` HIDES: mid-turn, so the picker could not be opened and
        // the move is waiting for the turn boundary. Both answers are `ok:true`.
        val queued = json.decodeFromString<UndoResult>(
            """{"ok":true,"applied":false,"queued":true,"to":"fable","delivery":"queued"}""",
        )
        assertTrue(queued.ok)
        assertFalse(queued.applied, "ok is not applied — that was the whole bug")
        assertTrue(queued.queued)

        // A daemon from before `applied` existed: nothing claimed, nothing lost.
        val old = json.decodeFromString<UndoResult>("""{"ok":true,"to":"fable","queued":false}""")
        assertFalse(old.applied)
    }

    @Test
    fun `an on-demand refresh answers with the slug, the word and the record`() {
        val r = json.decodeFromString<AccountRefreshed>(
            """{"ok":true,"slug":"spare","status":"refreshed",
                 "refresh":{"lastAt":1789460000000,"lastStatus":"refreshed",
                            "nextAt":1789485600000,"deadAt":null}}""",
        )
        assertEquals("spare", r.slug)
        assertEquals("refreshed", r.status)
        assertEquals(1_789_460_000_000L, r.refresh?.lastAt)
        assertNull(r.refresh?.deadAt)

        // The word for asking it to refresh the ACTIVE login. It is a 200, not a
        // refusal — the route that refuses is /activate.
        val skipped = json.decodeFromString<AccountRefreshed>(
            """{"ok":true,"slug":"owner-max","status":"active_skipped"}""",
        )
        assertTrue(skipped.ok)
        assertEquals("active_skipped", skipped.status)

        val dead = json.decodeFromString<AccountRefreshed>(
            """{"ok":false,"slug":"old-org","status":"refresh_token_expired",
                 "refresh":{"lastStatus":"known_dead_refresh_token","deadAt":1789000000000}}""",
        )
        assertFalse(dead.ok)
        assertEquals(1_789_000_000_000L, dead.refresh?.deadAt)
    }

    /**
     * ⚠ A FORM SAVED FROM ITS OWN DEFAULTS MUST NOT 400. The daemon rejects an
     * empty `defaultModel` and a `headsUpText` with no `{pct}` in it, and three
     * of these defaults used to be `""`, `"continue"` and `""` — none of which
     * the daemon has ever held. Copied verbatim from `lib/headroom.js`
     * `defaults()`.
     */
    @Test
    fun `the settings defaults are the daemon's own, not placeholders`() {
        val d = HeadroomSettings()
        assertEquals("claude-fable-5-1", d.defaultModel)
        assertTrue(d.resumePhrase.startsWith("Your usage limit has reset."))
        assertTrue(d.resumePhrase.endsWith("do not repeat work that is already complete."))
        assertTrue("{pct}" in d.headsUpText, "the daemon validates for this token")
        assertTrue("{next}" in d.headsUpText)
        assertTrue("{ladderPct}" in d.headsUpText)
        assertTrue(d.headsUpText.startsWith("[huginn headroom] "))
    }

    /**
     * ⚠ KEEP-AWAKE IS OFF IN THIS FILE'S DEFAULTS TOO, and it is the only default
     * here that spends money when it is wrong. A client whose default said
     * `true` would draw the toggle ON against a daemon that has it off — and the
     * form sends the WHOLE object on every save, so the next unrelated edit would
     * switch it on for real, with nobody having asked for it.
     */
    @Test
    fun `keep-awake is off by default on the client as well as on the host`() {
        val d = HeadroomSettings()
        assertFalse(d.keepAwake, "this must never arrive switched on")
        assertEquals("claude-haiku-4-5-20251001", d.keepAwakeModel, "the DATED id, never the alias")
        assertNull(d.keepAwakeQuietHours)
    }

    @Test
    fun `the keep-awake settings decode, and an older daemon's answer still does`() {
        val on = json.decodeFromString<HeadroomSettings>(
            """{"keepAwake":true,"keepAwakeModel":"claude-haiku-4-5-20251001","keepAwakeQuietHours":"01:00-07:00"}""",
        )
        assertTrue(on.keepAwake)
        assertEquals("01:00-07:00", on.keepAwakeQuietHours)

        // The same object from a daemon that has never heard of the feature.
        val old = json.decodeFromString<HeadroomSettings>("""{"headsUpPct":85,"ladderPct":92}""")
        assertFalse(old.keepAwake, "absent must read as off, not as unknown-so-probably-on")
        assertNull(old.keepAwakeQuietHours)
    }

    /**
     * The window half of the Status line, both ways round.
     *
     * `windowRunning` is NULLABLE on purpose: `null` is a daemon too old to have
     * been asked, which is not the same answer as `false`. Read as a plain
     * Boolean, an older host would report "no window running" forever and the
     * line would say so with complete confidence.
     */
    @Test
    fun `the status summary carries the window and what keep-awake spent`() {
        val running = json.decodeFromString<Status>(
            """
            {"host":"huginn","uptimeSec":10,"cores":4,"load":[0.1],"sessions":1,"chatsRunning":0,
             "headroom":{"mode":"ok","windowRunning":true,"windowResetsAt":"2026-09-15T10:30:00Z",
               "keepAwake":{"enabled":true,"model":"claude-haiku-4-5-20251001","lastAt":1789460000000,
                 "lastAtClock":"14:32","keptAwakeToday":3,"keptAwakeTotal":11,"lastOutcome":"ok",
                 "why":"no window is running"}}}
            """.trimIndent(),
        )
        val h = assertNotNull(running.headroom)
        assertEquals(true, h.windowRunning)
        assertEquals("2026-09-15T10:30:00Z", h.windowResetsAt)
        val ka = assertNotNull(h.keepAwake)
        assertTrue(ka.enabled)
        assertEquals("14:32", ka.lastAtClock)
        assertEquals(3, ka.keptAwakeToday)
        assertEquals(11, ka.keptAwakeTotal)
        assertEquals("no window is running", ka.why)

        val old = json.decodeFromString<Status>(
            """{"host":"huginn","uptimeSec":10,"cores":4,"load":[0.1],"sessions":1,"chatsRunning":0,
                "headroom":{"mode":"ok","worstPercent":51.0}}""",
        )
        val oldH = assertNotNull(old.headroom)
        assertNull(oldH.windowRunning, "an older daemon was never asked; that is not a No")
        assertNull(oldH.keepAwake)
    }

    @Test
    fun `headroom decodes with defaults for every block it omits`() {
        val idle = json.decodeFromString<Headroom>("""{"mode":"ok","serverTime":1789460000}""")
        assertEquals("ok", idle.mode)
        assertNull(idle.worst)
        assertNull(idle.settings)
        assertNull(idle.arbiter)
        assertTrue(idle.sessions.isEmpty())
        assertTrue(idle.held.isEmpty())
        assertTrue(idle.sentinels.isEmpty())

        val red = json.decodeFromString<Headroom>(
            """
            {"mode":"red",
             "worst":{"slug":"main","email":"a@example.com","window":"weekly_fable",
                      "percent":92.4,"label":"Current week (Fable)","resetsAt":"2026-09-15T10:30:00Z"},
             "sessions":[{"name":"w1","family":"opus",
                          "ladder":{"from":"fable","to":"opus","at":1789460100,"delivery":"confirmed"}}],
             "sentinels":{"STOP-FABLE":{"since":1789459000,"reason":"weekly_fable 89%"}},
             "held":[{"agentId":"a799b9","agentType":"workflow-subagent","since":1789459500}],
             "settings":{"ladderPct":92},
             "serverTime":1789460000}
            """.trimIndent(),
        )
        assertEquals("weekly_fable", red.worst?.window)
        assertEquals("opus", red.sessions.single().ladder?.to)
        assertTrue(red.sessions.single().autoResume, "the daemon's default rides the default here too")
        assertEquals("a799b9", red.held.single().agentId)
        assertNotNull(red.sentinels["STOP-FABLE"], "an armed sentinel keeps its raw payload")
        // A settings PATCH answer names only what it knows; the rest are the
        // daemon's documented defaults, which this model must mirror exactly.
        assertEquals(92, red.settings?.ladderPct)
        assertEquals(85, red.settings?.headsUpPct)
        assertEquals(listOf("fable", "opus", "sonnet"), red.settings?.ladder)
        assertEquals(1_800_000L, red.settings?.cooldownMs)
        assertEquals(95, red.settings?.accountSwitch?.threshold)
    }
}
