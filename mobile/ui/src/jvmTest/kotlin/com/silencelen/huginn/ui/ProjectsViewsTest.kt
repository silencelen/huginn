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
import kotlin.test.assertFalse
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
        /** A project the daemon has no lead row for yet — see the rollup tests. */
        leadPresent: Boolean = true,
    ) = ProjectRow(
        id = "6f0d2c41-0000-4000-8000-0000000000b2",
        name = "Status page flap",
        slug = "statusflap",
        kind = "software",
        status = status,
        cwd = cwd,
        memberCount = members, alive = alive, busy = busy, waiting = waiting,
        lead = if (leadPresent) ProjectLead(name = "statusflap-lead", claudeName = "statusflap/lead", present = true) else null,
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
            // ⚠ "just the lead so far", not "no members yet" (P-33/D-20): the
            // MEMBERS list under this header shows the lead, so the header
            // saying nobody is here contradicts the list below it.
            "just the lead so far · two sessions: docs and fw · /root/netplan/dev-ledger/lora-stick",
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
            "just the lead so far",
            projectSubtitle(row(members = 0, alive = 0, busy = 0, waiting = 0, cwd = ""), nowMs),
        )
        // No lead registered either: genuinely nobody, and the old sentence.
        assertEquals(
            "no members yet",
            projectSubtitle(
                row(members = 0, alive = 0, busy = 0, waiting = 0, cwd = "", leadPresent = false),
                nowMs,
            ),
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
        // ⚠ ONCE, NOT TWICE. Both panes are on screen together when there are no
        // projects, and each carried its own paragraph explaining what a project
        // IS — different wordings of the same three facts, side by side. The
        // explanation belongs in the pane with the room for it; the list says the
        // short fact, because the list is 280dp wide.
        assertTrue(PROJECTS_EMPTY.isNotBlank(), "a blank list must still say something")
        assertTrue(
            PROJECTS_EMPTY.length < 60,
            "the list pane gets the short one: $PROJECTS_EMPTY",
        )
        assertTrue(PROJECTS_BLURB.contains("a lead sizes"), PROJECTS_BLURB)
        assertTrue(
            PROJECTS_BLURB.contains("before anything is started"),
            "the approval gate is the thing worth saying up front: $PROJECTS_BLURB",
        )
        assertFalse(
            PROJECTS_BLURB.contains(PROJECTS_EMPTY),
            "the two panes must not repeat each other word for word",
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
            rate = ProjectRate(activeRecently = true, tokensPerMin10 = 1800, tokensPerMin60 = 1400),
        )
        // ⚠ THE APP'S NUMBER WORDS (P-33). It used to print the raw long —
        // "41383 tokens/min" beside a "561.6k" two cards away.
        assertEquals("1.8k/min over 10m · 1.4k/min over 60m", dashboardPace(d))
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
                "work  14 turns · 59.0k tokens · $2.60",
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

    // ----------------------------------------- membership, edited by hand

    /**
     * ⚠⚠ "DROP" AND "END" ARE ONE KEYSTROKE APART, and one of them stops a
     * session somebody is working in. The daemon says `ended: false` in the body
     * rather than leaving a client to assume; the screen has to be at least as
     * clear, so the consequence is printed beside the verb rather than left to
     * the word "drop" to carry.
     */
    @Test
    fun `the drop verb says what it does not do`() {
        assertEquals("Drop from project", PROJECT_DROP_VERB)
        assertTrue("keeps running" in PROJECT_DROP_NOTE, PROJECT_DROP_NOTE)
        // And it must not read as an ending, which is the whole risk.
        for (word in listOf("end", "kill", "stop", "close")) {
            assertFalse(word in PROJECT_DROP_VERB.lowercase(), "'$word' would read as ending the session")
        }
    }

    /**
     * The adopt form's two empty states are different questions and must read
     * that way: nothing picked yet is a prompt, nothing FREE is a fact about the
     * host.
     */
    @Test
    fun `the adopt form asks for a session rather than going quiet`() {
        assertTrue(PROJECT_ADOPT_NEEDS_SESSION.isNotBlank())
        assertTrue("session" in PROJECT_ADOPT_NEEDS_SESSION.lowercase(), PROJECT_ADOPT_NEEDS_SESSION)
        assertTrue("No other session" in PROJECT_ADOPT_NOTHING_FREE, PROJECT_ADOPT_NOTHING_FREE)
    }

    /**
     * ⚠ A SHELL THAT HAS NOT WIRED MEMBERSHIP OFFERS NOTHING. `membership = null`
     * is the state against a daemon without the routes, and it must draw no verb
     * at all rather than one that can only 404 — the same rule `archiveAvailable`
     * and `projectsAvailable` already follow.
     */
    @Test
    fun `an unwired shell adopts and drops nothing`() {
        val actions = ProjectMemberActions()
        // The defaults must be inert rather than absent: the composable calls
        // them unconditionally, so a missing one would be a crash on a daemon
        // that has no membership routes.
        actions.onAdopt("firmware", "scratch")
        actions.onDrop(dashMember("firmware", "idle"))
        actions.clearRefusal()
        // And with no live sessions the picker has nothing to offer, which is
        // what `PROJECT_ADOPT_NOTHING_FREE` is drawn for.
        assertEquals(emptyList(), ProjectRules.adoptable(actions.liveSessions, emptyList()))
        assertNull(actions.refusal)
        assertFalse(actions.busy)
    }

    @Test
    fun `a wired shell gets the role and the name it picked`() {
        var seen: Pair<String, String>? = null
        val actions = ProjectMemberActions(onAdopt = { role, name -> seen = role to name })
        actions.onAdopt("firmware", "scratch")
        assertEquals("firmware" to "scratch", seen)
    }
}
