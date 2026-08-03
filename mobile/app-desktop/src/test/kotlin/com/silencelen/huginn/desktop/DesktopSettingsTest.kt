package com.silencelen.huginn.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The identity this client presents to the daemon, and the file it keeps.
 *
 * The client id matters more than it looks: the daemon lists check-ins by it, and
 * decides from that list whether ANY client is a live notification route — which
 * is what holds back the household's Telegram fallback. A client that minted a
 * fresh id on every launch would fill that list with ghosts and make "is anyone
 * listening" unanswerable.
 */
class DesktopSettingsTest {

    private val dirs = mutableListOf<File>()

    private fun freshFile(): File {
        val dir = Files.createTempDirectory("huginn-settings-test").toFile()
        dirs += dir
        return File(dir, "settings.json")
    }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `the client id survives a restart`() {
        val file = freshFile()
        val first = DesktopSettings(file).clientIdNow()
        // A second construction is what a relaunch does: same file, new object.
        val second = DesktopSettings(file).clientIdNow()
        val third = DesktopSettings(file).clientIdNow()

        assertTrue(first.startsWith("desktop-kt-"), "id should name the client: $first")
        assertEquals(first, second, "a relaunch must not mint a new identity")
        assertEquals(first, third)
    }

    @Test
    fun `a separate install gets a separate identity`() {
        // Two config dirs are two installs, and they must not collide — this is
        // also why headless test runs with their own XDG_CONFIG_HOME legitimately
        // appear as distinct clients.
        assertNotEquals(DesktopSettings(freshFile()).clientIdNow(), DesktopSettings(freshFile()).clientIdNow())
    }

    @Test
    fun `the id is written to disk, not just held`() {
        val file = freshFile()
        val id = DesktopSettings(file).clientIdNow()
        assertTrue(file.exists(), "settings should have been written")
        assertTrue(file.readText().contains(id), "the id must be in the file, or a restart loses it")
    }

    @Test
    fun `a saved token survives, over and over, on top of an existing file`() = runBlocking {
        // THE REGRESSION. The swap used File.renameTo, which on Windows does not
        // replace an existing destination and returns false — inside a
        // runCatching that ignored it. So the FIRST save (no file yet) worked and
        // every save after it silently did nothing, and the owner's token
        // vanished across an update. This asserts the property rather than the
        // platform: write repeatedly over a file that already exists, and read it
        // back with a fresh instance each time.
        val file = freshFile()
        val s1 = DesktopSettings(file)
        s1.setToken("first-token")
        assertEquals("first-token", DesktopSettings(file).tokenNow())

        s1.setToken("second-token")
        assertEquals("second-token", DesktopSettings(file).tokenNow())

        // And through a different instance, which is what a relaunch is.
        DesktopSettings(file).setToken("third-token")
        assertEquals("third-token", DesktopSettings(file).tokenNow())
        assertTrue(file.readText().contains("third-token"))
    }

    @Test
    fun `no temp file is left behind`() = runBlocking {
        // A .tmp survivor means the swap did not happen — the shape of the bug
        // above, visible without knowing the platform's rename semantics.
        val file = freshFile()
        DesktopSettings(file).setToken("x")
        val leftovers = file.parentFile.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "settings.json.tmp was left behind: $leftovers")
    }

    @Test
    fun `a corrupt file is kept rather than silently overwritten`() = runBlocking {
        // Losing the settings is survivable; losing the only copy of the token
        // with no trace is not.
        val file = freshFile()
        file.parentFile.mkdirs()
        file.writeText("""{"token":"the-only-copy" NOT JSON""")
        DesktopSettings(file).setToken("replacement")
        val salvage = File(file.parentFile, file.name + ".corrupt")
        assertTrue(salvage.exists(), "a corrupt settings file should be preserved")
        assertTrue(salvage.readText().contains("the-only-copy"))
    }

    @Test
    fun `a corrupt settings file does not cost the identity twice over`() {
        // A file half-written by a killed process should cost the settings, not
        // stability: the client takes ONE new id and then keeps it.
        val file = freshFile()
        file.parentFile.mkdirs()
        file.writeText("{ not json")
        val recovered = DesktopSettings(file).clientIdNow()
        assertTrue(recovered.startsWith("desktop-kt-"))
        assertEquals(recovered, DesktopSettings(file).clientIdNow())
    }
}
