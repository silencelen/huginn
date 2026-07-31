package com.silencelen.huginn.desktop.notify

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Which of the two lists a notification, a tray row or an activation points at.
 *
 * Deliberately NOT [com.silencelen.huginn.desktop.View]: that enum has four
 * members and two of them (Status, Settings) can never be the subject of a
 * notification. A type that can only hold the reachable answers is the reason
 * there is no "what do we do with an `open?view=settings`" branch anywhere below.
 */
enum class TargetKind(val wire: String) {
    CHATS("chats"),
    SESSIONS("sessions"),
    ;

    companion object {
        fun fromWire(raw: String?): TargetKind? = entries.firstOrNull { it.wire == raw }
    }
}

/** A place in the app, and the key its notification is filed under. */
data class NavTarget(val kind: TargetKind, val id: String) {
    /**
     * Stable across a post/withdraw pair, which is the whole job: a notification
     * is taken down by key, and a key derived from anything mutable (the title,
     * the question) would leave the toast up when the question changed.
     */
    val key: String
        get() = when (kind) {
            TargetKind.CHATS -> "chat:$id"
            TargetKind.SESSIONS -> "sess:$id"
        }
}

/** What a `huginn://` URL asked for. */
sealed interface Activation {
    data class Open(val target: NavTarget) : Activation

    /**
     * [fingerprint] is non-null BY CONSTRUCTION. See [Activations.parse]: an
     * answer without one is not a degraded activation, it is a refused one, and
     * making the field nullable here would move that decision to every call site.
     */
    data class Answer(val session: String, val option: Int, val fingerprint: String) : Activation
}

/**
 * `huginn://` — how a toast button, a browser link or another copy of this app
 * reaches the running instance.
 *
 * Two verbs:
 * ```
 * huginn://open?view=chats|sessions&id=…      focus + navigate
 * huginn://answer?session=…&option=N&fp=…     answer a pane prompt
 * ```
 *
 * Pure and side-effect free on purpose — every rule below is a security rule, and
 * the ones that matter are testable without a window, a tray or a daemon.
 */
object Activations {

    const val SCHEME: String = "huginn"

    private val PREFIX = "$SCHEME://"

    /**
     * Parses one URL, or refuses it.
     *
     * THE FINGERPRINT IS MANDATORY ON `answer`, and that single line is the whole
     * security story of this verb. Anything running on this machine can fire a
     * scheme URL — a local process, a background tab, a link the owner clicks —
     * and huginn's host is root-equivalent. Without a fingerprint the daemon
     * answers whatever question happens to be on the pane at that instant, so a
     * forged link approves an arbitrary tool-use prompt. With it, the answer only
     * lands if it matches the exact question this app was showing when it built
     * the toast; anything else comes back 409 and nothing is typed.
     *
     * The app must never COMPUTE a fingerprint to fill the gap. It is the host's
     * identity for a question, and a second implementation of "which question is
     * this" is a second opinion — see [com.silencelen.huginn.data.PanePrompt].
     */
    fun parse(raw: String?): Activation? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith(PREFIX, ignoreCase = true)) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        // `authority`, not `host`: a host that fails the RFC's reg-name rules comes
        // back null and the verb would silently vanish. The authority is the raw
        // text between "//" and the first "/", "?" or "#", which is what we want.
        val verb = uri.authority?.lowercase().orEmpty()
        val q = query(uri.rawQuery)

        return when (verb) {
            "open" -> {
                val kind = TargetKind.fromWire(q["view"]) ?: return null
                val id = q["id"].orEmpty()
                if (id.isEmpty()) return null
                Activation.Open(NavTarget(kind, id))
            }

            "answer" -> {
                val session = q["session"].orEmpty()
                val option = q["option"]?.toIntOrNull() ?: return null
                val fingerprint = q["fp"].orEmpty()
                if (session.isEmpty() || option < 1) return null
                if (fingerprint.isEmpty()) return null
                Activation.Answer(session, option, fingerprint)
            }

            else -> null
        }
    }

    /**
     * Finds an activation in a launcher's argv.
     *
     * Only the LAST non-flag argument is considered — that is where every OS puts
     * the URL, and scanning everything would also sweep whatever a launcher, a
     * wrapper script or a debugger decided to pass along.
     */
    fun fromArgv(argv: Array<String>): Activation? = fromArgv(argv.toList())

    fun fromArgv(argv: List<String>): Activation? = parse(urlFromArgv(argv))

    /**
     * The RAW url, for handing to an instance that is already running — that one
     * does its own parsing, and forwarding a parsed object would mean a wire
     * format nobody needs.
     */
    fun urlFromArgv(argv: Array<String>): String? = urlFromArgv(argv.toList())

    fun urlFromArgv(argv: List<String>): String? {
        for (arg in argv.asReversed()) {
            if (arg.startsWith("-")) continue
            return if (parse(arg) != null) arg else null
        }
        return null
    }

    fun openUrl(target: NavTarget): String =
        "${PREFIX}open?view=${enc(target.kind.wire)}&id=${enc(target.id)}"

    fun answerUrl(session: String, option: Int, fingerprint: String): String =
        "${PREFIX}answer?session=${enc(session)}&option=$option&fp=${enc(fingerprint)}"

    /**
     * [URLEncoder] and [URLDecoder] agree with each other about `+` (space out,
     * `%2B` for a literal plus), which is all this has to be: both ends of every
     * one of these URLs are in this file.
     */
    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun query(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            val key = runCatching { URLDecoder.decode(k, StandardCharsets.UTF_8) }.getOrNull() ?: continue
            val value = runCatching { URLDecoder.decode(v, StandardCharsets.UTF_8) }.getOrNull() ?: continue
            out.putIfAbsent(key, value)
        }
        return out
    }
}
