package com.silencelen.huginn.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The core's device status — field names match `runner.Status` in Go. */
@Serializable
data class CoreDeviceStatus(
    val enabled: Boolean = false,
    val deviceId: String? = null,
    val enrolled: Boolean = false,
    val busy: Boolean = false,
    val locked: Boolean = false,
    val lockKnown: Boolean = false,
    val note: String = "",
)

/** The editable half — `daemon.DeviceSettings`. Null = leave as it is. */
@Serializable
data class CoreDeviceSettings(
    val enabled: Boolean? = null,
    val name: String? = null,
    val scope: String? = null,
    val root: String? = null,
    val actWhileLocked: Boolean? = null,
    val claude: String? = null,
)

@Serializable
data class CoreDeviceView(val status: CoreDeviceStatus = CoreDeviceStatus(), val settings: CoreDeviceSettings = CoreDeviceSettings())

/**
 * HTTP to `huginn core` over its AF_UNIX socket (docs/CORE-ARCHITECTURE.md §5).
 * P1 speaks only the core's own routes (`/core/…`); the `/appd/…` proxy arrives in P2.
 */
internal class CoreClient(private val socket: Path) {
    /** Bump with `version.Proto` in Go. */
    companion object {
        const val PROTO = "1"
        private const val BASE = "http://huginn-core"
        private val JSON = "application/json".toMediaType()
        internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(CoreSocketFactory(socket))
        .dns { listOf(InetAddress.getLoopbackAddress()) } // the host name is never resolved
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { it.proceed(it.request().newBuilder().header("X-Huginn-Core-Proto", PROTO).build()) }
        .build()

    private val streaming: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** Whether a socket file exists at all — cheap, and says "not installed" vs "not answering". */
    fun socketPresent(): Boolean = Files.exists(socket)

    class CoreError(val status: Int, message: String) : IOException(message)

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE + path)
            .method(method, body?.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { json.decodeFromString<Map<String, String>>(text)["error"] }.getOrNull()
                throw CoreError(r.code, msg ?: "huginn core answered ${r.code}")
            }
            text
        }
    }

    suspend fun device(): CoreDeviceView = json.decodeFromString(call("GET", "/core/device"))

    suspend fun patchDevice(s: CoreDeviceSettings): CoreDeviceView =
        json.decodeFromString(call("PATCH", "/core/device", json.encodeToString(s)))

    /** What the window shows ("sess:<name>", "chat:<id>", or null), so the core does not toast it. */
    suspend fun presence(focused: String?) {
        call("POST", "/core/presence", json.encodeToString(mapOf("focused" to (focused ?: ""))))
    }

    /** Forget the pairing (Settings → Remove this computer). */
    suspend fun unpair() { call("POST", "/core/unpair", "{}") }

    /**
     * `/core/events` as (event, data) pairs. The core replays the newest of
     * each event first, so a fresh collector needs no separate GET. Ends (and
     * the caller reconnects) when the core goes away.
     */
    fun events(): Flow<Pair<String, String>> = callbackFlow {
        val call: Call = streaming.newCall(Request.Builder().url("$BASE/core/events").build())
        val reader = Thread({
            runCatching {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw CoreError(r.code, "events answered ${r.code}")
                    val src = r.body!!.source()
                    var event = "message"
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> trySend(event to line.removePrefix("data:").trim())
                            line.isEmpty() -> event = "message"
                        }
                    }
                }
            }.onFailure { if (!call.isCanceled()) close(it) }
            close()
        }, "huginn-core-events").apply { isDaemon = true; start() }
        awaitClose { call.cancel(); reader.interrupt() }
    }.flowOn(Dispatchers.IO)
}
