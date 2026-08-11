package com.silencelen.huginn.desktop.update

import com.silencelen.huginn.update.GhAsset
import com.silencelen.huginn.update.GhRelease
import com.silencelen.huginn.update.ReleaseFeed
import com.silencelen.huginn.update.ReleaseFeedException
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop-specific update pieces: which platform key this build claims, the
 * hash gate, and the sequencing — the network replaced by fakes. Semver, the
 * manifest models and the release-index live in :core and are tested there.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual)`.
 */
class UpdatePlatformTest {

    @Test
    fun `platform keys are only claimed for targets that are built`() {
        assertEquals("windows-x64", UpdatePlatform.current("Windows 11", "amd64"))
        assertEquals("linux-x64", UpdatePlatform.current("Linux", "amd64"))
        assertEquals("linux-x64", UpdatePlatform.current("Linux", "x86_64"))
        assertNull(UpdatePlatform.current("Mac OS X", "aarch64"))
        assertNull(UpdatePlatform.current("Linux", "aarch64"))
    }
}

class Sha256Test {

    @Test
    fun `known vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.ofBytes(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.ofBytes("abc".toByteArray()),
        )
    }

    @Test
    fun `file hashing survives crossing the read buffer`() {
        val f = File.createTempFile("sha", ".bin")
        try {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            f.writeBytes(bytes)
            assertEquals(Sha256.ofBytes(bytes), Sha256.ofFile(f))
            assertTrue(Sha256.matches(f, Sha256.ofBytes(bytes).uppercase()))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `a hash that is not a hash never matches`() {
        val f = File.createTempFile("sha", ".bin")
        try {
            f.writeBytes("hello".toByteArray())
            // The half-written-manifest shapes. An empty or truncated expectation
            // must not pass for a file that hashes to anything.
            assertFalse(Sha256.matches(f, ""))
            assertFalse(Sha256.matches(f, "abc"))
            assertFalse(Sha256.matches(f, "z".repeat(64)))
            assertFalse(Sha256.matches(f, Sha256.ofBytes("hello!".toByteArray())))
            assertTrue(Sha256.matches(f, Sha256.ofBytes("hello".toByteArray())))
        } finally {
            f.delete()
        }
    }
}

/**
 * The sequencing, with GitHub replaced by fakes. Nothing here touches a socket or
 * spawns a process.
 */
class DesktopUpdaterTest {

    private class FakeFeed(
        override val repo: String = "test/repo",
        val releases: List<GhRelease> = emptyList(),
        val texts: Map<String, String> = emptyMap(),
    ) : ReleaseFeed {
        val fetched = mutableListOf<String>()
        override suspend fun list(perPage: Int): List<GhRelease> { fetched += "list"; return releases }
        override suspend fun getText(url: String): String {
            fetched += url
            return texts[url] ?: throw ReleaseFeedException(404, "no such asset: $url")
        }
    }

    private class FakeHttp(val files: Map<String, ByteArray> = emptyMap()) : UpdateHttp {
        val fetched = mutableListOf<String>()
        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            fetched += url
            val bytes = files[url] ?: throw UpdateHttpException(404, "no such artifact: $url")
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private fun manifestText(version: String, file: String, sha: String, size: Long, platform: String = "linux-x64") =
        """{"version":"$version","notes":"n","artifacts":{"$platform":{"file":"$file","sha256":"$sha","size":$size}}}"""

    /** A release carrying a manifest asset and its artifact asset, at test URLs. */
    private fun release(
        version: String,
        file: String,
        sha: String,
        size: Long,
        platform: String = "linux-x64",
    ): Pair<GhRelease, Map<String, String>> {
        val manifestUrl = "https://dl/$version/manifest.json"
        val artifactUrl = "https://dl/$version/$file"
        val r = GhRelease(
            tagName = "desktop-v$version",
            assets = listOf(
                GhAsset("manifest.json", manifestUrl, 100),
                GhAsset(file, artifactUrl, size),
            ),
        )
        return r to mapOf(manifestUrl to manifestText(version, file, sha, size, platform))
    }

    private fun tmpDir(): File = File.createTempFile("upd", "").let { it.delete(); it.mkdirs(); it }

    private fun updater(feed: FakeFeed, http: FakeHttp, dir: File, platform: String = "linux-x64", current: String = "0.1.0") =
        DesktopUpdater(current, platform, feed, http, "desktop-v", dir, false)

    @Test
    fun `newer release is downloaded, verified and parked`() = runTest {
        val dir = tmpDir()
        val payload = "installer-bytes".toByteArray()
        val (rel, texts) = release("0.2.0", "app-0.2.0.deb", Sha256.ofBytes(payload), payload.size.toLong())
        val feed = FakeFeed(releases = listOf(rel), texts = texts)
        val http = FakeHttp(files = mapOf("https://dl/0.2.0/app-0.2.0.deb" to payload))
        val s = updater(feed, http, dir).check()
        assertTrue(s is UpdateState.Ready, "expected Ready, got $s")
        assertEquals("0.2.0", s.version)
        assertEquals(payload.size.toLong(), s.file.length())
        assertFalse(s.installable) // Linux is told where the file is, never handed an installer
        dir.deleteRecursively()
    }

    @Test
    fun `a hash mismatch is discarded, not offered`() = runTest {
        val dir = tmpDir()
        val payload = "tampered".toByteArray()
        val (rel, texts) = release("0.2.0", "app-0.2.0.deb", Sha256.ofBytes("what-was-built".toByteArray()), 9)
        val feed = FakeFeed(releases = listOf(rel), texts = texts)
        val http = FakeHttp(files = mapOf("https://dl/0.2.0/app-0.2.0.deb" to payload))
        val s = updater(feed, http, dir).check()
        assertTrue(s is UpdateState.Error, "expected Error, got $s")
        assertTrue(s.message.contains("sha256"), s.message)
        assertFalse(File(dir, "app-0.2.0.deb").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `same version is up to date and downloads nothing`() = runTest {
        val dir = tmpDir()
        val (rel, texts) = release("0.1.0", "app-0.1.0.deb", "aa", 1)
        val feed = FakeFeed(releases = listOf(rel), texts = texts)
        val http = FakeHttp()
        assertEquals(UpdateState.UpToDate("0.1.0"), updater(feed, http, dir).check())
        assertTrue(http.fetched.isEmpty(), "must not fetch an artifact when up to date")
        dir.deleteRecursively()
    }

    @Test
    fun `newest is picked by semver among several releases`() = runTest {
        val dir = tmpDir()
        val payload = "bytes".toByteArray()
        val (r010, _) = release("0.10.0", "app-0.10.0.deb", Sha256.ofBytes(payload), payload.size.toLong())
        val (r09, _) = release("0.9.0", "app-0.9.0.deb", "aa", 1)
        val (r02, _) = release("0.2.0", "app-0.2.0.deb", "bb", 1)
        val feed = FakeFeed(
            releases = listOf(r09, r02, r010), // out of order; 0.10.0 wins by semver
            texts = mapOf("https://dl/0.10.0/manifest.json" to
                manifestText("0.10.0", "app-0.10.0.deb", Sha256.ofBytes(payload), payload.size.toLong())),
        )
        val http = FakeHttp(files = mapOf("https://dl/0.10.0/app-0.10.0.deb" to payload))
        val s = updater(feed, http, dir).check()
        assertTrue(s is UpdateState.Ready, "expected Ready, got $s")
        assertEquals("0.10.0", s.version)
        dir.deleteRecursively()
    }

    @Test
    fun `a release with no manifest asset is refused, never guessed at`() = runTest {
        val dir = tmpDir()
        // A release tagged newer but carrying only a binary — no manifest to hash against.
        val rel = GhRelease(
            tagName = "desktop-v0.2.0",
            assets = listOf(GhAsset("app-0.2.0.deb", "https://dl/0.2.0/app-0.2.0.deb", 1)),
        )
        val feed = FakeFeed(releases = listOf(rel))
        val http = FakeHttp()
        val s = updater(feed, http, dir).check()
        assertTrue(s is UpdateState.Error, "expected Error, got $s")
        assertTrue(s.message.contains("manifest.json"), s.message)
        assertTrue(http.fetched.isEmpty(), "nothing is downloaded when it cannot be verified")
        dir.deleteRecursively()
    }

    @Test
    fun `no desktop release published yet is reported, not treated as up to date`() = runTest {
        val dir = tmpDir()
        // Only an app-v release exists; the desktop updater must not adopt it.
        val feed = FakeFeed(releases = listOf(GhRelease(tagName = "app-v3.0.0")))
        val s = updater(feed, FakeHttp(), dir).check()
        assertTrue(s is UpdateState.Error, "expected Error, got $s")
        assertTrue(s.message.contains("desktop-v"), s.message)
        dir.deleteRecursively()
    }

    @Test
    fun `an already-verified download is not fetched twice`() = runTest {
        val dir = tmpDir()
        val payload = "installer-bytes".toByteArray()
        val (rel, texts) = release("0.2.0", "app-0.2.0.deb", Sha256.ofBytes(payload), payload.size.toLong())
        val url = "https://dl/0.2.0/app-0.2.0.deb"
        val feed = FakeFeed(releases = listOf(rel), texts = texts)
        val http = FakeHttp(files = mapOf(url to payload))
        val u = updater(feed, http, dir)
        u.check(); u.check()
        assertEquals(1, http.fetched.count { it == url }, "must not re-download while parked")
        dir.deleteRecursively()
    }

    @Test
    fun `a manifest that answers with rubbish is an error, never an install`() = runTest {
        val dir = tmpDir()
        val rel = GhRelease(
            tagName = "desktop-v0.2.0",
            assets = listOf(GhAsset("manifest.json", "https://dl/0.2.0/manifest.json", 1)),
        )
        val feed = FakeFeed(releases = listOf(rel), texts = mapOf("https://dl/0.2.0/manifest.json" to "<html>502</html>"))
        assertTrue(updater(feed, FakeHttp(), dir).check() is UpdateState.Error)
        dir.deleteRecursively()
    }

    @Test
    fun `a release with no build for this platform is reported, not guessed at`() = runTest {
        val dir = tmpDir()
        val (rel, texts) = release("0.2.0", "app.deb", "aa", 1, platform = "linux-x64")
        val feed = FakeFeed(releases = listOf(rel), texts = texts)
        val s = updater(feed, FakeHttp(), dir, platform = "windows-x64").check()
        assertTrue(s is UpdateState.Error, "expected Error, got $s")
        assertTrue(s.message.contains("windows-x64"), s.message)
        dir.deleteRecursively()
    }

    @Test
    fun `an artifact the manifest names but the release does not carry is refused`() = runTest {
        val dir = tmpDir()
        // Manifest points at app-0.2.0.deb, but the release has no such asset.
        val rel = GhRelease(
            tagName = "desktop-v0.2.0",
            assets = listOf(GhAsset("manifest.json", "https://dl/0.2.0/manifest.json", 1)),
        )
        val feed = FakeFeed(
            releases = listOf(rel),
            texts = mapOf("https://dl/0.2.0/manifest.json" to manifestText("0.2.0", "app-0.2.0.deb", "aa", 1)),
        )
        val s = updater(feed, FakeHttp(), dir).check()
        assertTrue(s is UpdateState.Error, "expected Error, got $s")
        assertTrue(s.message.contains("app-0.2.0.deb"), s.message)
        dir.deleteRecursively()
    }

    @Test
    fun `install runs the parked file only when one is ready`() = runTest {
        val dir = tmpDir()
        val payload = "setup".toByteArray()
        val (rel, texts) = release("0.2.0", "Setup-0.2.0.exe", Sha256.ofBytes(payload), payload.size.toLong(), "windows-x64")
        val feed = FakeFeed(releases = listOf(rel), texts = texts)
        val http = FakeHttp(files = mapOf("https://dl/0.2.0/Setup-0.2.0.exe" to payload))
        val launched = mutableListOf<File>()
        val u = DesktopUpdater(
            "0.1.0", "windows-x64", feed, http, "desktop-v", dir,
            isWindows = true, launcher = { launched += it; true },
        )
        assertFalse(u.install(), "nothing to install before a check")
        assertTrue(launched.isEmpty())

        val s = u.check()
        assertTrue(s is UpdateState.Ready, "expected Ready, got $s")
        assertTrue(s.installable)
        assertTrue(launched.isEmpty(), "check() must never start an installer")

        assertTrue(u.install())
        assertEquals(1, launched.size)
        assertEquals("Setup-0.2.0.exe", launched.single().name)
        dir.deleteRecursively()
    }
}
