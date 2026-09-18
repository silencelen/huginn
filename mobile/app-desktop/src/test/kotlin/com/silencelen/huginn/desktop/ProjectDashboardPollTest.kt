package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectRate
import com.silencelen.huginn.data.ProjectRow
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The dashboard poll, and the answers it must NOT put on screen again.
 *
 * ⚠⚠ THE ROLLUP IS THE EXPENSIVE ONE. Every tick the daemon sums twelve members'
 * overviews by walking their transcripts; the client's job is to stop re-drawing
 * the result when the daemon has told it nothing moved. `generatedAt` is when
 * this poll was ANSWERED — the only honest clock on a rollup — so two answers
 * carrying the same stamp are the same rollup, re-sent. Adopting one anyway
 * re-composes a twelve-row table, closes nothing but resets everything, five
 * seconds apart, forever.
 *
 * ⚠ AND THREE CASES ARE NOT SKIPS, each of which is a frozen screen if it is
 * read as one: the first answer, an answer about a DIFFERENT project, and an
 * answer with no stamp at all.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ProjectDashboardPollTest {

    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(): AppStore {
        val dir = Files.createTempDirectory("huginn-dashboard-test").toFile()
        dirs += dir
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        // Never started: nothing here may reach a daemon. The adopt step is the
        // whole subject and it has no I/O in it.
        return AppStore(DesktopSettings(File(dir, "settings.json")), Presence(), scope)
    }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dashboard(id: String = "p1", at: Long, per10m: Long = 0) = ProjectDashboard(
        project = ProjectRow(id = id, name = id, slug = id, kind = "software", status = "active"),
        generatedAt = at,
        rate = ProjectRate(activeRecently = true, tokensPer10m = per10m),
    )

    @Test
    fun `the same stamp twice is the same rollup, and the screen keeps the one it has`() {
        assertTrue(dashboardMoved(null, dashboard(at = 100)), "the first answer always lands")
        assertFalse(
            dashboardMoved(dashboard(at = 100), dashboard(at = 100)),
            "same project, same generatedAt: the daemon is re-sending what is already on screen",
        )
        assertTrue(dashboardMoved(dashboard(at = 100), dashboard(at = 101)), "a second later is news")
    }

    @Test
    fun `a poll that overtook its predecessor does not put the screen backwards`() {
        assertFalse(
            dashboardMoved(dashboard(at = 200), dashboard(at = 150)),
            "an answer older than the one on screen is a race, not news",
        )
    }

    @Test
    fun `walking to another project always lands, whatever the stamps say`() {
        // ⚠ STAMPS ARE PER-ANSWER, NOT PER-CLUSTER. Two projects answered in the
        // same second carry the same number, and skipping on it would draw the
        // first cluster's totals under the second one's name.
        assertTrue(
            dashboardMoved(dashboard(id = "a", at = 100), dashboard(id = "b", at = 100)),
            "a different project is a different rollup",
        )
    }

    @Test
    fun `a daemon that sends no stamp is never skipped`() {
        assertTrue(dashboardMoved(dashboard(at = 0), dashboard(at = 0)), "no clock means no basis to skip")
        assertTrue(dashboardMoved(dashboard(at = 100), dashboard(at = 0)), "it stopped stamping; draw what arrived")
    }

    @Test
    fun `the store holds the same object across an unchanged poll`() {
        val s = store()
        val first = dashboard(at = 500, per10m = 1_800)
        assertTrue(s.adoptDashboard(first), "the first answer fills an empty pane")
        assertSame(first, s.projectDashboard.value)

        // A SECOND OBJECT, equal in the one field that decides. Identity is what
        // is asserted because that is what a recomposition keys on: an equal copy
        // adopted anyway is the bug, and `assertEquals` would not see it.
        val resent = dashboard(at = 500, per10m = 1_800)
        assertFalse(s.adoptDashboard(resent), "the daemon re-sent the same rollup")
        assertSame(first, s.projectDashboard.value, "the screen must still hold the object it was drawn from")

        val moved = dashboard(at = 505, per10m = 2_000)
        assertTrue(s.adoptDashboard(moved))
        assertSame(moved, s.projectDashboard.value)
        assertEquals(2_000L, s.projectDashboard.value?.rate?.tokensPer10m)
    }

    @Test
    fun `walking to another project drops the rollup the old one was drawn from`() {
        val s = store()
        s.openProject("a")
        s.adoptDashboard(dashboard(id = "a", at = 500))
        assertTrue(s.projectDashboard.value != null, "the pane was filled before the walk")
        s.openProject("b")
        // ⚠ CLEARED ON THE WALK rather than left for the next poll to overwrite:
        // held, it would draw cluster A's numbers under B's name for a whole tick,
        // and `dashboardMoved` would be comparing stamps across two projects.
        assertEquals(null, s.projectDashboard.value, "the previous cluster's rollup does not follow you")
    }
}
