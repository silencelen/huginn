package com.silencelen.huginn.desktop.update

import com.silencelen.huginn.update.GhAsset
import com.silencelen.huginn.update.GhRelease
import com.silencelen.huginn.update.ReleaseFeed
import com.silencelen.huginn.update.ReleaseFeedException
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Windows installer is hashed again at the moment it is started, not only
 * when it was downloaded.
 *
 * It waits in a cache between the two, and an installer runs with the elevation
 * it asks for: one swapped in after it was verified must never be started as the
 * update. Install is pressed straight after the swap here, with no check in
 * between to notice it first.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual)`.
 */
class InstallRehashTest {

    private class Feed(val releases: List<GhRelease>, val texts: Map<String, String>) : ReleaseFeed {
        override val repo = "test/repo"
        override suspend fun list(perPage: Int): List<GhRelease> = releases
        override suspend fun getText(url: String): String =
            texts[url] ?: throw ReleaseFeedException(404, "no such asset: $url")
    }

    private class Http(val files: Map<String, ByteArray>) : UpdateHttp {
        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            val bytes = files[url] ?: throw UpdateHttpException(503, "artifact host down")
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun tmpDir(): File = File.createTempFile("rehash", "").let { it.delete(); it.mkdirs(); dirs += it; it }

    /** A Windows updater offered 0.2.0, and the list of what it started. */
    private fun updater(launched: MutableList<File>): DesktopUpdater {
        val payload = "setup-0.2.0".toByteArray()
        val manifestUrl = "https://dl/0.2.0/manifest.json"
        val artifactUrl = "https://dl/0.2.0/Setup-0.2.0.exe"
        val manifest = """{"version":"0.2.0","notes":"n","artifacts":{"windows-x64":""" +
            """{"file":"Setup-0.2.0.exe","sha256":"${Sha256.ofBytes(payload)}","size":${payload.size}}}}"""
        val release = GhRelease(
            tagName = "desktop-v0.2.0",
            assets = listOf(GhAsset("manifest.json", manifestUrl, 100), GhAsset("Setup-0.2.0.exe", artifactUrl, payload.size.toLong())),
        )
        return DesktopUpdater(
            "0.1.0", "windows-x64", Feed(listOf(release), mapOf(manifestUrl to manifest)), Http(mapOf(artifactUrl to payload)),
            "desktop-v", tmpDir(), isWindows = true, launcher = { launched += it; true },
        )
    }

    @Test
    fun `an installer swapped after it was verified is not started`() = runTest {
        val launched = mutableListOf<File>()
        val u = updater(launched)
        val ready = u.check() as UpdateState.Ready
        ready.file.writeBytes("not the installer that was verified".toByteArray())

        assertFalse(u.install(), "install reported a start")
        assertTrue(launched.isEmpty(), "a file that fails its hash was started")
        assertTrue(u.state.value is UpdateState.Error, "expected an Error saying why, got ${u.state.value}")
    }

    @Test
    fun `the installer that was verified is still started`() = runTest {
        val launched = mutableListOf<File>()
        val u = updater(launched)
        val ready = u.check() as UpdateState.Ready
        assertTrue(u.install())
        assertEquals(listOf(ready.file), launched)
    }
}
