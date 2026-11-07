package com.silencelen.huginn

import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.ui.DashboardBook
import com.silencelen.huginn.ui.DashboardPoll
import com.silencelen.huginn.ui.FileDashboardShelf
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The project page shows the last dashboard at once (R5 bug 1, owner
 * 2026-11-07: "a projects main page sometime takes a long time to load and
 * seems to have no cache, and just shows 'nothing to show yet' for 5ish
 * seconds").
 *
 * The rule itself is `:core`'s: a [DashboardBook] over a [FileDashboardShelf]
 * answers `held(id)` with what the project last said in any run. What the phone
 * owns is the use of it — the shelf under the app's cache directory, read on the
 * way into the page BEFORE the ready gate, the daemon's own rollup shape
 * surviving the round trip — and the retry after a failed read
 * ([DashboardPoll]), which used to wait a whole tick in silence.
 *
 * NOTE this module is on org.junit: (message, expected, actual).
 */
class DashboardShelfPhoneTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dir: File = Files.createTempDirectory("dashboards").toFile()

    @After
    fun tearDown() { dir.deleteRecursively() }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun dashboard(id: String, at: Long) = ProjectDashboard(
        project = ProjectRow(id = id, name = id, slug = id, kind = "software", status = "active"),
        generatedAt = at,
    )

    /** A fresh book over the phone's shelf directory: what a new process builds. */
    private fun book() = DashboardBook(FileDashboardShelf(dir))

    // ------------------------------------------------------------- the first draw

    @Test
    fun `what a project last said survives a process death, whole, in the daemon's own shape`() {
        val real = json.decodeFromString<ProjectDashboard>(fixture("project-dashboard.json"))
        val id = real.project!!.id
        assertTrue(book().adopt(id, real))
        // A NEW book over the same directory is the next process.
        val back = book().held(id)
        assertEquals("the daemon's rollup must round-trip through the shelf", real, back)
        assertEquals(real.members.size, back!!.members.size)
        assertEquals(real.generatedAt, back.generatedAt)
    }

    @Test
    fun `the page draws the shelved dashboard at once, and the empty text only when none ever`() {
        // Never answered, in this process or any earlier one: the one honest
        // "Nothing to show yet.".
        assertNull(book().held("p"))

        // An earlier process shelved one: the next process's book draws it
        // first, and the poll's answer replaces it by the book's own rule.
        book().adopt("p", dashboard("p", at = 100))
        val relaunched = book()
        assertEquals(100L, relaunched.held("p")!!.generatedAt)
        assertTrue("the poll's newer answer must still land", relaunched.adopt("p", dashboard("p", at = 105)))
        assertFalse("...and the same stamp re-sent must not", relaunched.adopt("p", dashboard("p", at = 105)))
        assertEquals("and the shelf followed", 105L, book().held("p")!!.generatedAt)
    }

    // ------------------------------------------------------------- the retry

    @Test
    fun `a failed read is retried soon, a landed one at the tick`() {
        assertEquals(5_000L, DashboardPoll.delayAfter(0))
        assertEquals(1_000L, DashboardPoll.delayAfter(1))
        assertEquals(2_000L, DashboardPoll.delayAfter(2))
        assertEquals(4_000L, DashboardPoll.delayAfter(3))
        assertEquals("never slower than the tick", 5_000L, DashboardPoll.delayAfter(4))
        assertEquals(5_000L, DashboardPoll.delayAfter(40))
    }

    // ------------------------------------------------------------- the wiring

    @Test
    fun `the view model seeds the page from the shelf before its first read, through the book, and retries soon`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val vm = File(root, "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt").readText()
        // The book is the shelf-backed one from :core, under the app's cache directory.
        assertTrue("the book has no shelf", vm.contains("DashboardBook(\n        com.silencelen.huginn.ui.FileDashboardShelf("))
        assertTrue("the shelf is not under the cache directory", vm.contains("cacheDir, \"dashboards\")"))
        assertFalse("the app-local cache is back beside the shelf", vm.contains("DashboardCache"))

        val poll = vm.substringAfter("fun startDashboardPolling(id: String) {").substringBefore("fun stopDashboardPolling()")
        assertTrue("the poll body was not found", poll.length > 200)
        val seed = poll.indexOf("_projectDashboard.value = dashboards.held(id)")
        val gate = poll.indexOf("awaitReady()")
        assertTrue("the page is not drawn from the book on the way in", seed > 0)
        assertTrue("the shelf needs no token: the book is read BEFORE the gate", gate > seed)
        assertTrue("a failed read waits the whole tick", poll.contains("DashboardPoll.delayAfter(failures)"))
        assertFalse("the fixed tick is back", poll.contains("delay(5_000)"))
    }
}
