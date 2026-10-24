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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
 *
 * ⚠ AND THE LISTENER PROVES ITSELF BEFORE A LAUNCH SAYS ANYTHING. A record
 * outlives a crash, and any account on the machine can bind the port a dead
 * instance's record names. A launch used to send the secret and its link to
 * whatever answered there and take a bare `ok` as delivery, so a squatter on
 * that port took every launch: the link, the secret with it, and the app
 * itself, which exited believing it had handed over — until someone deleted
 * the record by hand. Now a launch opens with a fresh random nonce ([PROVE])
 * and the listener must answer with an HMAC of it under the secret ([proof])
 * before the launch sends the secret or the link. A wrong answer, or none in
 * time, means the record is stale: the launch overwrites it and starts as the
 * one instance ([Handoff.UNPROVEN]).
 *
 * An app from before this check cannot answer. It reads the nonce line as a
 * wrong secret and hangs up at once, so a new launch beside an old running app
 * starts a second instance: two windows until the old one is quit, never a
 * launch that hangs or exits without starting. The old app also deletes the
 * record on its way out, so one more launch after that starts fresh beside the
 * new one; once every running app answers, it is one instance again. Older
 * launches, and the installer's and updater's quit request, still open with
 * the secret itself, which the listener checks exactly as before.
 */
class SingleInstance private constructor(
    /** Null when no loopback socket could be bound; the guard then degrades to nothing. */
    private val server: ServerSocket?,
    private val secret: String,
    private val portFile: File,
    /** What became of this launch's attempt to hand over before it took the slot. */
    val handoff: Handoff = Handoff.NOBODY,
) {

    /** What a launch's hand-off came to. */
    enum class Handoff {
        /** A listener proved itself and took the link: this launch exits. */
        DELIVERED,

        /** No record, or nothing listening on the port it names. */
        NOBODY,

        /**
         * Something answered on the recorded port and did not prove it holds the
         * secret — an app from before the proof, or another account's listener on
         * a dead instance's port — or proved it and then never took the link.
         * Nothing past the question reaches a listener that has not proved
         * itself; this launch took over.
         */
        UNPROVEN,
    }

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
        // The secret first — after the launch's [PROVE] question, when it asks
        // one — and nothing past [SECRET_MAX_BYTES] of it: until it matches,
        // this is anyone on the machine.
        var presented = readLine(input, SECRET_MAX_BYTES, deadline) ?: return
        if (presented.startsWith(PROVE)) {
            // A launch asking this listener to show it holds the secret before it
            // sends its own. The answer reveals nothing but an HMAC of the nonce,
            // so it goes to anyone who asks; the secret is still what lets them in.
            val nonce = presented.removePrefix(PROVE)
            if (!wellFormedNonce(nonce)) return
            s.getOutputStream().apply { write("${proof(secret, nonce)}\n".toByteArray(Charsets.UTF_8)); flush() }
            presented = readLine(input, SECRET_MAX_BYTES, deadline) ?: return
        }
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
         * Opens a launch's first line: "prove you hold the secret", the nonce after
         * it. A secret never contains a space ([ALPHABET]), so no listener takes
         * this for one, and an old one hangs up on it as a wrong secret.
         */
        internal const val PROVE: String = "prove "

        /** A launch's nonce: 16 random bytes as lowercase hex. */
        private const val NONCE_BYTES: Int = 16

        /**
         * The listener's answer: HMAC-SHA256 as lowercase hex, and a forwarder
         * reads no more than this of it.
         */
        private const val PROOF_MAX_BYTES: Int = 64

        /** Binds the HMAC to this one question, so it is never an answer to another. */
        private const val PROOF_LABEL: String = "huginn single instance\n"

        /**
         * What a listener answers to [PROVE] + [nonce]: HMAC-SHA256 under [secret]
         * of the nonce, as lowercase hex. Only a holder of the secret can compute
         * it, and a fresh nonce per launch means an answer seen once is no answer
         * to the next launch.
         */
        internal fun proof(secret: String, nonce: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal((PROOF_LABEL + nonce).toByteArray(Charsets.UTF_8)).toHex()
        }

        /** What a listener accepts as a nonce: lowercase hex, 16 to 48 characters. */
        private fun wellFormedNonce(nonce: String): Boolean =
            nonce.length in 16..48 && nonce.all { it in '0'..'9' || it in 'a'..'f' }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

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
            val handoff = forward(portFile, url)
            if (handoff == Handoff.DELIVERED) return null

            // Either nothing was listening or the record was stale (a crash, a
            // kill -9, a port another account took over). Take over; the file is
            // rewritten below either way.
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
                return SingleInstance(null, "", portFile, handoff)
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
            return SingleInstance(server, secret, portFile, handoff)
        }

        /**
         * Hands [url] to the instance [portFile] records, if one answers there and
         * proves it holds the record's secret.
         *
         * ⚠ NOTHING IS SENT BEFORE THE PROOF. The secret used to be the first line
         * out, to whatever held the port; now the nonce is, and the secret and
         * the link follow only a correct answer. Every read is bounded by one
         * deadline, so a listener that says nothing costs a launch a few seconds,
         * once, and never hangs it.
         */
        private fun forward(portFile: File, url: String?): Handoff {
            val lines = runCatching { portFile.readLines() }.getOrNull() ?: return Handoff.NOBODY
            val port = lines.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return Handoff.NOBODY
            val secret = lines.getOrNull(1)?.trim().orEmpty()
            val s = Socket()
            try {
                try {
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_500)
                } catch (_: IOException) {
                    return Handoff.NOBODY
                }
                return if (handOver(s, secret, url)) Handoff.DELIVERED else Handoff.UNPROVEN
            } catch (_: Exception) {
                return Handoff.UNPROVEN
            } finally {
                runCatching { s.close() }
            }
        }

        /** The exchange on a connected [s]: the nonce, the proof, then the secret and the link. */
        private fun handOver(s: Socket, secret: String, url: String?): Boolean {
            if (secret.isEmpty()) return false
            s.soTimeout = 3_000
            val deadline = System.nanoTime() + 3_000_000_000L
            val input = BufferedInputStream(s.getInputStream(), 128)
            val out = s.getOutputStream()
            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }.toHex()
            out.write("$PROVE$nonce\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val answer = readLine(input, PROOF_MAX_BYTES, deadline) ?: return false
            val expected = proof(secret, nonce)
            if (!MessageDigest.isEqual(answer.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))) {
                return false
            }
            out.write("$secret\n${handOff(url)}\n".toByteArray(Charsets.UTF_8))
            out.flush()
            return readLine(input, REPLY_MAX_BYTES, deadline) == "ok"
        }

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
