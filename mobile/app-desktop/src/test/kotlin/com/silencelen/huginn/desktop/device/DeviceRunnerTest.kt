package com.silencelen.huginn.desktop.device

import com.silencelen.huginn.data.HuginnClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // ------------------------------------------------------- the work root
    //
    // ProcessBuilder.directory(null) means "inherit the JVM's own cwd", so a
    // `work`-scope device whose configured root no longer resolves (a typo in
    // the unvalidated Settings field, an unmounted drive, a renamed folder) ran
    // the job in the app's INSTALL TREE and reported success: `ask` answered
    // about the wrong tree and `act`, which carries Bash/Edit/Write with an
    // empty deny list, would have written into it — all while the Devices row
    // kept printing "work starts in <declared root>". The headless Node runner
    // has refused the same job since 2026-08-25; this is the same refusal, in
    // the same words, so one device's behaviour does not depend on which runner
    // answered the poll.

    @Test
    fun `a work root that is not a directory refuses the job in the node runner's words`() {
        val gone = File(System.getProperty("java.io.tmpdir"), "huginn-no-such-root-${System.nanoTime()}")
        assertEquals(
            "orion cannot start work: ${gone.path} is not a directory",
            DeviceRunner.cwdRefusal("orion", gone.path),
        )
    }

    @Test
    fun `a file is not a work root either, and a real directory is`() {
        val dir = Files.createTempDirectory("huginn-device-root").toFile()
        try {
            val f = File(dir, "notes.txt").apply { writeText("x") }
            assertEquals(
                "orion cannot start work: ${f.path} is not a directory",
                DeviceRunner.cwdRefusal("orion", f.path),
            )
            assertNull(DeviceRunner.cwdRefusal("orion", dir.path), "a real directory must run")
        } finally {
            dir.deleteRecursively()
        }
    }
}
