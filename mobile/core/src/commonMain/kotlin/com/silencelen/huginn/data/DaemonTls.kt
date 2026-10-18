package com.silencelen.huginn.data

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * ─── TLS TO THE DAEMON, PINNED BY A PROOF ONLY THE TOKEN CAN MAKE ──────────
 *
 * `docs/dev/protocol-tls.md` is the contract; this file is the client's half of it, and the
 * part of it that is PURE — the HMAC input, the verdict on a challenge answer,
 * the pin-set rules and the URL rule — so all of it is decided by a test in
 * `commonTest` rather than by whichever platform happens to be running. The
 * sockets (a trust-anything probe that records the leaf, a pinning trust
 * manager for everything that carries the bearer) live in [DaemonTransport]'s
 * one OkHttp implementation, `jvmShared/…/OkHttpDaemonTransport.kt`.
 *
 * ⚠⚠ WHY THIS EXISTS. The bearer is root-equivalent on the daemon's host, and
 * until appd 3.11 it crossed the network in plain HTTP. That was only ever safe
 * for an owner whose network was already encrypted (Tailscale, a mesh VPN), and
 * huginn is a PUBLIC project: somebody on a flat home LAN or a café Wi-Fi gets no
 * such cover. So there is deliberately NO setting here that re-enables cleartext
 * to a remote host — the only plain-http left is loopback, where there is no wire.
 *
 * ⚠ WHY NOT A CA. appd mints its own self-signed certificate, so nothing can
 * vouch for it. The client checks the DAEMON instead, with the secret both ends
 * already hold and without sending it: the daemon's proof is an HMAC under the
 * token over the fingerprint of ITS certificate, the client recomputes it over
 * the fingerprint of the certificate ITS OWN HANDSHAKE SAW, and a man in the
 * middle — who must show his own certificate — would need the token to make the
 * two agree.
 */
object DaemonTls {

    /** Domain separation from the legacy proof: `:` never occurs in a nonce. */
    const val PREFIX: String = "huginn-tls-v1:"

    /** The only proof version this client understands. */
    const val VERSION: Int = 1

    /**
     * Pins kept per daemon. Four, newest first: enough to ride out a rotation
     * (old + new) and a CA-issued renewal or two without re-proving on every
     * launch, small enough that an ancient certificate does not stay trusted
     * forever.
     */
    const val MAX_PINS: Int = 4

    /** The first appd release that speaks TLS. */
    const val FIRST_TLS_MAJOR: Int = 3
    const val FIRST_TLS_MINOR: Int = 11

    /**
     * The contract's sentence for a failed proof, verbatim (TLS.md rule 5).
     *
     * ⚠ SAID, NEVER WORKED AROUND. There is no "connect anyway" for this one: the
     * whole value of the scheme is that a failed proof means the bearer stays on
     * this device.
     */
    const val INTERCEPTED: String =
        "this address could not prove it is your huginn daemon; someone may be intercepting the connection"

    /**
     * Appended when the far end echoed the very fingerprint our handshake saw but
     * its proof still did not match. Cryptographically that is the same refusal
     * — an interceptor can echo any fingerprint it likes — but it is ALSO exactly
     * what a mistyped or rotated token looks like, and a reader told only
     * "intercepted" goes hunting for an attacker instead of re-pasting a token.
     * Display only; it changes nothing about what is refused.
     */
    const val TOKEN_HINT: String = " (if the token was changed recently, check it first)"

    /** TLS.md rule 8, verbatim. */
    const val TOO_OLD: String = "the daemon is older than 3.11 and has no TLS; update it"

    // ------------------------------------------------------------ the proof

    /** The HMAC the daemon owes us for [nonce] over a certificate whose fingerprint is [fp]. */
    fun proof(token: String, nonce: String, fp: String): String =
        DaemonChallenge.hmacSha256(
            token.encodeToByteArray(),
            "$PREFIX$nonce:${fp.lowercase()}".encodeToByteArray(),
        ).toHex()

    /**
     * Whether [answer] proves the token holder is behind the certificate whose
     * fingerprint is [seenFp] — the one OUR handshake recorded, never the one the
     * body reports (TLS.md rule 1).
     *
     * Constant time, and false for anything blank, so an empty body can never
     * read as a match.
     */
    fun matches(token: String, nonce: String, seenFp: String, answer: String?): Boolean {
        val got = answer?.trim()?.lowercase() ?: return false
        if (got.isEmpty() || token.isEmpty() || nonce.isEmpty() || seenFp.isBlank()) return false
        val want = proof(token, nonce, seenFp)
        if (got.length != want.length) return false
        var diff = 0
        for (i in want.indices) diff = diff or (got[i].code xor want[i].code)
        return diff == 0
    }

