package com.silencelen.huginn.desktop.notify

import java.io.File
import java.net.InetAddress
import java.net.Socket
import com.silencelen.huginn.desktop.PrivateFiles
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The single-instance channel: a second launch hands its link to the running
 * app, and (since 2.3.6) an updater or the installer can ask it to QUIT the way
 * its tray's "Quit Huginn" does, instead of ending it by force.
 */
class SingleInstanceTest {
    private val dir: File = Files.createTempDirectory("si").toFile()
    private var held: SingleInstance? = null

    @AfterTest fun tearDown() { held?.close(); dir.deleteRecursively() }

    private fun send(secret: String, msg: String): String? {
        val (port, _) = File(dir, "instance.lock").readLines()
        Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { s ->
            s.soTimeout = 3000
            s.getOutputStream().write("$secret\n$msg\n".toByteArray())
            s.getOutputStream().flush()
            return s.getInputStream().bufferedReader().readLine()
        }
    }

    @Test
    fun `a quit request quits, and never reaches the link handler`() {
        val urls = LinkedBlockingQueue<String>()
        val quits = LinkedBlockingQueue<Unit>()
        held = assertNotNull(SingleInstance.claimOrForward(dir, null))
        held!!.listen(onUrl = { urls.add(it) }, onQuit = { quits.add(Unit) })
        val secret = File(dir, "instance.lock").readLines()[1]
        assertEquals("ok", send(secret, SingleInstance.QUIT))
        assertNotNull(quits.poll(3, TimeUnit.SECONDS), "the quit request did not reach onQuit")
        assertNull(urls.poll(300, TimeUnit.MILLISECONDS), "the quit request must not be handled as a link")
    }

    private fun mode(f: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test
    fun `the record is 0600 in a 0700 directory`() {
        // It used to be written at the umask (0644) inside a mkdirs() directory
        // (0755) and narrowed afterwards; an account watching the directory could
        // open it in between and keep reading the secret.
        if (!PrivateFiles.posix) return
        val config = File(dir, "fresh/huginn-desktop-kt")
        held = assertNotNull(SingleInstance.claimOrForward(config, null))
        assertEquals("rwx------", mode(config))
        assertEquals("rw-------", mode(File(config, "instance.lock")))
    }

    @Test
    fun `a stale record is replaced, never written through`() {
        // A record left by a crash, at the old 0644, must not be the file the new
        // secret is written into: the observer is a second name for it.
        if (!PrivateFiles.posix) return
        val lock = File(dir, "instance.lock").apply { writeText("1\nstale\n") }
        Files.setPosixFilePermissions(lock.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        val observer = File(dir, "observer")
        Files.createLink(observer.toPath(), lock.toPath())

        held = assertNotNull(SingleInstance.claimOrForward(dir, null))

        assertEquals("1\nstale\n", observer.readText(), "the new secret went into the old file")
        val (port, secret) = lock.readLines()
        assertNotEquals("1", port)
        assertEquals(24, secret.length)
        assertEquals("rw-------", mode(lock))
    }

    @Test
    fun `a second launch still hands its link over, and a wrong secret does nothing`() {
        val urls = LinkedBlockingQueue<String>()
        val quits = LinkedBlockingQueue<Unit>()
        held = assertNotNull(SingleInstance.claimOrForward(dir, null))
        held!!.listen(onUrl = { urls.add(it) }, onQuit = { quits.add(Unit) })
        assertNull(SingleInstance.claimOrForward(dir, "huginn://open"), "a second launch must hand over and exit")
        assertEquals("huginn://open", urls.poll(3, TimeUnit.SECONDS))
        send("not-the-secret", SingleInstance.QUIT)
        assertNull(quits.poll(500, TimeUnit.MILLISECONDS), "a wrong secret must not quit the app")
    }

    // ------------------------------------------------- before the secret

    @Test
    fun `a peer that dribbles a line and never ends it cannot hold the door`() {
        // Any account on the machine can connect before it has shown the secret.
        // This one sends a byte every 200 ms and never a newline, which used to
        // keep the one accepting thread reading for as long as it liked: the next
        // launch timed out, decided nobody was home and started a second app.
        val urls = LinkedBlockingQueue<String>()
        held = assertNotNull(SingleInstance.claimOrForward(dir, null))
        held!!.listen(onUrl = { urls.add(it) })
        val port = File(dir, "instance.lock").readLines()[0].toInt()
        val squatter = Socket(InetAddress.getLoopbackAddress(), port)
        val stop = AtomicBoolean(false)
        Thread {
            runCatching {
                while (!stop.get()) {
                    squatter.getOutputStream().apply { write('a'.code); flush() }
                    Thread.sleep(200)
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            Thread.sleep(400) // the squatter is being read before the launch arrives
            val second = SingleInstance.claimOrForward(dir, "huginn://open?view=chats&id=x")
            second?.close()
            assertNull(second, "the launch must still be handed over, not start a second app")
            assertEquals("huginn://open?view=chats&id=x", urls.poll(3, TimeUnit.SECONDS))
        } finally {
            stop.set(true)
            runCatching { squatter.close() }
        }
    }

    @Test
    fun `a line is read to its cap, its newline or its deadline, and no further`() {
        val far = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
        fun read(text: String, max: Int = 8) =
            SingleInstance.readLine(text.byteInputStream(), max, far)
        assertEquals("abc", read("abc\nrest"))
        assertEquals("abc", read("abc\r\n"), "a trailing CR is not part of the line")
        assertEquals("", read("\n"))
        assertEquals("12345678", read("12345678\n"), "exactly the cap fits")
        assertNull(read("123456789\n"), "one byte over the cap is refused")
        assertNull(read("x".repeat(100_000)), "a line that never ends is refused at the cap")
        assertNull(read("abc"), "a line cut off before its newline is not a line")
    }

    @Test
    fun `a line not finished by its deadline is refused`() {
        // A fake clock that moves a second per byte: the drip the deadline is for.
        var now = 0L
        val tick = { now.also { now += 1_000_000_000L } }
        val line = SingleInstance.readLine("abcdef\n".byteInputStream(), 64, 3_000_000_000L, tick)
        assertNull(line)
    }

    @Test
    fun `a launch link longer than the app reads is handed over as a plain summon`() {
        // Writing past what the far end reads could stall on a full socket buffer
        // until it hung up, and a failed hand-off starts a second app.
        val urls = LinkedBlockingQueue<String>()
        held = assertNotNull(SingleInstance.claimOrForward(dir, null))
        held!!.listen(onUrl = { urls.add(it) })
        val long = "huginn://open?view=chats&id=" + "x".repeat(SingleInstance.URL_MAX_BYTES)
        val second = SingleInstance.claimOrForward(dir, long)
        second?.close()
        assertNull(second, "an over-long link must still be handed over")
        assertEquals("", urls.poll(3, TimeUnit.SECONDS))
        assertEquals("", SingleInstance.handOff("huginn://open?view=chats&id=a\nb"))
        assertEquals("huginn://open?view=chats&id=ab", SingleInstance.handOff("huginn://open?view=chats&id=ab"))
    }
}
