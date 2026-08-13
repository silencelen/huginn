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

private val Context.dataStore by preferencesDataStore(name = "huginn_settings")

/**
 * Server URL + bearer token. Both are user-supplied: the token is minted on the
 * host by the daemon's deploy script, so there is nothing to hardcode here.
 * Default URL is huginn's tailnet address, which is where the daemon binds.
 *
 * Android's implementation of [HuginnSettings], and it stays here on purpose.
 * DataStore's own multiplatform API wants the file path spelled out, and this
 * store's path is wherever `preferencesDataStore("huginn_settings")` has been
 * putting it since 2.0 — on a phone that is in daily use, with the only copy of
 * the owner's token in it. The interface moved to :core so a second client has
 * something to implement; the FILE did not move, because a migration is a risk
 * with no payoff until that client exists.
 */
class SettingsStore(private val context: Context) : HuginnSettings {
    companion object {
        // Kept as aliases: these two names are read from a dozen call sites and
        // from the settings screen's slider bounds. One definition, in :core.
        const val DEFAULT_BASE_URL = HuginnSettings.DEFAULT_BASE_URL
        const val DEFAULT_FONT_SCALE = HuginnSettings.DEFAULT_FONT_SCALE
        private val BASE_URL = stringPreferencesKey("base_url")
        private val ROUTE_PINNED = booleanPreferencesKey("appd_route_pinned")
        private val TOKEN = stringPreferencesKey("token")
        private val FONT_SCALE = floatPreferencesKey("terminal_font_sp")
        private val NOTIFY = booleanPreferencesKey("notify_attention")
        private val NOTIFIED = stringSetPreferencesKey("notified_sessions")
        private val RUNNING_CHATS = stringSetPreferencesKey("running_chats")
        private val WATCH = booleanPreferencesKey("watch_continuously")
        private val DRAFTS = stringPreferencesKey("drafts")
        private val CLIENT_ID = stringPreferencesKey("client_id")
        private val CHAT_RUNS = stringPreferencesKey("chat_runs")
        private val APP_LOCK = booleanPreferencesKey("app_lock")
        private val PUSH_TOKEN = stringPreferencesKey("push_token")
        private val PUSH_TOKEN_AT = longPreferencesKey("push_token_at")
        private val LAST_PUSH_AT = longPreferencesKey("last_push_at")
        private val PUSHES_RECEIVED = longPreferencesKey("pushes_received")
        private val PUSHES_SENT = longPreferencesKey("pushes_sent")
        private val SEEDED = booleanPreferencesKey("watch_seeded")
        private val LAST_CONTACT = longPreferencesKey("last_contact_at")
        private val LAST_ALARM = longPreferencesKey("last_alarm_at")
        private val LAST_ERROR = stringPreferencesKey("last_watch_error")
        private val LAST_ERROR_AT = longPreferencesKey("last_watch_error_at")
        private val FLEET = stringPreferencesKey("fleet_snapshot")
    }

    /**
     * The home-screen widget's copy of the last observation, encoded by
     * [com.silencelen.huginn.notify.Fleet]. Cached rather than fetched at render
     * time: a widget draws whenever the launcher asks it to, including with the
     * host unreachable, and it should draw the last truth it saw — dated — not
     * an error.
     */
    val fleetSnapshot: Flow<String> = context.dataStore.data.map { it[FLEET] ?: "" }

    suspend fun setFleetSnapshot(encoded: String) {
        context.dataStore.edit { it[FLEET] = encoded }
    }