    /** What one challenge over TLS established. */
    sealed interface Verdict {
        /** The token holder is behind [fp]. Pin it. */
        data class Proven(val fp: String) : Verdict

        /** A daemon (or a proxy in front of one) too old to bind its proof. */
        data class TooOld(val version: String?) : Verdict

        /**
         * Something answered the challenge and did not prove it. [echoedOurFp]
         * is display-only — see [TOKEN_HINT].
         */
        data class Unproven(val echoedOurFp: Boolean) : Verdict

        /** Whatever answered is not huginn-shaped at all. */
        data object NotHuginn : Verdict
    }

    @Serializable
    private data class TlsBlock(val v: Int = 0, val fp: String = "", val proof: String = "")

    @Serializable
    private data class Answer(val proof: String = "", val version: String = "", val tls: TlsBlock? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The verdict on one `GET /v1/challenge` answered over a TLS connection whose
     * leaf hashed to [seenFp].
     *
     * ⚠⚠ THE LEGACY `proof` IS NEVER READ HERE (TLS.md rule 2). It proves that a
     * token holder exists somewhere behind this address, not that THIS channel
     * ends at it — a relaying interceptor passes it through untouched. A missing
     * `tls` block over https is "not proved", full stop; the only thing it may
     * still change is which sentence is printed.
     *
     * @param notTls the port answered the handshake with plain HTTP — the shape
     *   of a pre-3.11 daemon. [status]/[body]/[appdHeader] are then that plain
     *   unauthenticated answer, read only to tell "old huginn" from "not huginn".
     */
    fun judge(
        token: String,
        nonce: String,
        seenFp: String?,
        status: Int,
        body: String,
        appdHeader: String?,
        notTls: Boolean = false,
    ): Verdict {
        if (notTls) {
            return if (HuginnClient.answersLikeDaemon(appdHeader, status, body)) Verdict.TooOld(appdHeader)
            else Verdict.NotHuginn
        }
        val parsed = if (status in 200..299) runCatching { json.decodeFromString<Answer>(body) }.getOrNull() else null
        val version = parsed?.version?.takeIf { it.isNotBlank() } ?: appdHeader?.takeIf { it.isNotBlank() }
        if (parsed == null) {
            // A non-2xx or a body that is not the challenge's. An old daemon
            // behind somebody's TLS-terminating proxy 404s the route and still
            // stamps its header; anything else is somebody else's server.
            return if (isOlderThanTls(version)) Verdict.TooOld(version) else Verdict.NotHuginn
        }
        val tls = parsed.tls
        if (tls == null || tls.v != VERSION) {
            return if (isOlderThanTls(version)) Verdict.TooOld(version) else Verdict.Unproven(echoedOurFp = false)
        }
        val fp = seenFp?.lowercase()
            ?: return Verdict.Unproven(echoedOurFp = false)
        if (matches(token, nonce, fp, tls.proof)) return Verdict.Proven(fp)
        return Verdict.Unproven(echoedOurFp = tls.fp.trim().lowercase() == fp)
    }

    /**
     * True for a version string that names a release before 3.11. Null or
     * unreadable is NOT older — "I cannot tell" must never select the sentence
     * that tells the reader the fault is theirs to fix by upgrading.
     */
    fun isOlderThanTls(version: String?): Boolean {
        val parts = version?.trim()?.removePrefix("v")?.split('.') ?: return false
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: return false
        return major < FIRST_TLS_MAJOR || (major == FIRST_TLS_MAJOR && minor < FIRST_TLS_MINOR)
    }

    // ----------------------------------------------------------- the URL rule

    /**
     * Whether [host] is this machine — the only place plain http may still go.
     *
     * `127.0.0.0/8`, `::1` (bracketed or not, written out or not) and the bare
     * name `localhost`. ⚠ NOT `*.localhost`: RFC 6761 asks resolvers to send it
     * to loopback, but nothing guarantees a given resolver does, and a bearer in
     * cleartext is not the place to find out.
     */
    fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost") return true
        val v4 = h.split('.')
        if (v4.size == 4 && v4.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) return v4[0] == "127"
        if (':' in h) {
            // Expand `::` and compare against 0:0:0:0:0:0:0:1.
            val halves = h.split("::")
            if (halves.size > 2) return false
            val head = halves[0].takeIf { it.isNotEmpty() }?.split(':') ?: emptyList()
            val tail = if (halves.size == 2) halves[1].takeIf { it.isNotEmpty() }?.split(':') ?: emptyList() else emptyList()
            val groups = if (halves.size == 2) head + List(8 - head.size - tail.size) { "0" } + tail else head
            if (groups.size != 8) return false
            val values = groups.map { it.toIntOrNull(16) ?: return false }
            return values.dropLast(1).all { it == 0 } && values.last() == 1
        }
        return false
    }

