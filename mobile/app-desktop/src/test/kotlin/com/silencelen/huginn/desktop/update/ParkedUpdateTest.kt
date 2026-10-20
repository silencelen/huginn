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
 * A downloaded, verified update stays offered until somebody installs it.
 *
 * The check runs every four hours and on "Check now", and the laptop it runs on
 * is offline some of that time; GitHub's unauthenticated API answers 403 some of
 * the rest. Neither says anything about the installer already in the cache, so
 * neither may take "Install and restart" off the Updates page.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual)`.
 */
class ParkedUpdateTest {

    /** The release feed, with a switch for "GitHub cannot be reached". */
    private class SwitchableFeed(var releases: List<GhRelease>, var texts: Map<String, String>) : ReleaseFeed {
        override val repo = "test/repo"
        var down = false
        override suspend fun list(perPage: Int): List<GhRelease> {
            if (down) throw ReleaseFeedException(403, "API rate limit exceeded")
            return releases
        }
        override suspend fun getText(url: String): String {
            if (down) throw ReleaseFeedException(403, "API rate limit exceeded")
            return texts[url] ?: throw ReleaseFeedException(404, "no such asset: $url")
        }
    }

    private class CountingHttp(val files: Map<String, ByteArray>) : UpdateHttp {
        val fetched = mutableListOf<String>()
        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            fetched += url
            val bytes = files[url] ?: throw UpdateHttpException(503, "artifact host down")
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun tmpDir(): File = File.createTempFile("parked", "").let { it.delete(); it.mkdirs(); dirs += it; it }

    private fun manifest(version: String, file: String, sha: String, size: Int) =
        """{"version":"$version","notes":"n","artifacts":{"windows-x64":{"file":"$file","sha256":"$sha","size":$size}}}"""

    /** A Windows release: its manifest and its installer, at test URLs. */
    private fun release(version: String, payload: ByteArray): Triple<GhRelease, Map<String, String>, Pair<String, ByteArray>> {
        val file = "Setup-$version.exe"
        val manifestUrl = "https://dl/$version/manifest.json"
        val artifactUrl = "https://dl/$version/$file"
        val rel = GhRelease(
            tagName = "desktop-v$version",
            assets = listOf(GhAsset("manifest.json", manifestUrl, 100), GhAsset(file, artifactUrl, payload.size.toLong())),
        )
        return Triple(rel, mapOf(manifestUrl to manifest(version, file, Sha256.ofBytes(payload), payload.size)), artifactUrl to payload)
    }

    private class Rig(val updater: DesktopUpdater, val feed: SwitchableFeed, val http: CountingHttp, val launched: MutableList<File>)

    private fun rig(vararg releases: Pair<String, ByteArray>): Rig {
        val made = releases.map { (v, bytes) -> release(v, bytes) }
        val feed = SwitchableFeed(made.map { it.first }, made.flatMap { it.second.entries }.associate { it.key to it.value })
        val http = CountingHttp(made.associate { it.third })
        val launched = mutableListOf<File>()
        val updater = DesktopUpdater(
            "0.1.0", "windows-x64", feed, http, "desktop-v", tmpDir(),
            isWindows = true, launcher = { launched += it; true },
        )
        return Rig(updater, feed, http, launched)
    }

    @Test
    fun `a check that cannot reach GitHub leaves the verified update installable`() = runTest {
        val r = rig("0.2.0" to "setup-0.2.0".toByteArray())
        val ready = r.updater.check()
        assertTrue(ready is UpdateState.Ready && ready.installable, "expected an installable Ready, got $ready")

        r.feed.down = true
        val offline = r.updater.check()
        assertEquals(ready, offline, "a failed check replaced the parked update")
        assertEquals(ready, r.updater.state.value, "the Updates page draws Install only while the state is Ready")
        assertTrue(r.updater.install(), "Install and restart must still work with GitHub out of reach")
        assertEquals(listOf("Setup-0.2.0.exe"), r.launched.map { it.name })
        assertEquals(1, r.http.fetched.size, "nothing was fetched twice")
    }

    @Test
    fun `a newer release that will not download leaves the one already parked`() = runTest {
        val r = rig("0.2.0" to "setup-0.2.0".toByteArray())
        val parked = r.updater.check()
        assertTrue(parked is UpdateState.Ready, "expected Ready, got $parked")

        // 0.3.0 is published, but its installer cannot be fetched right now.
        val (next, texts, _) = release("0.3.0", "setup-0.3.0".toByteArray())
        r.feed.releases = r.feed.releases + next
        r.feed.texts = r.feed.texts + texts
        val after = r.updater.check()
        assertEquals(parked, after, "0.2.0 is still verified and still newer than what runs")
    }

    @Test
    fun `a second healthy check settles on the parked update without fetching it again`() = runTest {
        val r = rig("0.2.0" to "setup-0.2.0".toByteArray())
        val first = r.updater.check()
        val second = r.updater.check()
        assertEquals(first, second)
        assertEquals(first, r.updater.state.value, "the state must not be left reading Checking")
        assertEquals(1, r.http.fetched.size)
    }

    @Test
    fun `a parked file that no longer verifies is not offered again by a failed check`() = runTest {
        val r = rig("0.2.0" to "setup-0.2.0".toByteArray())
        val ready = r.updater.check() as UpdateState.Ready
        ready.file.writeBytes("something else".toByteArray())

        r.feed.down = true
        val s = r.updater.check()
        assertTrue(s is UpdateState.Error, "a file that fails its hash is not installable, got $s")
        assertFalse(r.updater.install())
        assertTrue(r.launched.isEmpty(), "nothing unverified may be started")
    }
}
