package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectRow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The per-project dashboard files both shells keep between runs.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class FileDashboardShelfTest {

    private val dirs = mutableListOf<File>()

    private fun dir(): File = Files.createTempDirectory("huginn-shelf").toFile().also { dirs += it }

    @AfterTest
    fun cleanup() { dirs.forEach { it.deleteRecursively() } }

    private fun dashboard(id: String, at: Long) = ProjectDashboard(
        project = ProjectRow(id = id, name = id, slug = id, kind = "software", status = "active"),
        generatedAt = at,
    )

    @Test
    fun `round trip through a directory - one file per project, junk skipped, nothing half-written left behind`() {
        val d = dir()
        val shelf = FileDashboardShelf(d)
        shelf.save("a", dashboard("a", at = 1))
        shelf.save("b", dashboard("b", at = 2))
        shelf.save("a", dashboard("a", at = 3)) // replaced in place
        File(d, "junk.json").writeText("not json")
        File(d, "half.json.tmp").writeText("{")

        val loaded = FileDashboardShelf(d).load()
        assertEquals(setOf("a", "b"), loaded.keys)
        assertEquals(3L, loaded["a"]?.generatedAt, "the later save won")
        assertEquals(2, d.listFiles { f -> f.name.endsWith(".json") && f.name != "junk.json" }?.size)
        assertFalse(File(d, "a.json.tmp").exists(), "the temp file was renamed over the target")

        shelf.remove("a")
        assertEquals(setOf("b"), FileDashboardShelf(d).load().keys)
    }

    @Test
    fun `a directory that does not exist yet is an empty shelf, and the first save creates it`() {
        val d = File(dir(), "nested/dashboards")
        assertEquals(emptyMap(), FileDashboardShelf(d).load())
        FileDashboardShelf(d).save("p", dashboard("p", at = 9))
        assertEquals(9L, FileDashboardShelf(d).load()["p"]?.generatedAt)
    }

    @Test
    fun `an id that is not a file name still gets its own file, inside the directory`() {
        val d = dir()
        val shelf = FileDashboardShelf(d)
        val odd = listOf("a/b", "a b", "..", "a_b", "../../etc/passwd", "")
        val files = odd.map { shelf.fileFor(it) }
        assertEquals(files.size, files.map { it.name }.toSet().size, "distinct files for distinct ids: ${files.map { it.name }}")
        files.forEach { f ->
            assertEquals(d.absoluteFile, f.absoluteFile.parentFile, "never outside the directory: $f")
            assertTrue(f.name.endsWith(".json"))
        }
        // The sanitised name is only a handle: the id read back is the real one.
        shelf.save("a/b", dashboard("a/b", at = 4))
        shelf.save("a b", dashboard("a b", at = 5))
        val loaded = shelf.load()
        assertEquals(4L, loaded["a/b"]?.generatedAt)
        assertEquals(5L, loaded["a b"]?.generatedAt)
        assertNotEquals(shelf.fileFor("a/b"), shelf.fileFor("a_b"), "an id that already IS a file name keeps it; the other is hashed")
    }
}
