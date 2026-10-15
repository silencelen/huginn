package com.silencelen.huginn.desktop.core

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Path
import javax.net.SocketFactory

/**
 * A [Socket] that is really the core's AF_UNIX socket, so OkHttp (and Ktor on
 * top of it) can speak HTTP to `huginn core` without knowing. `connect()`
 * ignores the address OkHttp resolved and opens [path] instead.
 *
 * Proven before it was written here: `proto/ipc` (11/11 on Debian, 10/10 inside
 * this app's own jlink runtime on Arch). Three traps shape it, and each one
 * HANGS or KILLS the app rather than failing a call — keep all three:
 *
 *  1. `Channels.newInputStream/newOutputStream` take the channel's
 *     `blockingLock()` for every read AND write on JDK 17, so one thread parked
 *     in read() stops every write. The streams below call the channel directly;
 *     `SocketChannelImpl` has separate read and write locks.
 *  2. A blocking channel ignores SO_TIMEOUT, and OkHttp's pool health check
 *     (`RealConnection.isHealthy`) reads with a 1 ms timeout — on an ignoring
 *     socket that read never returns. So the channel is non-blocking and every
 *     read waits on a [Selector] for at most [soTimeout].
 *  3. A close from another thread (Okio's timeout watchdog, `Call.cancel()`
 *     when a view drops its stream) surfaces as the UNCHECKED
 *     [ClosedSelectorException], which would escape every `catch (IOException)`
 *     in OkHttp. Both closed-flavours become the [SocketException] a real socket
 *     throws.
 */
internal class CoreSocket(private val path: Path) : Socket() {
    private lateinit var ch: SocketChannel
    private lateinit var readSel: Selector
    private lateinit var writeSel: Selector
    @Volatile private var timeoutMs = 0
    @Volatile private var closedFlag = false
    @Volatile private var inShut = false
    @Volatile private var outShut = false

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        ch.connect(UnixDomainSocketAddress.of(path)) // local: completes or fails at once
        ch.configureBlocking(false)
        readSel = Selector.open()
        writeSel = Selector.open()
        ch.register(readSel, SelectionKey.OP_READ)
        ch.register(writeSel, SelectionKey.OP_WRITE)
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    override fun isConnected(): Boolean = ::ch.isInitialized && ch.isConnected
    override fun isClosed(): Boolean = closedFlag
    override fun isInputShutdown(): Boolean = inShut
    override fun isOutputShutdown(): Boolean = outShut
    override fun setSoTimeout(timeout: Int) { timeoutMs = timeout }
    override fun getSoTimeout(): Int = timeoutMs
    override fun setTcpNoDelay(on: Boolean) {}
    override fun setKeepAlive(on: Boolean) {}
    // OkHttp logs and compares these; a fixed loopback answer is honest enough.
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort(): Int = 0
    override fun getLocalPort(): Int = 0
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(inetAddress, 0)

    @Synchronized
    override fun close() {
        if (closedFlag) return
        closedFlag = true
        if (!::ch.isInitialized) return
        // Wake both selectors so a read parked in select() sees the close.
        runCatching { readSel.wakeup(); writeSel.wakeup() }
        try { ch.close() } finally {
            runCatching { readSel.close() }
            runCatching { writeSel.close() }
        }
    }

    override fun shutdownInput() { inShut = true; ch.shutdownInput() }
    override fun shutdownOutput() { outShut = true; ch.shutdownOutput() }

    private val input = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val bb = ByteBuffer.wrap(b, off, len)
            val t = timeoutMs
            val deadline = if (t > 0) System.nanoTime() + t * 1_000_000L else Long.MAX_VALUE
            try {
                while (true) {
                    if (closedFlag) throw SocketException("Socket closed")
                    val n = ch.read(bb)
                    if (n != 0) return n // data, or -1 at EOF
                    if (deadline == Long.MAX_VALUE) {
                        readSel.select()
                    } else {
                        val left = (deadline - System.nanoTime()) / 1_000_000L
                        if (left <= 0) throw SocketTimeoutException("Read timed out")
                        readSel.select(maxOf(1L, left))
                    }
                    readSel.selectedKeys().clear()
                }
            } catch (e: ClosedSelectorException) {
                throw SocketException("Socket closed")
            } catch (e: ClosedChannelException) {
                throw SocketException("Socket closed")
            }
        }
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val bb = ByteBuffer.wrap(b, off, len)
            try {
                while (bb.hasRemaining()) {
                    if (closedFlag) throw SocketException("Socket closed")
                    if (ch.write(bb) == 0) {
                        writeSel.select()
                        writeSel.selectedKeys().clear()
                    }
                }
            } catch (e: ClosedSelectorException) {
                throw SocketException("Socket closed")
            } catch (e: ClosedChannelException) {
                throw SocketException("Socket closed")
            }
        }
    }

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
}

/** Hands OkHttp a [CoreSocket] for every connection. */
internal class CoreSocketFactory(private val path: Path) : SocketFactory() {
    override fun createSocket(): Socket = CoreSocket(path)
    override fun createSocket(host: String?, port: Int): Socket = createSocket().also { it.connect(null) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket("", port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket("", port)
}

/** Translated I/O failure so callers never see a raw channel exception. */
internal class CoreUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)