    /**
     * The URL actually dialled for [url]: `http://` stays only for loopback,
     * every other `http://` becomes `https://` on the same authority (TLS.md
     * rule 6). A bare authority is judged as http first — the same assumption
     * the client has always made — and so upgraded too. `https://` is untouched.
     *
     * ⚠ THERE IS NO OPT-OUT, AND THERE MUST NEVER BE ONE. An "allow plain http"
     * switch is a switch a phishing page can talk somebody into flipping, and its
     * only effect is to put a root-equivalent token on the wire.
     */
    fun secureUrl(url: String): String {
        val t = url.trim()
        if (t.isEmpty()) return t
        val lower = t.lowercase()
        if (lower.startsWith("https://")) return "https://" + t.substring(8)
        val rest = when {
            lower.startsWith("http://") -> t.substring(7)
            "://" in t -> return t // not ours to rewrite; RouteGuard refuses it
            else -> t
        }
        val authority = rest.substringBefore('/')
        val host = hostOf(authority)
        return if (isLoopbackHost(host)) "http://$rest" else "https://$rest"
    }

    /** The host part of an authority, brackets kept off an IPv6 literal. */
    internal fun hostOf(authority: String): String {
        val a = authority.substringAfterLast('@')
        if (a.startsWith("[")) return a.substring(1, a.indexOf(']').takeIf { it > 0 } ?: a.length)
        return a.substringBefore(':')
    }

    // ---------------------------------------------------------------- helpers

    private const val HEX = "0123456789abcdef"

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** Lowercase hex SHA-256 — the fingerprint format on the wire and in the pin set. */
    fun sha256Hex(bytes: ByteArray): String = DaemonChallenge.sha256(bytes).toHex()
}

/**
 * Why a request was not sent: the address failed the TLS proof, or the daemon
 * cannot do TLS at all.
 *
 * ⚠ DELIBERATELY NOT A [HuginnClient.HuginnException]. Both shells read
 * `!is HuginnException` as "the PATH is wrong, look for another route", and for
 * both cases that is right: a different pinned address may reach the real
 * daemon untouched, and an interceptor on one network is exactly when moving
 * matters. The message is the contract's own sentence and is shown verbatim.
 */
class DaemonTlsException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { INTERCEPTED, TOO_OLD }
}

/**
 * The pin set for ONE paired daemon (TLS.md rule 7): bound to the token rather
 * than to an address, because one daemon has one certificate however many
 * routes reach it.
 *
 * ⚠ [owner] IS A TAG OF THE TOKEN, NOT THE TOKEN. It sits in the same settings
 * file as the token, so it discloses nothing; it exists so pins can never
 * outlive a re-pairing even if a store forgets to clear them — a set whose owner
 * does not match the current token reads as empty.
 */
@Serializable
data class TlsPins(val owner: String = "", val pins: List<String> = emptyList()) {

    /** The pins, if they belong to [token]; otherwise none. */
    fun pinsFor(token: String): List<String> =
        if (token.isNotBlank() && owner == ownerTag(token)) pins else emptyList()

    /**
     * [fp] added for [token]: newest first, at most [DaemonTls.MAX_PINS], a
     * re-proved pin moved to the front rather than duplicated. A different token
     * starts a fresh set — the old daemon's pins are not this one's.
     */
    fun withPin(token: String, fp: String): TlsPins {
        val f = fp.trim().lowercase()
        val tag = ownerTag(token)
        val kept = if (owner == tag) pins.filter { it != f } else emptyList()
        return TlsPins(owner = tag, pins = (listOf(f) + kept).take(DaemonTls.MAX_PINS))
    }

    fun encode(): String = if (pins.isEmpty()) "" else codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        /** Unreadable or blank reads as no pins: the cost is one re-proof, never a refusal to start. */
        fun decode(raw: String?): TlsPins =
            if (raw.isNullOrBlank()) TlsPins()
            else runCatching { codec.decodeFromString(serializer(), raw) }.getOrDefault(TlsPins())

        fun ownerTag(token: String): String =
            DaemonTls.sha256Hex("huginn-tls-pins:${token.trim()}".encodeToByteArray()).take(16)
    }
}

