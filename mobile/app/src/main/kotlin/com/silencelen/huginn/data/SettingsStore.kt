package com.silencelen.huginn.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.catch
import java.io.File
import java.io.IOException
import com.silencelen.huginn.notify.PushTally
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val STORE_NAME = "huginn_settings"

/**
 * The context to salvage a corrupt store from — see [salvageAndEmpty].
 *
 * A top-level property delegate has no receiver inside its corruption handler,
 * and this file is the only one that touches `dataStore`, so [SettingsStore]'s
 * constructor puts the application context here on the way past.
 */
private val salvageFrom = java.util.concurrent.atomic.AtomicReference<Context?>(null)

/**
 * ⚠ A STORE THAT CANNOT BE READ MUST NOT MEAN AN APP THAT CANNOT BE OPENED.
 *
 * Declared with no corruption handler, an unparseable `.preferences_pb` threw
 * CorruptionException out of the three unguarded startup readers — MainActivity's
 * blocking lock read, AskActivity's, and the view model's init — on EVERY launch,
 * with no in-app recovery and "clear app data" (re-pairing the phone) as the only
 * remedy. The desktop twin already salvages a corrupt file to `.corrupt` and
 * launches, with a comment saying refusing to launch is not an option; Android
 * had no equivalent.
 *
 * ⚠ THE HANDLER IS HALF OF IT. ReplaceFileCorruptionHandler catches ONLY
 * CorruptionException — an unreadable path throws FileNotFoundException with or
 * without it — so the load-bearing half is the `catch` in [prefs], and this
 * repairs the file so writes work again afterwards.
 */
private val Context.dataStore by preferencesDataStore(
    name = STORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { salvageAndEmpty() },
)

/** Keeps the unreadable bytes beside the store before they are replaced. */
private fun salvageAndEmpty(): Preferences {
    salvageFrom.get()?.let { ctx ->
        runCatching {
            val f = ctx.preferencesDataStoreFile(STORE_NAME)
            if (f.exists()) f.copyTo(File(f.parentFile, f.name + ".corrupt"), overwrite = true)
        }
    }
    return emptyPreferences()
}

/**
 * A read that survives a store this device cannot read.
 *
 * CorruptionException extends IOException, so this covers both the unparseable
 * file the handler repairs and the unreadable path it cannot. Anything else is
 * a programming error and still throws.
 */
internal fun <T> Flow<T>.orEmptyOnIoFailure(empty: T): Flow<T> =
    catch { if (it is IOException) emit(empty) else throw it }

/**
 * A startup read of the app-lock setting that FAILS CLOSED.
 *
 * The two blocking reads on the first frame cannot wait on DataStore and must
 * not crash on it either; a lock that does not apply because the settings file
 * is unreadable is a lock that opens for whoever makes it unreadable.
 */
