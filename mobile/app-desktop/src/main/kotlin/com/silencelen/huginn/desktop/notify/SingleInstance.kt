package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.desktop.PrivateFiles
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Semaphore
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
 * URLs straight into the running app, or `!quit` it. (A URL that does arrive is
 * still only navigation unless it carries one of this run's toast nonces; see
 * [Activations.resolve].)
 *
 * ⚠ THE FILE IS 0600 FROM ITS FIRST BYTE, in a 0700 directory. It used to be
 * written at the process umask (0644 at the usual 022) and narrowed afterwards,
 * and an account watching the directory could open it in between and keep
 * reading the secret through the open descriptor. [PrivateFiles] gives the
 * create call the mode and swaps the finished file into place.
 *
 * ⚠ AND NOTHING IS READ ON THE ACCEPT THREAD. The port is reachable by every
 * account on the machine BEFORE anyone has shown the secret, and the hand-off
 * used to read an unbounded line, on the one thread that accepts, with a
 * per-read timeout that a byte every couple of seconds resets for ever. One
 * idle connection held the door shut: every launch after it timed out, decided
 * nobody was home, and started a second instance over the first one's record.
 * So each connection is read on its own short-lived thread, a few at a time,
 * at most [SECRET_MAX_BYTES] and [URL_MAX_BYTES] long, and all of it inside
 * [DEADLINE_MS] — see [readLine].
 */
