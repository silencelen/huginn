package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectRow
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A rollup that was asked for one project and answered after the reader moved
 * to another stays off screen.
 *
 * The rollup walks every member's transcript, so it can take seconds; the 5 s
 * poll asks for it while the reader is free to click another project in the
 * tree. Drawn anyway, it put the old project's totals and member rows under the
 * new project's name, and a Drop on one of those rows went to the new project.
 *
 * The fetch arrives through the store's seam and is held open by the test, so
 * "while it is in flight" is a line of the test rather than a race. The store is
 * never started and nothing here reaches a daemon.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ProjectDashboardSwitchTest {

    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }

    private fun store(): AppStore {
        val dir = Files.createTempDirectory("huginn-dashboard-switch").toFile()
        dirs += dir
        return AppStore(DesktopSettings(File(dir, "settings.json")), Presence(), scope())
    }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun dashboard(id: String, at: Long = 100) = ProjectDashboard(
        project = ProjectRow(id = id, name = id, slug = id, kind = "software", status = "active"),
        generatedAt = at,
    )

    @Test
    fun `a rollup that lands after the reader moved on is not drawn under the new project`() {
        val s = store()
        val answer = CompletableDeferred<ProjectDashboard>()
        s.openProject("a")
        scope().launch { s.refreshProjectDashboard { answer.await() } }

        s.openProject("b") // the tree click, while a's rollup is still being summed
        answer.complete(dashboard("a"))

        assertEquals("b", s.projectId.value)
        assertNull(s.projectDashboard.value, "a's totals and members must not appear under b")
    }

    @Test
    fun `a late rollup does not replace the one already drawn for the new project`() {
        val s = store()
        val late = CompletableDeferred<ProjectDashboard>()
        s.openProject("a")
        scope().launch { s.refreshProjectDashboard { late.await() } }

        s.openProject("b")
        val fresh = dashboard("b", at = 200)
        scope().launch { s.refreshProjectDashboard { id -> dashboard(id, at = 200) } }
        assertEquals(fresh, s.projectDashboard.value, "b's own answer landed")
        val drawn = s.projectDashboard.value

        late.complete(dashboard("a", at = 300))
        assertSame(drawn, s.projectDashboard.value, "a's answer, however recent, is not about b")
    }

    @Test
    fun `an answer about another project than the one asked for is not drawn`() {
        val s = store()
        s.openProject("b")
        scope().launch { s.refreshProjectDashboard { dashboard("a") } }
        assertNull(s.projectDashboard.value)
    }

    @Test
    fun `walking away and back before the answer lands still draws it`() {
        val s = store()
        val answer = CompletableDeferred<ProjectDashboard>()
        s.openProject("a")
        scope().launch { s.refreshProjectDashboard { answer.await() } }
        s.openProject("b")
        s.openProject("a")
        answer.complete(dashboard("a"))
        assertEquals("a", s.projectDashboard.value?.project?.id, "it is a's rollup and a is open again")
    }
}
