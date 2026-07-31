package com.silencelen.huginn.desktop.notify

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One running client, and a way for a second launch to hand it a `huginn://` URL.
 *
 * This is not a nicety. A protocol activation on every desktop OS works by
 * STARTING the registered handler with the URL in argv — so without this, every
 * toast button click launches a second copy of the app: two watch streams, two
 * notification claims, two 5-second polls, and two clients fighting over the tmux
 * size lease. The Electron client got it from `requestSingleInstanceLock`; a plain
 * JVM has no equivalent, so this is it.
 *
 * A loopback socket rather than a lock file, because the second instance has to
 * DELIVER something, not merely discover that it lost. Bound explicitly to
 * 127.0.0.1: a wildcard bind is what makes Windows raise a firewall prompt, and
 * would put an activation endpoint on the network besides.
 *
 * The record carries a secret alongside the port and every message must open with
 * it. Loopback is not a permission boundary — any local user could otherwise post
 * a `huginn://answer` straight into the running app, which is exactly the hole
 * [Activations.parse]'s fingerprint rule exists to close, reopened one layer down.
 * The file is mode 0600 for the same reason.
 */
class SingleInstance private constructor(
    /** Null when no loopback socket could be bound; the guard then degrades to nothing. */
    private val server: ServerSocket?,
    private val secret: String,
    private val portFile: File,
) {

    private val stopped = AtomicBoolean(false)

    /** True when this process actually holds the slot and can receive hand-offs. */
    val guarding: Boolean get() = server != null

    /**
     * Starts accepting hand-offs. [onUrl] is called on a daemon thread for every
     * URL a second launch delivers.
     */
    fun listen(onUrl: (String) -> Unit) {
        val socketServer = server ?: return
        val t = Thread({
            while (!stopped.get() && !socketServer.isClosed) {
                val socket = runCatching { socketServer.accept() }.getOrNull()
                if (socket == null) {
                    // accept() only fails here because the socket was closed on the
                    // way out; anything else and looping would spin a core.
                    break
                }
                socket.use { s -> runCatching { serve(s, onUrl) } }
            }
        }, "huginn-single-instance")
        t.isDaemon = true
        t.start()
    }

    private fun serve(s: Socket, onUrl: (String) -> Unit) {
        s.soTimeout = 3_000
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        if (reader.readLine() != secret) return
        val url = reader.readLine().orEmpty()
        OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8).apply { write("ok\n"); flush() }
        // Blank is a message too: a second launch with no URL means "someone
        // double-clicked the icon", and the right answer is to bring the window
        // forward rather than to ignore them.
        onUrl(url)
    }

    fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { server?.close() }
        runCatching { portFile.delete() }
    }

    companion object {

        /**
         * Claims the single-instance slot, or hands [url] to whoever already holds it.
         *
         * Returns null when another instance took delivery — the caller must then
         * exit AT ONCE, before starting any loop or claiming any lease.
         */
        fun claimOrForward(configDir: File, url: String?): SingleInstance? {
            val portFile = File(configDir, "instance.lock")
            if (forward(portFile, url)) return null

            // Either nothing was listening or the record was stale (a crash, a
            // kill -9). Take over; the file is rewritten below either way.
            val server = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8)
                }
            }.getOrNull()

            if (server == null) {
                // No loopback socket at all (a sandbox, an exhausted port range).
                // Running WITHOUT the guard beats refusing to start: what degrades
                // is protocol activation, and it degrades to opening a second
                // window rather than to an app that will not launch.
                return SingleInstance(null, "", portFile)
            }

            val secret = SecureRandom().ints(24, 0, ALPHABET.length)
                .toArray().joinToString("") { ALPHABET[it].toString() }

            runCatching {
                configDir.mkdirs()
                portFile.writeText("${server.localPort}\n$secret\n")
                portFile.setReadable(false, false)
                portFile.setWritable(false, false)
                portFile.setReadable(true, true)
                portFile.setWritable(true, true)
            }
            return SingleInstance(server, secret, portFile)
        }

        /** True when a live instance accepted the hand-off. */
        private fun forward(portFile: File, url: String?): Boolean = runCatching {
            val lines = portFile.readLines()
            val port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return false
            val secret = lines.getOrNull(1)?.trim().orEmpty()
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_500)
                s.soTimeout = 3_000
                OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8).apply {
                    write(secret); write("\n")
                    write(url.orEmpty()); write("\n")
                    flush()
                }
                BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine() == "ok"
            }
        }.getOrDefault(false)

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
