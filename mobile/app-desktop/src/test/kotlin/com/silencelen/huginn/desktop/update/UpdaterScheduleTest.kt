package com.silencelen.huginn.desktop.update

import com.silencelen.huginn.update.GhRelease
import com.silencelen.huginn.update.ReleaseFeed
import com.silencelen.huginn.update.ReleaseFeedException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHEN the updater checks, as opposed to what it decides when it does.
 *
 * The bug these exist for: `start()` checked once and then slept four hours flat,
 * so a check that failed (a feed briefly down) kept the client reporting broken
 * for the rest of the interval. So a failed pass retries on a backoff, and a
 * healthy one waits the whole interval. (There is no token to wake on any more —
 * the feed is public GitHub — so the two token-wake tests are gone.)
 *
 * These run on `runTest`'s virtual clock, so four hours of waiting costs nothing.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual)`.
 */
class UpdaterScheduleTest {

    /** Counts passes. Returns [releases], or fails the list when it is null. */
    private class CountingFeed(
        private val releases: List<GhRelease>? = listOf(GhRelease(tagName = "desktop-v0.1.0")),
    ) : ReleaseFeed {
        override val repo = "test/repo"
        val calls = AtomicInteger(0)
        override suspend fun list(perPage: Int): List<GhRelease> {
            calls.incrementAndGet()
            return releases ?: throw ReleaseFeedException(503, "feed down")
        }
        override suspend fun getText(url: String): String = error("schedule tests never fetch a manifest")
    }

    /** The schedule tests never reach a download; a healthy pass settles UpToDate. */
    private object DeadHttp : UpdateHttp {
        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): Unit =
            error("this test never reaches a download")
    }

    private fun tmpDir(): File = File.createTempFile("updsched", "").let { it.delete(); it.mkdirs(); it }

    private fun updater(feed: ReleaseFeed) =
        DesktopUpdater("0.1.0", "linux-x64", feed, DeadHttp, "desktop-v", tmpDir(), false)

    @Test
    fun `a failed pass retries in well under an hour`() = runTest {
        val feed = CountingFeed(releases = null) // feed down
        val job = updater(feed).start(this)

        advanceTimeBy(10)
        assertEquals(1, feed.calls.get(), "the launch check should have run")

        advanceTimeBy(DesktopUpdater.RETRY_MS + 1_000)
        assertTrue(feed.calls.get() >= 2, "a failed check must retry, got ${feed.calls.get()} passes")

        job.cancel()
    }

    @Test
    fun `the first pass runs immediately, with no token to wait for`() = runTest {
        // GitHub is public: unlike the old private feed there is nothing to enter
        // before a check can succeed, so launch checks at once.
        val feed = CountingFeed()
        val u = updater(feed)
        val job = u.start(this)
        advanceTimeBy(10)
        assertEquals(1, feed.calls.get(), "launch must check straight away")
        assertEquals(UpdateState.UpToDate("0.1.0"), u.state.value)
        job.cancel()
    }

    @Test
    fun `a successful pass still waits the whole interval`() = runTest {
        val feed = CountingFeed()
        val job = updater(feed).start(this)

        advanceTimeBy(10)
        assertEquals(1, feed.calls.get())

        advanceTimeBy(60 * 60 * 1000L) // an hour
        assertEquals(1, feed.calls.get(), "a healthy check should not have run again inside the interval")

        advanceTimeBy(DesktopUpdater.INTERVAL_MS)
        assertTrue(feed.calls.get() >= 2, "the four-hourly check should still happen")

        job.cancel()
    }

    @Test
    fun `a feed that stays down is backed off, not hammered`() = runTest {
        // Each pass writes a line to the diagnostics log AND spends a GitHub API
        // call (60/hr unauthenticated), so a dead feed must not hammer either.
        val feed = CountingFeed(releases = null)
        val job = updater(feed).start(this)

        advanceTimeBy(4 * 60 * 60 * 1000L)

        val n = feed.calls.get()
        assertTrue(n in 5..60, "expected a backed-off handful of retries over four hours, got $n")

        job.cancel()
    }

    @Test
    fun `the backoff resets once the feed comes back`() = runTest {
        var down = true
        val feed = object : ReleaseFeed {
            override val repo = "test/repo"
            val calls = AtomicInteger(0)
            override suspend fun list(perPage: Int): List<GhRelease> {
                calls.incrementAndGet()
                if (down) throw ReleaseFeedException(503, "feed down")
                return listOf(GhRelease(tagName = "desktop-v0.1.0"))
            }
            override suspend fun getText(url: String): String = error("not reached")
        }
        val job = updater(feed).start(this)

        advanceTimeBy(30 * 60 * 1000L) // half an hour of failures grows the backoff
        assertTrue(feed.calls.get() >= 3, "should have retried while down")

        down = false
        advanceTimeBy(DesktopUpdater.RETRY_MAX_MS + 1_000) // the next scheduled retry succeeds
        val afterRecovery = feed.calls.get()

        // Now healthy: the next pass is an interval away, not a backoff away.
        advanceTimeBy(60 * 60 * 1000L)
        assertEquals(
            afterRecovery,
            feed.calls.get(),
            "after recovering it should be on the normal interval, not still backing off",
        )

        job.cancel()
    }
}
