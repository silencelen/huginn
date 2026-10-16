package com.silencelen.huginn.desktop.notify

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
