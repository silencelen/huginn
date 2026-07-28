package com.silencelen.huginn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to huginn-appd. Every call carries the bearer token; a non-2xx response
 * is surfaced as [HuginnException] carrying the server's own error text, because
 * "unauthorized" vs "no such session" is exactly what the user needs to see.
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
) {
    class HuginnException(val code: Int, override val message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // Short timeouts for request/response, none for reads on the streaming client:
    // an SSE body legitimately stays open for the length of a Claude turn.
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // SSE chat streams legitimately stay open for a whole Claude turn, so no
    // read timeout applies to them.
    private val streamHttp = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Screen long polls are DIFFERENT: the server answers within its `wait`
    // window, so a silent connection is a dead one. With no timeout at all a
    // black-holed socket (network change, NAT expiry) blocked the poll forever —
    // the screen froze with no error and no retry, and nothing rearmed it.
    // Sized for the longest server-side hold (the watch parks for up to two
    // minutes) plus slack, so a live connection is never cut mid-wait — while a
    // genuinely dead socket still fails instead of hanging forever.
    private val pollHttp = http.newBuilder()
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): String {
        val base = baseUrlProvider().trim().trimEnd('/')
        val withScheme = if (base.startsWith("http://") || base.startsWith("https://")) base else "http://$base"
        return "$withScheme$path"
    }

    // The watch stream is a THIRD kind of timeout, and the distinction is the point
    // of the stream existing. Chat streams may be silent for a whole Claude turn, so
    // they get none. The watch stream is contractually never silent — the server
    // sends a keepalive every 25 seconds — so silence beyond a minute means the
    // socket is dead, which is exactly the failure that used to go unnoticed while
    // the phone slept and the app went on believing it was watching.
    private val watchHttp = http.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer ${tokenProvider().trim()}")
        val id = clientIdProvider().trim()
        if (id.isNotEmpty()) b.header("X-Huginn-Client", id)
        canNotifyProvider()?.let { b.header("X-Huginn-Notify", if (it) "1" else "0") }
        return b
    }

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    private fun errorFrom(code: Int, body: String): HuginnException {
        val msg = runCatching { json.decodeFromString<ApiError>(body).error }.getOrNull()
        return HuginnException(code, msg ?: "HTTP $code")
    }

    private enum class Client { NORMAL, POLL, STREAM }

    private suspend fun call(request: Request, via: Client = Client.NORMAL): String = withContext(Dispatchers.IO) {
        val c = when (via) {
            Client.NORMAL -> http
            Client.POLL -> pollHttp
            Client.STREAM -> streamHttp
        }
        c.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw errorFrom(resp.code, body)
            body
        }
    }

    // ------------------------------------------------------------ status

    suspend fun ping(): Ping = decode(call(builder("/v1/ping").get().build()))

    suspend fun status(): Status = decode(call(builder("/v1/status").get().build()))

    suspend fun alerts(): Alerts = decode(call(builder("/v1/alerts").get().build()))

    suspend fun setAlerts(enabled: Boolean? = null, mode: String? = null): Alerts {
        val body = buildJsonObject {
            enabled?.let { put("enabled", JsonPrimitive(it)) }
            mode?.let { put("mode", JsonPrimitive(it)) }
        }
        return decode(call(builder("/v1/alerts").post(encode(body)).build()))
    }

    /**
     * What the host has seen of this phone. Read from the host on purpose: asking
     * the phone whether it stayed awake is asking the witness to alibi itself.
     */
    suspend fun clients(): ClientsInfo = decode(call(builder("/v1/clients").get().build()))

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
        return decode(call(builder("/v1/push/register").post(encode(body)).build()))
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
        return decode(call(builder("/v1/sessions/$session/answer").post(encode(body)).build()))
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
        return decode(call(builder("/v1/sessions/$session/answer").post(encode(body)).build()))
    }

    suspend fun autoswitch(): Autoswitch = decode(call(builder("/v1/autoswitch").get().build()))

    suspend fun setAutoswitch(enabled: Boolean): Autoswitch {
        val body = buildJsonObject { put("enabled", JsonPrimitive(enabled)) }
        return decode(call(builder("/v1/autoswitch").post(encode(body)).build()))
    }

    /** Whether the HOST can push at all, and which devices it would reach. */
    suspend fun push(): PushStatus = decode(call(builder("/v1/push").get().build()))

    suspend fun testPush(): PushTestResult =
        decode(call(builder("/v1/push/test").post(ByteArray(0).toRequestBody(null)).build(), Client.POLL))

    suspend fun testAlert() {
        call(builder("/v1/alerts/test").post(ByteArray(0).toRequestBody(null)).build(), Client.POLL)
    }

    /**
     * Parks until something an alert depends on changes, or [waitMs] elapses.
     * Uses the long-poll client: the server holds this open deliberately.
     */
    suspend fun watch(knownHash: String?, waitMs: Int): Watch {
        val q = buildList {
            if (knownHash != null) add("hash=$knownHash")
            if (waitMs > 0) add("wait=$waitMs")
        }.joinToString("&")
        val path = "/v1/watch" + if (q.isEmpty()) "" else "?$q"
        return decode(call(builder(path).get().build(), if (waitMs > 0) Client.POLL else Client.NORMAL))
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
    fun watchStream(knownHash: String?): Flow<WatchEvent> = callbackFlow {
        val path = "/v1/watch?stream=1" + if (knownHash != null) "&hash=$knownHash" else ""
        val call = watchHttp.newCall(builder(path).get().build())
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (!c.isCanceled()) trySend(WatchEvent.Failure(e.message ?: "network error"))
                close()
            }

            override fun onResponse(c: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) {
                        trySend(WatchEvent.Failure(errorFrom(resp.code, body?.string().orEmpty()).message))
                        close(); return
                    }
                    try {
                        val source = body.source()
                        var event: String? = null
                        val data = StringBuilder()
                        while (!source.exhausted()) {
                            val line = source.readUtf8LineStrict()
                            when {
                                line.startsWith(":") -> trySend(WatchEvent.Alive)
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                                line.isEmpty() -> {
                                    when (event) {
                                        "state" -> runCatching { decode<Watch>(data.toString()) }
                                            .getOrNull()?.let { trySend(WatchEvent.State(it)) }
                                        // The server rotates a long-lived stream. Not
                                        // an error, and must not be treated as one:
                                        // backing off after a clean rotation would
                                        // leave the phone unwatched for no reason.
                                        "bye" -> { trySend(WatchEvent.Rotated); close(); return }
                                    }
                                    event = null; data.setLength(0)
                                }
                            }
                        }
                        // Ran out of body without a `bye`: the socket closed under us.
                        if (!c.isCanceled()) trySend(WatchEvent.Failure("stream ended"))
                    } catch (e: Exception) {
                        if (!c.isCanceled()) trySend(WatchEvent.Failure(e.message ?: "stream ended"))
                    }
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** Where an in-progress sign-in has got to. */
    suspend fun loginState(): LoginState =
        decode(call(builder("/v1/account/login/state").get().build()))

    /** Hands the pasted code to the waiting sign-in and reports the outcome. */
    suspend fun submitLoginCode(code: String): LoginState =
        decode(call(builder("/v1/account/login/code").post(jsonBody("code" to code)).build(), Client.POLL))

    /** Models the installed CLI offers, so the picker cannot go stale. */
    suspend fun models(): List<ModelChoice> =
        decode<ModelList>(call(builder("/v1/models").get().build())).models

    // ------------------------------------------------- account + usage

    suspend fun account(): Account = decode(call(builder("/v1/account").get().build()))

    /**
     * Starts a sign-in. Naming the account aims the authorize page at it, which
     * matters because that page otherwise uses whatever session the browser is
     * already carrying.
     */
    suspend fun startLogin(email: String? = null): LoginSession {
        val body = buildJsonObject { if (!email.isNullOrBlank()) put("email", JsonPrimitive(email.trim())) }
        return decode(call(builder("/v1/account/login").post(encode(body)).build(), Client.POLL))
    }

    suspend fun logout(): Account =
        decode(call(builder("/v1/account/logout").post(jsonBody("confirm" to "logout")).build()))

    suspend fun usage(): Usage = decode(call(builder("/v1/usage").get().build()))

    /** Saved logins on the host; `withPlan` also reads each one's headroom. */
    suspend fun savedAccounts(withPlan: Boolean = false): List<SavedAccount> =
        decode<SavedAccounts>(call(builder("/v1/accounts${if (withPlan) "?plan=1" else ""}").get().build())).accounts

    suspend fun activateAccount(slug: String): Account =
        decode(call(builder("/v1/accounts/$slug/activate").post(ByteArray(0).toRequestBody(null)).build()))

    suspend fun forgetAccount(slug: String) {
        call(builder("/v1/accounts/$slug").delete().build())
    }

    /** Plan utilization: the same numbers Claude Code's /usage shows. */
    suspend fun plan(): Plan = decode(call(builder("/v1/plan").get().build()))

    // ---------------------------------------------------------- sessions

    /** @param preview include per-session titles and activity previews (costlier). */
    suspend fun sessions(preview: Boolean = false): List<Session> =
        decode<SessionList>(call(builder("/v1/sessions${if (preview) "?preview=1" else ""}").get().build())).sessions

    suspend fun createSession(name: String) {
        call(builder("/v1/sessions").post(jsonBody("name" to name)).build())
    }

    suspend fun killSession(name: String) {
        call(builder("/v1/sessions/$name").delete().build())
    }

    suspend fun renameSession(from: String, to: String) {
        call(builder("/v1/sessions/$from/rename").post(jsonBody("name" to to)).build())
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
        return decode(call(builder(path).get().build(), if (waitMs > 0) Client.POLL else Client.NORMAL))
    }

    /** Hands the pane size back to tmux so an attached laptop re-fits at once. */
    suspend fun releaseSize(name: String) {
        call(builder("/v1/sessions/$name/size").delete().build())
    }

    /** Structured conversation for a tmux session, from its Claude transcript. */
    /**
     * Suggested next messages. The server generates on a turn boundary and
     * caches; this can take several seconds the first time, so it rides the
     * long-poll client rather than the 30s default.
     */
    suspend fun sessionSuggestions(name: String): Suggestions =
        decode(call(builder("/v1/sessions/$name/suggestions").get().build(), Client.POLL))

    suspend fun chatSuggestions(id: String): Suggestions =
        decode(call(builder("/v1/chats/$id/suggestions").get().build(), Client.POLL))

    /** Renames a chat; the title is the only field this touches. */
    suspend fun renameChat(id: String, title: String) {
        call(builder("/v1/chats/$id").patch(encode(buildJsonObject { put("title", JsonPrimitive(title)) })).build())
    }

    /** The individual agents behind a fan-out, for the work detail sheet. */
    suspend fun sessionAgents(name: String): AgentsInfo =
        decode(call(builder("/v1/sessions/$name/agents").get().build()))

    suspend fun sessionTranscript(name: String, offset: Long? = null, limit: Int = 400): TranscriptPage =
        decode(call(builder(
            "/v1/sessions/$name/transcript?limit=$limit" + (offset?.let { "&offset=$it" } ?: "")
        ).get().build()))

    /** The same, for a chat: a headless run writes an ordinary transcript too. */
    suspend fun chatTranscript(id: String, offset: Long? = null, limit: Int = 400): TranscriptPage =
        decode(call(builder(
            "/v1/chats/$id/transcript?limit=$limit" + (offset?.let { "&offset=$it" } ?: "")
        ).get().build()))

    /** Literal text, then named keys (tmux send-keys names, server-validated). */
    suspend fun sendKeys(name: String, text: String? = null, keys: List<String> = emptyList()) {
        val payload = buildJsonObject {
            if (text != null) put("text", JsonPrimitive(text))
            if (keys.isNotEmpty()) put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
        }
        call(builder("/v1/sessions/$name/keys").post(encode(payload)).build())
    }

    // ------------------------------------------------------------- chats

    suspend fun chats(): List<Chat> =
        decode<ChatList>(call(builder("/v1/chats").get().build())).chats

    suspend fun createChat(mode: String, model: String? = null, effort: String? = null): Chat {
        val body = buildJsonObject {
            put("mode", JsonPrimitive(mode))
            if (model != null) put("model", JsonPrimitive(model))
            if (effort != null) put("effort", JsonPrimitive(effort))
        }
        return decode(call(builder("/v1/chats").post(encode(body)).build()))
    }

    /** Model, effort and mode apply to the chat's NEXT turn. */
    suspend fun updateChat(id: String, model: String? = null, effort: String? = null, mode: String? = null): ChatDetail {
        val body = buildJsonObject {
            if (model != null) put("model", JsonPrimitive(model))
            if (effort != null) put("effort", JsonPrimitive(effort))
            if (mode != null) put("mode", JsonPrimitive(mode))
        }
        return decode(call(builder("/v1/chats/$id").patch(encode(body)).build()))
    }

    suspend fun chat(id: String): ChatDetail =
        decode(call(builder("/v1/chats/$id").get().build()))

    suspend fun deleteChat(id: String) {
        call(builder("/v1/chats/$id").delete().build())
    }

    suspend fun cancelChat(id: String) {
        call(builder("/v1/chats/$id/cancel").post(ByteArray(0).toRequestBody(null)).build())
    }

    /**
     * Posts a message and streams the run. Emits until the run ends; collecting
     * side cancellation aborts the HTTP call but NOT the server-side run, which
     * is deliberate: locking the phone must not kill Claude mid-task. Reattach
     * with [streamChat].
     */
    fun sendMessage(id: String, text: String): Flow<ChatEvent> =
        sse(builder("/v1/chats/$id/messages?stream=1").post(jsonBody("text" to text)).build())

    /**
     * Posts a message to a chat that is already running. The server queues it and
     * delivers it when the current run ends; there is no stream to follow because
     * the reply belongs to a future run.
     */
    suspend fun queueMessage(id: String, text: String) {
        call(builder("/v1/chats/$id/messages").post(jsonBody("text" to text)).build())
    }

    /** Reattaches to an in-flight run, replaying events after [since] (0 = all). */
    fun streamChat(id: String, since: Long = 0): Flow<ChatEvent> =
        sse(builder("/v1/chats/$id/stream?since=$since").get().build())

    private fun jsonBody(vararg pairs: Pair<String, String>) =
        encode(buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })

    private fun encode(obj: JsonObject) =
        json.encodeToString(JsonObject.serializer(), obj).toRequestBody(jsonMedia)

    /**
     * Minimal SSE reader. Frames are `event:`/`data:` lines terminated by a blank
     * line; `: ping` comment frames (the server heartbeat) are skipped.
     */
    private fun sse(request: Request): Flow<ChatEvent> = callbackFlow {
        val call = streamHttp.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (!c.isCanceled()) trySend(ChatEvent.Failure(e.message ?: "network error"))
                close()
            }

            override fun onResponse(c: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) {
                        val text = body?.string().orEmpty()
                        trySend(ChatEvent.Failure(errorFrom(resp.code, text).message))
                        close(); return
                    }
                    try {
                        val source = body.source()
                        var event: String? = null
                        val data = StringBuilder()
                        while (!source.exhausted()) {
                            val line = source.readUtf8LineStrict()
                            when {
                                line.startsWith(":") -> Unit                 // heartbeat comment
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                                line.startsWith("id:") -> Unit
                                line.isEmpty() -> {
                                    if (event != null) {
                                        parse(event, data.toString())?.let { trySend(it) }
                                        if (event == "done") { close(); return }
                                    }
                                    event = null; data.setLength(0)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!c.isCanceled()) trySend(ChatEvent.Failure(e.message ?: "stream ended"))
                    }
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

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
}
