package com.silencelen.huginn

import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.SavedAccounts
import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.SessionList
import com.silencelen.huginn.data.TranscriptPage
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
        assertEquals(0, old.depth)
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
