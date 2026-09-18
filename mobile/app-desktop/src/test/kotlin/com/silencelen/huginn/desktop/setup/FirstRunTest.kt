package com.silencelen.huginn.desktop.setup

import com.silencelen.huginn.settings.SetupStep
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The installer's answer file, as a FILE.
 *
 * `SetupFlowTest` covers the text: junk, a newer schema, a missing block. This
 * covers the half only a filesystem has — no file at all (which is every Linux
 * install, since the `.deb` asks nothing), a file that cannot be read, and the
 * rule that makes the whole channel safe: it is CONSUMED.
 *
 * ⚠ CONSUMED, AND THAT IS THE LOAD-BEARING PART. A pre-answer that survives is a
 * pre-answer that re-answers — somebody who turned local serving off in Settings
 * would find "Run setup again" skipping the step forever on the strength of a
 * tick they made at install time.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class FirstRunTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun configDir(): File =
        Files.createTempDirectory("huginn-first-run").toFile().also { dirs += it }

    private fun write(dir: File, text: String) = FirstRun.file(dir).writeText(text)

    @Test
    fun `no file means no pre-answers, which is every deb install`() {
        // The `.deb` has no postinst and debconf in a local unsigned package is
        // the wrong shape, so Linux meets the same flow carrying nothing. That
        // must not read as "the user declined everything".
        val dir = configDir()
        assertNull(FirstRun.read(dir))
        assertTrue(FirstRun.consume(dir).isEmpty)
    }

    @Test
    fun `a file the installer really writes is read into answers`() {
        val dir = configDir()
        write(
            dir,
            """
            {
              "version": 1,
              "source": "windows-installer",
              "features": {
                "claudePath": true,
                "device": true,
                "localAi": false,
                "autostart": true
              }
            }
            """.trimIndent(),
        )
        val pre = FirstRun.consume(dir)
        assertEquals(setOf(SetupStep.CLAUDE, SetupStep.DEVICE, SetupStep.AUTOSTART), pre.wanted)
        assertEquals(setOf(SetupStep.LOCAL_AI), pre.declined)
    }

    @Test
    fun `the file is deleted once it has been read`() {
        val dir = configDir()
        write(dir, """{"features":{"autostart":true}}""")
        assertTrue(FirstRun.file(dir).isFile)
        assertEquals(setOf(SetupStep.AUTOSTART), FirstRun.consume(dir).wanted)
        assertFalse(FirstRun.file(dir).exists(), "a surviving answer file re-answers on every launch")
        // And the second read is honestly empty rather than the same answers again.
        assertTrue(FirstRun.consume(dir).isEmpty)
    }

    @Test
    fun `a file that cannot be parsed is dropped rather than retried forever`() {
        val dir = configDir()
        write(dir, "{ this is not json")
        assertTrue(FirstRun.consume(dir).isEmpty, "junk must not become answers")
        assertFalse(
            FirstRun.file(dir).exists(),
            "junk that survives is junk re-parsed on every launch for the life of the install",
        )
    }

    @Test
    fun `an empty file is silence, not a decline`() {
        val dir = configDir()
        write(dir, "")
        assertNull(FirstRun.read(dir), "an empty file has nothing to say")
        assertTrue(FirstRun.consume(dir).isEmpty)
        assertFalse(FirstRun.file(dir).exists())
    }

    @Test
    fun `it sits beside settings json, under the name the installer writes`() {
        // ⚠ A CONTRACT WITH `huginn-desktop-kt.nsi`, which writes this path with
        // FileWrite and cannot be refactored by a compiler. The NSIS header
        // already documents the directory because its uninstaller has to find
        // settings.json there.
        val dir = configDir()
        assertEquals("first-run.json", FirstRun.NAME)
        assertEquals(File(dir, "first-run.json"), FirstRun.file(dir))
    }
}
