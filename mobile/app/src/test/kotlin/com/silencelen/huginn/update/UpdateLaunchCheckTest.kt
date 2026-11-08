package com.silencelen.huginn.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch check: once, and check ONLY.
 *
 * The phone used to look for an update only when Settings opened. The view model
 * now asks as it starts, through [UpdateLaunchCheck] — and this is the proof that
 * a launch costs one listing of the release feed, raises the card for a newer
 * release and nothing for the one already installed.
 *
 * JUnit4 (this module's convention): `assertTrue(message, condition)` — message
 * FIRST.
 */
class UpdateLaunchCheckTest {

    private class CountingFeed(private val release: GhRelease, private val texts: Map<String, String>) : ReleaseFeed {
        override val repo: String = "alex/huginn"
        var lists = 0
        override suspend fun list(perPage: Int): List<GhRelease> { lists++; return listOf(release) }
        override suspend fun getText(url: String): String = texts[url] ?: throw ReleaseFeedException(404, url)
    }

    /** One published release, 2.60.0, whose manifest carries [versionCode]. */
    private fun feed(versionCode: Long): CountingFeed {
        val manifestUrl = "https://dl.example.net/2.60.0/latest.json"
        val release = GhRelease(tagName = "app-v2.60.0", assets = listOf(GhAsset("latest.json", manifestUrl, 100)))
        return CountingFeed(
            release,
            mapOf(manifestUrl to """{"package":"com.silencelen.huginn","versionCode":$versionCode,"versionName":"2.60.0","apk":"Huginn-2.60.0.apk","sha256":"${"a".repeat(64)}","sizeBytes":42,"notes":"- Faster lists."}"""),
        )
    }

    @Test
    fun `a launch lists the feed once, and a newer release raises the notice`() = runTest {
        val feed = feed(versionCode = 200)
        val updater = PhoneUpdater(installedVersionCode = 100, feed = feed)
        val launch = UpdateLaunchCheck()

        assertTrue("the first call checks", launch.runOnce { updater.check() })
        assertFalse("a second call (a resume, a recomposition) is a no-op", launch.runOnce { updater.check() })
        assertFalse(launch.runOnce { updater.check() })
        assertEquals("one listing per launch", 1, feed.lists)

        val n = phoneUpdateNotice(updater.state.value, later = null, silenced = null, storeInstalled = { true })
        assertTrue("found, so the card shows: ${updater.state.value}", n != null)
        assertEquals("2.60.0", n!!.version)
        assertEquals(UpdateHandoff.Store, n.handoff)
    }

    @Test
    fun `the release already installed raises nothing`() = runTest {
        val feed = feed(versionCode = 200)
        val updater = PhoneUpdater(installedVersionCode = 200, feed = feed)
        assertTrue(UpdateLaunchCheck().runOnce { updater.check() })
        assertEquals(1, feed.lists)
        assertTrue("expected UpToDate, got ${updater.state.value}", updater.state.value is AppUpdateState.UpToDate)
        assertNull(phoneUpdateNotice(updater.state.value, later = null, silenced = null, storeInstalled = { true }))
    }
}