internal suspend fun lockEnabledOrLocked(read: suspend () -> Boolean): Boolean =
    runCatching { read() }.getOrDefault(true)

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

    init {
        salvageFrom.compareAndSet(null, context.applicationContext)
    }

    /** ⚠ EVERY READ IN THIS FILE GOES THROUGH HERE. See [orEmptyOnIoFailure]. */
    private val prefs: Flow<Preferences> = context.dataStore.data.orEmptyOnIoFailure(emptyPreferences())

    companion object {
        // Kept as an alias: this name is read from the settings screen's slider
        // bounds. One definition, in :core.
        const val DEFAULT_FONT_SCALE = HuginnSettings.DEFAULT_FONT_SCALE
        /**
         * ⚠ DERIVED, AND IT STAYS. Ten background call sites read the base URL
         * straight out of this store and know nothing about routes; the route
         * book writes this key through on every save so none of them had to
         * change. It is also what an older APK would read if this phone were
         * ever rolled back.
         */
        private val BASE_URL = stringPreferencesKey("base_url")

        /**
         * The stored bytes → the book, and back. ⚠ EXTRACTED TO BE TESTED: this
         * source set has no Robolectric, so a [SettingsStore] cannot be built in
         * a unit test at all — and the two rules worth holding (the migration
         * trigger, and that everything the book carries survives a write and a
         * read) are pure. Same reason [orEmptyOnIoFailure] is a function.
         *
         * In the companion because the keys are private to it, and taking the
         * preferences as a parameter rather than as a receiver so the call site
         * reads the same from inside the class and from a test.
         */
        internal fun routeBookFrom(p: Preferences): RouteBook {
            val stored = SettingsCodec.decodeRoutes(p[PINNED_ROUTES])
                // ⚠ UNTOUCHED. A never-written key is the pre-3.x store, and the
                // book is rebuilt from `base_url` + `appd_route_pinned` exactly as
                // it always was — dropped address included, which is the whole
                // reason the notice needs somewhere to live.
                ?: return AppdRoutes.migrate(p[BASE_URL], p[ROUTE_PINNED] ?: false)
            return RouteBook(
                routes = stored,
                activeId = p[ACTIVE_ROUTE_ID]?.takeIf { it.isNotBlank() },
                autoSwitch = p[AUTO_SWITCH] ?: true,
                droppedUrl = p[ROUTE_DROPPED]?.takeIf { it.isNotBlank() },
            ).normalized()
        }

        /**
         * ⚠ ONE EDIT, EVERY KEY. DataStore publishes each `edit` as its own
         * emission, so a worker that woke between two of them would build a
         * client for a route the list no longer holds — which is also why
         * `base_url` is written through here rather than derived by a reader.
         */
        internal fun putRouteBook(p: MutablePreferences, book: RouteBook) {
            p[PINNED_ROUTES] = SettingsCodec.encodeRoutes(book.routes)
            p[ACTIVE_ROUTE_ID] = book.activeId.orEmpty()
            p[AUTO_SWITCH] = book.autoSwitch
            p[BASE_URL] = book.activeUrl
            p[ROUTE_DROPPED] = book.droppedUrl.orEmpty()
        }

        internal fun routeHealthFrom(p: Preferences): Map<String, RouteHealth> =
            RouteHealthSnapshot.decode(p[ROUTE_HEALTH])

        internal fun putRouteHealth(p: MutablePreferences, value: Map<String, RouteHealth>, atMs: Long) {
            p[ROUTE_HEALTH] = RouteHealthSnapshot.encode(value, atMs)
        }

        /** Pre-routes. Read once, to migrate; never written again. */
        private val ROUTE_PINNED = booleanPreferencesKey("appd_route_pinned")
        private val PINNED_ROUTES = stringPreferencesKey("pinned_routes")
        private val ACTIVE_ROUTE_ID = stringPreferencesKey("active_route_id")
        private val AUTO_SWITCH = booleanPreferencesKey("auto_switch")
        /**
         * The address [RouteGuard] threw out of the book, kept so the settings
         * screen can still name it AFTER a restart.
         *
         * ⚠ IT SURVIVED EXACTLY ONE PROCESS BEFORE. The drop happens during the
         * pre-3.x migration — on the very first read, usually before any screen
         * has been opened — and the notice lived only in the in-memory book, so
         * the one reader it was written for (somebody opening Settings to find
         * out why the app stopped connecting) had already missed it.
         */
        private val ROUTE_DROPPED = stringPreferencesKey("route_dropped_url")
        /** [RouteHealthSnapshot], verbatim. See [HuginnSettings.routeHealth]. */
        private val ROUTE_HEALTH = stringPreferencesKey("route_health")
        private val TOKEN = stringPreferencesKey("token")
        private val FONT_SCALE = floatPreferencesKey("terminal_font_sp")
        private val NOTIFY = booleanPreferencesKey("notify_attention")
        private val NOTIFIED = stringSetPreferencesKey("notified_sessions")
        private val RUNNING_CHATS = stringSetPreferencesKey("running_chats")
        private val WATCH = booleanPreferencesKey("watch_continuously")
        private val DRAFTS = stringPreferencesKey("drafts")

        /** The session open when this client was last looked at. See [lastOpenSession]. */
        private val LAST_OPEN_SESSION = stringPreferencesKey("last_open_session")
        private val CLIENT_ID = stringPreferencesKey("client_id")
        private val CHAT_RUNS = stringPreferencesKey("chat_runs")
        private val HEADROOM_STALLED = stringSetPreferencesKey("headroom_stalled")
        private val HEADROOM_LADDERED = stringPreferencesKey("headroom_laddered")
        private val APP_LOCK = booleanPreferencesKey("app_lock")
        private val PUSH_TOKEN = stringPreferencesKey("push_token")
        private val PUSH_TOKEN_AT = longPreferencesKey("push_token_at")
        private val LAST_PUSH_AT = longPreferencesKey("last_push_at")
        private val PUSHES_RECEIVED = longPreferencesKey("pushes_received")
        private val PUSHES_SENT = longPreferencesKey("pushes_sent")
        private val PUSH_EPOCH = stringPreferencesKey("push_epoch")
        private val PUSH_REBASELINED = booleanPreferencesKey("push_rebaselined")
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
    val fleetSnapshot: Flow<String> = prefs.map { it[FLEET] ?: "" }

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
        val existing = prefs.map { it[CLIENT_ID] }.first()
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
    override val watchSeeded: Flow<Boolean> = prefs.map { it[SEEDED] ?: false }

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
        prefs.map { SettingsCodec.decodeChatRuns(it[CHAT_RUNS]) }

    override suspend fun setChatRuns(value: Map<String, Long>) {
        val encoded = SettingsCodec.encodeChatRuns(value)
        context.dataStore.edit { it[CHAT_RUNS] = encoded }
    }

    /**
     * Sessions sitting on a usage limit as of the last observation.
     *
     * Persisted for the reason every other watch baseline is: a session that
     * stalls at 1am and is picked back up at 2am did both while this process was
     * dead, and an in-memory baseline would have no record that either happened.
     * The alarm then rediscovers the truth and the shade catches up.
     */
    val stalledSessions: Flow<Set<String>> =
        prefs.map { it[HEADROOM_STALLED] ?: emptySet() }

    suspend fun setStalledSessions(value: Set<String>) {
        context.dataStore.edit { it[HEADROOM_STALLED] = value }
    }

    /**
     * Sessions the ladder has moved, name → the family they are on now.
     *
     * A MAP rather than a set, because a change of rung is an event too: a
     * session going fable → opus → sonnet moves twice, and a set would report
     * only the first, leaving the reader believing it is still on opus.
     * Encoded with the drafts codec — the shape is the same string-to-string map.
     */
    val ladderedSessions: Flow<Map<String, String>> =
        prefs.map { SettingsCodec.decodeDrafts(it[HEADROOM_LADDERED]) }

    suspend fun setLadderedSessions(value: Map<String, String>) {
        val encoded = SettingsCodec.encodeDrafts(value)
        context.dataStore.edit { it[HEADROOM_LADDERED] = encoded }
    }

    /**
     * The FCM token last handed to huginn, and when.
     *
     * Recorded so the delivery panel can say whether this phone has actually
     * registered — "push is configured on the host" and "this phone can be reached"
     * are different claims, and only the second one matters to you.
     */
    val pushToken: Flow<String> = prefs.map { it[PUSH_TOKEN] ?: "" }
    val pushTokenAt: Flow<Long> = prefs.map { it[PUSH_TOKEN_AT] ?: 0L }

    /**
     * When a push last actually ARRIVED — not when one was sent. This is the
     * evidence the heartbeat uses to decide it can stay out of the way.
     */
    val lastPushAt: Flow<Long> = prefs.map { it[LAST_PUSH_AT] ?: 0L }

    /**
     * How many pushes have actually ARRIVED here, against how many the host says it
     * sent. Counted rather than timed on purpose: the two numbers are compared
     * across a network boundary, and counts cannot disagree about what time it is.
     */
    val pushesReceived: Flow<Long> = prefs.map { it[PUSHES_RECEIVED] ?: 0L }
    val pushesSent: Flow<Long> = prefs.map { it[PUSHES_SENT] ?: 0L }

    /**
     * Which of the host's counter epochs [pushesReceived] belongs to.
     *
     * Empty means "no epoch seen yet", which is the state against a daemon older
     * than 3.0.5. See [com.silencelen.huginn.notify.PushTally].
     */
    val pushEpoch: Flow<String> = prefs.map { it[PUSH_EPOCH] ?: "" }

    /** The phone has re-based its tally at least once, so the page can say so. */
    val pushRebaselined: Flow<Boolean> = prefs.map { it[PUSH_REBASELINED] ?: false }

    suspend fun notePushArrived(atMs: Long) {
        context.dataStore.edit {
            it[LAST_PUSH_AT] = atMs
            it[PUSHES_RECEIVED] = (it[PUSHES_RECEIVED] ?: 0L) + 1
        }
    }

    /**
     * The host's own tally, learned from a watch response — AND the epoch it was
     * counted in, which is what stops the two numbers being compared across a
     * host-side restart.
     *
     * The whole reconciliation is [PushTally.reconcile]'s, tested in `:core`;
     * this writes the answer down. Both keys move in ONE edit so a page reading
     * them between two writes cannot see a count from one epoch labelled with
     * the other.
     */
    suspend fun notePushesSent(count: Long, epoch: String? = null) {
        context.dataStore.edit { prefs ->
            val reading = PushTally.reconcile(
                received = prefs[PUSHES_RECEIVED] ?: 0L,
                storedEpoch = prefs[PUSH_EPOCH],
                sent = count,
                epoch = epoch?.takeIf { it.isNotBlank() },
            )
            prefs[PUSHES_SENT] = count
            prefs[PUSHES_RECEIVED] = reading.received
            reading.epoch?.let { prefs[PUSH_EPOCH] = it }
            if (reading.rebaselined) prefs[PUSH_REBASELINED] = true
        }
    }

    suspend fun notePushToken(token: String, atMs: Long) {
        context.dataStore.edit { it[PUSH_TOKEN] = token; it[PUSH_TOKEN_AT] = atMs }
    }

    /** Require the device credential to open the app. */
    val appLock: Flow<Boolean> = prefs.map { it[APP_LOCK] ?: false }

    suspend fun setAppLock(value: Boolean) {
        context.dataStore.edit { it[APP_LOCK] = value }
    }

    /** Delivery health, so "is this working?" is answerable without guessing. */
    override val lastContactAt: Flow<Long> = prefs.map { it[LAST_CONTACT] ?: 0L }
    override val lastAlarmAt: Flow<Long> = prefs.map { it[LAST_ALARM] ?: 0L }
    override val lastWatchError: Flow<String> = prefs.map { it[LAST_ERROR] ?: "" }
    override val lastWatchErrorAt: Flow<Long> = prefs.map { it[LAST_ERROR_AT] ?: 0L }

    override suspend fun noteContact(atMs: Long) {
        context.dataStore.edit { it[LAST_CONTACT] = atMs }
    }

    override suspend fun noteAlarm(atMs: Long) {
        context.dataStore.edit { it[LAST_ALARM] = atMs }
    }

    override suspend fun noteWatchError(message: String, atMs: Long) {
        context.dataStore.edit { it[LAST_ERROR] = message.take(120); it[LAST_ERROR_AT] = atMs }
    }

    /**
     * The active route's address. Read off the book rather than off [BASE_URL]
     * so the two can never disagree — the key is the mirror, this is the truth.
     */
    override val baseUrl: Flow<String> = prefs.map { routeBookFrom(it).activeUrl }
    override val token: Flow<String> = prefs.map { it[TOKEN] ?: "" }

    /** Terminal text size in sp. Drives the column count reported to the server. */
    override val fontScale: Flow<Float> = prefs.map { it[FONT_SCALE] ?: DEFAULT_FONT_SCALE }

    override val notifyEnabled: Flow<Boolean> = prefs.map { it[NOTIFY] ?: true }

    /** Continuous watching via the foreground service, rather than a 15-minute poll. */
    override val watchEnabled: Flow<Boolean> = prefs.map { it[WATCH] ?: false }

    override suspend fun setWatchEnabled(value: Boolean) {
        context.dataStore.edit { it[WATCH] = value }
    }

    /**
     * Sessions already notified about, so the background poll fires on the
     * transition into needing-you rather than every 15 minutes forever.
     */
    override val notifiedSessions: Flow<Set<String>> = prefs.map { it[NOTIFIED] ?: emptySet() }

    /**
     * The pinned routes, the active one and the auto-switch flag.
     *
     * ⚠ MIGRATION HAPPENS ON READ, not at construction: DataStore has no "open
     * and upgrade" moment, and a migration run from a coroutine somewhere would
     * race the first background worker that asks for an address. [routeBookFrom] is
     * pure, so every reader — foreground or worker, before or after the first
     * write — computes the same book from the same stored bytes.
     */
    override val routeBook: Flow<RouteBook> = prefs.map { routeBookFrom(it) }

    /**
     * The per-route health the last resolution learned.
     *
     * ⚠ NOT WRITTEN BY [setRouteBook]. The book is the owner's preference and
     * changes when they edit it; this changes on every probe, and folding the two
     * into one write would mean a background sweep republishing the book — which
     * is what `applyBook` reconnects on.
     */
    override val routeHealth: Flow<Map<String, RouteHealth>> = prefs.map { routeHealthFrom(it) }

    override suspend fun setRouteHealth(value: Map<String, RouteHealth>, atMs: Long) {
        val encoded = RouteHealthSnapshot.encode(value, atMs)
        if (prefs.first()[ROUTE_HEALTH] == encoded) return
        context.dataStore.edit { putRouteHealth(it, value, atMs) }
    }

    /**
     * Writes the book AND the address it derives, in one edit. One edit matters:
     * DataStore publishes each `edit` as its own emission, and a worker that
     * woke between two of them would build a client for a route the list no
     * longer holds.
     */
    override suspend fun setRouteBook(value: RouteBook) {
        val book = value.normalized()
        context.dataStore.edit { putRouteBook(it, book) }
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
    override val runningChats: Flow<Set<String>> = prefs.map { it[RUNNING_CHATS] ?: emptySet() }

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
        prefs.map { SettingsCodec.decodeDrafts(it[DRAFTS]) }

    override suspend fun setDrafts(value: Map<String, String>) {
        val encoded = SettingsCodec.encodeDrafts(value)
        context.dataStore.edit { it[DRAFTS] = encoded }
    }

    /**
     * ⚠ THE READER'S PLACE, ACROSS A COLD START (P-35). `rememberSaveable` holds
     * it across a fold and a rotate and loses it to a force-stop, a low-memory
     * kill and a reboot — which on a phone is most of the ways an app closes.
     * See [HuginnSettings.lastOpenSession] for why the restore then waits for
     * the first sessions fetch before it acts on this.
     */
    override val lastOpenSession: Flow<String> =
        prefs.map { it[LAST_OPEN_SESSION].orEmpty() }

    override suspend fun setLastOpenSession(value: String) {
        context.dataStore.edit { it[LAST_OPEN_SESSION] = value }
    }
}
