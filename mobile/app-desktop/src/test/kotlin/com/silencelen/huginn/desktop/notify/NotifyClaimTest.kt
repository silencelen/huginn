package com.silencelen.huginn.desktop.notify

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
