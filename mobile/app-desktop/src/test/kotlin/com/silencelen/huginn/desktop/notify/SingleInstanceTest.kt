package com.silencelen.huginn.desktop.notify

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import com.silencelen.huginn.desktop.PrivateFiles
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ------------------------------------------- the listener proves itself

    /**
     * Something on the port a record names that is not this app's listener. It
     * keeps every line a launch sends, and [reply] decides what it says back to
     * the line at [index] on a connection: a line, nothing (null), or [HANG_UP].
     */
    private class Stranger(private val reply: (index: Int, line: String) -> String?) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port: Int = server.localPort
        val heard = CopyOnWriteArrayList<String>()

        init {
            Thread {
                while (true) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use { c ->
                            c.soTimeout = 5_000
                            val lines = c.getInputStream().bufferedReader()
                            var index = 0
                            while (true) {
                                val line = lines.readLine() ?: break
                                heard += line
                                val r = reply(index++, line) ?: continue
                                if (r == HANG_UP) break
                                c.getOutputStream().apply { write("$r\n".toByteArray()); flush() }
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() = server.close()

        companion object {
            const val HANG_UP = "\u0000hang up"
        }
    }

    private val deadSecret = "deadinstancesecret000000"

    /**
     * ⚠⚠ THE DEFECT. A crashed instance leaves its record behind, and another
     * account binds the port it names and answers `ok` to everything. Every
     * launch used to send that listener the secret and the link, believe the
     * `ok`, and exit: the app never started again until someone deleted the
     * record by hand.
     */
    @Test
    fun `a listener that cannot prove itself gets neither the secret nor the link, and the launch starts`() {
        Stranger { _, _ -> "ok" }.use { squatter ->
            val lock = File(dir, "instance.lock").apply { writeText("${squatter.port}\n$deadSecret\n") }
            val second = SingleInstance.claimOrForward(dir, "huginn://open?view=chats&id=x")
            held = second
            assertNotNull(second, "the launch believed a bare ok and exited without starting")
            assertFalse(squatter.heard.any { deadSecret in it }, "the secret went to whatever held the port")
            assertFalse(squatter.heard.any { "huginn://" in it }, "and the link went with it")
            val (port, secret) = lock.readLines()
            assertNotEquals(squatter.port.toString(), port, "the record still names the squatter's port")
            assertNotEquals(deadSecret, secret)
        }
    }

    @Test
    fun `a wrong answer to the question is told nothing more`() {
        Stranger { index, _ -> if (index == 0) "0".repeat(64) else "ok" }.use { liar ->
            File(dir, "instance.lock").writeText("${liar.port}\n$deadSecret\n")
            val second = SingleInstance.claimOrForward(dir, "huginn://open")
            held = second
            assertNotNull(second)
            assertEquals(SingleInstance.Handoff.UNPROVEN, second.handoff)
            assertEquals(1, liar.heard.size, "it heard more than the question: ${liar.heard}")
            assertTrue(liar.heard.single().startsWith(SingleInstance.PROVE))
        }
    }

    /**
     * ⚠ THE COST, PINNED. An app from before the proof reads the question as a
     * wrong secret and hangs up at once. A new launch beside it must start its
     * own instance — not wait out the deadline, not hang, not exit — and must not
     * have sent it the secret: two windows until the old one is quit.
     */
    @Test
    fun `beside an app from before the proof, a new launch starts at once`() {
        val oldSecret = "abcdefghijklmnopqrstuvwx"
        val oldApp = Stranger { index, line ->
            when {
                index == 0 && line != oldSecret -> Stranger.HANG_UP
                index == 0 -> null
                else -> "ok"
            }
        }
        oldApp.use {
            File(dir, "instance.lock").writeText("${oldApp.port}\n$oldSecret\n")
            val mark = System.nanoTime()
            val second = SingleInstance.claimOrForward(dir, "huginn://open")
            val tookMs = (System.nanoTime() - mark) / 1_000_000
            held = second
            assertNotNull(second, "the launch exited beside an app it could not hand over to")
            assertEquals(SingleInstance.Handoff.UNPROVEN, second.handoff)
            assertTrue(tookMs < 3_000, "it waited ${tookMs} ms for an app that had already hung up")
            assertFalse(oldApp.heard.any { oldSecret in it }, "the secret was sent before the proof")
        }
    }

    @Test
    fun `the listener answers the question with an HMAC of the nonce under its secret`() {
        val urls = LinkedBlockingQueue<String>()
        held = assertNotNull(SingleInstance.claimOrForward(dir, null))
        held!!.listen(onUrl = { urls.add(it) })
        val (port, secret) = File(dir, "instance.lock").readLines()
        val nonce = "0123456789abcdef0123456789abcdef"
        // The construction pinned here, not only through the code under test: an
        // older and a newer app must keep computing the same thing.
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal("huginn single instance\n$nonce".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, SingleInstance.proof(secret, nonce))
        Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { s ->
            s.soTimeout = 3000
            val input = s.getInputStream().bufferedReader()
            s.getOutputStream().apply { write("${SingleInstance.PROVE}$nonce\n".toByteArray()); flush() }
            assertEquals(expected, input.readLine())
            s.getOutputStream().apply { write("$secret\nhuginn://open\n".toByteArray()); flush() }
            assertEquals("ok", input.readLine())
        }
        assertEquals("huginn://open", urls.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun `the answer lets nobody in, and a malformed question gets no answer`() {
        val urls = LinkedBlockingQueue<String>()
        held = assertNotNull(SingleInstance.claimOrForward(dir, null))
        held!!.listen(onUrl = { urls.add(it) })
        val port = File(dir, "instance.lock").readLines()[0].toInt()
        fun ask(vararg lines: String): List<String?> =
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.soTimeout = 3000
                val input = s.getInputStream().bufferedReader()
                lines.map { line ->
                    s.getOutputStream().apply { write("$line\n".toByteArray()); flush() }
                    runCatching { input.readLine() }.getOrNull()
                }
            }
        assertEquals(listOf<String?>(null), ask("${SingleInstance.PROVE}not-a-nonce"))
        val nonce = "fedcba9876543210fedcba9876543210"
        val answers = ask("${SingleInstance.PROVE}$nonce", "not-the-secret")
        assertEquals(64, answers[0]?.length, "a well-formed question is answered")
        assertNull(answers[1], "and the wrong secret after it is still the wrong secret")
        assertNull(urls.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `an older launch that leads with the secret is still let in`() {
        // Older apps, and the installer's and updater's quit request, never ask
        // the question: the listener must keep taking the secret as the first line.
        val urls = LinkedBlockingQueue<String>()
        held = assertNotNull(SingleInstance.claimOrForward(dir, null))
        held!!.listen(onUrl = { urls.add(it) })
        val secret = File(dir, "instance.lock").readLines()[1]
        assertEquals("ok", send(secret, "huginn://open?view=chats&id=y"))
        assertEquals("huginn://open?view=chats&id=y", urls.poll(3, TimeUnit.SECONDS))
    }

    @Test
    fun `no record, or nothing on its port, is nobody home`() {
        val first = assertNotNull(SingleInstance.claimOrForward(dir, null))
        assertEquals(SingleInstance.Handoff.NOBODY, first.handoff)
        first.close()
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        File(dir, "instance.lock").writeText("$free\n$deadSecret\n")
        held = assertNotNull(SingleInstance.claimOrForward(dir, null))
        assertEquals(SingleInstance.Handoff.NOBODY, held!!.handoff)
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
