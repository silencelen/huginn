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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
     * FOUR TIMEOUT TIERS, and they are a contract rather than a detail — each one
     * is a production failure that went unnoticed until it had a number. Change
     * one only against the behaviour described beside it.
     */
    companion object {
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
    private fun absolute(path: String): String = withScheme(baseUrlProvider()) + path

    private fun withScheme(base: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.startsWith("http://") || b.startsWith("https://")) b else "http://$b"
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

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

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
     * Is anything answering at [candidate]? Any HTTP reply counts — a 401 still
     * proves the daemon is there, and the point is to find a live path, not to
     * check the token. Unauthenticated for the same reason.
     *
     * Lives on the client rather than in the UI so route resolution works the
     * same way from the desktop client, and so this module owns every socket the
     * app opens.
     */
    suspend fun probe(candidate: String): Boolean = runCatching {
        http.request {
            method = HttpMethod.Head
            url(withScheme(AppdRoutes.normalize(candidate)) + "/v1/sessions")
            timeout {
                connectTimeoutMillis = PROBE_TIMEOUT_MS
                socketTimeoutMillis = PROBE_TIMEOUT_MS
                requestTimeoutMillis = PROBE_TIMEOUT_MS
            }
        }
        true
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

    suspend fun testPush(): PushTestResult = decode(post("/v1/push/test", Tier.POLL))

    suspend fun testAlert() {
        post("/v1/alerts/test", Tier.POLL)
    }

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
    suspend fun models(): List<ModelChoice> = decode<ModelList>(call("/v1/models")).models

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

    /** Plan utilization: the same numbers Claude Code's /usage shows. */
    suspend fun plan(): Plan = decode(call("/v1/plan"))

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

    private fun uploadQuery(name: String?) = name?.let { "?name=" + it.encodeURLParameter() } ?: ""

    /** Renames a chat; the title is the only field this touches. */
    suspend fun renameChat(id: String, title: String) {
        call("/v1/chats/$id", HttpMethod.Patch, body = buildJsonObject { put("title", JsonPrimitive(title)) })
    }

    /** The individual agents behind a fan-out, for the work detail sheet. */
    suspend fun sessionAgents(name: String): AgentsInfo = decode(call("/v1/sessions/$name/agents"))

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

    /** Literal text, then named keys (tmux send-keys names, server-validated). */
    suspend fun sendKeys(name: String, text: String? = null, keys: List<String> = emptyList()) {
        val payload = buildJsonObject {
            if (text != null) put("text", JsonPrimitive(text))
            if (keys.isNotEmpty()) put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
        }
        post("/v1/sessions/$name/keys", body = payload)
    }

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
    ): Device = decode(
        post("/v1/devices", body = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("platform", JsonPrimitive(platform))
            put("scope", JsonPrimitive(scope))
            put("locked", JsonPrimitive(locked))
            if (id != null) put("id", JsonPrimitive(id))
            if (root != null) put("root", JsonPrimitive(root))
            if (version != null) put("version", JsonPrimitive(version))
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
    fun sendMessage(id: String, text: String): Flow<ChatEvent> =
        sse("/v1/chats/$id/messages?stream=1", HttpMethod.Post, jsonBody("text" to text))

    /**
     * Posts a message to a chat that is already running. The server queues it and
     * delivers it when the current run ends; there is no stream to follow because
     * the reply belongs to a future run.
     */
    suspend fun queueMessage(id: String, text: String) {
        post("/v1/chats/$id/messages", body = jsonBody("text" to text))
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
