package com.silencelen.huginn.desktop.update

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
 * The bug these exist for: `start()` checked once and then slept four hours flat.
 * The app checks on launch, which on a fresh install is BEFORE the owner has
 * typed a token — so the first pass failed with "no daemon token yet" and
 * Settings went on saying so for four hours after the token was entered and
 * everything else in the app was visibly working. A wrong token later corrected
 * read the same way, as a stale 401.
 *
 * These run on `runTest`'s virtual clock, so four hours of waiting costs nothing.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual,
 * message)` — the REVERSE of JUnit's.
 */
class UpdaterScheduleTest {

    /** Counts passes. Answers with [text], or fails the fetch when it is null. */
    private class CountingHttp(private val text: String? = null) : UpdateHttp {
        val calls = AtomicInteger(0)

        override suspend fun getText(url: String, token: String): String {
            calls.incrementAndGet()
            return text ?: throw UpdateHttpException(503, "feed down")
        }

        override suspend fun download(
            url: String,
            token: String,
            dest: File,
            onProgress: (Long, Long) -> Unit,
        ): Unit = error("this test never reaches a download")
    }

    private fun tmpDir(): File = File.createTempFile("updsched", "").let { it.delete(); it.mkdirs(); it }

    private val base = UpdateFeed.PINNED_BASES.first()

    private fun updater(
        http: UpdateHttp,
        token: () -> String = { "a-token-long-enough" },
    ) = DesktopUpdater("0.1.0", token, "linux-x64", http, listOf(base), tmpDir(), false)

    /** Same version as the updater's own, so a pass settles UpToDate and fetches nothing else. */
    private fun upToDateManifest() =
        """{"version":"0.1.0","notes":"n","artifacts":{"linux-x64":{"file":"a.deb","sha256":"x","size":1}}}"""

    @Test
    fun `a failed pass retries in well under an hour`() = runTest {
        val http = CountingHttp(null)
        val job = updater(http).start(this)

        advanceTimeBy(10)
        assertEquals(1, http.calls.get(), "the launch check should have run")

        advanceTimeBy(DesktopUpdater.RETRY_MS + 1_000)
        assertTrue(http.calls.get() >= 2, "a failed check must retry, got ${http.calls.get()} passes")

        job.cancel()
    }

    @Test
    fun `entering the token ends the wait at once`() = runTest {
        // THE OWNER'S CASE. The app starts with no token, fails without even
        // asking the network, and must not sit on that answer once a token
        // exists.
        var token = ""
        val http = CountingHttp(upToDateManifest())
        val job = updater(http) { token }.start(this)

        advanceTimeBy(10)
        assertEquals(0, http.calls.get(), "with no token there is nothing to ask")
        assertTrue(
            updater(http) { "" }.state.value is UpdateState.Idle,
            "a fresh updater starts idle",
        )

        token = "a-token-long-enough"
        advanceTimeBy(DesktopUpdater.WAKE_MS * 2)
        assertTrue(http.calls.get() >= 1, "the token appearing should have triggered a check")

        job.cancel()
    }

    @Test
    fun `the error clears once the token works`() = runTest {
        var token = ""
        val http = CountingHttp(upToDateManifest())
        val u = updater(http) { token }
        val job = u.start(this)

        advanceTimeBy(10)
        val failed = u.state.value
        assertTrue(failed is UpdateState.Error, "expected an error with no token, got $failed")

        token = "a-token-long-enough"
        advanceTimeBy(DesktopUpdater.WAKE_MS * 2)
        // The whole point: Settings stops reporting a problem that is fixed.
        assertEquals(UpdateState.UpToDate("0.1.0"), u.state.value)

        job.cancel()
    }

    @Test
    fun `a successful pass still waits the whole interval`() = runTest {
        // The backoff must not turn a healthy client into a once-a-minute poller.
        val http = CountingHttp(upToDateManifest())
        val job = updater(http).start(this)

        advanceTimeBy(10)
        assertEquals(1, http.calls.get())

        advanceTimeBy(60 * 60 * 1000L) // an hour
        assertEquals(1, http.calls.get(), "a healthy check should not have run again inside the interval")

        advanceTimeBy(DesktopUpdater.INTERVAL_MS)
        assertTrue(http.calls.get() >= 2, "the four-hourly check should still happen")

        job.cancel()
    }

    @Test
    fun `a feed that stays down is backed off, not hammered`() = runTest {
        // Each pass writes a line to the diagnostics log, so a dead feed must not
        // fill it. Flat 30s retries across four hours would be ~480 passes.
        val http = CountingHttp(null)
        val job = updater(http).start(this)

        advanceTimeBy(4 * 60 * 60 * 1000L)

        val n = http.calls.get()
        assertTrue(n in 5..60, "expected a backed-off handful of retries over four hours, got $n")

        job.cancel()
    }

    @Test
    fun `the backoff resets once the feed comes back`() = runTest {
        // Otherwise the first outage leaves the client checking every half hour
        // forever, and the next real release is up to thirty minutes late.
        var down = true
        val good = upToDateManifest()
        val http = object : UpdateHttp {
            val calls = AtomicInteger(0)
            override suspend fun getText(url: String, token: String): String {
                calls.incrementAndGet()
                if (down) throw UpdateHttpException(503, "feed down")
                return good
            }
            override suspend fun download(
                url: String,
                token: String,
                dest: File,
                onProgress: (Long, Long) -> Unit,
            ): Unit = error("not reached")
        }
        val job = updater(http).start(this)

        advanceTimeBy(30 * 60 * 1000L) // half an hour of failures grows the backoff
        assertTrue(http.calls.get() >= 3, "should have retried while down")

        down = false
        advanceTimeBy(DesktopUpdater.RETRY_MAX_MS + 1_000) // the next scheduled retry succeeds
        val afterRecovery = http.calls.get()

        // Now healthy: the next pass is an interval away, not a backoff away.
        advanceTimeBy(60 * 60 * 1000L)
        assertEquals(
            afterRecovery,
            http.calls.get(),
            "after recovering it should be on the normal interval, not still backing off",
        )

        job.cancel()
    }
}
