package com.silencelen.huginn.desktop.update

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The updater's decisions, each one tested where it is made.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`
 * — the REVERSE of JUnit's. With three String arguments both compile and one of
 * them asserts something else entirely. See the DESKTOP-MIGRATION trap list.
 */
class SemverTest {

    @Test
    fun `numeric components, not string ones`() {
        // The whole reason this class exists: as strings "0.10.0" < "0.9.0", so a
        // string compare stops the app updating forever at the tenth minor.
        assertTrue(Semver.isNewer("0.10.0", "0.9.0"))
        assertTrue(Semver.isNewer("1.0.0", "0.99.99"))
        assertTrue(Semver.isNewer("0.1.10", "0.1.9"))
        assertFalse(Semver.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `equal is not newer`() {
        assertFalse(Semver.isNewer("1.2.3", "1.2.3"))
        assertEquals(0, Semver.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `a pre-release loses to its own release`() {
        assertFalse(Semver.isNewer("0.2.0-rc1", "0.2.0"))
        assertTrue(Semver.isNewer("0.2.0", "0.2.0-rc1"))
        assertTrue(Semver.isNewer("0.2.0-rc2", "0.2.0-rc1"))
        assertTrue(Semver.isNewer("0.2.0-rc1", "0.1.9"))
    }

    @Test
    fun `build metadata is ignored for precedence`() {
        assertEquals(0, Semver.compare("1.2.3+build9", "1.2.3"))
    }

    @Test
    fun `garbage never reads as an update`() {
        assertFalse(Semver.isNewer("", "0.1.0"))
        assertFalse(Semver.isNewer("nightly", "0.1.0"))
        assertFalse(Semver.isNewer("9", "0.1.0"))
        assertFalse(Semver.isNewer("9.9", "0.1.0"))
        assertFalse(Semver.isNewer("../../etc", "0.1.0"))
        assertNull(Semver.parse("1.2.3.4"))
        assertFailsWith<IllegalArgumentException> { Semver.compare("x", "1.0.0") }
    }
}

class UpdateManifestTest {

    private val real = """
        {"version":"0.1.0","releasedAt":"2026-07-30T21:00:00Z","notes":"first",
         "artifacts":{
           "windows-x64":{"file":"Huginn-Desktop-Setup-0.1.0.exe","sha256":"aa","size":10},
           "linux-x64":{"file":"huginn-desktop-kt_0.1.0-1_amd64.deb","sha256":"bb","size":20}}}
    """.trimIndent()

    @Test
    fun `parses what the release script writes`() {
        val m = UpdateManifestCodec.parse(real)
        assertEquals("0.1.0", m.version)
        assertEquals("first", m.notes)
        assertEquals("Huginn-Desktop-Setup-0.1.0.exe", m.artifactFor("windows-x64")?.file)
        assertEquals(20L, m.artifactFor("linux-x64")?.size)
        assertNull(m.artifactFor("macos-arm64"))
    }

    @Test
    fun `an unknown platform key does not break an older client`() {
        // The forward-compatibility promise: adding macos to the feed must not
        // stop an installed windows build from updating.
        val withNew = real.replace(
            """"linux-x64":""",
            """"macos-arm64":{"file":"x.dmg","sha256":"cc","size":1,"newField":true},"linux-x64":""",
        )
        val m = UpdateManifestCodec.parse(withNew)
        assertEquals("Huginn-Desktop-Setup-0.1.0.exe", m.artifactFor("windows-x64")?.file)
    }

    @Test
    fun `a non-manifest is null, not an exception at the call site`() {
        assertNull(UpdateManifestCodec.parseOrNull("not json"))
        assertNull(UpdateManifestCodec.parseOrNull("""{"nope":1}"""))
        assertNull(UpdateManifestCodec.parseOrNull(""))
        // An HTML error page from a proxy is the realistic shape of this failure.
        assertNull(UpdateManifestCodec.parseOrNull("<html>401</html>"))
    }

    @Test
    fun `platform keys are only claimed for targets that are built`() {
        assertEquals("windows-x64", UpdatePlatform.current("Windows 11", "amd64"))
        assertEquals("linux-x64", UpdatePlatform.current("Linux", "amd64"))
        assertEquals("linux-x64", UpdatePlatform.current("Linux", "x86_64"))
        assertNull(UpdatePlatform.current("Mac OS X", "aarch64"))
        assertNull(UpdatePlatform.current("Linux", "aarch64"))
    }
}

class UpdateFeedTest {

    @Test
    fun `only the pinned bases are accepted`() {
        assertTrue(UpdateFeed.isPinned("http://100.97.198.90:8787"))
        assertTrue(UpdateFeed.isPinned("http://192.168.2.117:8787"))
        // Trailing slash and whitespace are the same address, not a bypass.
        assertTrue(UpdateFeed.isPinned("  http://100.97.198.90:8787/  "))
    }

    @Test
    fun `an arbitrary base is refused, because these builds are unsigned`() {
        // The threat: the app's SERVER setting is user-editable, so if the feed
        // were derived from it, one typo'd address is a "download and run this
        // .exe" primitive on the owner's machine.
        for (bad in listOf(
            "http://evil.example.com:8787",
            "https://100.97.198.90:8787",          // scheme is part of the pin
            "http://100.97.198.90:8788",           // so is the port
            "http://100.97.198.90.evil.com:8787",  // suffix trick
            "http://127.0.0.1:8787",               // allowed as an API host; NOT as a feed
            "",
        )) {
            assertFalse(UpdateFeed.isPinned(bad), "should refuse $bad")
            assertFailsWith<IllegalArgumentException>("should refuse $bad") {
                UpdateFeed.manifestUrl(bad)
            }
        }
    }

    @Test
    fun `the feed path is its own channel, never the Electron one`() {
        // Publishing Compose artifacts to /v1/desktop would offer the owner's
        // RUNNING Electron client an "update" that is a different application.
        assertEquals("/v1/desktop-kt", UpdateFeed.PATH)
        assertEquals(
            "http://100.97.198.90:8787/v1/desktop-kt/manifest",
            UpdateFeed.manifestUrl("http://100.97.198.90:8787"),
        )
    }

    @Test
    fun `an artifact name out of a manifest cannot steer the download`() {
        val base = UpdateFeed.PINNED_BASES.first()
        assertEquals("$base/v1/desktop-kt/a-1.0.0.exe", UpdateFeed.artifactUrl(base, "a-1.0.0.exe"))
        for (bad in listOf("../../etc/passwd", "a/b", "a\\b", ".hidden", "", "x".repeat(90), "a b.exe")) {
            assertFalse(UpdateFeed.isSafeArtifactName(bad), "should refuse $bad")
            assertFailsWith<IllegalArgumentException>("should refuse $bad") {
                UpdateFeed.artifactUrl(base, bad)
            }
        }
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
 * The sequencing, with the network replaced by a map. Nothing here touches a
 * socket or spawns a process.
 */
class DesktopUpdaterTest {

    private class FakeHttp(
        val texts: Map<String, String> = emptyMap(),
        val files: Map<String, ByteArray> = emptyMap(),
    ) : UpdateHttp {
        val fetched = mutableListOf<String>()
        override suspend fun getText(url: String, token: String): String {
            fetched += url
            return texts[url] ?: throw UpdateHttpException(404, "no such feed: $url")
        }
        override suspend fun download(url: String, token: String, dest: File, onProgress: (Long, Long) -> Unit) {
            fetched += url
            val bytes = files[url] ?: throw UpdateHttpException(404, "no such artifact: $url")
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private fun manifest(
        version: String,
        file: String,
        sha: String,
        size: Long,
        platform: String = "linux-x64",
    ) = """
        {"version":"$version","notes":"n","artifacts":{"$platform":{"file":"$file","sha256":"$sha","size":$size}}}
    """.trimIndent()

    private fun tmpDir(): File = File.createTempFile("upd", "").let { it.delete(); it.mkdirs(); it }

    private val base = UpdateFeed.PINNED_BASES.first()

    @Test
    fun `newer release is downloaded, verified and parked`() = runTest {
        val dir = tmpDir()
        val payload = "installer-bytes".toByteArray()
        val http = FakeHttp(
            texts = mapOf(
                UpdateFeed.manifestUrl(base) to
                    manifest("0.2.0", "app-0.2.0.deb", Sha256.ofBytes(payload), payload.size.toLong()),
            ),
            files = mapOf(UpdateFeed.artifactUrl(base, "app-0.2.0.deb") to payload),
        )
        val u = DesktopUpdater("0.1.0", { "a-token-long-enough" }, "linux-x64", http, listOf(base), dir, false)
        val s = u.check()
        assertTrue(s is UpdateState.Ready, "expected Ready, got $s")
        assertEquals("0.2.0", s.version)
        assertEquals(payload.size.toLong(), s.file.length())
        // Linux is told where the file is, never handed an installer to run.
        assertFalse(s.installable)
        assertFalse(u.install(), "install() must refuse on a non-installable platform")
        dir.deleteRecursively()
    }

    @Test
    fun `a hash mismatch is discarded, not offered`() = runTest {
        val dir = tmpDir()
        val payload = "tampered".toByteArray()
        val http = FakeHttp(
            texts = mapOf(
                UpdateFeed.manifestUrl(base) to
                    manifest("0.2.0", "app-0.2.0.deb", Sha256.ofBytes("what-was-built".toByteArray()), 9),
            ),
            files = mapOf(UpdateFeed.artifactUrl(base, "app-0.2.0.deb") to payload),
        )
        val u = DesktopUpdater("0.1.0", { "a-token-long-enough" }, "linux-x64", http, listOf(base), dir, false)
        val s = u.check()
        assertTrue(s is UpdateState.Error, "expected Error, got $s")
        assertTrue(s.message.contains("sha256"), s.message)
        // And the bad bytes are gone, so a later launch cannot find them lying there.
        assertFalse(File(dir, "app-0.2.0.deb").exists())
        assertFalse(u.install())
        dir.deleteRecursively()
    }

    @Test
    fun `same version is up to date and downloads nothing`() = runTest {
        val dir = tmpDir()
        val http = FakeHttp(
            texts = mapOf(UpdateFeed.manifestUrl(base) to manifest("0.1.0", "app-0.1.0.deb", "aa", 1)),
        )
        val u = DesktopUpdater("0.1.0", { "a-token-long-enough" }, "linux-x64", http, listOf(base), dir, false)
        assertEquals(UpdateState.UpToDate("0.1.0"), u.check())
        assertEquals(1, http.fetched.size, "must not fetch an artifact when up to date")
        dir.deleteRecursively()
    }

    @Test
    fun `an unpinned base is refused before any request is made`() = runTest {
        val dir = tmpDir()
        val http = FakeHttp()
        val u = DesktopUpdater(
            "0.1.0", { "a-token-long-enough" }, "linux-x64", http,
            listOf("http://evil.example.com:8787"), dir, false,
        )
        val s = u.check()
        assertTrue(s is UpdateState.Error, "expected Error, got $s")
        assertEquals(UpdateFeed.REFUSED, s.message)
        assertTrue(http.fetched.isEmpty(), "an unpinned feed must not be contacted at all")
        dir.deleteRecursively()
    }

    @Test
    fun `an already-verified download is not fetched twice`() = runTest {
        val dir = tmpDir()
        val payload = "installer-bytes".toByteArray()
        val url = UpdateFeed.artifactUrl(base, "app-0.2.0.deb")
        val http = FakeHttp(
            texts = mapOf(
                UpdateFeed.manifestUrl(base) to
                    manifest("0.2.0", "app-0.2.0.deb", Sha256.ofBytes(payload), payload.size.toLong()),
            ),
            files = mapOf(url to payload),
        )
        val u = DesktopUpdater("0.1.0", { "a-token-long-enough" }, "linux-x64", http, listOf(base), dir, false)
        u.check()
        u.check()
        // 90 MB every four hours while the owner has not restarted yet is the bug.
        assertEquals(1, http.fetched.count { it == url })
        dir.deleteRecursively()
    }

    @Test
    fun `a feed that answers with rubbish is an error, never an install`() = runTest {
        val dir = tmpDir()
        val http = FakeHttp(texts = mapOf(UpdateFeed.manifestUrl(base) to "<html>502</html>"))
        val u = DesktopUpdater("0.1.0", { "a-token-long-enough" }, "linux-x64", http, listOf(base), dir, false)
        assertTrue(u.check() is UpdateState.Error)
        dir.deleteRecursively()
    }

    @Test
    fun `no token means no request`() = runTest {
        val dir = tmpDir()
        val http = FakeHttp()
        val u = DesktopUpdater("0.1.0", { "  " }, "linux-x64", http, listOf(base), dir, false)
        assertTrue(u.check() is UpdateState.Error)
        assertTrue(http.fetched.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `a release with no build for this platform is reported, not guessed at`() = runTest {
        val dir = tmpDir()
        val http = FakeHttp(
            texts = mapOf(UpdateFeed.manifestUrl(base) to manifest("0.2.0", "app.deb", "aa", 1)),
        )
        val u = DesktopUpdater("0.1.0", { "a-token-long-enough" }, "windows-x64", http, listOf(base), dir, false)
        val s = u.check()
        assertTrue(s is UpdateState.Error, "expected Error, got $s")
        assertTrue(s.message.contains("windows-x64"), s.message)
        dir.deleteRecursively()
    }

    @Test
    fun `the second pinned route is tried when the first is unreachable`() = runTest {
        val dir = tmpDir()
        val alt = UpdateFeed.PINNED_BASES[1]
        val payload = "bytes".toByteArray()
        val http = FakeHttp(
            texts = mapOf(
                UpdateFeed.manifestUrl(alt) to
                    manifest("0.2.0", "app-0.2.0.deb", Sha256.ofBytes(payload), payload.size.toLong()),
            ),
            files = mapOf(UpdateFeed.artifactUrl(alt, "app-0.2.0.deb") to payload),
        )
        val u = DesktopUpdater(
            "0.1.0", { "a-token-long-enough" }, "linux-x64", http,
            UpdateFeed.PINNED_BASES, dir, false,
        )
        assertTrue(u.check() is UpdateState.Ready)
        dir.deleteRecursively()
    }

    @Test
    fun `install runs the parked file only when one is ready`() = runTest {
        val dir = tmpDir()
        val payload = "setup".toByteArray()
        val http = FakeHttp(
            texts = mapOf(
                UpdateFeed.manifestUrl(base) to
                    manifest("0.2.0", "Setup-0.2.0.exe", Sha256.ofBytes(payload), payload.size.toLong(), "windows-x64"),
            ),
            files = mapOf(UpdateFeed.artifactUrl(base, "Setup-0.2.0.exe") to payload),
        )
        val launched = mutableListOf<File>()
        val u = DesktopUpdater(
            "0.1.0", { "a-token-long-enough" }, "windows-x64", http, listOf(base), dir,
            isWindows = true, launcher = { launched += it; true },
        )
        assertFalse(u.install(), "nothing to install before a check")
        assertTrue(launched.isEmpty())

        val s = u.check()
        assertTrue(s is UpdateState.Ready, "expected Ready, got $s")
        assertTrue(s.installable)
        // Nothing auto-runs: the check completed and launched nothing.
        assertTrue(launched.isEmpty(), "check() must never start an installer")

        assertTrue(u.install())
        assertEquals(1, launched.size)
        assertEquals("Setup-0.2.0.exe", launched.single().name)
        dir.deleteRecursively()
    }
}
