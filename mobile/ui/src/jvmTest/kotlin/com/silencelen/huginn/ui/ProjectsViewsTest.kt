package com.silencelen.huginn.ui

import com.silencelen.huginn.data.GraphTokens
import com.silencelen.huginn.data.GraphTotals
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectDashboardMember
import com.silencelen.huginn.data.ProjectLead
import com.silencelen.huginn.data.ProjectRate
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.SessionHeadroom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lines a person reads about a cluster: the row, the dashboard heading, and
 * what a member's disclosure reveals.
 *
 * Asserted without a window for the reason [ArchivedSessionsViewTest] gives —
 * there is no compose-ui-test in these modules, and the text IS the behaviour.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ProjectsViewsTest {

    private val nowSec = 1_789_460_000L
    private val nowMs = nowSec * 1000

    private fun row(
        members: Int = 3,
        alive: Int = 3,
        busy: Int = 1,
        waiting: Int = 1,
        status: String = "active",
        cwd: String = "/root/netplan/status-page",
        manifestRev: Int = 0,
        manifestSummary: String? = null,
        endedReason: String? = null,
    ) = ProjectRow(
        id = "6f0d2c41-0000-4000-8000-0000000000b2",
        name = "Status page flap",
        slug = "statusflap",
        kind = "software",
        status = status,
        cwd = cwd,
        memberCount = members, alive = alive, busy = busy, waiting = waiting,
        lead = ProjectLead(name = "statusflap-lead", claudeName = "statusflap/lead", present = true),
        manifestRev = manifestRev,
        manifestSummary = manifestSummary,
        endedReason = endedReason,
        createdAt = nowSec - 86_400,
        updatedAt = nowSec - 7_200,
        rev = 4,
    )

    private fun dashMember(
        role: String,
        status: String?,
        state: String? = null,
        needsYou: Boolean = false,
        waitingFor: String? = null,
        pending: Int = 0,
        lead: Boolean = false,
        headroom: SessionHeadroom? = null,
        agents: Int = 0,
        turns: Int = 0,
        tokens: GraphTokens = GraphTokens(),
        cost: Double? = null,
    ) = ProjectDashboardMember(
        role = role,
        name = "statusflap-$role",
        claudeName = "statusflap/$role",
        sessionId = "sid-$role",
        lead = lead,
        present = true,
        alive = true,
        status = status,
        waitingFor = waitingFor,
        needsYou = needsYou,
        state = state,
        pendingSends = pending,
        headroom = headroom,
        turns = turns,
        tokens = tokens,
        estCostUsd = cost,
        agentCount = agents,
        lastActivityTs = nowSec - 600,
        spawnedAt = nowSec - 7_200,
    )

    // ------------------------------------------------------------- the row

    @Test
    fun `the row leads with the answer to the only question it is asked`() {
        assertEquals(
            "1 of 3 working · 1 needs you · /root/netplan/status-page",
            projectSubtitle(row(), nowMs),
        )
    }

    @Test
    fun `a proposed row puts the proposal where the eye already is`() {
        // The one thing a proposed project is FOR is being answered, so the
        // summary rides the subtitle rather than waiting behind a tap.
        assertEquals(
            "no members yet · two sessions: docs and fw · /root/netplan/dev-ledger/lora-stick",
            projectSubtitle(
                row(
                    members = 0, alive = 0, busy = 0, waiting = 0,
                    status = "proposed",
                    cwd = "/root/netplan/dev-ledger/lora-stick",
                    manifestRev = 2,
                    manifestSummary = "two sessions: docs and fw",
                ),
                nowMs,
            ),
        )
    }

    @Test
    fun `a missing directory leaves no dangling separator`() {
        assertEquals("1 of 3 working · 1 needs you", projectSubtitle(row(cwd = ""), nowMs))
        assertEquals(
            "no members yet",
            projectSubtitle(row(members = 0, alive = 0, busy = 0, waiting = 0, cwd = ""), nowMs),
        )
    }

    @Test
    fun `an archived project says WHY in the daemon's own words`() {
        // ⚠ "the lead session is gone" is the whole story and a client summary of
        // it is not. The daemon writes that sentence when it archives a project
        // itself, and it is the difference between a filed project and a broken
        // one.
        assertEquals(
            "none running · /root/netplan/status-page · the lead session is gone · 2h ago",
            projectSubtitle(
                row(
                    members = 2, alive = 0, busy = 0, waiting = 0,
                    status = "archived",
                    endedReason = "the lead session is gone",
                ),
                nowMs,
            ),
        )
        // And one the owner filed, with nothing to explain.
        assertEquals(
            "none running · /root/netplan/status-page · archived · 2h ago",
            projectSubtitle(row(members = 2, alive = 0, busy = 0, waiting = 0, status = "archived"), nowMs),
        )
    }

    @Test
    fun `an empty list explains itself rather than showing a blank`() {
        assertTrue(PROJECTS_EMPTY.contains("a lead sizes"), PROJECTS_EMPTY)
        assertTrue(
            PROJECTS_EMPTY.contains("before anything is started"),
            "the approval gate is the thing worth saying up front: $PROJECTS_EMPTY",
        )
    }

    @Test
    fun `an unfetched disclosure is not an empty cluster`() {
        // ⚠ THE LIST ROUTE CARRIES NO MEMBERSHIP. "Reading the cluster…" and "no
        // members yet" are different facts, and saying the second about the first
        // is telling somebody their project is empty when it is not.
        assertTrue(PROJECT_MEMBERS_LOADING != PROJECT_NO_MEMBERS)
        assertTrue(PROJECT_NO_MEMBERS.contains("still sizing"), PROJECT_NO_MEMBERS)
    }

    @Test
    fun `a member row leads with its role, and the lead says so`() {
        assertEquals("db", memberLabel(dashMember("db", "busy")))
        assertEquals("lead (lead)", memberLabel(dashMember("lead", "idle", lead = true)))
    }

    // ------------------------------------------------------- the dashboard

    @Test
    fun `the heading repeats the line the person tapped`() {
        // Arriving here from the list row, they should recognise what they pressed
        // — and the counts come off the daemon's own row, not out of the member
        // list beside it.
        val d = ProjectDashboard(
            project = row(members = 2, alive = 2, busy = 1, waiting = 1),
            generatedAt = nowSec - 600,
            members = listOf(dashMember("db", "busy"), dashMember("web", "waiting", needsYou = true)),
        )
        assertEquals("Status page flap · 1 of 2 working · 1 needs you · as of 10m ago", dashboardCaption(d, nowMs))
    }

    @Test
    fun `a member's line says what it is doing and what is waiting on it`() {
        assertEquals("working · 10m ago", memberSubtitle(dashMember("db", "busy"), nowMs))
        assertEquals(
            "needs you — input needed · 2 sends waiting · 10m ago",
            memberSubtitle(dashMember("web", "waiting", needsYou = true, waitingFor = "input needed", pending = 2), nowMs),
        )
        assertEquals(
            "idle · 1 send waiting · 10m ago",
            memberSubtitle(dashMember("api", "idle", pending = 1), nowMs),
        )
        // A word from a newer daemon is not drawn as idle.
        assertEquals("no state yet · 10m ago", memberSubtitle(dashMember("probe", "teleporting"), nowMs))
    }

    @Test
    fun `the pace is reported, never projected`() {
        // ⚠ THESE ARE THE MEMBERS' RATES ADDED. Running a single session's
        // projection off them would put a confident time-to-limit on a number
        // that has twelve authors.
        val d = ProjectDashboard(
            project = row(),
            rate = ProjectRate(activeRecently = true, tokensPer10m = 1800, tokensPer60m = 1400),
        )
        assertEquals("1800 tokens/min over 10m · 1400 over 60m", dashboardPace(d))
        assertNull(dashboardPace(ProjectDashboard(project = row())), "no rate, no line")
        assertNull(
            dashboardPace(ProjectDashboard(project = row(), rate = ProjectRate())),
            "a silent cluster gets no pace line at all",
        )
        assertEquals(
            "active, too little to measure a rate",
            dashboardPace(ProjectDashboard(project = row(), rate = ProjectRate(activeRecently = true))),
        )
    }

    @Test
    fun `the disclosure carries the agent COUNT, never an agent id`() {
        // ⚠ An agent id is scoped to the session that spawned it, so a link from a
        // project-wide row would address nothing — and the daemon sends no ids at
        // all. The count is a fact; a link would be a broken promise.
        val m = dashMember(
            "db", "busy",
            headroom = SessionHeadroom(family = "fable", ladder = "opus"),
            agents = 2,
            turns = 14,
            tokens = GraphTokens(input = 41_000, output = 18_000, cacheRead = 820_000),
            cost = 2.6,
        )
        assertEquals(
            listOf(
                "peer name  statusflap/db",
                "tmux  statusflap-db",
                "model  fable · moved to opus",
                "agents  2",
                "work  14 turns · 59000 tokens · $2.60",
            ),
            memberDetailLines(m),
        )
    }

    @Test
    fun `a member with nothing extra reveals only its two names`() {
        assertEquals(
            listOf("peer name  statusflap/api", "tmux  statusflap-api"),
            memberDetailLines(dashMember("api", "idle")),
        )
    }

    @Test
    fun `a stalled member says so where somebody will look for the reason`() {
        val m = dashMember(
            "web", "idle",
            headroom = SessionHeadroom(family = "fable", stalled = true, autoResume = false),
        )
        assertEquals(
            "model  fable · stopped at the usage limit · auto-resume off",
            memberDetailLines(m)[2],
        )
    }

    @Test
    fun `a dashboard with no totals draws no header of zeroes`() {
        // A header of zeroes reads as a cluster that has done nothing, which is a
        // different claim from "the daemon has not walked these transcripts".
        // generatedAt 0 is the daemon's "no stamp", and it must never become 1970.
        val d = ProjectDashboard(project = row(), generatedAt = 0, totals = null)
        assertNull(d.totals)
        assertEquals("Status page flap · 1 of 3 working · 1 needs you", dashboardCaption(d, nowMs))
    }

    @Test
    fun `the totals are the daemon's additive shape, so the shared header works`() {
        val t = GraphTotals(turns = 31, toolCalls = 184, tokens = GraphTokens(input = 88_000, output = 39_000))
        val d = ProjectDashboard(project = row(), generatedAt = nowSec, totals = t)
        assertEquals(31, d.totals?.turns)
    }
}
