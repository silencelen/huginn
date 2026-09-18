package com.silencelen.huginn.ui

import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.data.GraphTotals
import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectDashboardMember
import com.silencelen.huginn.data.ProjectMember
import com.silencelen.huginn.data.SessionHeadroom
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun member(role: String, state: String?, needsYou: Boolean? = false) = ProjectMember(
        name = "statusflap/$role", role = role, spawnedAt = nowSec - 7_200,
        state = state, needsYou = needsYou, pendingSends = 0, lastActivityTs = nowSec - 600,
    )

    private fun project() = Project(
        id = "6f0d2c41-0000-4000-8000-0000000000b2",
        name = "Status page flap",
        cwd = "/root/netplan/status-page",
        createdAt = nowSec - 86_400,
        members = listOf(
            member("db", "running"),
            member("web", "attention"),
            member("api", "idle"),
        ),
    )

    // ------------------------------------------------------------- the row

    @Test
    fun `the row leads with the answer to the only question it is asked`() {
        assertEquals(
            "1 of 3 working · 1 needs you · /root/netplan/status-page",
            projectSubtitle(project(), nowMs),
        )
    }

    @Test
    fun `a missing directory leaves no dangling separator`() {
        assertEquals("1 of 3 working · 1 needs you", projectSubtitle(project().copy(cwd = null), nowMs))
        assertEquals("no members yet", projectSubtitle(Project(id = "x", name = "New"), nowMs))
    }

    @Test
    fun `an ended project says when, so a quiet row is not mistaken for a stuck one`() {
        assertEquals(
            "1 of 2 working · /root/netplan/status-page · ended 2h ago",
            projectSubtitle(
                project().copy(endedAt = nowSec - 7_200, members = project().members.filter { it.role != "web" }),
                nowMs,
            ),
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

    // ------------------------------------------------------- the dashboard

    private fun dashMember(
        role: String,
        state: String?,
        needsYou: Boolean? = false,
        pending: Int? = 0,
        headroom: SessionHeadroom? = null,
        streams: List<AgentRun> = emptyList(),
        totals: GraphTotals? = null,
    ) = ProjectDashboardMember(
        name = "statusflap/$role", role = role, spawnedAt = nowSec - 7_200,
        state = state, needsYou = needsYou, pendingSends = pending, lastActivityTs = nowSec - 600,
        headroom = headroom, streams = streams, totals = totals,
    )

    @Test
    fun `the heading repeats the line the person tapped`() {
        // Arriving here from the list row, they should recognise what they pressed.
        val d = ProjectDashboard(
            project = project(),
            members = listOf(dashMember("db", "running"), dashMember("web", "attention")),
            updatedAt = nowSec - 600,
        )
        assertEquals("Status page flap · 1 of 2 working · 1 needs you · as of 10m ago", dashboardCaption(d, nowMs))
    }

    @Test
    fun `a member's line says what it is doing and what is waiting on it`() {
        assertEquals("working · 10m ago", memberSubtitle(dashMember("db", "running"), nowMs))
        assertEquals(
            "needs you · 2 sends waiting · 10m ago",
            memberSubtitle(dashMember("web", "attention", pending = 2), nowMs),
        )
        assertEquals(
            "idle · 1 send waiting · 10m ago",
            memberSubtitle(dashMember("api", "idle", pending = 1), nowMs),
        )
        // An unknown word from a newer daemon is not drawn as idle.
        assertEquals("no state yet · 10m ago", memberSubtitle(dashMember("probe", "teleporting", null), nowMs))
    }

    @Test
    fun `the disclosure carries the agent COUNT, never an agent id`() {
        // ⚠ An agent id is scoped to the session that spawned it, so a link from a
        // project-wide row would address nothing. The count is a fact; a link
        // would be a broken promise.
        val m = dashMember(
            "db", "running",
            headroom = SessionHeadroom(family = "fable", ladder = "opus"),
            streams = listOf(AgentRun(id = "af7ca8", active = true), AgentRun(id = "c0d4e8", active = false)),
            totals = GraphTotals(turns = 14, toolCalls = 92, errors = 2),
        )
        assertEquals(
            listOf(
                "peer name  statusflap/db",
                "model  fable · moved to opus",
                "agents  2 · 1 live",
                "work  14 turns · 92 tools · 2 tool errors",
            ),
            memberDetailLines(m),
        )
    }

    @Test
    fun `a member with nothing extra reveals only its peer name`() {
        assertEquals(listOf("peer name  statusflap/api"), memberDetailLines(dashMember("api", "idle")))
    }

    @Test
    fun `a stalled member says so where somebody will look for the reason`() {
        val m = dashMember(
            "web", "idle",
            headroom = SessionHeadroom(family = "fable", stalled = true, autoResume = false),
        )
        assertEquals(
            "model  fable · stopped at the usage limit · auto-resume off",
            memberDetailLines(m)[1],
        )
    }
}