/**
 * The in-memory pin set every [HuginnClient] in a process consults, written
 * through to settings.
 *
 * ⚠ ONE PER PROCESS, AND ATTACHED BY THE SETTINGS STORE. The phone builds a
 * client in ten background places (receivers, workers, the push service) from
 * bare `{ base }`/`{ token }` lambdas, deliberately without a settings handle —
 * so the pins cannot ride in a constructor argument without touching every one
 * of them, and a pin learned by the watch service should spare the next widget
 * refresh a round trip. Each store calls [attach] when it is built, and every one
 * of those call sites builds its store before its client.
 *
 * ⚠ PERSISTENCE IS AN OPTIMISATION, NOT THE SECURITY. Every pin was proved with
 * the token; a book with nothing loaded simply proves again. So a client used
 * without any store attached (a test, a tool) is exactly as safe, only slower.
 *
 * [check] is read synchronously from inside a TLS handshake, which is why the
 * state is a volatile snapshot rather than something behind the mutex.
 */
class PinBook {

    @Volatile private var state: TlsPins = TlsPins()
    @Volatile private var store: HuginnSettings? = null
    @Volatile private var loadedFrom: HuginnSettings? = null
    private val loadLock = Mutex()

    /** Held while one coroutine proves, so a cold start's six parallel calls cause one proof, not six. */
    internal val proving = Mutex()

    fun attach(settings: HuginnSettings) { store = settings }

    /** Pins for [token], as of now. Safe from any thread. */
    fun pinsFor(token: String): List<String> = state.pinsFor(token)

    /** Reads the persisted set once per attached store. Cheap after the first call. */
    suspend fun load() {
        val s = store ?: return
        if (loadedFrom === s) return
        loadLock.withLock {
            if (loadedFrom === s) return
            val persisted = TlsPins.decode(runCatching { s.tlsPins.first() }.getOrNull())
            // Something learned in memory before the load (a proof that won the
            // race) is newer than the file; keep it in front.
            val mem = state
            state = when {
                mem.pins.isEmpty() -> persisted
                mem.owner == persisted.owner ->
                    TlsPins(mem.owner, (mem.pins + persisted.pins).distinct().take(DaemonTls.MAX_PINS))
                else -> mem
            }
            loadedFrom = s
        }
    }

    /** Records a proved fingerprint for [token] and writes it through. */
    suspend fun add(token: String, fp: String) {
        val next = state.withPin(token, fp)
        if (next == state) return
        state = next
        store?.let { s -> runCatching { s.setTlsPins(next.encode()) } }
    }

    companion object {
        /** The process's book. See the class comment for who attaches it. */
        val process: PinBook = PinBook()
    }
}

/**
 * What a TLS probe connection saw.
 *
 * @param leafFp sha256 of the leaf certificate's DER, lowercase hex, recorded by
 *   the probe's own trust manager during the handshake. Null when there was no
 *   TLS (see [notTls]).
 * @param notTls the port answered the TLS hello with plain HTTP — what a
 *   pre-3.11 daemon does. [status]/[body]/[appdHeader] are then from an
 *   unauthenticated plain request, kept only to name it.
 */
class TlsProbeReply(
    val leafFp: String?,
    val status: Int,
    val body: String,
    val appdHeader: String?,
    val notTls: Boolean = false,
)

/**
 * The two kinds of connection TLS.md asks for, behind one seam so the proof
 * logic above is testable without a socket.
 *
 * ⚠ TWO CHANNELS THAT MUST NEVER MIX (rule 3). [probe] accepts ANY certificate
 * and so may carry nothing secret — it never sends an Authorization header and
 * its connection is closed after one exchange. [engine] carries the bearer and
 * accepts a certificate if and only if its leaf fingerprint is in the pin set;
 * the system CAs are not consulted and hostname verification is off, because
 * the pin replaces both (the daemon's certificate is named `huginn-appd` and is
 * dialled by IP).
 */
interface DaemonTransport {
    /** The engine for every bearer-carrying request. [pins] is read during each handshake. */
    fun engine(pins: () -> Collection<String>): HttpClientEngine

    /**
     * `GET [url]` on a fresh trust-anything TLS connection, no Authorization,
     * closed afterwards. Throws the transport's own exception when nothing could
     * be reached at all — the caller words that as "unreachable", as it always has.
     */
    suspend fun probe(url: String, timeoutMs: Long): TlsProbeReply

    /**
     * The fingerprint of the leaf a pinned handshake refused, when that is what
     * [failure] is — the trigger for a re-proof (rule 5). Null for every other
     * failure, which then surfaces exactly as it always did.
     */
    fun unpinnedLeaf(failure: Throwable): String?
}
