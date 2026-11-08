package com.silencelen.huginn.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone updater finds a release and stops, with GitHub replaced by a fake:
 * no Context, no network, and nothing on disk. Semver, the release index and
 * the manifest models live in :core and are tested there.
 *
 * JUnit4 (this module's convention): `assertTrue(message, condition)` — message
 * FIRST — and no smart-cast contract, so an `is` assertion is followed by an
 * explicit cast.
 */
class PhoneUpdaterTest {

    private class FakeFeed(
        override val repo: String = "alex/huginn",
        val releases: List<GhRelease> = emptyList(),
        val texts: Map<String, String> = emptyMap(),
    ) : ReleaseFeed {
        override suspend fun list(perPage: Int): List<GhRelease> = releases
        override suspend fun getText(url: String): String =
            texts[url] ?: throw ReleaseFeedException(404, "no such asset: $url")
    }

    private fun latestJson(versionCode: Long, versionName: String, notes: String = "n") =
        """{"package":"com.silencelen.huginn","versionCode":$versionCode,"versionName":"$versionName",
            "apk":"Huginn-$versionName.apk","sha256":"${"a".repeat(64)}","sizeBytes":42,"notes":"$notes"}""".trimIndent()

    /** A release carrying latest.json, at a test URL. */
    private fun release(versionCode: Long, versionName: String, notes: String = "n"): Pair<GhRelease, Map<String, String>> {
        val manifestUrl = "https://dl.example.net/$versionName/latest.json"
        val r = GhRelease(tagName = "app-v$versionName", assets = listOf(GhAsset("latest.json", manifestUrl, 100)))
        return r to mapOf(manifestUrl to latestJson(versionCode, versionName, notes))
    }

    private fun updater(feed: FakeFeed, installedVc: Long) =
        PhoneUpdater(installedVersionCode = installedVc, feed = feed)

    @Test
    fun `a build with update checks off never asks the feed`() = runTest {
        val asked = object : ReleaseFeed {
            override val repo: String = "alex/huginn"
            override suspend fun list(perPage: Int): List<GhRelease> = error("a disabled updater listed releases")
            override suspend fun getText(url: String): String = error("a disabled updater fetched $url")
        }
        val s = PhoneUpdater(installedVersionCode = 1, feed = asked, enabled = false).check()
        assertTrue("expected the disabled message, got $s",
            s is AppUpdateState.Error && s.message == PhoneUpdater.DISABLED)
    }

    @Test
    fun `a newer release is Available, with its notes and its release page`() = runTest {
        val (rel, texts) = release(200, "2.60.0", notes = "- Faster lists.")
        val s = updater(FakeFeed(releases = listOf(rel), texts = texts), installedVc = 100).check()
        assertTrue("expected Available, got $s", s is AppUpdateState.Available)
        val a = s as AppUpdateState.Available
        assertEquals("2.60.0", a.versionName)
        assertEquals(200L, a.versionCode)
        assertEquals("- Faster lists.", a.notes)
        // The page of the tag that was found, in the pinned repo — not a guess from the version.
        assertEquals("https://github.com/alex/huginn/releases/tag/app-v2.60.0", a.releasePageUrl)
    }

    @Test
    fun `an equal or older versionCode is up to date`() = runTest {
        val (rel, texts) = release(100, "2.59.0")
        val feed = FakeFeed(releases = listOf(rel), texts = texts)
        val same = updater(feed, installedVc = 100).check()
        assertTrue("expected UpToDate, got $same", same is AppUpdateState.UpToDate)
        val older = updater(feed, installedVc = 150).check()
        assertTrue("expected UpToDate, got $older", older is AppUpdateState.UpToDate)
    }

    @Test
    fun `no app release published yet is an error`() = runTest {
        // Only a desktop release exists; the phone must not adopt it.
        val s = updater(FakeFeed(releases = listOf(GhRelease(tagName = "desktop-v0.6.0"))), 100).check()
        assertTrue("expected Error, got $s", s is AppUpdateState.Error)
        assertTrue((s as AppUpdateState.Error).message.contains("mobile-v"))
    }

    @Test
    fun `a release without its manifest is an error, not a guess from the tag`() = runTest {
        val rel = GhRelease(
            tagName = "app-v2.60.0",
            assets = listOf(GhAsset("Huginn-2.60.0.apk", "https://dl.example.net/2.60.0/Huginn-2.60.0.apk", 42)),
        )
        val s = updater(FakeFeed(releases = listOf(rel)), 100).check()
        assertTrue("expected Error, got $s", s is AppUpdateState.Error)
        assertTrue((s as AppUpdateState.Error).message.contains("latest.json"))
    }

    @Test
    fun `a manifest that cannot be read is an error`() = runTest {
        val manifestUrl = "https://dl.example.net/2.60.0/latest.json"
        val rel = GhRelease(tagName = "app-v2.60.0", assets = listOf(GhAsset("latest.json", manifestUrl, 100)))
        val feed = FakeFeed(releases = listOf(rel), texts = mapOf(manifestUrl to "<html>rate limited</html>"))
        val s = updater(feed, 100).check()
        assertTrue("expected Error, got $s", s is AppUpdateState.Error)
        assertTrue((s as AppUpdateState.Error).message.contains("manifest"))
    }
}