class SingleInstance private constructor(
    /** Null when no loopback socket could be bound; the guard then degrades to nothing. */
    private val server: ServerSocket?,
    private val secret: String,
    private val portFile: File,
) {

    private val stopped = AtomicBoolean(false)

    /** Connections being read right now. Bounded: see [listen]. */
    private val inFlight = Semaphore(MAX_IN_FLIGHT)

    /** True when this process actually holds the slot and can receive hand-offs. */
    val guarding: Boolean get() = server != null

    /**
     * Starts accepting hand-offs. [onUrl] is called on a daemon thread for every
     * URL a second launch delivers.
     *
     * The accept loop only accepts. Each connection is read on a thread of its
     * own, so a peer that connects and then says nothing (or says it one byte at
     * a time) costs one of [MAX_IN_FLIGHT] slots for at most [DEADLINE_MS], and
     * the next launch is still answered at once. With every slot taken, a new
     * connection is closed rather than queued behind them.
     */
    fun listen(onUrl: (String) -> Unit, onQuit: () -> Unit = {}) {
        val socketServer = server ?: return
        val t = Thread({
            while (!stopped.get() && !socketServer.isClosed) {
                val socket = runCatching { socketServer.accept() }.getOrNull()
                if (socket == null) {
                    // accept() only fails here because the socket was closed on the
                    // way out; anything else and looping would spin a core.
                    break
                }
                if (!inFlight.tryAcquire()) {
                    runCatching { socket.close() }
                    continue
                }
                val peer = Thread({
                    try {
                        socket.use { s -> runCatching { serve(s, onUrl, onQuit) } }
                    } finally {
                        inFlight.release()
                    }
                }, "huginn-single-instance-peer")
                peer.isDaemon = true
                runCatching { peer.start() }.onFailure {
                    inFlight.release()
                    runCatching { socket.close() }
                }
            }
        }, "huginn-single-instance")
        t.isDaemon = true
        t.start()
    }

    private fun serve(s: Socket, onUrl: (String) -> Unit, onQuit: () -> Unit) {
        s.soTimeout = READ_TIMEOUT_MS
        val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000L
        val input = BufferedInputStream(s.getInputStream(), 256)
        // The secret first, and nothing past [SECRET_MAX_BYTES] of it: until it
        // matches, this is anyone on the machine.
        val presented = readLine(input, SECRET_MAX_BYTES, deadline) ?: return
        if (!MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8))) return
        // Past the secret it is this user's own launch. A URL line that is too
        // long or too slow is answered as a blank one (bring the window up) rather
        // than refused, because a refusal sends that launch off to start a second
        // instance. [forward] never sends one that long.
        val url = readLine(input, URL_MAX_BYTES, deadline).orEmpty()
        s.getOutputStream().apply { write("ok\n".toByteArray(Charsets.UTF_8)); flush() }
        // QUIT, from an updater or the installer (`huginn desktop update`, the
        // NSIS installer): the app's own Quit, so it closes the way the tray's
        // "Quit Huginn" does instead of being killed — a forced kill of the JVM
        // left the jpackage launcher showing error dialogs (PC01, 2026-10-16).
        // Never a URL: a web page can launch huginn:// links, and only a holder of
        // this file's secret (this user) can say this.
        if (url == QUIT) {
            onQuit()
            return
        }
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

        /** The message that asks a running app to quit (see [serve]). */
        const val QUIT = "!quit"

        /** The secret is 24 characters; anything much longer is not it. */
        internal const val SECRET_MAX_BYTES: Int = 64

        /**
         * The longest URL a hand-off carries. Every link this app builds (an
         * answer with its fingerprint and nonce, an open, an ack) is well under
         * half of this.
         */
        internal const val URL_MAX_BYTES: Int = 512

        /** How long one read may wait for its next byte. */
        internal const val READ_TIMEOUT_MS: Int = 1_000

        /** How long a whole hand-off may take, from accept to the last byte read. */
        internal const val DEADLINE_MS: Long = 2_000

        /** Connections read at once. A real second launch is one, and brief. */
        internal const val MAX_IN_FLIGHT: Int = 4

        /** The reply: "ok" and a newline, and a forwarder reads no more than this. */
        private const val REPLY_MAX_BYTES: Int = 16

        /**
         * One `\n`-terminated line of at most [maxBytes] bytes, or null when the
         * line is longer than that, ends before its newline, or is not finished by
         * [deadlineNanos] (a [System.nanoTime] instant). A trailing `\r` is dropped.
         *
         * Byte by byte against the cap and the clock, because the two ways a peer
         * holds a reader are a line that never ends and a line that is never
         * finished, and a per-read timeout stops neither.
         */
        internal fun readLine(
            input: InputStream,
            maxBytes: Int,
            deadlineNanos: Long,
            nanoTime: () -> Long = System::nanoTime,
        ): String? {
            val out = ByteArrayOutputStream(minOf(maxBytes, 128))
            while (true) {
                if (nanoTime() - deadlineNanos >= 0) return null
                val b = try {
                    input.read()
                } catch (_: IOException) {
                    // SocketTimeoutException included: a peer that has gone quiet
                    // for a whole read timeout is not about to finish its line.
                    return null
                }
                when {
                    b < 0 -> return null
                    b == '\n'.code -> break
                    out.size() >= maxBytes -> return null
                    else -> out.write(b)
                }
            }
            val bytes = out.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, end, Charsets.UTF_8)
        }

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
                PrivateFiles.dir(configDir)
                val record = "${server.localPort}\n$secret\n".toByteArray(Charsets.UTF_8)
                // Windows keeps the in-place write it always had: no POSIX modes
                // to get wrong, and the file sits under the user's own profile.
                if (PrivateFiles.posix) PrivateFiles.replace(portFile, record) else portFile.writeBytes(record)
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
                s.getOutputStream().apply {
                    write("$secret\n${handOff(url)}\n".toByteArray(Charsets.UTF_8))
                    flush()
                }
                // Bounded on this side too: whatever holds that port has not
                // proved it is the running app until it says "ok".
                val reply = readLine(
                    BufferedInputStream(s.getInputStream(), REPLY_MAX_BYTES),
                    REPLY_MAX_BYTES,
                    System.nanoTime() + 3_000_000_000L,
                )
                reply == "ok"
            }
        }.getOrDefault(false)

        /**
         * The URL line a launch sends: [url] as it is, or blank (bring the window
         * up) when it is longer than the running app reads or would break the
         * line. Writing past what the far end reads can stall on a full socket
         * buffer until the far end hangs up — and a hand-off that fails starts a
         * second instance.
         */
        internal fun handOff(url: String?): String {
            val u = url.orEmpty()
            if ('\n' in u || '\r' in u) return ""
            return if (u.toByteArray(Charsets.UTF_8).size <= URL_MAX_BYTES) u else ""
        }

        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
