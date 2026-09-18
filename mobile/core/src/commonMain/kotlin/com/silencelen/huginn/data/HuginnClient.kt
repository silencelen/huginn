package com.silencelen.huginn.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Talks to huginn-appd. Every call carries the bearer token; a non-2xx response
 * is surfaced as [HuginnException] carrying the server's own error text, because
 * "unauthorized" vs "no such session" is exactly what the user needs to see.
 *
 * Multiplatform: the phone and the desktop client speak to the same daemon, and
 * a second hand-written copy of these routes is a second place for them to drift
 * from it. The one thing that is not common is the HTTP engine — see
 * [huginnHttpEngine].
 */
class HuginnClient(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    /**
     * Stable per-installation id, sent so the HOST can record that this phone is
     * still listening. That record is the only way to answer "did my phone keep
     * checking in overnight?", because the phone cannot report having gone quiet
     * and waking it to ask ends the very state under investigation.
     *
     * Blank for the ordinary UI client: a foreground screen is not evidence of
     * background delivery and should not be counted as such.
     */
    private val clientIdProvider: () -> String = { "" },
    /**
     * Whether Android will actually display what this app posts. Null means "not
     * saying". The host holds Telegram back when a phone is listening, so a
     * listening app that cannot show anything must not claim to be a route.
     */
    private val canNotifyProvider: () -> Boolean? = { null },
    /** Injected by tests (a mock engine); production takes the platform default. */
    engine: HttpClientEngine = huginnHttpEngine(),
) {
    class HuginnException(val code: Int, override val message: String) : Exception(message)

    /**
     * Something answered at this address, and it was not huginn.
     *
     * ⚠ DELIBERATELY NOT A [HuginnException]. Both shells classify a failure as
     * network-vs-server with `it !is HuginnException`, and the whole point of
     * this case is that the address is wrong — a captive portal, a proxy
     * interstitial, a stranger's web page, a stale RFC1918 pin on a foreign LAN.
     * Wrapping it as a server error would stop the client re-resolving away from
     * exactly the host it should be leaving.
     *
     * And the message is a SENTENCE, not the body. kotlinx appends the input it
     * choked on to its own exception unconditionally, and nothing in `mobile/`
     * caught it, so `errorTextFor`'s fallback printed
     * `Unexpected JSON token at offset 0: … JSON input: <the page>` onto the
     * Status screen, untruncated.
     */
    class NotHuginnException(override val message: String = NOT_HUGINN) : Exception(message)

    /**
     * FOUR TIMEOUT TIERS, and they are a contract rather than a detail — each one
     * is a production failure that went unnoticed until it had a number. Change
     * one only against the behaviour described beside it.
     */
    companion object {
        /** What a call says when nothing is pinned yet. A first run, not a fault. */
        const val NO_ROUTE: String = "No route yet — add the address huginn answers on in Settings"

        /** What a call says when the address answered and the answer was not huginn's. */
        const val NOT_HUGINN: String = "that address answered, but not like huginn does"

        /** Establishing the connection. Short: a route that does not answer must fail fast enough for the resolver to try the next one. */
        const val CONNECT_TIMEOUT_MS: Long = 8_000

        /** Ordinary request/response. */
        const val READ_TIMEOUT_MS: Long = 30_000

        /**
         * Chat streams stay open for a whole Claude turn, so the timeout cannot be
         * short — but it must EXIST. With none, a socket black-holed mid-turn never
         * failed, the flow never completed, and the chat sat with `sending` true and
         * a composer that would not send until the app was restarted. The daemon now
         * emits a keepalive comment every 20s, so silence for a minute means the
         * path is genuinely gone rather than that Claude is thinking.
         */
        const val STREAM_READ_TIMEOUT_MS: Long = 60_000

        /**
         * Screen long polls are DIFFERENT: the server answers within its `wait`
         * window, so a silent connection is a dead one. With no timeout at all a
         * black-holed socket (network change, NAT expiry) blocked the poll forever —
         * the screen froze with no error and no retry, and nothing rearmed it.
         * Sized for the longest server-side hold (the watch parks for up to two
         * minutes) plus slack, so a live connection is never cut mid-wait — while a
         * genuinely dead socket still fails instead of hanging forever.
         */
        const val POLL_READ_TIMEOUT_MS: Long = 150_000

        /** The whole long-poll call, end to end. OkHttp's `callTimeout`, kept. */
        const val POLL_CALL_TIMEOUT_MS: Long = 180_000

        /**
         * The watch stream is a THIRD kind of timeout, and the distinction is the point
         * of the stream existing. Chat streams may be silent for a whole Claude turn, so
         * they get a full minute. The watch stream is contractually never silent — the
         * server sends a keepalive every 25 seconds — so silence beyond a minute means
         * the socket is dead, which is exactly the failure that used to go unnoticed
         * while the phone slept and the app went on believing it was watching.
         */
        const val WATCH_READ_TIMEOUT_MS: Long = 60_000

        /**
         * Route probing. Not one of the four tiers: this one is a question about
         * whether a path exists at all, asked of every candidate in turn, so it
         * has to give up faster than a real call would.
         */
        const val PROBE_TIMEOUT_MS: Long = 3_000

        /**
         * The daemon stamps its version on EVERY response, the 401 included, so a
         * client can tell huginn from whatever else is listening on that address
         * without sending it anything. Preferred over the body shape below when
         * present; absent from daemons older than the release that added it,
         * which is why the body rule still exists.
         */
        const val APPD_HEADER: String = "X-Huginn-Appd"

        private val probeJson = Json { ignoreUnknownKeys = true }

        /**
         * Whether a probe reply PROVES the daemon rather than merely a socket.
         *
         * ⚠ THE BEARER FOLLOWS THE ROUTE. `probe` used to return true for any
         * completed HTTP exchange — a NAS's login page, a printer, a captive
         * portal, a 404 from whoever holds that DHCP lease today — and the
         * resolver then made that host the active route, after which the very
         * next call handed it a root-equivalent daemon token in cleartext. A
         * live path is not the question; a live *huginn* is.
         *
         * Two markers, neither of which requires the token:
         *
         *  - [APPD_HEADER] on the response, whatever the status.
         *  - a `401` whose body is the daemon's own JSON error shape. `/v1/ping`
         *    is token-gated, so this is what an unauthenticated probe gets from
         *    a real daemon, and the shape is narrow enough that a stranger's
         *    plain-text or HTML 401 does not pass.
         *
         * Neither is unforgeable — an active attacker can copy both — and that is
         * not what this is for: it closes the case where an ordinary host that
         * happens to answer is PREFERRED over a live daemon. The durable fix is a
         * token-proving challenge, which needs a daemon change.
         */
        fun provesDaemon(appdHeader: String?, status: Int, body: String): Boolean {
            if (!appdHeader.isNullOrBlank()) return true
            if (status != 401) return false
            val error = runCatching { probeJson.decodeFromString<ApiError>(body).error }.getOrNull()
            return !error.isNullOrBlank()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Spelled out rather than `Json.withCharset`, to keep the header byte-identical to what the daemon has always been sent. */
    private val jsonMedia = ContentType.parse("application/json; charset=utf-8")

    /**
     * ONE client, not four.
     *
     * OkHttp needed a separate instance per timeout tier because its timeouts are
     * per-client; Ktor's are per-request, so the tiers below are applied by
     * [applyTier] and the connection pool is shared instead of split four ways.
     * `expectSuccess = false` because this client reads the server's own error
     * text out of a non-2xx body rather than letting the plugin throw over it.
     */
    private val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = READ_TIMEOUT_MS
            // requestTimeoutMillis deliberately unset — infinite. Only the POLL
            // tier caps a whole call; a chat stream legitimately runs for as long
            // as Claude does.
        }
    }

    /** Releases the engine. Optional on Android (the process owns it); the desktop client should call it. */
    fun close() = http.close()

    /**
     * Named `absolute`, not `url`: inside a Ktor request block `url(...)` is the
     * builder's own extension, and a helper by that name resolves to the wrong
     * one there — silently, since both take a String.
     */
    private fun absolute(path: String): String {
        val base = baseUrlProvider()
        // ⚠ AN EMPTY BASE URL IS A STATE, NOT A URL. A fresh install pins no
        // route, so this is what every call makes on first launch — and
        // `withScheme("")` produces `http:///v1/status`, whose parse failure
        // surfaced in the status bar as the word "v1". Said plainly instead, and
        // said here so both shells say the same thing.
        if (base.isBlank()) throw HuginnException(0, NO_ROUTE)
        return withScheme(base) + path
    }

    /**
     * ⚠ CASE-INSENSITIVELY. A scheme is a scheme however it is spelled, and 2.x's
     * setter only trimmed — so `HTTP://192.168.2.117:8787` was storable, and this
     * helper used to build `http://HTTP//192.168.2.117:8787/v1/ping` out of it.
     */
    private fun withScheme(base: String): String {
        val b = base.trim().trimEnd('/')
        val schemed = b.startsWith("http://", ignoreCase = true) || b.startsWith("https://", ignoreCase = true)
        return if (schemed) b else "http://$b"
    }

    private enum class Tier { NORMAL, POLL, STREAM, WATCH }

    private fun HttpRequestBuilder.applyTier(tier: Tier) {
        // Unset fields fall back to the plugin-level configuration above, so each
        // of these overrides exactly what it names and nothing else.
        when (tier) {
            Tier.NORMAL -> Unit
            Tier.POLL -> timeout {
                socketTimeoutMillis = POLL_READ_TIMEOUT_MS
                requestTimeoutMillis = POLL_CALL_TIMEOUT_MS
            }
            Tier.STREAM -> timeout { socketTimeoutMillis = STREAM_READ_TIMEOUT_MS }
            Tier.WATCH -> timeout { socketTimeoutMillis = WATCH_READ_TIMEOUT_MS }
        }
    }

    private fun HttpRequestBuilder.build(path: String, method: HttpMethod, tier: Tier, body: Any?) {
        this.method = method
        url(absolute(path))
        header("Authorization", "Bearer ${tokenProvider().trim()}")
        val id = clientIdProvider().trim()
        if (id.isNotEmpty()) header("X-Huginn-Client", id)
        canNotifyProvider()?.let { header("X-Huginn-Notify", if (it) "1" else "0") }
        applyTier(tier)
        when (body) {
            null -> Unit
            is JsonObject -> {
                contentType(jsonMedia)
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }
            else -> setBody(body)
        }
    }

    /**
     * A 2xx body into a model — or [NotHuginnException] if it is not one. The
     * raw text is never carried into the message; see that type for why.
     */
    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString(body)
        } catch (e: SerializationException) {
            throw NotHuginnException()
        }

    private fun errorFrom(code: Int, body: String): HuginnException {
        val msg = runCatching { json.decodeFromString<ApiError>(body).error }.getOrNull()
        return HuginnException(code, msg ?: "HTTP $code")
    }

    private suspend fun call(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        tier: Tier = Tier.NORMAL,
        body: Any? = null,
    ): String {
        val resp = http.request { build(path, method, tier, body) }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, text)
        return text
    }

    private suspend fun post(path: String, tier: Tier = Tier.NORMAL, body: Any? = null) =
        call(path, HttpMethod.Post, tier, body)

    /**
     * Is HUGINN answering at [candidate]? Not "is anything answering" — see
     * [provesDaemon] for why that question was the wrong one and what it cost.
     *
     * Unauthenticated, and deliberately: a probe that carried the bearer would
     * disclose it to exactly the stranger this is trying to detect. `GET
     * /v1/ping` without a token is a 401 from a real daemon, which is the
     * cheapest thing it can be asked to say.
     *
     * Lives on the client rather than in the UI so route resolution works the
     * same way from the desktop client, and so this module owns every socket the
     * app opens.
     */
    suspend fun probe(candidate: String): Boolean = runCatching {
        val resp = http.request {
            method = HttpMethod.Get
            url(withScheme(AppdRoutes.normalize(candidate)) + "/v1/ping")
            timeout {
                connectTimeoutMillis = PROBE_TIMEOUT_MS
                socketTimeoutMillis = PROBE_TIMEOUT_MS
                requestTimeoutMillis = PROBE_TIMEOUT_MS
            }
        }
        // Bounded by the probe timeouts above: a host that dribbles a body at a
        // probe fails the same way one that never answers does.
        provesDaemon(resp.headers[APPD_HEADER], resp.status.value, resp.bodyAsText())
    }.getOrDefault(false)

    // ------------------------------------------------------------ status

    suspend fun ping(): Ping = decode(call("/v1/ping"))

    suspend fun status(): Status = decode(call("/v1/status"))

    suspend fun alerts(): Alerts = decode(call("/v1/alerts"))

    suspend fun setAlerts(enabled: Boolean? = null, mode: String? = null): Alerts {
        val body = buildJsonObject {
            enabled?.let { put("enabled", JsonPrimitive(it)) }
            mode?.let { put("mode", JsonPrimitive(it)) }
        }
        return decode(post("/v1/alerts", body = body))
    }

    /**
     * Rewrites the host's quick-action wording — the text a selection verb puts in
     * the composer on BOTH clients.
     *
     * A PATCH, and every field optional, because the editor saves what it was
     * shown: sending only what changed is what keeps two people editing different
     * fields from overwriting each other. [rev] is the copy the editor was opened
     * on; the daemon 409s a stale one rather than silently taking the older text.
     *
     * The daemon owns the refusals (a template must carry `{selection}` exactly
     * once, `quote` must not carry it at all, 400 characters each) and this
     * surfaces its words rather than guessing at them here.
     */
    suspend fun setQuickActions(
        explain: String? = null,
        execute: String? = null,
        askInNewChat: String? = null,
        quote: String? = null,
        rev: Int? = null,
    ): QuickActions {
        val body = buildJsonObject {
            explain?.let { put("explain", JsonPrimitive(it)) }
            execute?.let { put("execute", JsonPrimitive(it)) }
            askInNewChat?.let { put("askInNewChat", JsonPrimitive(it)) }
            quote?.let { put("quote", JsonPrimitive(it)) }
            rev?.let { put("rev", JsonPrimitive(it)) }
        }
        return decode(call("/v1/quick-actions", HttpMethod.Patch, body = body))
    }

    /**
     * What the host has seen of this phone. Read from the host on purpose: asking
     * the phone whether it stayed awake is asking the witness to alibi itself.
     */
    suspend fun clients(): ClientsInfo = decode(call("/v1/clients"))

    /**
     * Hands this device's FCM registration token to huginn.
     *
     * Keyed by the installation id rather than by the token, because Firebase rotates
     * tokens: keyed the other way, every reinstall would leave a dead token behind for
     * the host to retry forever.
     */
    suspend fun registerPush(installId: String, token: String, model: String? = null): PushRegistration {
        val body = buildJsonObject {
            put("installId", JsonPrimitive(installId))
            put("token", JsonPrimitive(token))
            model?.let { put("model", JsonPrimitive(it)) }
        }
        return decode(post("/v1/push/register", body = body))
    }

    /**
     * Answers a session's numbered question.
     *
     * [fingerprint] identifies the question being answered, and the host refuses the
     * answer if the pane has moved on. Checked there rather than here because this app
     * cannot hold the pane still between looking at it and typing into it — and a digit
     * delivered to the wrong prompt could accept something never seen.
     */
    suspend fun answerPrompt(session: String, option: Int, fingerprint: String? = null): AnswerResult {
        val body = buildJsonObject {
            put("option", JsonPrimitive(option))
            fingerprint?.let { put("fingerprint", JsonPrimitive(it)) }
        }
        return answerCall(session, body)
    }

    /**
     * Answers a multi-select question with the full DESIRED set. The host diffs
     * against the dialog's current checkboxes and does the toggle-review-submit
     * dance, so a question half-answered in tmux still ends up exactly as asked.
     */
    suspend fun answerPromptMulti(session: String, options: List<Int>, fingerprint: String? = null): AnswerResult {
        val body = buildJsonObject {
            put("options", JsonArray(options.map { JsonPrimitive(it) }))
            fingerprint?.let { put("fingerprint", JsonPrimitive(it)) }
        }
        return answerCall(session, body)
    }

    /**
     * The /answer route speaks in 409s — "changed", "gone", "undetected" — and
     * each body is a full AnswerResult whose `reason` the UI steers on (an
     * undetected answer opens the Screen tab). Throwing on the 409, as the plain
     * `call` path does, reduced all of that to an error STRING: `ok=false`
     * results were unreachable and every caller's reason handling was dead code.
     * So the answer routes decode the 409 body instead of throwing; every other
     * status still throws like any request.
     */
    private suspend fun answerCall(session: String, body: JsonObject): AnswerResult {
        val resp = http.request { build("/v1/sessions/$session/answer", HttpMethod.Post, Tier.NORMAL, body) }
        val text = resp.bodyAsText()
        if (resp.status.isSuccess() || resp.status.value == 409) {
            runCatching { decode<AnswerResult>(text) }.getOrNull()?.let { return it }
        }
        throw errorFrom(resp.status.value, text)
    }

    suspend fun autoswitch(): Autoswitch = decode(call("/v1/autoswitch"))

    suspend fun setAutoswitch(enabled: Boolean): Autoswitch {
        val body = buildJsonObject { put("enabled", JsonPrimitive(enabled)) }
        return decode(post("/v1/autoswitch", body = body))
    }

    /** Whether the HOST can push at all, and which devices it would reach. */
    suspend fun push(): PushStatus = decode(call("/v1/push"))

    /**
     * Parks until something an alert depends on changes, or [waitMs] elapses.
     * Uses the long-poll tier: the server holds this open deliberately.
     */
    suspend fun watch(knownHash: String?, waitMs: Int): Watch {
        val q = buildList {
            if (knownHash != null) add("hash=$knownHash")
            if (waitMs > 0) add("wait=$waitMs")
        }.joinToString("&")
        val path = "/v1/watch" + if (q.isEmpty()) "" else "?$q"
        return decode(call(path, tier = if (waitMs > 0) Tier.POLL else Tier.NORMAL))
    }

    /**
     * The watching connection: state when something changes, and a heartbeat when
     * nothing does.
     *
     * A separate reader from [sse] rather than a shared one, because the two want
     * opposite things from a comment frame. A chat stream treats `:` as noise to
     * skip; here it is the entire payload — proof the path is still open in both
     * directions. Collapsing them would mean the reader that most needs to notice
     * silence being written by the code that ignores it.
     */
    fun watchStream(knownHash: String?): Flow<WatchEvent> = flow {
        val path = "/v1/watch?stream=1" + if (knownHash != null) "&hash=$knownHash" else ""
        try {
            http.prepareRequest { build(path, HttpMethod.Get, Tier.WATCH, null) }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    emit(WatchEvent.Failure(errorFrom(resp.status.value, resp.bodyAsText()).message))
                    return@execute
                }
                val lines = SseLines(resp.bodyAsChannel())
                var event: String? = null
                val data = StringBuilder()
                while (true) {
                    val line = lines.next() ?: break
                    when {
                        line.startsWith(":") -> emit(WatchEvent.Alive)
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                        line.isEmpty() -> {
                            when (event) {
                                "state" -> runCatching { decode<Watch>(data.toString()) }
                                    .getOrNull()?.let { emit(WatchEvent.State(it)) }
                                // The server rotates a long-lived stream. Not
                                // an error, and must not be treated as one:
                                // backing off after a clean rotation would
                                // leave the phone unwatched for no reason.
                                "bye" -> { emit(WatchEvent.Rotated); return@execute }
                            }
                            event = null; data.setLength(0)
                        }
                    }
                }
                // Ran out of body without a `bye`: the socket closed under us.
                emit(WatchEvent.Failure("stream ended"))
            }
        } catch (e: CancellationException) {
            // The collector went away (screen closed, service stopped). Not a
            // failure, and reporting it as one would put an error on a screen
            // nobody is looking at and rearm a watcher that was told to stop.
            throw e
        } catch (e: Throwable) {
            emit(WatchEvent.Failure(e.message ?: "network error"))
        }
        // UNLIMITED for the same reason as [sse] — read the note there before
        // changing either. This stream is the quieter of the two, but it is also
        // the one that must never stall: it is what notices the socket is dead.
    }.buffer(Channel.UNLIMITED).flowOn(huginnIoDispatcher)

    /** Where an in-progress sign-in has got to. */
    suspend fun loginState(): LoginState = decode(call("/v1/account/login/state"))

    /** Hands the pasted code to the waiting sign-in and reports the outcome. */
    suspend fun submitLoginCode(code: String): LoginState =
        decode(post("/v1/account/login/code", Tier.POLL, jsonBody("code" to code)))

    /** Models the installed CLI offers, so the picker cannot go stale. */
    // ?local=1 is the OPT-IN for family:"local" rows: this client knows to keep
    // them out of session pickers and to treat picking one as a machine choice,
    // so it asks for them. An older daemon ignores the unknown parameter.
    suspend fun models(): List<ModelChoice> = decode<ModelList>(call("/v1/models?local=1")).models

    // ------------------------------------------------- account + usage

    suspend fun account(): Account = decode(call("/v1/account"))

    /**
     * Starts a sign-in. Naming the account aims the authorize page at it, which
     * matters because that page otherwise uses whatever session the browser is
     * already carrying.
     */
    suspend fun startLogin(email: String? = null): LoginSession {
        val body = buildJsonObject { if (!email.isNullOrBlank()) put("email", JsonPrimitive(email.trim())) }
        return decode(post("/v1/account/login", Tier.POLL, body))
    }

    suspend fun logout(): Account =
        decode(post("/v1/account/logout", body = jsonBody("confirm" to "logout")))

    suspend fun usage(): Usage = decode(call("/v1/usage"))

    /** Saved logins on the host; `withPlan` also reads each one's headroom. */
    suspend fun savedAccounts(withPlan: Boolean = false): List<SavedAccount> =
        decode<SavedAccounts>(call("/v1/accounts${if (withPlan) "?plan=1" else ""}")).accounts

    suspend fun activateAccount(slug: String): Account = decode(post("/v1/accounts/$slug/activate"))

    suspend fun forgetAccount(slug: String) {
        call("/v1/accounts/$slug", HttpMethod.Delete)
    }

    /**
     * Refreshes one saved profile's token in the background, now.
     *
     * Returns the daemon's status WORD rather than a boolean, because the
     * failures need different things from the reader: `refresh_token_expired`
     * means only a re-login helps, `lock_busy` means try again in a moment, and
     * `refresh_failed` means the network. A bare false said the same thing to
     * all of them, and that was the bug the expired-token switch has had since
     * it shipped. Two of the words are not failures at all — `not_needed` and
     * `active_skipped` — which is the other half of why this is not a boolean.
     *
     * ⚠ THIS ROUTE NEVER REFUSES. Asking it to refresh the ACTIVE login answers
     * `200 {ok:true, status:"active_skipped"}`; the route that refuses is
     * `/activate`, with a 409 whose text names the date.
     *
     * The whole vocabulary is on [AccountRefreshed.status].
     */
    suspend fun refreshAccount(slug: String): String =
        decode<AccountRefreshed>(post("/v1/accounts/$slug/refresh")).status

    /** The same call, whole — for a caller that needs more than the word. */
    suspend fun refreshAccountFull(slug: String): AccountRefreshed =
        decode(post("/v1/accounts/$slug/refresh"))

    /** Plan utilization: the same numbers Claude Code's /usage shows. */
    suspend fun plan(): Plan = decode(call("/v1/plan"))

    // --------------------------------------------------------- headroom

    /**
     * The whole headroom picture: every account's windows, the laddered
     * sessions, held spawns, sentinels and the arbiter's own reasoning.
     *
     * 404s on a daemon older than 3.0.0 — callers treat the throw as "this host
     * has no headroom subsystem" and hide the surface, rather than showing it
     * empty, which would read as "nothing is wrong".
     */
    suspend fun headroom(): Headroom = decode(call("/v1/headroom"))

    /**
     * Patches the owner-editable settings.
     *
     * ⚠ THE FORM SENDS THE WHOLE OBJECT, every field, every save — see
     * `SettingsView.patchOf`, whose own kdoc says so ("Whole rather than a diff,
     * deliberately"). The route ACCEPTS a partial body, and this kdoc used to
     * claim the form sent one and that two open settings screens therefore could
     * not clobber each other's untouched thresholds. They can, and they do: the
     * last save wins outright. The daemon validates and answers with the full
     * settings; a rule broken comes back as a 400 whose text names it.
     */
    suspend fun setHeadroomSettings(patch: JsonObject): HeadroomSettings =
        decode(call("/v1/headroom/settings", HttpMethod.Patch, body = patch))

    /**
     * Puts a session back on the model the daemon moved it off.
     *
     * Also stamps the session as human-decided, which is what keeps the arbiter
     * from laddering it straight back down on the next tick — an Undo that gets
     * silently undone is worse than no Undo.
     */
    suspend fun undoLadder(name: String): UndoResult =
        decode(post("/v1/sessions/$name/headroom/undo"))

    // ---------------------------------------------------------- sessions

    /** @param preview include per-session titles and activity previews (costlier). */
    suspend fun sessions(preview: Boolean = false): List<Session> =
        decode<SessionList>(call("/v1/sessions${if (preview) "?preview=1" else ""}")).sessions

    /**
     * Creates a tmux session and returns the name it ACTUALLY got, which is not
     * necessarily the one asked for.
     *
     * tmux rewrites some characters and still reports success — a '.' becomes
     * '_' — and the route's own name rule allows them through. Opening the
     * requested name rather than the returned one is a 404 on everything the
     * client does next, which is why the host reads the name back from tmux
     * instead of echoing the request.
     */
    suspend fun createSession(name: String): String =
        decode<CreatedSession>(post("/v1/sessions", body = jsonBody("name" to name))).name

    suspend fun killSession(name: String) {
        call("/v1/sessions/$name", HttpMethod.Delete)
    }

    /**
     * A session's ledger without its map: cheap enough to open a screen with.
     *
     * 409 when the Claude hook has not fired yet (a plain shell, or a session
     * whose first prompt is still being typed) — the caller shows the empty
     * state rather than an error, because neither is a fault.
     */
    suspend fun sessionOverview(name: String): SessionOverview =
        decode(call("/v1/sessions/$name/overview"))

    /**
     * The map, or nothing when the cursor still matches.
     *
     * Both halves of the cursor are sent. Passing only the parent size would
     * report a fan-out as unchanged for as long as it runs, because the parent
     * transcript does not grow while its agents do.
     */
    suspend fun sessionGraph(name: String, cursor: GraphCursor? = null): SessionGraph {
        val q = cursor?.let { "?size=${it.size}&agentBytes=${it.agentBytes}" } ?: ""
        return decode(call("/v1/sessions/$name/graph$q"))
    }

    /**
     * The goals and notes a person keeps against a run.
     *
     * Fields are sent INDIVIDUALLY — two editors on one screen autosave on their
     * own debounces, and a save carrying both would write whatever the other
     * field held when this one was last read.
     */
    suspend fun saveSessionMeta(name: String, goals: String? = null, notes: String? = null): SessionMeta =
        decode<SessionMetaSaved>(post("/v1/sessions/$name/meta", body = buildJsonObject {
            goals?.let { put("goals", JsonPrimitive(it)) }
            notes?.let { put("notes", JsonPrimitive(it)) }
        })).meta

    /**
     * Per-session auto-resume, on the same meta record as goals and notes.
     *
     * [value] has three states and null is one of them — "follow the global" —
     * so it is sent as an EXPLICIT JSON null rather than omitted. Omitting it is
     * how the other two fields say "do not touch me", and the two meanings would
     * be indistinguishable on the wire otherwise.
     */
    suspend fun setSessionAutoResume(name: String, value: Boolean?): SessionMeta =
        decode<SessionMetaSaved>(post("/v1/sessions/$name/meta", body = buildJsonObject {
            put("autoResume", if (value == null) JsonNull else JsonPrimitive(value))
        })).meta

    /**
     * Soft end: the host types its wrap-up phrase into the pane so Claude can
     * finish and commit; with auto-end on (the host default) the session then
     * ends itself once it settles. Returns what was actually sent — the phrase
     * is the HOST's, never a client copy. 409s when a question is waiting or
     * when the pane has no recorded Claude state (it may be a plain shell).
     */
    suspend fun softEndSession(name: String, auto: Boolean? = null): SoftEndResult =
        decode(post("/v1/sessions/$name/soft-end", body = buildJsonObject {
            if (auto != null) put("auto", JsonPrimitive(auto))
        }))

    /**
     * Compact the session's context (the "context manager" action): the host
     * types "/compact" into the pane so the owner can reclaim context from a
     * phone/desktop. 409s when a question is waiting or the pane has no recorded
     * Claude state (a plain shell would run "/compact" as a command).
     */
    suspend fun compactSession(name: String): CompactResult =
        decode(post("/v1/sessions/$name/compact", body = buildJsonObject {}))

    // ---- archive: sessions ended on purpose, and the way back into them

    /**
     * Every archived session, newest first.
     *
     * ALSO THE FEATURE PROBE. A daemon older than archive answers 404 here and
     * both clients hide the Archived section and the Archive action on that,
     * rather than showing a door that leads to an error — the same shape as
     * [scratchpads] and the silent-404 refreshRounds precedent.
     */
    suspend fun archives(): List<ArchivedSession> =
        decode<ArchiveList>(call("/v1/archive")).archives

    /**
     * End a session for good and keep the card that brings it back.
     *
     * GRACEFUL by default: the host types its wrap-up phrase, lets the turn
     * finish, and writes the card in the instant before the kill — so the 202
     * says `pending`, not `archived`, and the row appears when the session
     * settles. [now] is the escape hatch for a session with nothing to wrap up.
     *
     * ⚠ 409 ON A WAITING QUESTION, and the message is the point. "answer the
     * waiting question first, then archive the session" tells somebody exactly
     * what to do, so it is thrown as a [HuginnException] whose message the UI
     * shows verbatim rather than replacing with a summary of its own.
     */
    suspend fun archiveSession(name: String, now: Boolean = false): ArchiveResult =
        decode(post("/v1/sessions/$name/archive", body = buildJsonObject {
            put("mode", JsonPrimitive(if (now) "now" else "graceful"))
        }))

    /**
     * Bring one back: the host recreates the tmux session, restores its kept
     * transcript if Claude Code has swept its own, and resumes into it.
     *
     * [name] is a REQUEST, not a promise — the old name is taken when free, a
     * numbered one when not, and the result carries what tmux actually called it.
     */
    suspend fun reviveArchive(id: String, name: String? = null): ReviveResult =
        decode(post("/v1/archive/$id/revive", body = buildJsonObject {
            name?.let { put("name", JsonPrimitive(it)) }
        }))

    /** Forgets the row AND the transcript copy it was keeping. There is no undo. */
    suspend fun deleteArchive(id: String) {
        call("/v1/archive/$id", HttpMethod.Delete)
    }

    suspend fun renameSession(from: String, to: String) {
        post("/v1/sessions/$from/rename", body = jsonBody("name" to to))
    }

    /**
     * @param cols/rows  the phone's real geometry; the server leases a tmux resize
     *   so Claude Code re-wraps to fit instead of the phone showing a window of a
     *   laptop-shaped layout.
     * @param knownHash  long-poll: return only once the screen differs from this.
     * @param waitMs     how long the server may hold the request.
     * @param force      resize even though another client is attached.
     */
    suspend fun screen(
        name: String,
        cols: Int? = null,
        rows: Int? = null,
        history: Int = 0,
        knownHash: String? = null,
        waitMs: Int = 0,
        force: Boolean = false,
    ): Screen {
        val q = buildList {
            if (cols != null && rows != null) { add("cols=$cols"); add("rows=$rows") }
            if (history > 0) add("history=$history")
            if (knownHash != null) add("hash=$knownHash")
            if (waitMs > 0) add("wait=$waitMs")
            if (force) add("force=1")
        }.joinToString("&")
        val path = "/v1/sessions/$name/screen" + if (q.isEmpty()) "" else "?$q"
        // A long poll outlives the normal read timeout but must still time out.
        return decode(call(path, tier = if (waitMs > 0) Tier.POLL else Tier.NORMAL))
    }

    /** Hands the pane size back to tmux so an attached laptop re-fits at once. */
    suspend fun releaseSize(name: String) {
        call("/v1/sessions/$name/size", HttpMethod.Delete)
    }

    /**
     * Suggested next messages. The server generates on a turn boundary and
     * caches; this can take several seconds the first time, so it rides the
     * long-poll tier rather than the 30s default.
     */
    suspend fun sessionSuggestions(name: String): Suggestions =
        decode(call("/v1/sessions/$name/suggestions", tier = Tier.POLL))

    suspend fun chatSuggestions(id: String): Suggestions =
        decode(call("/v1/chats/$id/suggestions", tier = Tier.POLL))

    /**
     * Lands a file on huginn where a chat's Read tool can see it, STREAMING it.
     *
     * Raw bytes, not multipart: one file needs no parts, and the server names it
     * so nothing sent here decides where it is written. The body is pulled from
     * [stream] a chunk at a time straight onto the socket — a router or NVR
     * backup is tens of megabytes, and buffering one whole on a phone means
     * holding it twice. See [ByteStream] for why the source is an interface
     * rather than one platform's stream type.
     */
    suspend fun uploadStream(mime: String, name: String?, stream: ByteStream): UploadResult =
        // Closed HERE rather than inside StreamBody.writeTo, so the handle is
        // released exactly once and also when the request fails BEFORE a byte is
        // written — a connect timeout must not leave a content-provider handle
        // open on the phone.
        try {
            decode(post("/v1/uploads" + uploadQuery(name), body = StreamBody(mime, stream)))
        } finally {
            stream.close()
        }

    suspend fun upload(bytes: ByteArray, mime: String, name: String? = null): UploadResult =
        uploadStream(mime, name, bytes.asByteStream())

    /**
     * Reads a stored upload back by its server-assigned basename — the chat
     * history thumbnail path. 404 after a manual delete is expected; callers
     * fall back to the "photo attached" placeholder.
     */
    suspend fun uploadBytes(name: String): ByteArray {
        val resp = http.request { build("/v1/uploads/" + name.encodeURLParameter(), HttpMethod.Get, Tier.NORMAL, null) }
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, resp.bodyAsText())
        return resp.bodyAsBytes()
    }

    /**
     * Reads an image file the assistant NAMED — a rendered chart, a screenshot —
     * so the transcript can draw it instead of printing a path nobody can open
     * from here. Distinct from [uploadBytes], which reads back a file this client
     * itself uploaded and addresses it by the server-minted basename.
     *
     * ⚠ CONTAINMENT IS THE DAEMON'S, NOT THIS CLIENT'S. `GET /v1/files/image`
     * resolves the path against its own allowlisted roots (uploads, the
     * scratchpad render dir, the session's cwd when [session] is given) and
     * answers 403 for everything else, plus 415 for a non-image type and 413 for
     * an oversized one. This side must never grow its own copy of that rule: two
     * opinions about what is readable is how one of them gets it wrong. Passing
     * [session] asks the daemon to consider that session's working directory —
     * it does not widen anything here.
     *
     * Throws like every other call on a non-2xx; the thumbnail loader turns that
     * into a remembered miss and a placeholder.
     */
    suspend fun imageBytes(path: String, session: String? = null): ByteArray {
        val q = StringBuilder("/v1/files/image?path=").append(path.encodeURLParameter())
        session?.takeIf { it.isNotBlank() }?.let { q.append("&session=").append(it.encodeURLParameter()) }
        val resp = http.request { build(q.toString(), HttpMethod.Get, Tier.NORMAL, null) }
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, resp.bodyAsText())
        return resp.bodyAsBytes()
    }

    private fun uploadQuery(name: String?) = name?.let { "?name=" + it.encodeURLParameter() } ?: ""

    /** Renames a chat; the title is the only field this touches. */
    suspend fun renameChat(id: String, title: String) {
        call("/v1/chats/$id", HttpMethod.Patch, body = buildJsonObject { put("title", JsonPrimitive(title)) })
    }

    /**
     * The individual agents behind a fan-out, for the work detail sheet.
     *
     * @param all lifts the daemon's 45-minute recent-activity filter. The stream
     * picker passes it: its live chips would not need it, but the `…` pill folds
     * the SETTLED agents away rather than dropping them, and the whole point of
     * unfolding it is to reach a run that ended an hour ago. Off by default —
     * the work sheet asks "what is happening", which is a different question
     * with a much longer answer.
     */
    suspend fun sessionAgents(name: String, all: Boolean = false): AgentsInfo =
        decode(call("/v1/sessions/$name/agents${if (all) "?all=1" else ""}"))

    /**
     * One agent's own transcript, paged exactly like the parent session's.
     *
     * A second stream needs its own cursor pair — the parent's offsets describe
     * a different file and a fan-out makes the parent's size lie about progress
     * — so this is deliberately a separate call rather than a filter on
     * [sessionTranscript].
     *
     * ⚠ [agentId] GOES THROUGH VERBATIM (url-encoded, nothing else). The list
     * route emits the BARE hex and the transcript route accepts it bare or
     * `agent-` prefixed; a client that "helpfully" added or stripped the prefix
     * would be a third opinion about an id it did not mint. `StreamPicker`'s
     * `shortId` is display only — it never reaches a URL.
     */
    suspend fun agentTranscript(
        name: String,
        agentId: String,
        offset: Long? = null,
        limit: Int = 400,
        until: Long? = null,
    ): TranscriptPage = decode(call(
        "/v1/sessions/$name/agents/${agentId.encodeURLParameter()}/transcript?limit=$limit" +
            (offset?.let { "&offset=$it" } ?: "") + (until?.let { "&until=$it" } ?: ""),
    ))

    /**
     * [offset] tails forward from a byte already read; [until] reads BACKWARDS,
     * returning the page that ends where the given one began.
     *
     * Pass a previous page's [TranscriptPage.windowStart] as [until] to walk into
     * history. Consecutive pages abut exactly — a windowStart is always a record
     * boundary — so nothing arrives twice and nothing falls between them.
     */
    suspend fun sessionTranscript(
        name: String,
        offset: Long? = null,
        limit: Int = 400,
        until: Long? = null,
    ): TranscriptPage = decode(call(
        "/v1/sessions/$name/transcript?limit=$limit" +
            (offset?.let { "&offset=$it" } ?: "") + (until?.let { "&until=$it" } ?: ""),
    ))

    /** The same, for a chat: a headless run writes an ordinary transcript too. */
    suspend fun chatTranscript(
        id: String,
        offset: Long? = null,
        limit: Int = 400,
        until: Long? = null,
    ): TranscriptPage = decode(call(
        "/v1/chats/$id/transcript?limit=$limit" +
            (offset?.let { "&offset=$it" } ?: "") + (until?.let { "&until=$it" } ?: ""),
    ))

    /**
     * Literal text, then named keys (tmux send-keys names, server-validated).
     *
     * A [scratchpadId] reaches the pane as a PATH rather than as the page: the
     * daemon materialises it beside the store and names the file, because a pane
     * takes 8,000 characters and a page holds 100,000.
     */
    suspend fun sendKeys(
        name: String,
        text: String? = null,
        keys: List<String> = emptyList(),
        scratchpadId: String? = null,
    ): SendKeysResult {
        val payload = buildJsonObject {
            if (text != null) put("text", JsonPrimitive(text))
            if (keys.isNotEmpty()) put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
            scratchpadId?.let { put("scratchpadId", JsonPrimitive(it)) }
        }
        return decode(post("/v1/sessions/$name/keys", body = payload))
    }

    /**
     * What the daemon is still holding for this session, and what it is waiting
     * on. Cheap: the in-memory queue and a cached gate result, no transcript.
     *
     * A poll rather than a stream on purpose — the send outlives the socket, and
     * two clients can have the same session open.
     */
    suspend fun typingStatus(name: String): TypingState =
        decode(call("/v1/sessions/$name/typing"))

    // ⚠ NO `cancelTyping`. `/v1/sessions/:name/typing` is GET-only — there has
    // never been a DELETE — so the method that used to sit here 404'd on every
    // daemon it was ever shipped against. It had no call site, which is the only
    // reason nobody found out. Do not re-add it without the route.

    // ------------------------------------------------------------- chats

    // ---- devices: other machines that can run a chat in their context

    suspend fun devices(): List<Device> = decode<DeviceList>(call("/v1/devices")).devices

    suspend fun device(id: String): Device = decode(call("/v1/devices/$id"))

    suspend fun deleteDevice(id: String) {
        call("/v1/devices/$id", HttpMethod.Delete)
    }

    /**
     * Enrols this machine, or re-enrols it.
     *
     * Passing the id back is what keeps a restart from leaving a ghost in the
     * list; the daemon keeps the original enrolment date and updates the rest.
     */
    suspend fun registerDevice(
        name: String,
        platform: String,
        scope: String,
        id: String? = null,
        root: String? = null,
        version: String? = null,
        locked: Boolean = false,
        machine: String? = null,
    ): Device = decode(
        post("/v1/devices", body = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("platform", JsonPrimitive(platform))
            put("scope", JsonPrimitive(scope))
            put("locked", JsonPrimitive(locked))
            if (id != null) put("id", JsonPrimitive(id))
            if (root != null) put("root", JsonPrimitive(root))
            if (version != null) put("version", JsonPrimitive(version))
            // The box this row belongs to, so a renamed device still folds
            // into one machine object with its serving sibling.
            if (machine != null) put("machine", JsonPrimitive(machine))
        }),
    )

    /** Still here, and this is what I am willing to do now. */
    suspend fun deviceBeat(
        id: String,
        locked: Boolean? = null,
        scope: String? = null,
        version: String? = null,
    ): BeatResult = decode(
        post("/v1/devices/$id/beat", body = buildJsonObject {
            locked?.let { put("locked", JsonPrimitive(it)) }
            scope?.let { put("scope", JsonPrimitive(it)) }
            version?.let { put("version", JsonPrimitive(it)) }
        }),
    )

    /**
     * Waits for the next job, up to [waitS] seconds. Null means nothing came.
     *
     * Tier.WATCH, because this connection is SUPPOSED to be silent for most of its
     * life — the normal read timeout would tear down a healthy poll and make the
     * device look like it kept dropping off.
     */
    suspend fun pollWork(id: String, waitS: Int = 25, locked: Boolean? = null): DeviceWork? {
        val q = StringBuilder("/v1/devices/$id/work?wait=$waitS")
        if (locked != null) q.append("&locked=").append(if (locked) "1" else "0")
        return decode<WorkEnvelope>(call(q.toString(), tier = Tier.WATCH)).work
    }

    /**
     * Posts results for a run.
     *
     * Batches, not one long chunked upload: a home network drops, and a dropped
     * stream is indistinguishable from a finished run. Short posts with an explicit
     * terminal frame make the ending something this device states.
     */
    suspend fun postWorkEvents(
        deviceId: String,
        workId: String,
        lines: List<String>,
        done: Boolean = false,
        exitCode: Int? = null,
        error: String? = null,
        locked: Boolean? = null,
    ): EventsAck = decode(
        post("/v1/devices/$deviceId/work/$workId/events", body = buildJsonObject {
            put("lines", JsonArray(lines.map { JsonPrimitive(it) }))
            if (done) put("done", JsonPrimitive(true))
            exitCode?.let { put("exitCode", JsonPrimitive(it)) }
            error?.let { put("error", JsonPrimitive(it)) }
            locked?.let { put("locked", JsonPrimitive(it)) }
        }),
    )

    // ---- rounds: work the host does on a schedule

    suspend fun rounds(): List<Round> = decode<RoundList>(call("/v1/rounds")).rounds

    suspend fun round(id: String): Round = decode(call("/v1/rounds/$id"))

    /**
     * Sent field by field rather than by serialising [RoundSchedule] whole: the
     * daemon validates the shape per kind, and an `interval` carrying a stray
     * `days: []` or a `weekly` carrying a null `everyMinutes` is a request that
     * says more than it means.
     */
    private fun scheduleJson(s: RoundSchedule): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(s.kind))
        s.at?.let { put("at", JsonPrimitive(it)) }
        s.tz?.let { put("tz", JsonPrimitive(it)) }
        if (s.days.isNotEmpty()) put("days", JsonArray(s.days.map { JsonPrimitive(it) }))
        if (s.dates.isNotEmpty()) put("dates", JsonArray(s.dates.map { JsonPrimitive(it) }))
        s.everyMinutes?.let { put("everyMinutes", JsonPrimitive(it)) }
    }

    /**
     * @param goal what "done" means, as a completion test the run is asked to
     *   answer. Empty is legitimate — a Round that reports on something has no
     *   finish line — but it is the difference between a report and a verdict,
     *   so it is second in the list rather than buried at the end.
     * @param host a device id, or null for the huginn host. Checked at CREATION
     *   rather than at 3am on a Sunday: an `act` Round pinned to a look-only
     *   machine is refused now instead of failing every week with nobody watching.
     */
    suspend fun createRound(
        title: String,
        prompt: String,
        schedule: RoundSchedule,
        goal: String = "",
        mode: String = "ask",
        notifyWhen: String = "attention",
        model: String? = null,
        effort: String? = null,
        catchUp: Boolean = false,
        host: String? = null,
    ): Round = decode(
        post("/v1/rounds", body = buildJsonObject {
            put("title", JsonPrimitive(title))
            put("prompt", JsonPrimitive(prompt))
            put("schedule", scheduleJson(schedule))
            if (goal.isNotBlank()) put("goal", JsonPrimitive(goal))
            put("mode", JsonPrimitive(mode))
            put("notifyWhen", JsonPrimitive(notifyWhen))
            if (model != null) put("model", JsonPrimitive(model))
            if (effort != null) put("effort", JsonPrimitive(effort))
            if (catchUp) put("catchUp", JsonPrimitive(true))
            if (host != null) put("host", JsonPrimitive(host))
        }),
    )

    /** Only what is passed is changed; anything omitted is left alone. */
    suspend fun updateRound(
        id: String,
        enabled: Boolean? = null,
        title: String? = null,
        prompt: String? = null,
        schedule: RoundSchedule? = null,
        goal: String? = null,
        mode: String? = null,
        notifyWhen: String? = null,
        catchUp: Boolean? = null,
        host: String? = null,
        // The daemon's PATCH accepted these all along; the client never offered
        // them, so a Round's model and effort were unchangeable for its whole
        // life. Null leaves each alone; an empty string clears back to the host
        // default (the goal-clearing precedent: blank is the only way to say
        // "no particular model any more").
        model: String? = null,
        effort: String? = null,
    ): Round = decode(
        call("/v1/rounds/$id", HttpMethod.Patch, body = buildJsonObject {
            enabled?.let { put("enabled", JsonPrimitive(it)) }
            title?.let { put("title", JsonPrimitive(it)) }
            prompt?.let { put("prompt", JsonPrimitive(it)) }
            schedule?.let { put("schedule", scheduleJson(it)) }
            // Sent even when blank, unlike create: clearing a goal is a real edit,
            // and "omit what you do not mean to change" makes blank the only way
            // to say "this Round no longer has a finish line".
            goal?.let { put("goal", JsonPrimitive(it)) }
            mode?.let { put("mode", JsonPrimitive(it)) }
            notifyWhen?.let { put("notifyWhen", JsonPrimitive(it)) }
            catchUp?.let { put("catchUp", JsonPrimitive(it)) }
            host?.let { put("host", JsonPrimitive(it)) }
            model?.let { put("model", JsonPrimitive(it)) }
            effort?.let { put("effort", JsonPrimitive(it)) }
        }),
    )

    suspend fun deleteRound(id: String) {
        call("/v1/rounds/$id", HttpMethod.Delete)
    }

    /**
     * A better draft of one Round field, written by a model and returned as a
     * PROPOSAL — this never saves anything, and there is no id because the Round
     * may not exist yet.
     *
     * The whole draft is sent, not just the field being rewritten: the goal only
     * makes sense next to the prompt, and both only make sense next to the mode,
     * which decides whether the run may change anything at all.
     *
     * Tier.POLL, like suggestions: the host runs a real model call for this and it
     * can take several seconds, which the 30s default would sometimes clip into a
     * timeout that looks like a broken button.
     *
     * @param field "prompt" or "goal". The daemon refuses anything else with a 400 —
     *   the one case here that IS an exception rather than a [PolishResult.error].
     */
    suspend fun polishRound(
        field: String,
        title: String = "",
        prompt: String = "",
        goal: String = "",
        mode: String = "ask",
    ): PolishResult = decode(
        post("/v1/rounds/polish", Tier.POLL, buildJsonObject {
            put("field", JsonPrimitive(field))
            put("title", JsonPrimitive(title))
            put("prompt", JsonPrimitive(prompt))
            put("goal", JsonPrimitive(goal))
            put("mode", JsonPrimitive(mode))
        }),
    )

    /** Fires the Round now. The report arrives the same way a scheduled one does. */
    suspend fun runRound(id: String): RoundRunStarted = decode(post("/v1/rounds/$id/run"))

    /**
     * "I have read this and dealt with it" — or, with false, "no I have not".
     *
     * Marks the RUN, never the Round, so firing again produces a report nobody
     * has answered yet. The report itself is untouched: this records that
     * somebody saw it, and never edits what it said.
     */
    suspend fun ackRound(id: String, acknowledged: Boolean = true): Round =
        decode(post("/v1/rounds/$id/ack", body = mapOf("acknowledged" to acknowledged)))

    // ---- scratchpads: the user's own pages, quoted into a message on request

    /**
     * Every page, without any of their text.
     *
     * ALSO THE FEATURE PROBE. A daemon older than scratchpads has no such route
     * and answers 404, and both clients hide every scratchpad control on that
     * rather than parsing a version — same shape as the silent-404 refreshRounds
     * precedent, and for the same reason: a version string is a claim about what
     * a build contains, while a 404 is the route itself answering.
     */
    suspend fun scratchpads(): List<Scratchpad> = decode<ScratchpadList>(call("/v1/scratchpads")).pads

    /** One page, with its content and the rev a save must carry back. */
    suspend fun scratchpad(id: String): Scratchpad = decode(call("/v1/scratchpads/$id"))

    suspend fun createScratchpad(name: String, content: String = ""): Scratchpad = decode(
        post("/v1/scratchpads", body = buildJsonObject {
            put("name", JsonPrimitive(name))
            if (content.isNotEmpty()) put("content", JsonPrimitive(content))
        }),
    )

    /**
     * The autosave, and the one call here that treats a non-2xx as an answer.
     *
     * A 409 means the other device saved first. It is not a failure — it arrives
     * carrying the current text and its rev, which is everything the editor needs
     * to adopt it — so it comes back as [ScratchpadSave.conflict] rather than as a
     * throw. Every other status still throws, because a 400 about a name IS a
     * refusal and belongs on the failure path.
     */
    suspend fun saveScratchpad(
        id: String,
        rev: Int,
        name: String? = null,
        content: String? = null,
    ): ScratchpadSave {
        val body = buildJsonObject {
            put("rev", JsonPrimitive(rev))
            name?.let { put("name", JsonPrimitive(it)) }
            content?.let { put("content", JsonPrimitive(it)) }
        }
        val resp = http.request { build("/v1/scratchpads/$id", HttpMethod.Patch, Tier.NORMAL, body) }
        val text = resp.bodyAsText()
        if (resp.status.value == 409) return ScratchpadSave(decode(text), conflict = true)
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, text)
        return ScratchpadSave(decode(text), conflict = false)
    }

    suspend fun deleteScratchpad(id: String) {
        call("/v1/scratchpads/$id", HttpMethod.Delete)
    }

    suspend fun chats(): List<Chat> = decode<ChatList>(call("/v1/chats")).chats

    /**
     * @param host a device id, or null for this host. Checked at CREATION rather
     *   than at the first message, so "that machine is asleep" is answered by the
     *   button that made the chat instead of by a message that seems to vanish.
     */
    suspend fun createChat(
        mode: String,
        model: String? = null,
        effort: String? = null,
        host: String? = null,
    ): Chat {
        val body = buildJsonObject {
            put("mode", JsonPrimitive(mode))
            if (model != null) put("model", JsonPrimitive(model))
            if (effort != null) put("effort", JsonPrimitive(effort))
            if (host != null) put("host", JsonPrimitive(host))
        }
        return decode(post("/v1/chats", body = body))
    }

    /** Model, effort and mode apply to the chat's NEXT turn. */
    suspend fun updateChat(id: String, model: String? = null, effort: String? = null, mode: String? = null): ChatDetail {
        val body = buildJsonObject {
            if (model != null) put("model", JsonPrimitive(model))
            if (effort != null) put("effort", JsonPrimitive(effort))
            if (mode != null) put("mode", JsonPrimitive(mode))
        }
        return decode(call("/v1/chats/$id", HttpMethod.Patch, body = body))
    }

    suspend fun chat(id: String): ChatDetail = decode(call("/v1/chats/$id"))

    suspend fun deleteChat(id: String) {
        call("/v1/chats/$id", HttpMethod.Delete)
    }

    suspend fun cancelChat(id: String) {
        post("/v1/chats/$id/cancel")
    }

    /**
     * Posts a message and streams the run. Emits until the run ends; collecting
     * side cancellation aborts the HTTP call but NOT the server-side run, which
     * is deliberate: locking the phone must not kill Claude mid-task. Reattach
     * with [streamChat].
     */
    fun sendMessage(id: String, text: String, scratchpadId: String? = null): Flow<ChatEvent> =
        sse("/v1/chats/$id/messages?stream=1", HttpMethod.Post, messageBody(text, scratchpadId))

    /**
     * Posts a message to a chat that is already running. The server queues it and
     * delivers it when the current run ends; there is no stream to follow because
     * the reply belongs to a future run.
     *
     * A [scratchpadId] here is composed by the daemon AT RECEIPT and stored
     * already-composed, so the queued message quotes the page as it read when Send
     * was pressed rather than as it reads whenever the queue happens to drain.
     */
    suspend fun queueMessage(id: String, text: String, scratchpadId: String? = null) {
        post("/v1/chats/$id/messages", body = messageBody(text, scratchpadId))
    }

    /**
     * ABSENT means no page at all, which is not the same as naming Main.
     *
     * The daemon falls back to Main only for a reference it was actually asked
     * for; omitting the field is how a message says it carries none. Sending
     * `null` explicitly would read the same way to `explicitNulls = false`, so the
     * field is simply not built.
     */
    private fun messageBody(text: String, scratchpadId: String?): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(text))
        scratchpadId?.let { put("scratchpadId", JsonPrimitive(it)) }
    }

    /** Reattaches to an in-flight run, replaying events after [since] (0 = all). */
    fun streamChat(id: String, since: Long = 0): Flow<ChatEvent> =
        sse("/v1/chats/$id/stream?since=$since", HttpMethod.Get, null)

    private fun jsonBody(vararg pairs: Pair<String, String>) =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

    /**
     * Minimal SSE reader. Frames are `event:`/`data:` lines terminated by a blank
     * line; `: ping` comment frames (the server heartbeat) are skipped.
     *
     * Hand-rolled over the response's byte channel rather than Ktor's SSE plugin.
     * The plugin joins multi-line `data:` with newlines, surfaces comments as a
     * field on an event rather than as an event, and — the deciding one — hides
     * whether the body ended on a frame boundary or mid-line, which is precisely
     * how these readers tell a finished stream from a dropped link. See
     * [SseLines]; the shape below is the same loop the app has run since 2.0.
     */
    private fun sse(path: String, method: HttpMethod, body: JsonObject?): Flow<ChatEvent> = flow {
        try {
            http.prepareRequest { build(path, method, Tier.STREAM, body) }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    emit(ChatEvent.Failure(errorFrom(resp.status.value, resp.bodyAsText()).message))
                    return@execute
                }
                val lines = SseLines(resp.bodyAsChannel())
                var event: String? = null
                val data = StringBuilder()
                while (true) {
                    val line = lines.next() ?: break
                    when {
                        line.startsWith(":") -> Unit                 // heartbeat comment
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                        line.startsWith("id:") -> Unit
                        line.isEmpty() -> {
                            if (event != null) {
                                parse(event, data.toString())?.let { emit(it) }
                                if (event == "done") return@execute
                            }
                            event = null; data.setLength(0)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ChatEvent.Failure(e.message ?: "stream ended"))
        }
        // UNLIMITED because the producer is the socket reader and the collector is
        // the main thread. This started as a callbackFlow whose default capacity is
        // 64 and whose trySend DROPS silently when full: reattaching to a running
        // chat replays up to 4000 buffered events in one burst, so deltas — and even
        // the `done` frame that triggers the transcript reload — were being
        // discarded during a single 16ms frame, leaving a half-rendered answer that
        // only a screen change fixed.
        //
        // The Ktor port changed the FAILURE MODE, not the need. `flow` + `buffer`
        // suspends the producer instead of dropping, so a bounded buffer would now
        // cost frames' worth of latency rather than the frames themselves — but the
        // producer here is the socket reader, and stalling it behind a Compose
        // recomposition backs the pressure all the way up to the daemon's writer.
        // Unbounded keeps the socket draining at the socket's pace. Pinned by
        // SseTest's two burst tests; do not shrink it without reading them.
    }.buffer(Channel.UNLIMITED).flowOn(huginnIoDispatcher)

    private fun parse(event: String, data: String): ChatEvent? {
        val obj = runCatching { json.decodeFromString<JsonObject>(data) }.getOrNull()
        // `content` on a primitive gives the raw lexeme for numbers/booleans too,
        // which is what the toLongOrNull/toDoubleOrNull conversions below want.
        fun str(k: String) = runCatching { obj?.get(k)?.jsonPrimitive?.content }.getOrNull()
        return when (event) {
            "started" -> ChatEvent.Started(str("chatId") ?: "")
            "delta" -> ChatEvent.Delta(str("text") ?: return null)
            "assistant" -> ChatEvent.Assistant(str("text") ?: return null)
            "tool_start" -> ChatEvent.ToolStart(str("name") ?: "tool")
            "tool" -> ChatEvent.Tool(str("name") ?: "tool", str("input"))
            "result" -> ChatEvent.Result(
                ok = str("ok") != "false",
                durationMs = str("durationMs")?.toLongOrNull(),
                costUsd = str("costUsd")?.toDoubleOrNull(),
            )
            "error" -> ChatEvent.Failure(str("text") ?: "error")
            "done" -> ChatEvent.Done
            else -> null
        }
    }


    // ---- projects: a cluster of sessions with roles, and the pages this host serves
    //
    // ⚠ TWO ROUTES HERE ANSWER A 404 WITH NULL RATHER THAN A THROW, and that is
    // the feature probe. Both branches shipped after a lot of daemons were
    // already installed, and a client that greeted every one of them with a red
    // error would be reporting the absence of something nobody asked for. Null
    // means "this daemon has never heard of projects"; an EMPTY LIST means the
    // feature is there and you have none. The shells hide every control on the
    // first and draw an empty state on the second — the same shape as
    // [scratchpads]'s silent 404, made explicit because there are two of them
    // now and "it threw" is a poor way to carry a fact this load-bearing.
    //
    // ⚠⚠ AND THREE MORE ANSWER A 409 WITH A VALUE. An untrusted working
    // directory, a spawn under the headroom arbiter's STOP, a save that lost a
    // race: each arrives with the whole fix or the whole current state inside
    // it, and each is a state of the house rather than a fault in the request.

    /** A GET whose 404 is an answer: null, not an exception. */
    private suspend fun probeGet(path: String): String? {
        val resp = http.request { build(path, HttpMethod.Get, Tier.NORMAL, null) }
        val text = resp.bodyAsText()
        if (resp.status.value == 404) return null
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, text)
        return text
    }

    /**
     * Every project and the host's cap, or NULL when this daemon has no projects
     * feature at all.
     *
     * ⚠ NULL AND EMPTY ARE DIFFERENT ANSWERS. See the block comment above.
     *
     * @param all include archived projects. The daemon leaves them out otherwise,
     *   because `archived` is terminal and a tree that kept them would only grow.
     */
    suspend fun projects(all: Boolean = false): ProjectList? =
        probeGet(if (all) "/v1/projects?all=1" else "/v1/projects")?.let { decode<ProjectList>(it) }

    /**
     * One project: the record, the row the tree draws, and every member's live
     * state.
     *
     * Decoded twice out of one body on purpose — the daemon spreads the project
     * into the top level and hangs `row` and `live` beside it, and a model that
     * repeated all fifteen project fields would be a second place to get the
     * record wrong.
     */
    suspend fun project(id: String): ProjectDetail {
        val text = call("/v1/projects/$id")
        val extras = decode<ProjectDetailExtras>(text)
        return ProjectDetail(decode<Project>(text), extras.row, extras.live)
    }

    /** The members' overviews, summed. Polled while the dashboard is on screen. */
    suspend fun projectDashboard(id: String): ProjectDashboard =
        decode(call("/v1/projects/$id/dashboard"))

    /**
     * Start a project: the daemon launches its lead session and types the brief
     * into it.
     *
     * [brief] is not optional and is not a description — it is the WHOLE first
     * message the lead receives, and the thing it sizes the project from.
     *
     * ⚠ THE 409 IS AN ANSWER, NOT A THROW. The commonest refusal is a working
     * directory Claude Code has not been trusted in, and the daemon's sentence
     * about it IS the fix — so it comes back as [ProjectCreated.refusal] for the
     * sheet to show under the field, with everything the person typed still in
     * it. Every other status still throws, because a 400 about a name is a
     * refusal of the request rather than a state of the house.
     */
    suspend fun createProject(
        name: String,
        kind: String,
        brief: String,
        cwd: String? = null,
    ): ProjectCreated {
        val body = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("kind", JsonPrimitive(kind))
            put("brief", JsonPrimitive(brief))
            cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", JsonPrimitive(it)) }
        }
        val resp = http.request { build("/v1/projects", HttpMethod.Post, Tier.NORMAL, body) }
        val text = resp.bodyAsText()
        if (resp.status.value == 409) {
            val why = runCatching { decode<ApiError>(text).error }.getOrNull()
            return ProjectCreated(null, why ?: "that project could not be created")
        }
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, text)
        return ProjectCreated(decode<Project>(text), null)
    }

    /**
     * Rename, re-brief, pause/resume, archive, or replace the manifest.
     *
     * [rev] is what the editor last read, and it is what makes the save safe.
     *
     * ⚠ THE 409 HAS TWO SHAPES. A stale rev comes back as the CURRENT PROJECT,
     * bare, to be adopted — the saveScratchpad contract, which both shells
     * already know. An illegal status move comes back as an `{error}` with no
     * project in it, which is a refusal rather than a race. Told apart by whether
     * the body carries an id, because the status code cannot tell them apart.
     */
    suspend fun saveProject(
        id: String,
        rev: Int,
        name: String? = null,
        brief: String? = null,
        status: String? = null,
        manifest: ProjectManifest? = null,
    ): ProjectSave {
        val body = buildJsonObject {
            put("rev", JsonPrimitive(rev))
            name?.let { put("name", JsonPrimitive(it)) }
            brief?.let { put("brief", JsonPrimitive(it)) }
            status?.let { put("status", JsonPrimitive(it)) }
            // The daemon re-validates an edited manifest with the SAME parser the
            // lead's own block goes through, so this travels as the parsed shape
            // rather than as a fence.
            manifest?.let { put("manifest", json.encodeToJsonElement(ProjectManifest.serializer(), it)) }
        }
        val resp = http.request { build("/v1/projects/$id", HttpMethod.Patch, Tier.NORMAL, body) }
        val text = resp.bodyAsText()
        if (resp.status.value == 409) {
            val current = runCatching { decode<Project>(text) }.getOrNull()?.takeIf { it.id.isNotBlank() }
            if (current != null) return ProjectSave(current, conflict = true)
            val why = runCatching { decode<ApiError>(text).error }.getOrNull()
            return ProjectSave(null, conflict = false, refusal = why ?: "that change was refused")
        }
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, text)
        return ProjectSave(decode<Project>(text), conflict = false)
    }

    /**
     * Create the members the owner approved.
     *
     * ⚠⚠ THE BODY IS THE APPROVAL AND THE REV, AND NOTHING ELSE. The client does
     * not say which members to make — the manifest already does, and a client
     * that re-sent the roles would be a second opinion about the plan the owner
     * has just looked at. [manifestRev] is what the card was drawn from, so a
     * notification that has been sitting on a lock screen while the lead revised
     * its plan cannot spawn the revision.
     *
     * ⚠ AND THE 200 IS NOT A VERDICT. Spawning is a loop over tmux, so a partial
     * result comes back as `ok:false` with both lists inside a 200 — see
     * [SpawnResult]. The 409s are the STOP sentinel and the stale rev, and both
     * are answers.
     */
    suspend fun spawnProject(id: String, manifestRev: Int): SpawnOutcome {
        val body = buildJsonObject {
            put("approve", JsonPrimitive(true))
            put("manifestRev", JsonPrimitive(manifestRev))
        }
        val resp = http.request { build("/v1/projects/$id/spawn", HttpMethod.Post, Tier.NORMAL, body) }
        val text = resp.bodyAsText()
        if (resp.status.value == 409) {
            val why = runCatching { decode<ApiError>(text).error }.getOrNull()
            // A stale rev carries the CURRENT project, so the card can redraw
            // itself around the plan that is actually on offer.
            val current = runCatching { decode<SpawnResult>(text).project }.getOrNull()
            return SpawnOutcome(null, why ?: "the host is not spawning sessions right now", current)
        }
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, text)
        return SpawnOutcome(decode<SpawnResult>(text), null)
    }

    /**
     * Turn the proposal down. The manifest is KEPT at its rev so an editor can
     * still open it; only the status moves back to drafting, and the lead is told
     * so it does not wait forever for an approval that is not coming.
     */
    suspend fun discardProposal(id: String): Project = decode(post("/v1/projects/$id/discard"))

    /**
     * Type a line into one member, from another.
     *
     * ⚠ THIS IS NOT PEER MESSAGING. A `SendMessage` between two Claude sessions
     * travels their own socket and triggers a turn with no keypress; the daemon
     * routes none of it. This route is the daemon TYPING into a pane, so it rides
     * the send queue and its gates — which is why the answer carries
     * [ProjectMessageResult.queued] and [ProjectMessageResult.blockedBy] exactly
     * as an ordinary send does.
     *
     * [from] and [to] each name a role, a tmux name or a `<slug>/<role>` peer
     * name; the daemon resolves all three.
     */
    suspend fun messageProject(id: String, from: String, to: String, text: String): ProjectMessageResult =
        decode(
            post(
                "/v1/projects/$id/message",
                body = buildJsonObject {
                    put("from", JsonPrimitive(from))
                    put("to", JsonPrimitive(to))
                    put("text", JsonPrimitive(text))
                },
            ),
        )

    /**
     * Forget the project, and optionally end its sessions.
     *
     * ⚠ THE DEFAULT ENDS NOTHING. `graceful` puts the wind-down phrase in each
     * composer and lets the settle timer close them; `now` kills them. Anything
     * else deletes the record and leaves twelve live sessions alone, which is the
     * safe reading of a button labelled Delete.
     */
    suspend fun deleteProject(id: String, end: String? = null): ProjectDeleted =
        decode(
            call(
                "/v1/projects/$id",
                HttpMethod.Delete,
                body = buildJsonObject { end?.let { put("end", JsonPrimitive(it)) } },
            ),
        )

    // ---- consoles: the internal pages this host serves

    /**
     * The registry, its caps and the approval — or NULL when this daemon has no
     * consoles feature.
     *
     * The same probe contract as [projects]: null means absent, an empty
     * `consoles` list means present and empty.
     */
    suspend fun consoles(): ConsoleList? = probeGet("/v1/consoles")?.let { decode<ConsoleList>(it) }

    /**
     * Add a console. The row comes back with no observation on it — a brand new
     * console has never been probed, which is not the same as being down.
     *
     * A refusal here IS a refusal of the request (the address is not on this
     * host, the LAN, the tailnet or the mesh; the scheme is not http) and still
     * throws — [ConsoleRules.urlProblem] mirrors the rule so the field can answer
     * before the round trip.
     */
    suspend fun createConsole(
        name: String,
        url: String,
        kind: String? = null,
        notes: String? = null,
    ): Console =
        decode(
            post(
                "/v1/consoles",
                body = buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("url", JsonPrimitive(url))
                    kind?.let { put("kind", JsonPrimitive(it)) }
                    notes?.let { put("notes", JsonPrimitive(it)) }
                },
            ),
        )

    /**
     * Edit one console. [version] is the copy this edit was made against.
     *
     * ⚠ THE 409 IS AN ANSWER, carrying the row as the daemon now holds it — the
     * [saveScratchpad] shape, for the same reason: the other client having saved
     * first is the ordinary outcome of two devices on one registry, and it
     * arrives with everything needed to adopt it. A 400 about a bad address IS a
     * refusal of the request and still throws.
     */
    suspend fun saveConsole(
        id: String,
        version: Int,
        name: String? = null,
        url: String? = null,
        kind: String? = null,
        notes: String? = null,
    ): ConsoleSave {
        val body = buildJsonObject {
            put("version", JsonPrimitive(version))
            name?.let { put("name", JsonPrimitive(it)) }
            url?.let { put("url", JsonPrimitive(it)) }
            kind?.let { put("kind", JsonPrimitive(it)) }
            notes?.let { put("notes", JsonPrimitive(it)) }
        }
        val resp = http.request { build("/v1/consoles/$id", HttpMethod.Patch, Tier.NORMAL, body) }
        val text = resp.bodyAsText()
        if (resp.status.value == 409) {
            // The body is `{error, console}`: the refusal AND the current row. The
            // row is what the editor adopts; the sentence is the daemon's, kept
            // so a caller that wants to say why can.
            val c = runCatching { decode<ConsoleConflict>(text) }.getOrNull()
            return ConsoleSave(c?.console ?: Console(id = id), conflict = true, refusal = c?.error)
        }
        if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, text)
        return ConsoleSave(decode(text), conflict = false, refusal = null)
    }

    /** Remove a console from the registry. A second delete is a 404, not a second success. */
    suspend fun deleteConsole(id: String) {
        call("/v1/consoles/$id", HttpMethod.Delete)
    }

    /**
     * Probe one console now, from the host, and answer with the refreshed row.
     *
     * The daemon awaits the probe (bounded at 2 s) rather than answering
     * optimistically, so the row this returns is the verdict rather than a
     * promise of one.
     */
    suspend fun probeConsole(id: String): Console = decode(post("/v1/consoles/$id/probe"))

    /**
     * A request body that is written, not held.
     *
     * A null [contentLength] makes Ktor send the upload chunked, which is what an
     * OkHttp `RequestBody` returning -1 did, so a file whose provider would not
     * report a size still uploads.
     */
    private class StreamBody(mime: String, private val stream: ByteStream) : OutgoingContent.WriteChannelContent() {
        override val contentType: ContentType =
            runCatching { ContentType.parse(mime) }.getOrDefault(ContentType.Application.OctetStream)

        override val contentLength: Long? = stream.contentLength.takeIf { it >= 0 }

        /** Does NOT close [stream] — [uploadStream] owns that, so it happens once and always. */
        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n > 0) channel.writeFully(buf, 0, n)
            }
            channel.flush()
        }
    }
}
