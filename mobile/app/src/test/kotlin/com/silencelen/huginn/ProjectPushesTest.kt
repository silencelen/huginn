package com.silencelen.huginn

import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectDetail
import com.silencelen.huginn.data.ProjectPendingChange
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.notify.ProjectNotices
import com.silencelen.huginn.notify.ProjectPushes
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN OPEN PROJECT PAGE SHOWS A ROSTER CHANGE THAT ARRIVES WHILE IT IS OPEN.
 *
 * Fold, 2026-11-03: the lead proposed a roster change (`pendingChange` rev 2)
 * with the project page on screen. The shade said so; the page never drew the
 * card — not on its five-second poll (which fetched only the dashboard, and the
 * card is read off the project's ROW), not on Refresh (which re-read the list,
 * while the card preferred the detail fetched on open). Leaving and coming back
 * showed it. Three fixes, each held here: the newer row wins, a project push
 * re-reads the open page at once, and the page's poll and Refresh re-read the
 * row.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ProjectPushesTest {

    private val change = ProjectPendingChange(rev = 2, token = "9f2c4e1a7b3d5f60", summary = "add a checker")

    private fun row(rev: Int, pending: ProjectPendingChange? = null) =
        ProjectRow(id = "p1", name = "e2eproj", slug = "e2eproj", kind = "software", status = "active", rev = rev, pendingChange = pending)

    private fun detail(row: ProjectRow?) = ProjectDetail(project = Project(id = "p1", name = "e2eproj", rev = row?.rev ?: 0), row = row)

    @Test
    fun `a change that landed after the page opened is drawn from the newer row`() {
        // Opened at rev 5 with nothing pending; Refresh then re-read the LIST,
        // which carries the change at rev 6. The detail's stale row used to win.
        val opened = detail(row(rev = 5))
        val refreshed = listOf(row(rev = 6, pending = change))
        assertEquals(change, ProjectPushes.openRow(opened, refreshed, "p1")?.pendingChange)
        // And the other way round: a detail re-read after the list is newer.
        val reRead = detail(row(rev = 7, pending = change))
        assertSame(reRead.row, ProjectPushes.openRow(reRead, listOf(row(rev = 6)), "p1"))
        // A change that was answered (list newer, nothing pending) comes down.
        assertNull(ProjectPushes.openRow(detail(row(rev = 6, pending = change)), listOf(row(rev = 8)), "p1")?.pendingChange)
        // Either source alone is used, a tie keeps the detail's, and another
        // project's detail is never read for this one.
        assertEquals(6, ProjectPushes.openRow(null, listOf(row(rev = 6)), "p1")?.rev)
        assertEquals(5, ProjectPushes.openRow(detail(row(rev = 5)), emptyList(), "p1")?.rev)
        val same = detail(row(rev = 5, pending = change))
        assertSame(same.row, ProjectPushes.openRow(same, listOf(row(rev = 5)), "p1"))
        val other = ProjectDetail(project = Project(id = "p2"), row = row(rev = 99, pending = change).copy(id = "p2"))
        assertEquals("p1's own list row, not p2's detail", 4, ProjectPushes.openRow(other, listOf(row(rev = 4)), "p1")?.rev)
        assertNull(ProjectPushes.openRow(null, emptyList(), "p1"))
    }

    @Test
    fun `a roster change or a proposal names the project to re-read, and nothing else does`() {
        val payload = """{"projectId":"p1","rev":"2","token":"9f2c4e1a7b3d5f60"}"""
        assertEquals("p1", ProjectPushes.projectOf(ProjectNotices.KIND_CHANGE, null, payload))
        assertEquals("p1", ProjectPushes.projectOf(ProjectNotices.KIND_PROPOSED, null, """{"projectId":"p1","manifestRev":"3"}"""))
        assertEquals("the subject when the payload has no id", "p1", ProjectPushes.projectOf(ProjectNotices.KIND_CHANGE, "p1", null))
        assertNull(ProjectPushes.projectOf("session_attention", "p1", payload))
        assertNull(ProjectPushes.projectOf("chat_attention", "p1", null))
        assertNull(ProjectPushes.projectOf(ProjectNotices.KIND_CHANGE, null, null))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an announced project reaches whoever is listening`() = runTest {
        val heard = async { ProjectPushes.changed.first() }
        runCurrent()
        ProjectPushes.announce("p1")
        // Virtual time: an announcement that never arrives fails here at once.
        assertEquals("p1", withTimeoutOrNull(1_000) { heard.await() })
    }

    // ------------------------------------------------------------ the wiring

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(relative: String): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("$relative read as ${text.length} chars — wrong file", text.length > 5_000)
        return text
    }

    @Test
    fun `the page's poll, Refresh and a push each re-read the open project's row`() {
        val vm = source("ui/HuginnViewModel.kt")
        val poll = vm.substringAfter("fun startDashboardPolling(").substringBefore("fun stopDashboardPolling(")
        assertTrue("the poll read as ${poll.length} chars — wrong slice", poll.length in 100..3_000)
        assertTrue("the page's poll fetches only the dashboard again", poll.contains("landOpenProject(id)"))
        val refresh = vm.substringAfter("fun refreshAll()").substringBefore("private var sessionsPollJob")
        assertTrue("the refresh read as ${refresh.length} chars — wrong slice", refresh.length in 100..8_000)
        assertTrue("Refresh does not re-read the open project", refresh.contains("dashboardId?.let { landOpenProject(it) }"))
        assertTrue("nothing listens for a project push", vm.contains("ProjectPushes.changed.collect"))
        assertTrue(
            "the messaging service does not tell the page",
            source("notify/HuginnMessagingService.kt").contains("ProjectPushes.announce("),
        )
        assertTrue(
            "the card is read from the detail's row whenever there is one again",
            source("MainActivity.kt").contains(".openRow(projectDetail, projects, id)?.pendingChange"),
        )
    }
}
