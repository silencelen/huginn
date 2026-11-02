package com.silencelen.huginn.desktop.update

import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.Presence
import com.silencelen.huginn.update.GhAsset
import com.silencelen.huginn.update.GhRelease
import com.silencelen.huginn.update.ReleaseFeed
import com.silencelen.huginn.update.ReleaseFeedException
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The announcing surfaces keep the parked update while a re-check runs.
 *
 * ⚠ THE UPDATER READS `Checking` EVERY FOUR HOURS, for as long as GitHub takes to
 * answer. Read straight off its state, the card and the rail dot blinked out on
 * every tick and came back when the pass settled (owner, 2026-11-01). The parked
 * file is still on disk through the pass, and the pass re-settles it, so the
 * surfaces hold the version until the pass says otherwise.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class UpdateNoticeThroughCheckTest {

    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun ready(v: String) = UpdateState.Ready(v, File("/tmp/none"), "n", installable = false)

    @Test
    fun `the rule - Checking keeps what was parked, every other verdict replaces it`() {
        val r = ready("2.7.0")
        assertEquals(r, parkedThrough(null, r))
        assertEquals(r, parkedThrough(r, UpdateState.Checking), "a re-check is not a verdict")
        assertNull(parkedThrough(null, UpdateState.Checking))
        assertNull(parkedThrough(r, UpdateState.UpToDate("2.7.0")))
        assertNull(parkedThrough(r, UpdateState.Error("offline")))
        assertNull(parkedThrough(r, UpdateState.Downloading("2.8.0", 0, 10)), "something newer is coming")
        assertNull(parkedThrough(r, UpdateState.Idle))
        assertEquals(ready("2.8.0"), parkedThrough(r, ready("2.8.0")))
    }

    /** A feed whose SECOND listing hangs until the test lets it go: a re-check in flight. */
    private class GatedFeed(private val release: GhRelease, private val texts: Map<String, String>) : ReleaseFeed {
        override val repo: String = "test/repo"
        var lists = 0
        val secondGate = CompletableDeferred<Unit>()
        override suspend fun list(perPage: Int): List<GhRelease> {
            lists++
            if (lists >= 2) secondGate.await()
            return listOf(release)
        }
        override suspend fun getText(url: String): String = texts[url] ?: throw ReleaseFeedException(404, url)
    }

    private class FakeHttp(private val files: Map<String, ByteArray>) : UpdateHttp {
        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            val bytes = files[url] ?: throw UpdateHttpException(404, url)
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    @Test
    fun `the store's notice survives a re-check tick`() = runBlocking {
        val dir = Files.createTempDirectory("huginn-notice-test").toFile().also { dirs += it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val payload = "installer-bytes".toByteArray()
        val sha = Sha256.ofBytes(payload)
        val manifestUrl = "https://dl/2.7.0/manifest.json"
        val fileUrl = "https://dl/2.7.0/huginn-desktop-2.7.0.deb"
        val release = GhRelease(
            tagName = "desktop-v2.7.0",
            assets = listOf(GhAsset("manifest.json", manifestUrl, 100), GhAsset("huginn-desktop-2.7.0.deb", fileUrl, payload.size.toLong())),
        )
        val manifest = """{"version":"2.7.0","notes":"- Faster lists.","artifacts":{"linux-x64":{"file":"huginn-desktop-2.7.0.deb","sha256":"$sha","size":${payload.size}}}}"""
        val feed = GatedFeed(release, mapOf(manifestUrl to manifest))
        val updater = DesktopUpdater("0.1.0", "linux-x64", feed, FakeHttp(mapOf(fileUrl to payload)), "desktop-v", File(dir, "cache"), false)
        // Never started: nothing here reaches a daemon. The updater is driven by hand.
        val store = AppStore(DesktopSettings(File(dir, "settings.json")), Presence(), scope, updater = updater)

        val first = updater.check()
        assertTrue(first is UpdateState.Ready, "the fake release parks: $first")
        assertEquals("2.7.0", store.updateNotice.value?.version, "the card announces the parked update")
        assertEquals("2.7.0", store.updateMark.value?.version)

        // The four-hourly re-check: Checking, and GitHub has not answered yet.
        val pass = scope.launch { updater.check() }
        assertEquals(UpdateState.Checking, updater.state.value, "the pass is in flight")
        assertEquals("2.7.0", store.updateNotice.value?.version, "the card does not blink out while the pass runs")
        assertEquals("2.7.0", store.updateMark.value?.version, "nor the rail dot")

        feed.secondGate.complete(Unit)
        pass.join()
        assertTrue(updater.state.value is UpdateState.Ready, "the pass re-settled the parked file")
        assertEquals("2.7.0", store.updateNotice.value?.version)

        // And the answers still take it down.
        store.updateLater("2.7.0")
        assertNull(store.updateNotice.value, "Later hides the card")
        assertEquals("2.7.0", store.updateMark.value?.version, "but not the quiet marks")
        store.updateSilence("2.7.0")
        assertNull(store.updateMark.value)
    }
}
