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

    // ------------------------------------------------- the retry note's end
    //
    // ⚠ THE SUCCESS BRANCH RESET `failures` AND NOTHING ELSE. The note is only
    // ever written where a poll FAILS, so one blip — a proxy restarted, a wifi
    // hiccup — pinned "Retrying: Failed to connect to /127.0.0.1:18820" under
    // "Available to huginn" for the rest of the app's life. Measured: 45 minutes
    // of `/work` 200s every 25s and `/beat` 200s every 60s, the daemon reporting
    // the device `online` with a current lastSeen, and Settings still calling it
    // failing. Only a restart of the app cleared it.
    //
    // Clearing the counter and clearing the sentence are the same event.

    @Test
    fun `a poll that comes back takes the retrying note off`() {
        assertEquals(
            "Enrolled, waiting for work",
            DeviceRunner.noteAfterPoll(
                DeviceRunner.RETRYING + "Failed to connect to /127.0.0.1:18820",
                "Enrolled, waiting for work",
            ),
            "the success branch owes the note, not only the failure counter",
        )
    }

    @Test
    fun `it replaces the retry note and nothing else`() {
        // Everything else this field holds is either still true or belongs to a
        // different writer: a refusal is the last thing that refusal said and is
        // not undone by the next poll, and "Running a job" is set either side of
        // this call by the work path itself.
        listOf(
            "Refused a job: orion is read-only while locked",
            "Running a job",
            "could not start claude on orion: No such file or directory",
            "Enrolled, locked — still acting, as this machine is set to",
        ).forEach {
            assertEquals(it, DeviceRunner.noteAfterPoll(it, "Enrolled, waiting for work"),
                "a poll must not overwrite \"$it\"")
        }
    }

    /**
     * And the loop still CALLS it. The rule above is a pure function nobody has to
     * use: the whole bug was a success branch that reset the counter and left the
     * sentence alone, which is a call that is missing rather than a call that is
     * wrong. Source-level, because `serve()` is a private suspend loop around a
     * 25-second long poll and a real one would be a sleep, not a test.
     */
    @Test
    fun `the successful poll branch clears the note`() {
        val src = File("src/main/kotlin/com/silencelen/huginn/desktop/device/DeviceRunner.kt").readText()
        assertTrue(src.length > 5_000, "read as ${src.length} chars — wrong file")
        val success = src.substringAfter("client.pollWork(").substringBefore("catch (e: CancellationException)")
        assertTrue(success.length in 1..2_000, "the poll's success branch was not found")
        assertTrue(
            "noteAfterPoll(" in success,
            "a poll that comes back must clear the retry note, not only the counter:\n$success",
        )
    }

    @Test
    fun `the note the failure writes is the note the success recognises`() {
        // One constant, both ends. Two literals here is exactly how the clearer
        // stops matching the writer and the note becomes permanent again.
        val written = DeviceRunner.RETRYING + "connection reset"
        assertTrue(written.startsWith(DeviceRunner.RETRYING))
        assertEquals("idle", DeviceRunner.noteAfterPoll(written, "idle"))
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
