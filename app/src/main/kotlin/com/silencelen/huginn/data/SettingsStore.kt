package com.silencelen.huginn.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "huginn_settings")

/**
 * Server URL + bearer token. Both are user-supplied: the token is minted on the
 * host by the daemon's deploy script, so there is nothing to hardcode here.
 * Default URL is huginn's tailnet address, which is where the daemon binds.
 */
class SettingsStore(private val context: Context) {
    companion object {
        const val DEFAULT_BASE_URL = "http://100.97.198.90:8787"
        const val DEFAULT_FONT_SCALE = 9f
        private val BASE_URL = stringPreferencesKey("base_url")
        private val TOKEN = stringPreferencesKey("token")
        private val FONT_SCALE = floatPreferencesKey("terminal_font_sp")
        private val NOTIFY = booleanPreferencesKey("notify_attention")
        private val NOTIFIED = stringSetPreferencesKey("notified_sessions")
        private val RUNNING_CHATS = stringSetPreferencesKey("running_chats")
        private val WATCH = booleanPreferencesKey("watch_continuously")
        private val DRAFTS = stringPreferencesKey("drafts")
        private val CLIENT_ID = stringPreferencesKey("client_id")
        private val CHAT_RUNS = stringPreferencesKey("chat_runs")
        private val PUSH_TOKEN = stringPreferencesKey("push_token")
        private val PUSH_TOKEN_AT = longPreferencesKey("push_token_at")
        private val SEEDED = booleanPreferencesKey("watch_seeded")
        private val LAST_CONTACT = longPreferencesKey("last_contact_at")
        private val LAST_ALARM = longPreferencesKey("last_alarm_at")
        private val LAST_ERROR = stringPreferencesKey("last_watch_error")
        private val LAST_ERROR_AT = longPreferencesKey("last_watch_error_at")
    }

