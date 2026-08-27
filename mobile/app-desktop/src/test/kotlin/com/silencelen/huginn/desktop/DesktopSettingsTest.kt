package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.HuginnSettings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ------------------------------------------------------------- landing

    @Test
    fun `a fresh install opens on sessions`() {
        // The owner's complaint, at the layer that answers it: nothing recorded
        // must mean Sessions, not whichever View constant happens to be first.
        // His own settings file predates the field and takes exactly this branch.
        assertEquals(View.SESSIONS, DesktopSettings(freshFile()).lastViewNow())
        assertEquals(null, DesktopSettings(freshFile()).lastChatIdNow())
        assertEquals(null, DesktopSettings(freshFile()).lastSessionNameNow())
    }

    @Test
    fun `the position survives a relaunch`() {
        val file = freshFile()
        DesktopSettings(file).setLanding(View.SESSIONS, chatId = "c9", sessionName = "jtyper")

        val next = DesktopSettings(file)
        assertEquals(View.SESSIONS, next.lastViewNow())
        assertEquals("c9", next.lastChatIdNow())
        assertEquals("jtyper", next.lastSessionNameNow())
    }

    @Test
    fun `an errand does not become the landing view`() {
        // Glancing at Status or Settings must not decide where the next launch
        // opens — and must not forget what was open behind it either.
        val file = freshFile()
        val settings = DesktopSettings(file)
        settings.setLanding(View.CHATS, chatId = "c1", sessionName = null)
        settings.setLanding(View.SETTINGS, chatId = "c1", sessionName = null)
        settings.setLanding(View.STATUS, chatId = "c1", sessionName = null)

        val next = DesktopSettings(file)
        assertEquals(View.CHATS, next.lastViewNow())
        assertEquals("c1", next.lastChatIdNow())
    }

    @Test
    fun `a closed target is recorded as closed`() {
        // Escape out of a chat, then quit: the next launch opens the list, not the
        // chat that was deliberately closed.
        val file = freshFile()
        val settings = DesktopSettings(file)
        settings.setLanding(View.CHATS, chatId = "c1", sessionName = null)
        settings.setLanding(View.CHATS, chatId = null, sessionName = null)
        assertEquals(null, DesktopSettings(file).lastChatIdNow())
    }

    // ------------------------------------------------ giving the enrolment back
    //
    // ⚠ The device id is the ONLY handle that can retire this machine's row at
    // the daemon. Turning the toggle off used to flip a boolean and nothing else,
    // so the row sat "not reachable" in everybody's device list for its full
    // thirty days. The fix is not to delete the id on the click — that loses the
    // handle exactly when the daemon is unreachable, which is exactly when a
    // machine is being decommissioned. The debt is RECORDED and paid off later.

    @Test
    fun `turning the offer off records what is still owed`() {
        val settings = DesktopSettings(freshFile())
        settings.setDeviceId("dev-1")
        settings.setDeviceEnabled(true)
        settings.setDeviceEnabled(false)

        assertTrue(settings.deviceUnenrolPendingNow(), "the row still has to be retired")
        assertEquals("dev-1", settings.deviceIdNow(), "and the only handle that can do it is kept")
    }

    @Test
    fun `a machine that never enrolled owes nothing when it is switched off`() {
        val settings = DesktopSettings(freshFile())
        settings.setDeviceEnabled(true)
        settings.setDeviceEnabled(false)
        // A pending flag here would keep the runner alive forever to retry a
        // DELETE against an id that does not exist.
        assertFalse(settings.deviceUnenrolPendingNow())
    }

    @Test
    fun `turning it back on withdraws the debt`() {
        // The runner is about to re-enrol under this same id, so deleting the row
        // would retire the enrolment being used right now.
        val settings = DesktopSettings(freshFile())
        settings.setDeviceId("dev-1")
        settings.setDeviceEnabled(false)
        assertTrue(settings.deviceUnenrolPendingNow())

        settings.setDeviceEnabled(true)
        assertFalse(settings.deviceUnenrolPendingNow())
        assertEquals("dev-1", settings.deviceIdNow())
    }

    @Test
    fun `the debt survives being closed before the daemon could be reached`() {
        // The case the flag exists for: a laptop retired and shut down on a train.
        val file = freshFile()
        DesktopSettings(file).apply {
            setDeviceId("dev-1")
            setDeviceEnabled(false)
        }

        val next = DesktopSettings(file)
        assertTrue(next.deviceUnenrolPendingNow(), "still owed after a relaunch")
        assertEquals("dev-1", next.deviceIdNow())
        assertFalse(next.deviceEnabledNow())
    }

    @Test
    fun `removal takes the token, the handle and the half-written messages`() {
        val settings = DesktopSettings(freshFile())
        runBlocking {
            settings.setToken("secret-token-value")
            settings.setDrafts(mapOf("chat-1" to "half a sentence"))
        }
        settings.setDeviceId("dev-1")
        settings.setDeviceEnabled(true)

        settings.clearForRemoval()

        assertEquals("", settings.tokenNow(), "the credential goes")
        assertEquals("", settings.deviceIdNow(), "the rows are already retired by now")
        assertFalse(settings.deviceEnabledNow())
        assertFalse(settings.deviceUnenrolPendingNow(), "nothing is owed once the rows are gone")
        assertEquals(emptyMap(), runBlocking { settings.drafts.first() })
    }

    @Test
    fun `removal keeps the address, which is not a credential`() {
        // The connect screen would only ask for the same one back.
        val settings = DesktopSettings(freshFile())
        runBlocking { settings.setBaseUrl(HuginnSettings.DEFAULT_BASE_URL) }
        settings.clearForRemoval()
        assertEquals(HuginnSettings.DEFAULT_BASE_URL, settings.baseUrlNow())
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
