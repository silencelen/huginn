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

    companion object {
        /**
         * Setup's test notification. It has nowhere to go: a click only brings
         * the window up. It used to open a chat by this id, which on Windows
         * (where a toast outlives its withdraw in the Action Center) landed on
         * "Untitled - not found" after setup (run 3).
         */
        val SETUP_TEST = NavTarget(TargetKind.CHATS, "huginn-setup-test")
    }
}

/** What a `huginn://` URL asked for. */
sealed interface Activation {
    data class Open(val target: NavTarget) : Activation

    /**
     * [fingerprint] and [nonce] are non-null BY CONSTRUCTION. See
     * [Activations.parse]: an answer missing either is not a degraded answer, it
     * is navigation, and making the fields nullable here would move that decision
     * to every call site. A parsed Answer is still only a CLAIM: [Activations.resolve]
     * acts on it only when [nonce] is one this process issued for exactly this.
     */
    data class Answer(val session: String, val option: Int, val fingerprint: String, val nonce: String) : Activation

    /**
     * Put a session back on the model the ladder moved it off.
     *
     * NONCED, like [Answer]. It used to carry nothing but the session name, on
     * the reasoning that a model change is neither destructive nor privileged.
     * But anyone can fire a scheme URL, and this one spends the owner's Fable
     * window and stops the ladder moving the session for ten minutes. So it is
     * acted on only with a nonce from the toast that offered it; otherwise it
     * opens the session, where the model is one click away in the header.
     */
    data class Undo(val session: String, val nonce: String) : Activation

    /**
     * Dismiss a notification without going anywhere.
     *
     * The "OK" half of a bounded pair. It exists because the alternative reading
     * of OK — navigate to the thing — is a different verb wearing the same word,
     * and a button that says OK and moves the reader somewhere is a button that
     * gets pressed once and then never again.
     */
    data class Ack(val key: String) : Activation
}

/**
 * What the running app does with a `huginn://` URL, decided before anything is done.
 *
 * Kept apart from [Activation] because a parsed URL is a claim and this is the
 * verdict on it. Only [Answer] and [Undo] act for the owner, and only
 * [Activations.resolve] makes them.
 */
sealed interface ActivationStep {
    /** Bring the window up and nothing else. */
    data object Summon : ActivationStep

    /**
     * Bring the window up on [target]. [refused] says why, when this is an answer
     * or an undo that was not acted on, for the log.
     */
    data class Navigate(val target: NavTarget, val refused: String? = null) : ActivationStep

    /** The exact call the in-app answer makes: `answerPrompt(session, option, fp)`. */
    data class Answer(val session: String, val option: Int, val fingerprint: String) : ActivationStep

    data class Undo(val session: String) : ActivationStep

    /** Take the notification filed under [key] down. */
    data class Dismiss(val key: String) : ActivationStep
}

/**
 * `huginn://` — how a toast button, a browser link or another copy of this app
 * reaches the running instance.
 *
 * Four verbs:
 * ```
 * huginn://open?view=chats|sessions&id=…               focus + navigate
 * huginn://answer?session=…&option=N&fp=…&nonce=…      answer a pane prompt
 * huginn://undo?session=…&nonce=…                      put a laddered session back
 * huginn://ack?key=…                                   take a notification down
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
     * AN `answer` OR `undo` WITHOUT A NONCE IS NAVIGATION. Anything running on
     * this machine can fire a scheme URL — a local process, a background tab, a
     * link the owner clicks — and huginn's host is root-equivalent. The
     * fingerprint does not stop that: it is an unkeyed hash of the question and
     * its options, so anyone who can predict a stock dialog can compute it, and
     * a link carrying only a fingerprint once approved whatever it named. So both
     * verbs need a nonce as well, and a link without one comes back as
     * [Activation.Open] on the session: the window comes up there and the owner
     * clicks. Whether a nonce that IS present is any good is [resolve]'s question.
     *
     * THE FINGERPRINT STAYS MANDATORY ON `answer`, for its own job: the daemon
     * types the answer only if it matches the exact question this app was showing
     * when it built the toast; anything else comes back 409 and nothing is typed.
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
                val nonce = q["nonce"].orEmpty()
                if (session.isEmpty() || option < 1) return null
                if (fingerprint.isEmpty() || nonce.isEmpty()) return sessionOf(session)
                Activation.Answer(session, option, fingerprint, nonce)
            }

            "undo" -> {
                val session = q["session"].orEmpty()
                val nonce = q["nonce"].orEmpty()
                when {
                    session.isEmpty() -> null
                    nonce.isEmpty() -> sessionOf(session)
                    else -> Activation.Undo(session, nonce)
                }
            }

            "ack" -> {
                val key = q["key"].orEmpty()
                if (key.isEmpty()) null else Activation.Ack(key)
            }

            else -> null
        }
    }

    private fun sessionOf(name: String): Activation = Activation.Open(NavTarget(TargetKind.SESSIONS, name))

    /**
     * What to do with [raw], which may have come from anywhere.
     *
     * The single place an outside URL can become an action taken for the owner,
     * and it becomes one only when its nonce is one [nonces] issued for exactly
     * that session, question and option, has not been used, and is younger than
     * its TTL. Everything else that names a session opens it — including any link
     * that STARTED this process, since a nonce from a previous run cannot be in a
     * fresh table — so a forged or stale link is navigation, never an answer.
     */
    fun resolve(raw: String?, nonces: ActivationNonces): ActivationStep =
        when (val a = parse(raw)) {
            null -> ActivationStep.Summon
            is Activation.Open -> ActivationStep.Navigate(a.target)
            is Activation.Ack -> ActivationStep.Dismiss(a.key)
            is Activation.Answer -> {
                val v = nonces.redeemAnswer(a.nonce, a.session, a.option, a.fingerprint)
                if (v == ActivationNonces.Verdict.ACCEPTED) {
                    ActivationStep.Answer(a.session, a.option, a.fingerprint)
                } else {
                    refused(a.session, "answer", v)
                }
            }
            is Activation.Undo -> {
                val v = nonces.redeemUndo(a.nonce, a.session)
                if (v == ActivationNonces.Verdict.ACCEPTED) ActivationStep.Undo(a.session) else refused(a.session, "undo", v)
            }
        }

    private fun refused(session: String, verb: String, v: ActivationNonces.Verdict) =
        ActivationStep.Navigate(
            NavTarget(TargetKind.SESSIONS, session),
            refused = "$verb link with ${v.name.lowercase()} nonce: opened the session instead",
        )

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

    fun answerUrl(session: String, option: Int, fingerprint: String, nonce: String): String =
        "${PREFIX}answer?session=${enc(session)}&option=$option&fp=${enc(fingerprint)}&nonce=${enc(nonce)}"

    fun undoUrl(session: String, nonce: String): String =
        "${PREFIX}undo?session=${enc(session)}&nonce=${enc(nonce)}"

    fun ackUrl(key: String): String = "${PREFIX}ack?key=${enc(key)}"

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