    /**
     * Stable id for this installation, minted once. Sent to the host so it can
     * record that this phone is still checking in; a random UUID rather than
     * anything derived from the device, since its only job is to be the same
     * tomorrow as it is today.
     */
    suspend fun clientId(): String {
        val existing = context.dataStore.data.map { it[CLIENT_ID] }.first()
        if (!existing.isNullOrBlank()) return existing
        val minted = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[CLIENT_ID] = minted }
        return minted
    }

    /**
     * Whether an observation has ever been recorded.
     *
     * Load-bearing, and it fixes a real hole. The baseline used to be re-seeded
     * every time the watcher started, which meant anything that changed while the
     * watcher was dead was silently absorbed as "how things have always been" —
     * and the watcher is most likely to have been killed exactly while the phone
     * was asleep, which is the case this is all for. Persisting the fact of having
     * looked lets a restart COMPARE instead of forget.
     */
    val watchSeeded: Flow<Boolean> = context.dataStore.data.map { it[SEEDED] ?: false }

    suspend fun setWatchSeeded(value: Boolean) {
        context.dataStore.edit { it[SEEDED] = value }
    }

    /**
     * Completed-run counts per chat, as of the last observation.
     *
     * Persisted alongside the running set because the two answer different questions
     * and only one of them survives a gap. "Which chats were running" misses a chat
     * that started and finished between two looks, and with a ten-minute background
     * check that is an ordinary occurrence rather than a corner case.
     */
    val chatRuns: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        val raw = prefs[CHAT_RUNS] ?: return@map emptyMap()
        runCatching {
            Json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
        }.getOrDefault(emptyMap())
    }

    suspend fun setChatRuns(value: Map<String, Long>) {
        val encoded = Json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), value)
        context.dataStore.edit { it[CHAT_RUNS] = encoded }
    }

    /**
     * The FCM token last handed to huginn, and when.
     *
     * Recorded so the delivery panel can say whether this phone has actually
     * registered — "push is configured on the host" and "this phone can be reached"
     * are different claims, and only the second one matters to you.
     */
    val pushToken: Flow<String> = context.dataStore.data.map { it[PUSH_TOKEN] ?: "" }
    val pushTokenAt: Flow<Long> = context.dataStore.data.map { it[PUSH_TOKEN_AT] ?: 0L }

    suspend fun notePushToken(token: String, atMs: Long) {
        context.dataStore.edit { it[PUSH_TOKEN] = token; it[PUSH_TOKEN_AT] = atMs }
    }

    /** Delivery health, so "is this working?" is answerable without guessing. */
    val lastContactAt: Flow<Long> = context.dataStore.data.map { it[LAST_CONTACT] ?: 0L }
    val lastAlarmAt: Flow<Long> = context.dataStore.data.map { it[LAST_ALARM] ?: 0L }
    val lastWatchError: Flow<String> = context.dataStore.data.map { it[LAST_ERROR] ?: "" }
    val lastWatchErrorAt: Flow<Long> = context.dataStore.data.map { it[LAST_ERROR_AT] ?: 0L }

    suspend fun noteContact(atMs: Long) {
        context.dataStore.edit { it[LAST_CONTACT] = atMs }
    }

    suspend fun noteAlarm(atMs: Long) {
        context.dataStore.edit { it[LAST_ALARM] = atMs }
    }

    suspend fun noteWatchError(message: String, atMs: Long) {
        context.dataStore.edit { it[LAST_ERROR] = message.take(120); it[LAST_ERROR_AT] = atMs }
    }

    val baseUrl: Flow<String> = context.dataStore.data.map { it[BASE_URL] ?: DEFAULT_BASE_URL }
    val token: Flow<String> = context.dataStore.data.map { it[TOKEN] ?: "" }

    /** Terminal text size in sp. Drives the column count reported to the server. */
    val fontScale: Flow<Float> = context.dataStore.data.map { it[FONT_SCALE] ?: DEFAULT_FONT_SCALE }

    val notifyEnabled: Flow<Boolean> = context.dataStore.data.map { it[NOTIFY] ?: true }

    /** Continuous watching via the foreground service, rather than a 15-minute poll. */
    val watchEnabled: Flow<Boolean> = context.dataStore.data.map { it[WATCH] ?: false }

    suspend fun setWatchEnabled(value: Boolean) {
        context.dataStore.edit { it[WATCH] = value }
    }

    /**
     * Sessions already notified about, so the background poll fires on the
     * transition into needing-you rather than every 15 minutes forever.
     */
    val notifiedSessions: Flow<Set<String>> = context.dataStore.data.map { it[NOTIFIED] ?: emptySet() }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[BASE_URL] = value.trim() }
    }

    suspend fun setToken(value: String) {
        context.dataStore.edit { it[TOKEN] = value.trim() }
    }

    suspend fun setFontScale(value: Float) {
        context.dataStore.edit { it[FONT_SCALE] = value.coerceIn(5.5f, 22f) }
    }

    suspend fun setNotifyEnabled(value: Boolean) {
        context.dataStore.edit { it[NOTIFY] = value }
    }

    suspend fun setNotifiedSessions(value: Set<String>) {
        context.dataStore.edit { it[NOTIFIED] = value }
    }

    /**
     * Chats seen running at the last check. A chat that was running and no longer
     * is has finished — which is the only way to notice completion without a push
     * channel, and it needs the previous observation to compare against.
     */
    val runningChats: Flow<Set<String>> = context.dataStore.data.map { it[RUNNING_CHATS] ?: emptySet() }

    suspend fun setRunningChats(value: Set<String>) {
        context.dataStore.edit { it[RUNNING_CHATS] = value }
    }

    /**
     * Unsent composer text, keyed by target ("sess:name" / "chat:id").
     *
     * Persisted rather than held in the composable: a half-written message must
     * survive navigating away, and survive the process being killed while the
     * phone is in your pocket, which is exactly when it happens.
     */
    val drafts: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[DRAFTS] ?: return@map emptyMap()
        runCatching {
            Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
        }.getOrElse { emptyMap() }
    }

    suspend fun setDrafts(value: Map<String, String>) {
        val trimmed = value.filterValues { it.isNotEmpty() }
        val encoded = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), trimmed)
        context.dataStore.edit { it[DRAFTS] = encoded }
    }
}
