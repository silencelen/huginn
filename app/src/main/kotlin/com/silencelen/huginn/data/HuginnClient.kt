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
    private val pollHttp = http.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun url(path: String): String {
        val base = baseUrlProvider().trim().trimEnd('/')
        val withScheme = if (base.startsWith("http://") || base.startsWith("https://")) base else "http://$base"
        return "$withScheme$path"
    }

    private fun builder(path: String) = Request.Builder()
        .url(url(path))
        .header("Authorization", "Bearer ${tokenProvider().trim()}")

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

    /** Models the installed CLI offers, so the picker cannot go stale. */
    suspend fun models(): List<ModelChoice> =
        decode<ModelList>(call(builder("/v1/models").get().build())).models

    // ------------------------------------------------- account + usage

    suspend fun account(): Account = decode(call(builder("/v1/account").get().build()))

    /** Starts an interactive sign-in in a tmux session; returns its name. */
    suspend fun startLogin(): LoginSession =
        decode(call(builder("/v1/account/login").post(ByteArray(0).toRequestBody(null)).build()))

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
