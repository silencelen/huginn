package com.silencelen.huginn.desktop.device

import com.silencelen.huginn.data.HuginnClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a failed work-events POST is worth retrying.
 *
 * The bug this guards: the flusher cleared `pending` before the post, so ANY
 * failure lost that batch of transcript lines. A transient blip must restore the
 * batch to the front and retry; a genuinely permanent status must stop instead of
 * hammering the same code twice a second for the life of the child.
 */
class DeviceRunnerTest {

    private fun http(code: Int) = HuginnClient.HuginnException(code, "HTTP $code")

    @Test
    fun `gone, unauthorised and over-cap are permanent`() {
        // 404 run gone (daemon restart), 401/403 token no longer authorises,
        // 400/413 batch rejected outright.
        listOf(400, 401, 403, 404, 413).forEach {
            assertTrue(DeviceRunner.isPermanentPostFailure(http(it)), "HTTP $it must be permanent")
        }
    }

    @Test
    fun `a blip is transient and keeps the batch`() {
        // 5xx, 429, a socket timeout: retry, do not drop the answer.
        listOf(500, 502, 503, 429, 408).forEach {
            assertFalse(DeviceRunner.isPermanentPostFailure(http(it)), "HTTP $it must be transient")
        }
        assertFalse(DeviceRunner.isPermanentPostFailure(RuntimeException("connection reset")))
    }
}
