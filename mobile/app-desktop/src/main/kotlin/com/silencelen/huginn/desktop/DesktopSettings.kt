package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.HuginnSettings
import com.silencelen.huginn.data.SettingsCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * The desktop half of [HuginnSettings]: one JSON file under the user config dir.
 *
 * A SEPARATE FILE from the Electron client's, on purpose. `~/.config/Electron`
 * (unpackaged) and `~/.config/huginn-desktop` (packaged) belong to an app that is
 * still in service; two programs writing one config with different schemas and
 * different token encodings is how the owner loses a working install to a client
 * that does not exist yet. This is a fresh install with a fresh directory, and
 * they can coexist until Electron retires.
 *
 * The store did not become multiplatform and was not meant to — see the header of
 * [HuginnSettings]. What is shared is the CONTRACT: the same property names, the
 * same defaults, and [SettingsCodec] for the two map-valued settings, so the two
 * clients cannot drift on what a draft key or a run counter means.
 *
 * Everything is held in memory as [MutableStateFlow] and written through on every
 * mutation. The file is small (a token, a URL, a handful of counters and the
 * drafts), and reading it once at construction means no call site ever awaits I/O
 * to learn the base URL.
 */
class DesktopSettings(private val file: File = defaultFile()) : HuginnSettings {

    @Serializable
    private data class Stored(
        val baseUrl: String = HuginnSettings.DEFAULT_BASE_URL,
        /**
         * PLAINTEXT, and this is the one thing the Electron client does better:
         * it puts the token through `safeStorage` (libsecret on Linux) and falls
         * back to plaintext with a visible flag. There is no equivalent in a
         * plain JVM without dragging in a keyring binding, so the file is mode
         * 0600 and this comment is the honest label. Phase 4 owns the keyring;
         * pretending to encrypt in the meantime would be worse than saying so.
         */
        val token: String = "",
        val routePinned: Boolean = false,
        val clientId: String = "",
        val fontScale: Float = HuginnSettings.DEFAULT_FONT_SCALE,
        val notifyEnabled: Boolean = true,
        val watchEnabled: Boolean = true,
        val watchSeeded: Boolean = false,
        val notifiedSessions: List<String> = emptyList(),
        val runningChats: List<String> = emptyList(),
        /** Encoded by [SettingsCodec] rather than as a native map: same bytes as the phone writes. */
        val chatRuns: String = "",
        val drafts: String = "",
        val lastContactAt: Long = 0,
        val lastAlarmAt: Long = 0,
        val lastWatchError: String = "",
        val lastWatchErrorAt: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /**
     * Guards the read-modify-write below. Compose event handlers, the poll loop
     * and the watch loop all mutate settings from different coroutines, and a
     * lost update here is a lost token.
     */
    private val lock = Any()
    private var stored: Stored = load()

    private val _baseUrl = MutableStateFlow(stored.baseUrl)
    private val _token = MutableStateFlow(stored.token)
    private val _routePinned = MutableStateFlow(stored.routePinned)
    private val _fontScale = MutableStateFlow(stored.fontScale)
    private val _notifyEnabled = MutableStateFlow(stored.notifyEnabled)
    private val _watchEnabled = MutableStateFlow(stored.watchEnabled)
    private val _watchSeeded = MutableStateFlow(stored.watchSeeded)
    private val _notifiedSessions = MutableStateFlow(stored.notifiedSessions.toSet())
    private val _runningChats = MutableStateFlow(stored.runningChats.toSet())
    private val _chatRuns = MutableStateFlow(SettingsCodec.decodeChatRuns(stored.chatRuns))
    private val _drafts = MutableStateFlow(SettingsCodec.decodeDrafts(stored.drafts))
    private val _lastContactAt = MutableStateFlow(stored.lastContactAt)
    private val _lastAlarmAt = MutableStateFlow(stored.lastAlarmAt)
    private val _lastWatchError = MutableStateFlow(stored.lastWatchError)
    private val _lastWatchErrorAt = MutableStateFlow(stored.lastWatchErrorAt)

    init {
        if (stored.clientId.isEmpty()) mutate { it.copy(clientId = "desktop-kt-${UUID.randomUUID()}") }
        if (stored.token.isEmpty()) bootstrapDevToken()
    }

    // ------------------------------------------------------ HuginnSettings

    override val baseUrl: Flow<String> = _baseUrl.asStateFlow()
    override val token: Flow<String> = _token.asStateFlow()
    override val routePinned: Flow<Boolean> = _routePinned.asStateFlow()
    override val fontScale: Flow<Float> = _fontScale.asStateFlow()
    override val notifyEnabled: Flow<Boolean> = _notifyEnabled.asStateFlow()
    override val watchEnabled: Flow<Boolean> = _watchEnabled.asStateFlow()
    override val watchSeeded: Flow<Boolean> = _watchSeeded.asStateFlow()
    override val notifiedSessions: Flow<Set<String>> = _notifiedSessions.asStateFlow()
    override val runningChats: Flow<Set<String>> = _runningChats.asStateFlow()
    override val chatRuns: Flow<Map<String, Long>> = _chatRuns.asStateFlow()
    override val drafts: Flow<Map<String, String>> = _drafts.asStateFlow()
    override val lastContactAt: Flow<Long> = _lastContactAt.asStateFlow()
    override val lastAlarmAt: Flow<Long> = _lastAlarmAt.asStateFlow()
    override val lastWatchError: Flow<String> = _lastWatchError.asStateFlow()
    override val lastWatchErrorAt: Flow<Long> = _lastWatchErrorAt.asStateFlow()

    override suspend fun setBaseUrl(value: String) {
        val next = AppdRoutes.normalize(value)
        require(isAllowedBaseUrl(next)) { REFUSED }
        _baseUrl.value = next
        mutate { it.copy(baseUrl = next) }
    }

    override suspend fun setToken(value: String) {
        val next = value.trim()
        _token.value = next
        mutate { it.copy(token = next) }
    }

    override suspend fun selectRoute(url: String, pinned: Boolean) {
        val next = AppdRoutes.normalize(url)
        require(isAllowedBaseUrl(next)) { REFUSED }
        _baseUrl.value = next
        _routePinned.value = pinned
        mutate { it.copy(baseUrl = next, routePinned = pinned) }
    }

    override suspend fun clientId(): String = synchronized(lock) { stored.clientId }

    override suspend fun setFontScale(value: Float) {
        val next = value.coerceIn(HuginnSettings.MIN_FONT_SCALE, HuginnSettings.MAX_FONT_SCALE)
        _fontScale.value = next
        mutate { it.copy(fontScale = next) }
    }

    override suspend fun setNotifyEnabled(value: Boolean) {
        _notifyEnabled.value = value
        mutate { it.copy(notifyEnabled = value) }
    }

    override suspend fun setWatchEnabled(value: Boolean) {
        _watchEnabled.value = value
        mutate { it.copy(watchEnabled = value) }
    }

    override suspend fun setWatchSeeded(value: Boolean) {
        _watchSeeded.value = value
        mutate { it.copy(watchSeeded = value) }
    }

    override suspend fun setNotifiedSessions(value: Set<String>) {
        _notifiedSessions.value = value
        mutate { it.copy(notifiedSessions = value.toList()) }
    }

    override suspend fun setRunningChats(value: Set<String>) {
        _runningChats.value = value
        mutate { it.copy(runningChats = value.toList()) }
    }

    override suspend fun setChatRuns(value: Map<String, Long>) {
        _chatRuns.value = value
        mutate { it.copy(chatRuns = SettingsCodec.encodeChatRuns(value)) }
    }

    override suspend fun setDrafts(value: Map<String, String>) {
        _drafts.value = value
        mutate { it.copy(drafts = SettingsCodec.encodeDrafts(value)) }
    }

    override suspend fun noteContact(atMs: Long) {
        _lastContactAt.value = atMs
        mutate { it.copy(lastContactAt = atMs) }
    }

    override suspend fun noteAlarm(atMs: Long) {
        _lastAlarmAt.value = atMs
        mutate { it.copy(lastAlarmAt = atMs) }
    }

    override suspend fun noteWatchError(message: String, atMs: Long) {
        _lastWatchError.value = message
        _lastWatchErrorAt.value = atMs
        mutate { it.copy(lastWatchError = message, lastWatchErrorAt = atMs) }
    }

    // ------------------------------------------------- synchronous readers
    //
    // HuginnClient takes `() -> String` providers, not a settings object, so it
    // can be built by anything holding the values. These are what feed them.

    fun baseUrlNow(): String = _baseUrl.value
    fun tokenNow(): String = _token.value
    fun clientIdNow(): String = synchronized(lock) { stored.clientId }
    fun notifyEnabledNow(): Boolean = _notifyEnabled.value
    fun routePinnedNow(): Boolean = _routePinned.value
    val tokenState: StateFlow<String> get() = _token.asStateFlow()

    /** Where the settings live, for the Settings screen to show. */
    val path: String get() = file.absolutePath

    // ------------------------------------------------------------ plumbing

    private fun load(): Stored = runCatching {
        json.decodeFromString(Stored.serializer(), file.readText())
    }.getOrElse {
        // First run, or a file half-written by a killed process. Losing drafts is
        // recoverable; refusing to launch is not.
        Stored()
    }

    private fun mutate(block: (Stored) -> Stored) {
        synchronized(lock) {
            stored = block(stored)
            save(stored)
        }
    }

    private fun save(value: Stored) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(Stored.serializer(), value))
            restrictToOwner(tmp)
            // Atomic rename: a crash mid-write must not leave a truncated file
            // where the token used to be.
            tmp.renameTo(file)
            restrictToOwner(file)
        }
    }

    private fun restrictToOwner(f: File) {
        f.setReadable(false, false); f.setWritable(false, false)
        f.setReadable(true, true); f.setWritable(true, true)
    }

    /**
     * Dev convenience for THIS box only: running unpackaged on huginn itself, the
     * daemon token is readable at its canonical path, so use it rather than making
     * a developer paste their own machine's token into their own machine. The
     * Electron client does the same, gated the same way.
     *
     * Gated on not-being-packaged because a shipped build must never read a path
     * it happens to find on someone else's disk.
     */
    private fun bootstrapDevToken() {
        if (isPackaged()) return
        runCatching {
            val t = File(DEV_TOKEN_PATH).readText().trim()
            if (t.length >= 32) {
                _token.value = t
                mutate { it.copy(token = t) }
            }
        }
    }

    companion object {
        const val DEV_TOKEN_PATH: String = "/etc/huginn-appd/token"

        const val REFUSED: String =
            "refusing that server address — huginn only talks to its own daemon"

        /**
         * Where this client is allowed to point, and NOT cosmetic validation: the
         * bearer token follows the base URL on every single request, so an
         * unvalidated setting is a one-field path to handing the daemon token —
         * root-equivalent on this host — to any address someone can talk a user
         * into typing. Kept in step with the Electron client's own list.
         */
        val ALLOWED_HOSTS: Set<String> = setOf(
            "100.97.198.90", // tailnet
            "192.168.2.117", // yggdrasil / VLAN 2
            "localhost",
            "127.0.0.1",
        )

        fun isAllowedBaseUrl(raw: String): Boolean {
            val u = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return false
            if (u.scheme != "http" && u.scheme != "https") return false
            val path = u.path ?: ""
            if (path.isNotEmpty() && path != "/") return false
            return u.host in ALLOWED_HOSTS
        }

        /**
         * jpackage stamps this on every launcher it generates, and nothing else
         * sets it — so its absence is "running from Gradle".
         */
        fun isPackaged(): Boolean = System.getProperty("jpackage.app-path") != null

        fun defaultFile(): File {
            val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.config")
            return File("$base/huginn-desktop-kt/settings.json")
        }
    }
}
