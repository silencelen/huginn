package com.silencelen.huginn.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The watch stream is re-opened whenever the notification claim it carries
 * changes, not only when presence does.
 *
 * `X-Huginn-Notify` is stamped on the request when the stream OPENS, and appd
 * re-reads it from that same request on every keepalive. So turning
 * Notifications off at the desk, or a notifier whose posts have started
 * failing, changed nothing the daemon could see: it went on holding the
 * Telegram fallback back for a window that would show nothing.
 *
 * What is asserted is the key the watch loop re-opens on ([AppStore.watchKey]):
 * every emission is one drop-and-reconnect. The store is never started and its
 * route book is empty, so nothing here can reach a daemon.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class WatchStreamClaimTest {

    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(): AppStore {
        val dir = Files.createTempDirectory("huginn-watch-claim").toFile()
        dirs += dir
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        return AppStore(DesktopSettings(File(dir, "settings.json")), Presence(), scope)
    }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `turning Notifications off re-opens the stream that carries the claim`() = runTest {
        val s = store()
        val opened = mutableListOf<Triple<Long, Boolean, Boolean>>()
        backgroundScope.launch { s.watchKey().collect { opened += it } }
        runCurrent()
        assertEquals(1, opened.size, "the stream opens once")

        s.settings.setNotifyEnabled(false)
        runCurrent()
        assertEquals(2, opened.size, "the switch must reach the daemon, and only a re-open carries it")
    }

    @Test
    fun `a notifier that stops delivering re-opens the stream within the recheck`() = runTest {
        val s = store()
        var deliver = true
        s.canDeliver = { deliver }
        val opened = mutableListOf<Triple<Long, Boolean, Boolean>>()
        backgroundScope.launch { s.watchKey().collect { opened += it } }
        runCurrent()

        deliver = false // libnotify's first post found no notification daemon
        advanceTimeBy(AppStore.CLAIM_RECHECK_MS + 1)
        assertEquals(2, opened.size, "a window that cannot show a notification must stop claiming them")
    }

    @Test
    fun `a claim that holds still re-opens nothing`() = runTest {
        val s = store()
        val opened = mutableListOf<Triple<Long, Boolean, Boolean>>()
        backgroundScope.launch { s.watchKey().collect { opened += it } }
        advanceTimeBy(10 * AppStore.CLAIM_RECHECK_MS)
        assertEquals(1, opened.size, "the recheck must not drop a healthy stream every few seconds")
    }
}
