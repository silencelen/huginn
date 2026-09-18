package com.silencelen.huginn.desktop.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `X-Huginn-Notify` claim must reflect whether a notification can ACTUALLY be
 * rendered — not merely that the window is focused. A desktop that resolved to
 * [NoNotifier], or whose backend has proven itself broken, is not a route, and
 * claiming otherwise holds back the household Telegram fallback while every "needs
 * you" falls on the floor.
 */
class NotifyClaimTest {

    private class Stub(override val healthy: Boolean) : Notifier {
        override val name = "stub"
        override fun post(request: NotifyRequest) = Unit
        override fun withdraw(key: String) = Unit
    }

    @Test
    fun `NoNotifier can never deliver`() {
        assertFalse(NoNotifier.canDeliver())
    }

    @Test
    fun `a healthy backend can deliver, an unhealthy one cannot`() {
        assertTrue(Stub(healthy = true).canDeliver())
        assertFalse(Stub(healthy = false).canDeliver())
    }

    // ------------------------------------------------- libnotify, when the bus
    //                                                    is not actually there
    //
    // notify-send installed, DISPLAY set, and NO notification daemon answering
    // on the session bus is an ordinary Linux configuration (a bare WM, a
    // broken session, a container with a forwarded display). createOrNull
    // probes the BINARY and the display only, so it succeeds — and every post
    // then fails silently. With no `healthy` override, the desktop went on
    // stamping X-Huginn-Notify: 1, the daemon counted it as a live delivery
    // route, and each "needs you" was lost on screen AND held back from the
    // Telegram fallback (routeAlerts deliver:0 held:1). A held alert consumes
    // the transition edge, so the loss is permanent per event.

    private fun request(key: String = "k") = NotifyRequest(
        key = key, title = "t", body = "b", urgent = true, target = NavTarget(TargetKind.SESSIONS, "jtyper"),
    )

    @Test
    fun `libnotify that cannot reach a notification daemon stops claiming to be a route`() {
        val notifier = LibnotifyNotifier(canClose = false) { _, _ -> null }
        assertTrue(notifier.canDeliver(), "it has not failed yet")
        notifier.post(request())
        assertFalse(notifier.healthy, "a failed notify-send must be recorded")
        assertFalse(notifier.canDeliver(), "and must not hold back the Telegram fallback")
    }

    @Test
    fun `a libnotify that works keeps its claim`() {
        val notifier = LibnotifyNotifier(canClose = false) { _, _ -> "42\n" }
        notifier.post(request())
        assertTrue(notifier.healthy)
        assertTrue(notifier.canDeliver())
    }

    @Test
    fun `once libnotify has failed the next notification goes to the fallback`() {
        val libnotify = LibnotifyNotifier(canClose = false) { _, _ -> null }
        val seen = mutableListOf<String>()
        val second = object : Notifier {
            override val name = "awt"
            override fun post(request: NotifyRequest) { seen += request.key }
            override fun withdraw(key: String) = Unit
        }
        val chain = FallbackNotifier(libnotify, second)
        chain.post(request("one"))
        chain.post(request("two"))
        // The first is spent proving the primary is dead; everything after it
        // has somewhere to go.
        assertEquals(listOf("two"), seen)
    }

    @Test
    fun `a hung notify-send is abandoned at the timeout, not read to EOF`() {
        // `readBytes()` blocked until the child's stdout closed, so the 4s
        // TIMEOUT_MS was never reached and each post against a hung notify-send
        // cost 12 s measured — on the notification path of a live app.
        val started = System.nanoTime()
        val out = LibnotifyNotifier.runQuiet(listOf("sh", "-c", "sleep 6"), 400L)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertNull(out, "a command that did not finish has no output to trust")
        assertTrue(tookMs < 3_000, "the read must be bounded too: took ${tookMs}ms")
    }
}