    /**
     * Stable id for this installation, minted once. Sent to the host so it can
     * record that this phone is still checking in; a random UUID rather than
     * anything derived from the device, since its only job is to be the same
     * tomorrow as it is today.
     */
    override suspend fun clientId(): String {
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
    override val watchSeeded: Flow<Boolean> = context.dataStore.data.map { it[SEEDED] ?: false }

    override suspend fun setWatchSeeded(value: Boolean) {
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
    override val chatRuns: Flow<Map<String, Long>> =
        context.dataStore.data.map { SettingsCodec.decodeChatRuns(it[CHAT_RUNS]) }

    override suspend fun setChatRuns(value: Map<String, Long>) {
        val encoded = SettingsCodec.encodeChatRuns(value)
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

    /**
     * When a push last actually ARRIVED — not when one was sent. This is the
     * evidence the heartbeat uses to decide it can stay out of the way.
     */
    val lastPushAt: Flow<Long> = context.dataStore.data.map { it[LAST_PUSH_AT] ?: 0L }

    /**
     * How many pushes have actually ARRIVED here, against how many the host says it
     * sent. Counted rather than timed on purpose: the two numbers are compared
     * across a network boundary, and counts cannot disagree about what time it is.
     */
    val pushesReceived: Flow<Long> = context.dataStore.data.map { it[PUSHES_RECEIVED] ?: 0L }
    val pushesSent: Flow<Long> = context.dataStore.data.map { it[PUSHES_SENT] ?: 0L }

    suspend fun notePushArrived(atMs: Long) {
        context.dataStore.edit {
            it[LAST_PUSH_AT] = atMs
            it[PUSHES_RECEIVED] = (it[PUSHES_RECEIVED] ?: 0L) + 1
        }
    }

    /** The host's own tally, learned from a watch response. */
    suspend fun notePushesSent(count: Long) {
        context.dataStore.edit { it[PUSHES_SENT] = count }
    }

    suspend fun notePushToken(token: String, atMs: Long) {
        context.dataStore.edit { it[PUSH_TOKEN] = token; it[PUSH_TOKEN_AT] = atMs }
    }

    /** Require the device credential to open the app. */
    val appLock: Flow<Boolean> = context.dataStore.data.map { it[APP_LOCK] ?: false }

    suspend fun setAppLock(value: Boolean) {
        context.dataStore.edit { it[APP_LOCK] = value }
    }

    /** Delivery health, so "is this working?" is answerable without guessing. */
    override val lastContactAt: Flow<Long> = context.dataStore.data.map { it[LAST_CONTACT] ?: 0L }
    override val lastAlarmAt: Flow<Long> = context.dataStore.data.map { it[LAST_ALARM] ?: 0L }
    override val lastWatchError: Flow<String> = context.dataStore.data.map { it[LAST_ERROR] ?: "" }
    override val lastWatchErrorAt: Flow<Long> = context.dataStore.data.map { it[LAST_ERROR_AT] ?: 0L }

    override suspend fun noteContact(atMs: Long) {
        context.dataStore.edit { it[LAST_CONTACT] = atMs }
    }

    override suspend fun noteAlarm(atMs: Long) {
        context.dataStore.edit { it[LAST_ALARM] = atMs }
    }

    override suspend fun noteWatchError(message: String, atMs: Long) {
        context.dataStore.edit { it[LAST_ERROR] = message.take(120); it[LAST_ERROR_AT] = atMs }
    }

    override val baseUrl: Flow<String> = context.dataStore.data.map { it[BASE_URL] ?: DEFAULT_BASE_URL }
    override val token: Flow<String> = context.dataStore.data.map { it[TOKEN] ?: "" }

    /** Terminal text size in sp. Drives the column count reported to the server. */
    override val fontScale: Flow<Float> = context.dataStore.data.map { it[FONT_SCALE] ?: DEFAULT_FONT_SCALE }

    override val notifyEnabled: Flow<Boolean> = context.dataStore.data.map { it[NOTIFY] ?: true }

    /** Continuous watching via the foreground service, rather than a 15-minute poll. */
    override val watchEnabled: Flow<Boolean> = context.dataStore.data.map { it[WATCH] ?: false }

    override suspend fun setWatchEnabled(value: Boolean) {
        context.dataStore.edit { it[WATCH] = value }
    }

    /**
     * Sessions already notified about, so the background poll fires on the
     * transition into needing-you rather than every 15 minutes forever.
     */
    override val notifiedSessions: Flow<Set<String>> = context.dataStore.data.map { it[NOTIFIED] ?: emptySet() }

    override suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[BASE_URL] = value.trim() }
    }

    /**
     * True when the route was chosen by hand, which stops auto-resolution from
     * moving off it. Typing a custom URL pins it implicitly.
     */
    override val routePinned: Flow<Boolean> = context.dataStore.data.map { it[ROUTE_PINNED] ?: false }

    /**
     * Switches the active route. Written to the same key the background workers
     * already read, so a switch applies to notifications and the watch service
     * too, not just the foreground UI.
     */
    override suspend fun selectRoute(url: String, pinned: Boolean) {
        context.dataStore.edit {
            it[BASE_URL] = url.trim()
            it[ROUTE_PINNED] = pinned
        }
    }

    override suspend fun setToken(value: String) {
        context.dataStore.edit { it[TOKEN] = value.trim() }
    }

    override suspend fun setFontScale(value: Float) {
        context.dataStore.edit {
            it[FONT_SCALE] = value.coerceIn(HuginnSettings.MIN_FONT_SCALE, HuginnSettings.MAX_FONT_SCALE)
        }
    }

    override suspend fun setNotifyEnabled(value: Boolean) {
        context.dataStore.edit { it[NOTIFY] = value }
    }

    override suspend fun setNotifiedSessions(value: Set<String>) {
        context.dataStore.edit { it[NOTIFIED] = value }
    }

    /**
     * Chats seen running at the last check. A chat that was running and no longer
     * is has finished — which is the only way to notice completion without a push
     * channel, and it needs the previous observation to compare against.
     */
    override val runningChats: Flow<Set<String>> = context.dataStore.data.map { it[RUNNING_CHATS] ?: emptySet() }

    override suspend fun setRunningChats(value: Set<String>) {
        context.dataStore.edit { it[RUNNING_CHATS] = value }
    }

    /**
     * Unsent composer text, keyed by target ("sess:name" / "chat:id").
     *
     * Persisted rather than held in the composable: a half-written message must
     * survive navigating away, and survive the process being killed while the
     * phone is in your pocket, which is exactly when it happens.
     */
    override val drafts: Flow<Map<String, String>> =
        context.dataStore.data.map { SettingsCodec.decodeDrafts(it[DRAFTS]) }

    override suspend fun setDrafts(value: Map<String, String>) {
        val encoded = SettingsCodec.encodeDrafts(value)
        context.dataStore.edit { it[DRAFTS] = encoded }
    }
}
