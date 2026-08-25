package com.silencelen.huginn.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone updater's find/download/install sequencing, with GitHub and the
 * Android installer replaced by fakes — no Context, no PackageInstaller. Semver,
 * the release-index and the manifest models live in :core and are tested there.
 *
 * JUnit4 (this module's convention): `assertTrue(message, condition)` — message
 * FIRST — and no smart-cast contract, so an `is` assertion is followed by an
 * explicit cast.
 */
class PhoneUpdaterTest {

    private class FakeFeed(
        override val repo: String = "test/repo",
        val releases: List<GhRelease> = emptyList(),
        val texts: Map<String, String> = emptyMap(),
    ) : ReleaseFeed {
        override suspend fun list(perPage: Int): List<GhRelease> = releases
        override suspend fun getText(url: String): String =
            texts[url] ?: throw ReleaseFeedException(404, "no such asset: $url")
    }

    private fun latestJson(versionCode: Long, versionName: String, sha: String, notes: String = "n") =
        """{"package":"com.silencelen.huginn","versionCode":$versionCode,"versionName":"$versionName",
            "apk":"Huginn-$versionName.apk","sha256":"$sha","sizeBytes":42,"notes":"$notes"}""".trimIndent()

    /** A release carrying latest.json + its APK asset, at test URLs. */
    private fun release(versionCode: Long, versionName: String, sha: String): Pair<GhRelease, Map<String, String>> {
        val manifestUrl = "https://dl/$versionName/latest.json"
        val apkUrl = "https://dl/$versionName/Huginn-$versionName.apk"
        val r = GhRelease(
            tagName = "app-v$versionName",
            assets = listOf(
                GhAsset("latest.json", manifestUrl, 100),
                GhAsset("Huginn-$versionName.apk", apkUrl, 42),
            ),
        )
        return r to mapOf(manifestUrl to latestJson(versionCode, versionName, sha))
    }

    private fun tmpDir(): File = File.createTempFile("phu", "").let { it.delete(); it.mkdirs(); it }

    private fun updater(
        feed: FakeFeed,
        installedVc: Long,
        dir: File,
        bytes: Map<String, ByteArray> = emptyMap(),
        installed: MutableList<File> = mutableListOf(),
    ) = PhoneUpdater(
        installedVersionCode = installedVc,
        feed = feed,
        cacheDir = dir,
        downloader = { url, dest, prog ->
            val b = bytes[url] ?: throw IllegalStateException("no bytes for $url")
            dest.parentFile?.mkdirs(); dest.writeBytes(b); prog(b.size.toLong(), b.size.toLong())
        },
        installer = { installed += it; true },
    )

    @Test
    fun `a newer release is Available but not downloaded`() = runTest {
        val dir = tmpDir()
        val payload = "apk-bytes".toByteArray()
        val (rel, texts) = release(200, "2.60.0", Sha256.ofBytes(payload))
        val s = updater(FakeFeed(releases = listOf(rel), texts = texts), installedVc = 100, dir = dir).check()
        assertTrue("expected Available, got $s", s is AppUpdateState.Available)
        val a = s as AppUpdateState.Available
        assertEquals("2.60.0", a.versionName)
        assertEquals(200L, a.versionCode)
        assertFalse("check() must not download", File(dir, "Huginn-2.60.0.apk").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `an equal or older versionCode is up to date`() = runTest {
        val dir = tmpDir()
        val (rel, texts) = release(100, "2.59.0", "aa")
        val s = updater(FakeFeed(releases = listOf(rel), texts = texts), installedVc = 100, dir = dir).check()
        assertTrue("expected UpToDate, got $s", s is AppUpdateState.UpToDate)
        dir.deleteRecursively()
    }

    @Test
    fun `download verifies the sha256 and becomes Ready`() = runTest {
        val dir = tmpDir()
        val payload = "apk-bytes".toByteArray()
        val (rel, texts) = release(200, "2.60.0", Sha256.ofBytes(payload))
        val url = "https://dl/2.60.0/Huginn-2.60.0.apk"
        val u = updater(FakeFeed(releases = listOf(rel), texts = texts), 100, dir, bytes = mapOf(url to payload))
        assertTrue(u.check() is AppUpdateState.Available)
        val s = u.download()
        assertTrue("expected Ready, got $s", s is AppUpdateState.Ready)
        assertEquals(payload.size.toLong(), (s as AppUpdateState.Ready).file.length())
        dir.deleteRecursively()
    }

    @Test
    fun `a hash mismatch is discarded, never installed`() = runTest {
        val dir = tmpDir()
        val (rel, texts) = release(200, "2.60.0", Sha256.ofBytes("what-was-built".toByteArray()))
        val url = "https://dl/2.60.0/Huginn-2.60.0.apk"
        val installed = mutableListOf<File>()
        val u = updater(FakeFeed(releases = listOf(rel), texts = texts), 100, dir,
            bytes = mapOf(url to "tampered".toByteArray()), installed = installed)
        u.check()
        val s = u.download()
        assertTrue("expected Error, got $s", s is AppUpdateState.Error)
        assertTrue((s as AppUpdateState.Error).message.contains("sha256"))
        assertFalse(File(dir, "Huginn-2.60.0.apk").exists())
        assertFalse("a discarded download must not be installable", u.install())
        assertTrue(installed.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `install runs only when Ready, and hands over the verified file`() = runTest {
        val dir = tmpDir()
        val payload = "apk-bytes".toByteArray()
        val (rel, texts) = release(200, "2.60.0", Sha256.ofBytes(payload))
        val url = "https://dl/2.60.0/Huginn-2.60.0.apk"
        val installed = mutableListOf<File>()
        val u = updater(FakeFeed(releases = listOf(rel), texts = texts), 100, dir,
            bytes = mapOf(url to payload), installed = installed)
        assertFalse("nothing to install before a download", u.install())
        u.check(); u.download()
        assertTrue(u.install())
        assertEquals(1, installed.size)
        assertEquals("Huginn-2.60.0.apk", installed.single().name)
        dir.deleteRecursively()
    }

    @Test
    fun `download without a prior check is an error`() = runTest {
        val dir = tmpDir()
        assertTrue(updater(FakeFeed(), 100, dir).download() is AppUpdateState.Error)
        dir.deleteRecursively()
    }

    @Test
    fun `no app release published yet is an error`() = runTest {
        val dir = tmpDir()
        // Only a desktop release exists; the phone must not adopt it.
        val s = updater(FakeFeed(releases = listOf(GhRelease(tagName = "desktop-v0.6.0"))), 100, dir).check()
        assertTrue("expected Error, got $s", s is AppUpdateState.Error)
        assertTrue((s as AppUpdateState.Error).message.contains("mobile-v"))
        dir.deleteRecursively()
    }

    @Test
    fun `a release with no APK asset is refused`() = runTest {
        val dir = tmpDir()
        val manifestUrl = "https://dl/2.60.0/latest.json"
        val rel = GhRelease(
            tagName = "app-v2.60.0",
            assets = listOf(GhAsset("latest.json", manifestUrl, 100)), // no .apk
        )
        val feed = FakeFeed(releases = listOf(rel), texts = mapOf(manifestUrl to latestJson(200, "2.60.0", "aa")))
        val s = updater(feed, 100, dir).check()
        assertTrue("expected Error, got $s", s is AppUpdateState.Error)
        assertTrue((s as AppUpdateState.Error).message.contains("APK"))
        dir.deleteRecursively()
    }
}
