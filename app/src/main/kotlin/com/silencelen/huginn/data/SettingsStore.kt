package com.silencelen.huginn.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
