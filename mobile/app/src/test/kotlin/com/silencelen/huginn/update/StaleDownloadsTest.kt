package com.silencelen.huginn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The once-at-start sweep of what the old self-updater left in the cache: the
 * `updates/` directory goes whole, the rest of the cache is not touched, and a
 * phone that never downloaded one has nothing to sweep.
 *
 * JUnit4 (this module's convention): `assertTrue(message, condition)` — message
 * FIRST.
 */
class StaleDownloadsTest {

    private fun cache(): File = File.createTempFile("huc", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `the old download directory goes, whole`() {
        val cache = cache()
        val updates = File(cache, "updates").also { it.mkdirs() }
        File(updates, "Huginn-2.60.0.apk").writeBytes(ByteArray(10))
        File(updates, "Huginn-2.61.0.apk.part").writeBytes(ByteArray(3))
        assertTrue("nothing counted as removed", StaleDownloads.sweep(cache) > 0)
        assertFalse("the directory survived the sweep", updates.exists())
        cache.deleteRecursively()
    }

    @Test
    fun `the rest of the cache is not touched`() {
        val cache = cache()
        File(cache, "updates").mkdirs()
        val capture = File(File(cache, "captures").also { it.mkdirs() }, "img.jpg").also { it.writeBytes(ByteArray(5)) }
        val sent = File(File(cache, "sent").also { it.mkdirs() }, "notes.txt").also { it.writeText("x") }
        StaleDownloads.sweep(cache)
        assertTrue("a capture went with the sweep", capture.isFile)
        assertTrue("a file Claude sent went with the sweep", sent.isFile)
        cache.deleteRecursively()
    }

    @Test
    fun `a phone that never downloaded one has nothing to sweep`() {
        val cache = cache()
        assertEquals(0, StaleDownloads.sweep(cache))
        assertFalse("the sweep created the directory it removes", File(cache, "updates").exists())
        // A cache directory that does not exist yet is not an error either.
        assertEquals(0, StaleDownloads.sweep(File(cache, "missing")))
        cache.deleteRecursively()
    }
}
