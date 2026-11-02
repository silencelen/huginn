package com.silencelen.huginn.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The launch check: once, and check ONLY.
 *
 * The phone used to look for an update only when Settings opened. The view model
 * now asks as it starts, through [UpdateLaunchCheck] — and this is the proof that
 * a launch costs one listing of the release feed and never a download (~50 MB,
 * maybe on mobile data) or an install (the system's question, on a tap).
 *
 * JUnit4 (this module's convention): `assertTrue(message, condition)` — message
 * FIRST.
 */
class UpdateLaunchCheckTest {

    private class CountingFeed(private val release: GhRelease, private val texts: Map<String, String>) : ReleaseFeed {
        override val repo: String = "test/repo"
        var lists = 0
        override suspend fun list(perPage: Int): List<GhRelease> { lists++; return listOf(release) }
        override suspend fun getText(url: String): String = texts[url] ?: throw ReleaseFeedException(404, url)
    }

    @Test
    fun `a launch lists the feed once and never downloads or installs`() = runTest {
        val dir = File.createTempFile("ulc", "").let { it.delete(); it.mkdirs(); it }
        val manifestUrl = "https://dl/2.60.0/latest.json"
        val release = GhRelease(
            tagName = "app-v2.60.0",
            assets = listOf(GhAsset("latest.json", manifestUrl, 100), GhAsset("Huginn-2.60.0.apk", "https://dl/2.60.0/Huginn-2.60.0.apk", 42)),
        )
        val feed = CountingFeed(
            release,
            mapOf(manifestUrl to """{"package":"com.silencelen.huginn","versionCode":200,"versionName":"2.60.0","apk":"Huginn-2.60.0.apk","sha256":"${"a".repeat(64)}","sizeBytes":42,"notes":"- Faster lists."}"""),
        )
        var downloads = 0
        var installs = 0
        val updater = PhoneUpdater(
            installedVersionCode = 100,
            feed = feed,
            cacheDir = dir,
            downloader = { _, _, _ -> downloads++ },
            installer = { installs++; true },
        )
        val launch = UpdateLaunchCheck()

        assertTrue("the first call checks", launch.runOnce { updater.check() })
        assertFalse("a second call (a resume, a recomposition) is a no-op", launch.runOnce { updater.check() })
        assertFalse(launch.runOnce { updater.check() })

        assertEquals("one listing per launch", 1, feed.lists)
        assertEquals("a launch never downloads", 0, downloads)
        assertEquals("a launch never installs", 0, installs)
        val s = updater.state.value
        assertTrue("found, not fetched: $s", s is AppUpdateState.Available)
        assertEquals("2.60.0", (s as AppUpdateState.Available).versionName)
        dir.deleteRecursively()
    }
}
