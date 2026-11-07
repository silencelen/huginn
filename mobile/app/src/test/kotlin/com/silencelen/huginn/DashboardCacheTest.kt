package com.silencelen.huginn

import com.silencelen.huginn.data.DashboardCache
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.ui.DashboardBook
import com.silencelen.huginn.ui.DashboardPoll
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The project page shows the last dashboard at once (R5 bug 1, owner
 * 2026-11-07: "a projects main page sometime takes a long time to load and
 * seems to have no cache, and just shows 'nothing to show yet' for 5ish
 * seconds").
 *
 * [DashboardBook] in `:core` already covers a walk back within a process. What
 * was missing on the phone: the book dies with the process (Android reclaims
 * it, the app lock restarts the activity, a notification tap lands on a project
 * page in a fresh process), and a failed first read waited a whole tick in
 * silence. [DashboardCache] is the book's memory on disk; [DashboardPoll] is
 * the retry.
 *
 * NOTE this module is on org.junit: (message, expected, actual).
 */
class DashboardCacheTest {

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

    // ------------------------------------------------------------- the files

    @Test
    fun `what a project last said survives a process death, whole`() {
        val real = json.decodeFromString<ProjectDashboard>(fixture("project-dashboard.json"))
        val id = real.project!!.id
        DashboardCache(dir).write(id, real)
        // A NEW instance over the same directory is the next process.
        val back = DashboardCache(dir).read(id)
        assertEquals("the daemon's own shape must round-trip", real, back)
        assertEquals(real.members.size, back!!.members.size)
        assertEquals(real.generatedAt, back.generatedAt)
    }

    @Test
    fun `nothing kept, or a file that will not parse, reads as nothing rather than a crash`() {
        val cache = DashboardCache(dir)
        assertNull(cache.read("never"))
        dir.mkdirs()
        File(dir, DashboardCache.fileName("broken")).writeText("{ not json")
        assertNull("a half-written file is not a dashboard", cache.read("broken"))
        File(dir, DashboardCache.fileName("empty")).writeText("")
        assertNull(cache.read("empty"))
    }

    @Test
    fun `a newer answer replaces the file, and projects that are gone take theirs with them`() {
        val cache = DashboardCache(dir)
        cache.write("a", dashboard("a", at = 100))
        cache.write("a", dashboard("a", at = 105))
        cache.write("b", dashboard("b", at = 100))
        assertEquals(105L, cache.read("a")!!.generatedAt)
        cache.retain(setOf("a"))
        assertNull("b is gone from the list, so from the disk", cache.read("b"))
        assertEquals("a stays", 105L, cache.read("a")!!.generatedAt)
        cache.forget("a")
        assertNull(cache.read("a"))
    }

    @Test
    fun `the file name is a safe spelling of the id`() {
        val name = DashboardCache.fileName("../../etc/passwd")
        assertFalse("a path separator in a project id must not name a path", name.contains('/') || name.contains(".."))
        assertTrue(name.endsWith(".json"))
        assertEquals("6f0d2c41-0000-4000-8000-0000000000b2.json", DashboardCache.fileName("6f0d2c41-0000-4000-8000-0000000000b2"))
        assertEquals("_.json", DashboardCache.fileName(""))
    }

    // ------------------------------------------------------------- the first draw

    @Test
    fun `the page draws the kept dashboard at once, and the empty text only when none ever`() {
        val cache = DashboardCache(dir)
        val book = DashboardBook()
        // Never answered, in this process or any earlier one: the one honest
        // "Nothing to show yet."
        assertNull(cache.seed(book, "p"))
        assertNull(book.held("p"))

        // An earlier process kept one: it is drawn first, and the book holds it
        // so the poll's answer replaces it by the book's own rule.
        cache.write("p", dashboard("p", at = 100))
        val drawn = cache.seed(book, "p")
        assertEquals(100L, drawn!!.generatedAt)
        assertSame(drawn, book.held("p"))
        assertTrue("the poll's newer answer must still land", book.adopt("p", dashboard("p", at = 105)))
        assertFalse("...and the same stamp re-sent must not", book.adopt("p", dashboard("p", at = 105)))
    }

    @Test
    fun `within a process the book wins over an older file`() {
        val cache = DashboardCache(dir)
        val book = DashboardBook()
        cache.write("p", dashboard("p", at = 100))
        val live = dashboard("p", at = 200)
        book.adopt("p", live)
        assertSame("a reopen must not fall back to the disk", live, cache.seed(book, "p"))
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
    fun `the view model seeds the page from disk before its first read, keeps every answer, and retries soon`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val vm = File(root, "app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt").readText()
        val poll = vm.substringAfter("fun startDashboardPolling(id: String) {").substringBefore("fun stopDashboardPolling()")
        assertTrue("the poll body was not found", poll.length > 200)
        val seed = poll.indexOf("dashboardCache.seed(dashboards, id)")
        val gate = poll.indexOf("awaitReady()")
        assertTrue("the page is not seeded from the disk cache", seed > 0)
        assertTrue("the file needs no token: read it BEFORE the gate", gate > seed)
        assertTrue("an answer that moved is not kept for the next process", poll.contains("dashboardCache.write(id, d)"))
        assertTrue("a failed read waits the whole tick", poll.contains("DashboardPoll.delayAfter(failures)"))
        assertFalse("the fixed tick is back", poll.contains("delay(5_000)"))
        assertTrue("the disk must forget what the book forgets", vm.contains("dashboardCache.retain(live)"))
    }
}
