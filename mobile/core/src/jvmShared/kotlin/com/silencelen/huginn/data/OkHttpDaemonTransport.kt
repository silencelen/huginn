package com.silencelen.huginn.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.coroutines.coroutineContext

/**
 * The one production [DaemonTransport]: OkHttp on both the phone and the desktop.
 *
 * ⚠ ONE SOURCE, TWO COMPILATIONS. This directory is added to BOTH `androidMain`
 * and `jvmMain` as an extra source root (see `core/build.gradle.kts`) instead of
 * being an intermediate source set, because a trust manager is plain JSSE on both
 * platforms and the security-critical half of TLS.md must not exist twice.
 *
 * @param configure extra engine configuration — the desktop's route-witness
 *   interceptor. ⚠ Applied BEFORE the TLS settings below, so nothing passed here
 *   can replace the pinning trust manager or the socket factory: OkHttp's
 *   builder keeps the last `sslSocketFactory` it is given, and ours is last.
 */
class OkHttpDaemonTransport(
    private val configure: OkHttpConfig.() -> Unit = {},
) : DaemonTransport {

    /**
     * The fingerprint the pinning trust manager last refused, for a platform
     * whose TLS stack loses the exception's cause on the way out (see
     * [unpinnedLeaf]). Consumed on read.
     */
    private val lastRefused = AtomicReference<String?>(null)

    override fun engine(pins: () -> Collection<String>): HttpClientEngine {
        val tm = PinnedTrustManager(pins) { lastRefused.set(it) }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttp.create {
            configure()
            config {
                sslSocketFactory(ctx.socketFactory, tm)
                // ⚠ OFF ON PURPOSE, AND ONLY HERE (TLS.md rule 4). The daemon's
                // certificate is named `huginn-appd` and is dialled by IP, so a
                // hostname check fails every real connection — and it adds
                // nothing: the trust manager above accepts exactly the
                // certificates whose fingerprints were proved under the token,
                // whatever name they carry.
                hostnameVerifier { _, _ -> true }
            }
        }
    }

    override suspend fun probe(url: String, timeoutMs: Long): TlsProbeReply = withContext(Dispatchers.IO) {
        val recorder = RecordingTrustManager()
        // Whether TCP connected and a TLS hello went out — what separates "a port
        // that does not speak TLS" from "nothing there" when no certificate came.
        val helloSent = java.util.concurrent.atomic.AtomicBoolean(false)
        val tlsStarted = object : okhttp3.EventListener() {
            override fun secureConnectStart(call: okhttp3.Call) { helloSent.set(true) }
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
        // ⚠⚠ ITS OWN POOL, EMPTIED AFTERWARDS (TLS.md rule 3). This client
        // accepts ANY certificate, so a connection it opened must never be
        // handed to a request that carries the bearer. OkHttp would not reuse
        // it across a different socket factory anyway; a private pool with
        // zero idle connections makes that a property of this function rather
        // than of OkHttp's address-equality rules.
        val pool = ConnectionPool(0, 1, TimeUnit.MILLISECONDS)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, recorder)
            .hostnameVerifier { _, _ -> true }
            .connectionPool(pool)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            // A redirect would take the probe to somewhere it did not ask about.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .eventListener(tlsStarted)
            .build()
        // No Authorization header, ever: this channel trusts anyone.
        val call = client.newCall(Request.Builder().url(url).header("Connection", "close").get().build())
        // ⚠ OUR OWN FLAG, NOT `call.isCanceled()`: OkHttp's callTimeout cancels
        // the call too, and a timeout is exactly when the old-daemon check below
        // must still run. Only the caller walking away skips it.
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = coroutineContext[Job]?.invokeOnCompletion { if (it != null) { abandoned.set(true); call.cancel() } }
        try {
            call.execute().use { resp ->
                TlsProbeReply(
                    leafFp = recorder.leafFp,
                    status = resp.code,
                    body = resp.body?.let { b -> boundedText(b.source()) } ?: "",
                    appdHeader = resp.header(HuginnClient.APPD_HEADER)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: IOException) {
            // ⚠ CONNECTED, SAID HELLO, AND NO CERTIFICATE EVER CAME BACK: the
            // port does not speak TLS — the shape of a pre-3.11 daemon, whose
            // single port answers a TLS hello with an HTTP 400 (node) or simply
            // waits for a request line that never ends (other servers, hence
            // IOException and not only SSLException). Ask it, in plain text and
            // with no bearer, only so the reader can be told "update the daemon"
            // rather than "could not connect". Never a fallback: nothing secret
            // is sent and nothing learned here can be adopted. A port that
            // refused or never connected skips this entirely.
            if (recorder.leafFp != null || !helloSent.get() || abandoned.get()) throw e
            plaintextPing(url, timeoutMs) ?: throw e
        } finally {
            cancel?.dispose()
            pool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    override fun unpinnedLeaf(failure: Throwable): String? {
        var t: Throwable? = failure
        var depth = 0
        var handshake = false
        while (t != null && depth++ < 12) {
            if (t is UnpinnedCertificateException) {
                lastRefused.set(null)
                return t.fp
            }
            if (t is SSLHandshakeException) handshake = true
            t = t.cause
        }
        // Conscrypt has, across Android releases, both kept and dropped the
        // trust manager's exception as the cause; the record survives either way.
        return if (handshake) lastRefused.getAndSet(null) else null
    }

    /**
     * `GET /v1/ping` over a raw socket in plain text, NO Authorization — only to
     * name what is listening. A raw socket rather than OkHttp because the phone's
     * network security config forbids cleartext through the HTTP stack to
     * anything but loopback, and that rule is right for every request but this
     * one, which carries nothing.
     */
    private fun plaintextPing(url: String, timeoutMs: Long): TlsProbeReply? = runCatching {
        val u = url.toHttpUrl()
        Socket().use { s ->
            s.connect(InetSocketAddress(u.host, u.port), timeoutMs.toInt())
            s.soTimeout = timeoutMs.toInt()
            val hostHeader = (if (':' in u.host) "[${u.host}]" else u.host) + ":" + u.port
            s.getOutputStream().apply {
                write("GET /v1/ping HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val raw = readBounded(s.getInputStream())
            val head = raw.substringBefore("\r\n\r\n")
            val body = raw.substringAfter("\r\n\r\n", "")
            val status = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return@runCatching null
            val header = head.lineSequence().drop(1)
                .firstOrNull { it.substringBefore(':').trim().equals(HuginnClient.APPD_HEADER, ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
            TlsProbeReply(leafFp = null, status = status, body = body, appdHeader = header, notTls = true)
        }
    }.getOrNull()

    private fun readBounded(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (out.size() < MAX_PROBE_BODY) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: IOException) {
            // A timeout after the headers is still an answer.
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun boundedText(source: okio.BufferedSource): String {
        source.request(MAX_PROBE_BODY.toLong())
        return source.buffer.readUtf8(minOf(source.buffer.size, MAX_PROBE_BODY.toLong()))
    }

    private companion object {
        /** A probe body is a few hundred bytes; a host that sends more is not huginn. */
        const val MAX_PROBE_BODY = 64 * 1024
    }
}

/** Lowercase hex SHA-256 of a certificate's DER — the fingerprint TLS.md pins. */
internal fun fingerprintOf(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

/**
 * The refusal the pinned channel raises — a [CertificateException], so JSSE
 * aborts the handshake before a byte of the request (and its bearer) is written.
 */
internal class UnpinnedCertificateException(val fp: String) :
    CertificateException("server certificate $fp is not pinned for this daemon")

/**
 * Accepts a server certificate iff its leaf fingerprint is in [pins].
 *
 * ⚠ AN [X509ExtendedTrustManager], NOT A PLAIN X509TrustManager. Handed a plain
 * one, the JDK wraps it in a shim that runs its own endpoint-identification and
 * algorithm checks around ours; extending the extended type keeps this the ONLY
 * check, which is the contract (no CAs, no hostname — the pin replaces both).
 */
internal class PinnedTrustManager(
    private val pins: () -> Collection<String>,
    private val onRefused: (String) -> Unit,
) : X509ExtendedTrustManager() {

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("the server presented no certificate")
        val fp = fingerprintOf(leaf)
        if (fp in pins()) return
        onRefused(fp)
        throw UnpinnedCertificateException(fp)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { refuseClient() }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) { refuseClient() }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) { refuseClient() }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The probe's trust manager: accepts ANYTHING and records the leaf it saw.
 *
 * ⚠ SAFE ONLY BECAUSE OF WHERE IT IS USED. It backs a client that sends no
 * Authorization header and is thrown away after one request; what it records is
 * the input to the proof, and only a proof that matches lets that fingerprint
 * near the bearer. Never give it to anything else.
 */
internal class RecordingTrustManager : X509ExtendedTrustManager() {
    @Volatile var leafFp: String? = null
        private set

    private fun record(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("the server presented no certificate")
        leafFp = fingerprintOf(leaf)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = record(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = record(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = record(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { refuseClient() }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) { refuseClient() }
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) { refuseClient() }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun refuseClient(): Nothing = throw CertificateException("huginn clients do not accept client connections")
