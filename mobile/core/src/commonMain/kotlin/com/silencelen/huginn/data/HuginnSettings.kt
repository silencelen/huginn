package com.silencelen.huginn.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What every Huginn client remembers between launches.
 *
 * An interface here and an implementation per platform, rather than one shared
 * store. The STORE did not move and was not meant to: Android's copy is
 * `androidx.datastore`, whose file the owner's phone has been reading and
 * writing since 2.0, and the multiplatform way to open one names its path
 * explicitly. Getting that path wrong by a directory on an app someone uses
 * daily loses their token and their drafts, and nothing about a desktop client
 * that does not exist yet is worth that risk. What IS shared is the contract —
 * the names, the defaults, and the encoding of the two map-valued settings —
 * which is the part a second client would otherwise reimplement slightly
 * differently and drift on.
 *
 * [HuginnClient] deliberately does NOT take one of these: it takes `{ base }`,
 * `{ token }` lambdas, so a background worker can build a client from values it
 * already has without opening a settings store at all. That decoupling predates
 * this interface and outlives it.
 */
interface HuginnSettings {

    // ------------------------------------------------------ connection

    val baseUrl: Flow<String>
    val token: Flow<String>

    /** True when the route was chosen by hand, which stops auto-resolution moving off it. */
    val routePinned: Flow<Boolean>

    suspend fun setBaseUrl(value: String)
    suspend fun setToken(value: String)

    /** Switches the active route, for the UI and the background workers at once. */
    suspend fun selectRoute(url: String, pinned: Boolean)

    /**
     * Stable id for this installation, minted once. Sent to the host so it can
     * record that this client is still checking in.
     */
    suspend fun clientId(): String

    // ---------------------------------------------------------- display

    /** Terminal text size in sp. Drives the column count reported to the server. */
    val fontScale: Flow<Float>
    suspend fun setFontScale(value: Float)

    // ------------------------------------------------------- watching

    val notifyEnabled: Flow<Boolean>
    suspend fun setNotifyEnabled(value: Boolean)

    /** Continuous watching, rather than a periodic poll. */
    val watchEnabled: Flow<Boolean>
    suspend fun setWatchEnabled(value: Boolean)

    /**
     * Whether an observation has ever been recorded. Load-bearing: a baseline
     * re-seeded on every start silently absorbs everything that changed while
     * the watcher was dead, which is exactly the window it exists to cover.
     */
    val watchSeeded: Flow<Boolean>
    suspend fun setWatchSeeded(value: Boolean)

    /** Sessions already notified about, so a transition fires once and not forever. */
    val notifiedSessions: Flow<Set<String>>
    suspend fun setNotifiedSessions(value: Set<String>)

    /** Chats seen running at the last check; the previous observation to compare against. */
    val runningChats: Flow<Set<String>>
    suspend fun setRunningChats(value: Set<String>)

    /**
     * Completed-run counts per chat. Kept alongside the running set because
     * "which chats were running" misses one that started and finished between
     * two looks — an ordinary occurrence on a ten-minute check.
     */
    val chatRuns: Flow<Map<String, Long>>
    suspend fun setChatRuns(value: Map<String, Long>)

    // ---------------------------------------------------------- drafts

    /** Unsent composer text, keyed by target ("sess:name" / "chat:id"). */
    val drafts: Flow<Map<String, String>>
    suspend fun setDrafts(value: Map<String, String>)

    // ------------------------------------------------------- diagnostics

    val lastContactAt: Flow<Long>
    val lastAlarmAt: Flow<Long>
    val lastWatchError: Flow<String>
    val lastWatchErrorAt: Flow<Long>

    suspend fun noteContact(atMs: Long)
    suspend fun noteAlarm(atMs: Long)
    suspend fun noteWatchError(message: String, atMs: Long)

    companion object {
        /** huginn's tailnet address, which is where the daemon binds. */
        const val DEFAULT_BASE_URL: String = "http://100.97.198.90:8787"
        const val DEFAULT_FONT_SCALE: Float = 9f

        /** The bounds a font scale is clamped to, wherever it is set from. */
        const val MIN_FONT_SCALE: Float = 5.5f
        const val MAX_FONT_SCALE: Float = 22f
    }
}

/**
 * How the two map-valued settings are stored: a JSON object in a single string
 * preference, because neither store has a map type and both clients have to read
 * the other's shape if the same account is ever used from both.
 *
 * Unreadable input decodes to an empty map rather than throwing. A settings file
 * half-written by a process that was killed must not stop the app from starting;
 * losing a draft is recoverable, refusing to launch is not.
 */
object SettingsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = MapSerializer(String.serializer(), String.serializer())
    private val longs = MapSerializer(String.serializer(), Long.serializer())

    fun decodeDrafts(raw: String?): Map<String, String> =
        if (raw.isNullOrEmpty()) emptyMap()
        else runCatching { json.decodeFromString(strings, raw) }.getOrDefault(emptyMap())

    /** Empty values are dropped: an emptied composer is not a draft. */
    fun encodeDrafts(value: Map<String, String>): String =
        json.encodeToString(strings, value.filterValues { it.isNotEmpty() })

    fun decodeChatRuns(raw: String?): Map<String, Long> =
        if (raw.isNullOrEmpty()) emptyMap()
        else runCatching { json.decodeFromString(longs, raw) }.getOrDefault(emptyMap())

    fun encodeChatRuns(value: Map<String, Long>): String = json.encodeToString(longs, value)
}
