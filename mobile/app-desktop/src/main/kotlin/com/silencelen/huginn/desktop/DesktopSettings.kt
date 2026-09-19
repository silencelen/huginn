package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.HuginnSettings
import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.data.RouteGuard
import com.silencelen.huginn.data.SettingsCodec
import com.silencelen.huginn.desktop.device.Unenrol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

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
        /**
         * ⚠ DERIVED FROM THE ACTIVE ROUTE, and it stays — [AppStore] reads it
         * synchronously to build the client before anything has collected a
         * flow, and an older build rolled back onto this file would find the
         * address exactly where it left it.
         *
         * ⚠ AND THE DEFAULT IS EMPTY, which it was not: it was the tailnet
         * address, and that made a BRAND NEW install indistinguishable from a
         * pre-routes one — both read the same string, so a fresh desktop would
         * migrate itself two pins it had never been told about and start dialling
         * one of them. A file that has been saved once always carries this key
         * (`encodeDefaults = true`), so an upgrade still migrates; an absent file
         * now honestly says nothing is pinned.
         */
        val baseUrl: String = "",
        /**
         * PLAINTEXT, and this is the one thing the Electron client does better:
         * it puts the token through `safeStorage` (libsecret on Linux) and falls
         * back to plaintext with a visible flag. There is no equivalent in a
         * plain JVM without dragging in a keyring binding, so the file is mode
         * 0600 and this comment is the honest label. Phase 4 owns the keyring;
         * pretending to encrypt in the meantime would be worse than saying so.
         */
        val token: String = "",
        /** Pre-routes. Read once, to migrate; never written again. */
        val routePinned: Boolean = false,
        /**
         * The pinned routes, [SettingsCodec]-encoded — the same JSON array the
         * phone writes into `pinned_routes`.
         *
         * ⚠ EMPTY STRING MEANS "NEVER WRITTEN", which is what triggers the
         * migration from [baseUrl] + [routePinned]. `"[]"` is a different answer
         * — a book somebody emptied — and confusing the two would re-seed the
         * built-ins on every launch after the owner deleted them.
         */
        val pinnedRoutes: String = "",
        val activeRouteId: String = "",
        val autoSwitch: Boolean = true,
        /** The first-launch local-AI offer card: shown once, dismissed forever. */
        val localOfferSeen: Boolean = false,
        val clientId: String = "",
        val notifyEnabled: Boolean = true,
        val watchEnabled: Boolean = true,
        val watchSeeded: Boolean = false,
        val notifiedSessions: List<String> = emptyList(),
        val runningChats: List<String> = emptyList(),
        /** Encoded by [SettingsCodec] rather than as a native map: same bytes as the phone writes. */
        val chatRuns: String = "",
        val drafts: String = "",
        /** Sent-message history per target, for Up/Down recall (SettingsCodec-encoded). */
        val sentHistory: String = "",
        val lastContactAt: Long = 0,
        val lastAlarmAt: Long = 0,
        val lastWatchError: String = "",
        val lastWatchErrorAt: Long = 0,
        /**
         * Desktop-only, and not part of [HuginnSettings]: the phone has no window
         * to close. Default true because the whole point of the always-on layer is
         * that closing the window does not stop the watch stream — but it is a
         * SETTING rather than a rule, because an app that will not close is an app
         * the owner cannot get rid of.
         */
        val closeToTray: Boolean = true,

        /**
         * STARTS WITH THE SESSION. Off by default, like every other thing this
         * app does to the machine it is installed on — a client that adds itself
         * to somebody's login without being asked is a client they uninstall.
         *
         * The FLAG is what the owner chose; the Startup shortcut / `.desktop`
         * file is only its effect, and the two are reconciled at launch
         * ([com.silencelen.huginn.desktop.setup.Autostart.reconcile]) because the
         * file can go without this app being told — an upgrade that replaced the
         * launcher, a restored profile, the desktop's own Startup editor.
         */
        val autostart: Boolean = false,

        /**
         * HOW FAR THROUGH FIRST-RUN SETUP THIS INSTALL GOT, as one
         * [com.silencelen.huginn.settings.SetupFlow]-encoded string.
         *
         * Persisted because this window CLOSES TO THE TRAY rather than quitting,
         * so "halfway through setup" is a state that lasts days rather than
         * minutes — and starting somebody over at the address they typed on
         * Tuesday is how a flow gets abandoned. Unreadable content reads as no
         * progress rather than as a throw; see `SetupFlow.decode`.
         */
        val setupProgress: String = "",

        /**
         * The flow has been to its end once. What this gates is only whether it
         * OPENS ITSELF — "Run setup again" is always available, and is
         * idempotent and never destructive, which is the property that makes it
         * safe to press on a working install.
         */
        val setupDone: Boolean = false,

        /**
         * THIS MACHINE AS A DEVICE. Off by default and it must stay that way: this
         * is the switch that lets another machine run commands here, and a feature
         * that arrives already on is a feature nobody consented to.
         *
         * The scope is stored as its wire word rather than an enum ordinal, so a
         * file written by a build that knew about a fourth scope still reads as
         * something — and DevicePolicy.parse turns anything it does not recognise
         * into the narrowest one rather than the widest.
         */
        val deviceEnabled: Boolean = false,
        val deviceId: String = "",
        /**
         * The toggle went off and the daemon has NOT yet confirmed the row is
         * gone. Persisted rather than held in memory because the case it exists
         * for is exactly the one where the app is closed before the daemon can be
         * reached — a laptop switched off after being retired. See [Unenrol].
         */
        val deviceUnenrolPending: Boolean = false,
        val deviceScope: String = "look",
        /**
         * Whether a lock screen withdraws Act on this machine — "Keep act mode
         * while locked".
         *
         * ⚠ FALSE IS THE DEFAULT AND THE DEFAULT IS THE FEATURE. This decides
         * whether a remote request may become Bash on a computer with nobody in
         * front of it, so a settings file that predates the field — and one that
         * fails to parse — must read as no. It is a standing answer about ONE
         * machine, which is why it is persisted here rather than asked for, and
         * why nothing the daemon sends can set it.
         */
        val deviceActWhileLocked: Boolean = false,
        /** Where a `work`-scoped run starts. Not a sandbox — see DevicePolicy. */
        val deviceRoot: String = "",
        /**
         * An explicit path to the CLI, for the case PATH does not carry it — a
         * Windows app launched from a shortcut does not always inherit the shell's
         * PATH, and "could not start claude" with no way to point at it is a dead
         * end rather than a bug report.
         */
        val deviceClaudePath: String = "",

        /**
         * WHERE THE WINDOW WAS. Desktop-only for the obvious reason, and worth
         * persisting for a less obvious one: this client is always-on and hides to
         * the tray, so "restart" is rare and a window that reopens 1280×840 in the
         * middle of the screen is a window the owner re-places by hand on exactly
         * the occasions they are already annoyed — after a crash, after an update.
         *
         * -1 for x/y means "never placed": the window manager centres it, which is
         * the right first-run answer and cannot be expressed as a coordinate.
         */
        val windowX: Int = -1,
        val windowY: Int = -1,
        val windowW: Int = WindowLayout.DEFAULT_W,
        val windowH: Int = WindowLayout.DEFAULT_H,
        val windowMaximized: Boolean = false,

        /** The list/detail seam, in dp. See [Splitter.clamp]. */
        val listWidth: Float = Splitter.DEFAULT,

        /**
         * THE LIST PANE, SHUT. One flag for the whole frame rather than one per
         * view: "I want the width" is a statement about this window and this
         * desk, not about chats-as-opposed-to-sessions, and a per-view version
         * would mean walking the rail to find which of them is still holding a
         * column you thought you had closed.
         *
         * Kept SEPARATE from [listWidth] on purpose — collapsing must not move
         * the width, or the way back would arrive at a default nobody chose
         * instead of at the pane they had dragged. Default false: a client whose
         * list is missing on first launch is a client with no visible navigation.
         */
        val listCollapsed: Boolean = false,

        /**
         * WHERE THE WINDOW WAS LOOKING. Desktop-only, same argument as the window
         * geometry above: this client hides to the tray rather than quitting, so a
         * relaunch usually follows an update or a crash — the two occasions where
         * being put back on the wrong screen is most annoying.
         *
         * Empty means "never recorded", which [Landing.parse] reads as its own
         * default rather than as Chats. That is what makes an install that predates
         * this field open on Sessions.
         */
        val lastView: String = "",
        val lastChatId: String = "",
        val lastSessionName: String = "",

        /**
         * WHICH SETTINGS DRAWER WAS OPEN. Nine categories means a list you arrive
         * at rather than a scroll you land in the middle of, and a two-pane frame
         * that always opened on the first one would send the reader back to *Host
         * & sign-in* every time they glanced away from *Usage*.
         *
         * Empty is the honest first-run answer and reads as "no opinion" — the
         * frame lands on the first category that exists for this host, which is
         * also what happens when a remembered one stops existing (a daemon that
         * dropped /v1/headroom while Usage was open).
         *
         * ⚠ THIS IS NOT A LANDING. [Landing.persistable] still refuses to reopen
         * the window into Settings at all; what is remembered here is only WHICH
         * drawer, for when Settings is opened on purpose.
         */
        val settingsSection: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /**
     * Guards the read-modify-write below. Compose event handlers, the poll loop
     * and the watch loop all mutate settings from different coroutines, and a
     * lost update here is a lost token.
     */
    private val lock = Any()
    private var stored: Stored = load()

    /**
     * Migration happens HERE, once, at construction — the desktop store does
     * have an open-and-upgrade moment where the phone's DataStore does not, so
     * the book is settled before the first reader asks for an address.
     */
    private val _routeBook = MutableStateFlow(
        SettingsCodec.decodeRoutes(stored.pinnedRoutes)
            ?.let {
                RouteBook(
                    routes = it,
                    activeId = stored.activeRouteId.takeIf { id -> id.isNotBlank() },
                    autoSwitch = stored.autoSwitch,
                ).normalized()
            }
            ?: AppdRoutes.migrate(stored.baseUrl, stored.routePinned)
    )
    private val _baseUrl = MutableStateFlow(_routeBook.value.activeUrl)
    private val _token = MutableStateFlow(stored.token)
    private val _notifyEnabled = MutableStateFlow(stored.notifyEnabled)
    private val _watchEnabled = MutableStateFlow(stored.watchEnabled)
    private val _watchSeeded = MutableStateFlow(stored.watchSeeded)
    private val _notifiedSessions = MutableStateFlow(stored.notifiedSessions.toSet())
    private val _runningChats = MutableStateFlow(stored.runningChats.toSet())
    private val _chatRuns = MutableStateFlow(SettingsCodec.decodeChatRuns(stored.chatRuns))
    private val _drafts = MutableStateFlow(SettingsCodec.decodeDrafts(stored.drafts))
    private val _sentHistory = MutableStateFlow(SettingsCodec.decodeSentHistory(stored.sentHistory))
    private val _lastContactAt = MutableStateFlow(stored.lastContactAt)
    private val _lastAlarmAt = MutableStateFlow(stored.lastAlarmAt)
    private val _lastWatchError = MutableStateFlow(stored.lastWatchError)
    private val _lastWatchErrorAt = MutableStateFlow(stored.lastWatchErrorAt)
    private val _closeToTray = MutableStateFlow(stored.closeToTray)
    private val _autostart = MutableStateFlow(stored.autostart)
    private val _deviceEnabled = MutableStateFlow(stored.deviceEnabled)
    private val _deviceUnenrolPending = MutableStateFlow(stored.deviceUnenrolPending)
    private val _deviceScope = MutableStateFlow(stored.deviceScope)
    private val _deviceActWhileLocked = MutableStateFlow(stored.deviceActWhileLocked)
    private val _deviceRoot = MutableStateFlow(stored.deviceRoot)
    private val _deviceClaudePath = MutableStateFlow(stored.deviceClaudePath)
    private val _windowLayout = MutableStateFlow(
        WindowLayout(stored.windowX, stored.windowY, stored.windowW, stored.windowH, stored.windowMaximized)
    )
    private val _listWidth = MutableStateFlow(Splitter.clamp(stored.listWidth))
    private val _listCollapsed = MutableStateFlow(stored.listCollapsed)

    init {
        if (stored.clientId.isEmpty()) mutate { it.copy(clientId = "desktop-kt-${UUID.randomUUID()}") }
        if (stored.token.isEmpty()) bootstrapDevToken()
    }

    // ------------------------------------------------------ HuginnSettings

    override val baseUrl: Flow<String> = _baseUrl.asStateFlow()
    override val token: Flow<String> = _token.asStateFlow()
    override val routeBook: Flow<RouteBook> = _routeBook.asStateFlow()
    /**
     * ⚠ NOT A DESKTOP SETTING, and the flow is a constant on purpose.
     *
     * Terminal text size is the phone's pinch-zoom (`TerminalScreen.kt`). On this
     * client it was persisted, clamped and flowed with ZERO readers and ZERO
     * writers — a settings key nothing could ever change, which the redesign's
     * inventory found while counting what Settings actually holds. The contract
     * stays (the phone's implementation of it is real); the dead plumbing behind
     * it does not.
     */
    override val fontScale: Flow<Float> = MutableStateFlow(HuginnSettings.DEFAULT_FONT_SCALE).asStateFlow()
    override val notifyEnabled: Flow<Boolean> = _notifyEnabled.asStateFlow()
    override val watchEnabled: Flow<Boolean> = _watchEnabled.asStateFlow()
    override val watchSeeded: Flow<Boolean> = _watchSeeded.asStateFlow()
    override val notifiedSessions: Flow<Set<String>> = _notifiedSessions.asStateFlow()
    override val runningChats: Flow<Set<String>> = _runningChats.asStateFlow()
    override val chatRuns: Flow<Map<String, Long>> = _chatRuns.asStateFlow()
    override val drafts: Flow<Map<String, String>> = _drafts.asStateFlow()
    override val sentHistory: Flow<Map<String, List<String>>> = _sentHistory.asStateFlow()
    override val lastContactAt: Flow<Long> = _lastContactAt.asStateFlow()
    override val lastAlarmAt: Flow<Long> = _lastAlarmAt.asStateFlow()
    override val lastWatchError: Flow<String> = _lastWatchError.asStateFlow()
    override val lastWatchErrorAt: Flow<Long> = _lastWatchErrorAt.asStateFlow()

    /**
     * Writes the book AND the address it derives, in one mutation — so nothing
     * can read a `baseUrl` belonging to a route the list no longer holds.
     *
     * The guard already ran: [RouteBook] refuses an address [RouteGuard] will
     * not have, so by the time a book arrives here every URL in it is one this
     * client is allowed to send a bearer to.
     */
    override suspend fun setRouteBook(value: RouteBook) {
        val book = value.normalized()
        _routeBook.value = book
        _baseUrl.value = book.activeUrl
        mutate {
            it.copy(
                pinnedRoutes = SettingsCodec.encodeRoutes(book.routes),
                activeRouteId = book.activeId.orEmpty(),
                autoSwitch = book.autoSwitch,
                baseUrl = book.activeUrl,
            )
        }
    }

    override suspend fun setToken(value: String) {
        val next = value.trim()
        _token.value = next
        mutate { it.copy(token = next) }
    }

    private val _localOfferSeen = kotlinx.coroutines.flow.MutableStateFlow(stored.localOfferSeen)
    val localOfferSeen: Flow<Boolean> = _localOfferSeen.asStateFlow()
    fun localOfferSeenNow(): Boolean = _localOfferSeen.value

    suspend fun setLocalOfferSeen() {
        _localOfferSeen.value = true
        mutate { it.copy(localOfferSeen = true) }
    }

    override suspend fun clientId(): String = synchronized(lock) { stored.clientId }

    /** No-op here — see [fontScale]. The desktop has no terminal text size. */
    override suspend fun setFontScale(value: Float) = Unit

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

    override suspend fun setSentHistory(value: Map<String, List<String>>) {
        _sentHistory.value = value
        mutate { it.copy(sentHistory = SettingsCodec.encodeSentHistory(value)) }
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

    /**
     * Close-to-tray. Desktop-only, so it is not on the [HuginnSettings] contract,
     * and its setter is NOT suspend: it is toggled from a tray menu item, which
     * has no coroutine scope of its own and no reason to acquire one to write a
     * boolean into a file that is already held in memory.
     */
    val closeToTray: StateFlow<Boolean> = _closeToTray.asStateFlow()

    // ------------------------------------------------- this machine as a device
    //
    // Read by DeviceRunner on a loop rather than collected: it is a long-lived
    // background loop, not a composition, and `*Now()` keeps the reads honest
    // about being point-in-time.

    val deviceEnabled: StateFlow<Boolean> = _deviceEnabled.asStateFlow()
    val deviceUnenrolPending: StateFlow<Boolean> = _deviceUnenrolPending.asStateFlow()
    val deviceScope: StateFlow<String> = _deviceScope.asStateFlow()
    val deviceActWhileLocked: StateFlow<Boolean> = _deviceActWhileLocked.asStateFlow()
    val deviceRoot: StateFlow<String> = _deviceRoot.asStateFlow()
    val deviceClaudePath: StateFlow<String> = _deviceClaudePath.asStateFlow()

    fun deviceEnabledNow(): Boolean = _deviceEnabled.value
    fun deviceUnenrolPendingNow(): Boolean = _deviceUnenrolPending.value
    fun deviceScopeNow(): String = _deviceScope.value
    fun deviceActWhileLockedNow(): Boolean = _deviceActWhileLocked.value
    fun deviceRootNow(): String = _deviceRoot.value
    fun deviceClaudePathNow(): String = _deviceClaudePath.value
    fun deviceIdNow(): String = synchronized(lock) { stored.deviceId }

    /**
     * Turns the offer on or off. Off ALSO records what is still owed to the
     * daemon — the row it enrolled has to be deleted, and only the stored id can
     * do it, so the switch and the debt are written together rather than left to
     * two callers to keep in step. See [Unenrol] for the ordering.
     */
    fun setDeviceEnabled(value: Boolean) {
        _deviceEnabled.value = value
        if (value) {
            // Turning it back ON withdraws the debt: the runner is about to
            // re-enrol with this same id, so deleting the row would retire the
            // enrolment that is being used right now.
            _deviceUnenrolPending.value = false
            mutate { it.copy(deviceEnabled = true, deviceUnenrolPending = false) }
        } else {
            val owed = Unenrol.owesUnenrol(deviceIdNow())
            _deviceUnenrolPending.value = owed
            mutate { it.copy(deviceEnabled = false, deviceUnenrolPending = owed) }
        }
    }

    /** The debt is settled: the daemon confirmed the row is gone (or never had one). */
    fun clearDeviceUnenrolPending() {
        _deviceUnenrolPending.value = false
        mutate { it.copy(deviceUnenrolPending = false) }
    }

    fun setDeviceScope(value: String) {
        _deviceScope.value = value
        mutate { it.copy(deviceScope = value) }
    }

    /**
     * ⚠ SET HERE AND NOWHERE ELSE. Widening what a device will do requires
     * touching the device — there is deliberately no route, no push and no
     * remote verb that reaches this, for the same reason the scope has none.
     */
    fun setDeviceActWhileLocked(value: Boolean) {
        _deviceActWhileLocked.value = value
        mutate { it.copy(deviceActWhileLocked = value) }
    }

    fun setDeviceRoot(value: String) {
        _deviceRoot.value = value
        mutate { it.copy(deviceRoot = value) }
    }

    fun setDeviceClaudePath(value: String) {
        _deviceClaudePath.value = value
        mutate { it.copy(deviceClaudePath = value) }
    }

    /**
     * The enrolment id the daemon gave this machine. Persisted so a restart
     * re-enrols as the SAME device instead of leaving a ghost in the list.
     *
     * ⚠ Clearing it (`""`) THROWS AWAY THE ONLY HANDLE that can retire this
     * machine's row, so it is done in exactly one place — after the daemon has
     * confirmed the DELETE. See [Unenrol].
     */
    fun setDeviceId(value: String) {
        mutate { it.copy(deviceId = value) }
    }

    /**
     * Back to the connect screen: the token this app authenticates with, the
     * enrolment handle, and the half-written messages go.
     *
     * SERVER-FIRST is the caller's job, not this one's — by the time this runs
     * the rows are already retired at the daemon, because a token dropped first
     * cannot retire anything afterwards. That ordering is the whole reason this
     * is one call rather than three: nothing here should be reachable from a
     * path that failed halfway.
     *
     * The base URL stays. It is an address rather than a credential, and the
     * connect screen would only ask for the same one back. (A from-source run on
     * the daemon's own host re-reads the dev token at next launch — see
     * [bootstrapDevToken] — which is a development convenience and does not
     * apply to a packaged build.)
     */
    fun clearForRemoval() {
        _token.value = ""
        _drafts.value = emptyMap()
        _deviceEnabled.value = false
        _deviceUnenrolPending.value = false
        // A standing permission does not survive the machine being handed back.
        // Leaving it set would mean the next enrolment on this box — possibly by
        // somebody else — silently began with the lock rule already waived.
        _deviceActWhileLocked.value = false
        mutate {
            it.copy(
                token = "",
                drafts = "",
                deviceId = "",
                deviceEnabled = false,
                deviceUnenrolPending = false,
                deviceActWhileLocked = false,
            )
        }
    }

    fun setCloseToTray(value: Boolean) {
        _closeToTray.value = value
        mutate { it.copy(closeToTray = value) }
    }

    fun closeToTrayNow(): Boolean = _closeToTray.value

    // ------------------------------------------------------- setup + autostart
    //
    // Same shape as close-to-tray and for the same reasons: desktop-only (the
    // phone has no login session and no first-run flow yet), and NOT suspend,
    // because both are written from a click on a row and from a step of a flow
    // that owns no coroutine scope worth acquiring to set a boolean already in
    // memory.

    val autostart: StateFlow<Boolean> = _autostart.asStateFlow()

    fun autostartNow(): Boolean = _autostart.value

    /**
     * Records the CHOICE. Writing the Startup shortcut or the `.desktop` file is
     * [com.silencelen.huginn.desktop.setup.Autostart]'s job and is deliberately
     * NOT done here: this store is read synchronously at construction by code
     * that must not spawn a PowerShell, and a settings setter that shells out is
     * a settings setter that can hang the window.
     */
    fun setAutostart(value: Boolean) {
        _autostart.value = value
        mutate { it.copy(autostart = value) }
    }

    /** The encoded flow, or empty for an install that has never run it. */
    fun setupProgressNow(): String = synchronized(lock) { stored.setupProgress }

    fun setSetupProgress(value: String) {
        synchronized(lock) {
            if (stored.setupProgress == value) return
            mutate { it.copy(setupProgress = value) }
        }
    }

    fun setupDoneNow(): Boolean = synchronized(lock) { stored.setupDone }

    fun setSetupDone(value: Boolean) {
        synchronized(lock) {
            if (stored.setupDone == value) return
            mutate { it.copy(setupDone = value) }
        }
    }

    // ------------------------------------------------------- window + layout
    //
    // Both are written on every change and both are cheap to write (the file is a
    // token, a URL and a handful of counters), but a DRAG is not one change — it
    // is one per frame. The callers debounce; see Main.kt's window watcher and the
    // splitter in Shell.kt. Persisting per frame would rewrite this file sixty
    // times a second on a resize, which is the one way a settings file that also
    // holds the token gets corrupted.

    val windowLayout: StateFlow<WindowLayout> = _windowLayout.asStateFlow()

    fun setWindowLayout(value: WindowLayout) {
        if (value == _windowLayout.value) return
        _windowLayout.value = value
        mutate {
            it.copy(
                windowX = value.x,
                windowY = value.y,
                windowW = value.w,
                windowH = value.h,
                windowMaximized = value.maximized,
            )
        }
    }

    /** The list/detail seam, in dp. Always inside [Splitter]'s bounds. */
    val listWidth: StateFlow<Float> = _listWidth.asStateFlow()

    fun setListWidth(value: Float) {
        val next = Splitter.clamp(value)
        if (next == _listWidth.value) return
        _listWidth.value = next
        mutate { it.copy(listWidth = next) }
    }

    /** Drag, in dp of pointer travel. Clamped, so the seam stops rather than runs. */
    fun nudgeListWidth(delta: Float) = setListWidth(_listWidth.value + delta)

    /** Keyboard adjust: one coarse step. A drag is what fine adjustment is for. */
    fun widenList() = nudgeListWidth(Splitter.STEP)
    fun narrowList() = nudgeListWidth(-Splitter.STEP)
    fun resetListWidth() = setListWidth(Splitter.DEFAULT)

    /**
     * Whether the list pane is shut. Persisted, because a pane you have to close
     * again on every launch is a pane you stop closing — the same argument the
     * width has made since the seam existed.
     *
     * NOT suspend, like the window geometry and for the same reason: it is
     * written from a click on the notch and from the window's key handler,
     * neither of which has a coroutine scope worth acquiring to set a boolean
     * that is already in memory. It is also not per-frame, so it does not need
     * the debounce the drag does.
     */
    val listCollapsed: StateFlow<Boolean> = _listCollapsed.asStateFlow()

    fun listCollapsedNow(): Boolean = _listCollapsed.value

    fun setListCollapsed(value: Boolean) {
        if (value == _listCollapsed.value) return
        _listCollapsed.value = value
        mutate { it.copy(listCollapsed = value) }
    }

    fun toggleListCollapsed() = setListCollapsed(!_listCollapsed.value)

    // ---------------------------------------------------------- landing
    //
    // Read synchronously at construction, which is what lets the store pick its
    // opening view without a suspend — the window composes before any coroutine
    // this app launches has run, and a view that snapped from Chats to Sessions a
    // frame later would be worse than never restoring it.

    fun lastViewNow(): View = Landing.parse(synchronized(lock) { stored.lastView })
    fun lastChatIdNow(): String? = synchronized(lock) { stored.lastChatId }.takeIf { it.isNotEmpty() }
    fun lastSessionNameNow(): String? =
        synchronized(lock) { stored.lastSessionName }.takeIf { it.isNotEmpty() }

    /**
     * Records the position. NOT suspend and NOT written per navigation event — the
     * caller debounces, because Alt+↓ down a session list is one of these per key
     * repeat and this file also holds the token.
     *
     * A non-[Landing.persistable] view is dropped rather than stored: see the note
     * on [Landing]. The ids are still recorded in that case, so glancing at
     * Settings does not forget which session was open behind it.
     */
    fun setLanding(view: View, chatId: String?, sessionName: String?) {
        val chat = chatId.orEmpty()
        val session = sessionName.orEmpty()
        // One critical section for the read AND the write: `mutate` takes the same
        // (reentrant) lock, and a compare-then-write split across two of them is
        // how the last writer wins with the wrong value.
        synchronized(lock) {
            val encoded = if (Landing.persistable(view)) Landing.encode(view) else stored.lastView
            if (stored.lastView == encoded &&
                stored.lastChatId == chat &&
                stored.lastSessionName == session
            ) return
            mutate { it.copy(lastView = encoded, lastChatId = chat, lastSessionName = session) }
        }
    }

    /**
     * The Settings drawer that was open. Read synchronously for the same reason
     * the landing is: the pane composes before any coroutine has run, and a list
     * that snapped from *Host* to *Usage* a frame later would be worse than not
     * remembering at all.
     */
    fun settingsSectionNow(): String = synchronized(lock) { stored.settingsSection }

    /**
     * Records it. NOT suspend, like the window geometry: it is written from a
     * click on a category row, which has no coroutine scope worth acquiring to
     * set a string that is already in memory — and unlike the seam it is one
     * write per navigation rather than one per frame.
     */
    fun setSettingsSection(value: String) {
        synchronized(lock) {
            if (stored.settingsSection == value) return
            mutate { it.copy(settingsSection = value) }
        }
    }

    fun baseUrlNow(): String = _baseUrl.value
    fun tokenNow(): String = _token.value
    fun clientIdNow(): String = synchronized(lock) { stored.clientId }
    fun notifyEnabledNow(): Boolean = _notifyEnabled.value
    fun routeBookNow(): RouteBook = _routeBook.value
    val tokenState: StateFlow<String> get() = _token.asStateFlow()

    /** Where the settings live, for the Settings screen to show. */
    val path: String get() = file.absolutePath

    // ------------------------------------------------------------ plumbing

    private fun load(): Stored = runCatching {
        json.decodeFromString(Stored.serializer(), file.readText())
    }.getOrElse {
        // First run, or a file half-written by a killed process. Refusing to
        // launch is not an option — but neither is quietly starting fresh on top
        // of a file that still holds the token. If there was ANYTHING here, keep
        // a copy: the next save would otherwise overwrite the only record of it,
        // and "my token vanished" is unanswerable without one.
        if (file.exists() && file.length() > 0L) {
            runCatching {
                val salvage = file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
                // ⚠ AND AS PRIVATE AS WHAT IT COPIED. Kotlin's copyTo is delete +
                // stream copy, so the new file takes the process UMASK rather than
                // the source's mode: 0644 at the usual 022, in a 0755 config dir,
                // beside a deliberately 0600 original — carrying the same
                // plaintext root-equivalent daemon bearer, with nothing in the app
                // that ever removes or re-restricts it.
                restrictToOwner(salvage)
            }
        }
        Stored()
    }

    private fun mutate(block: (Stored) -> Stored) {
        synchronized(lock) {
            stored = block(stored)
            save(stored)
        }
    }

    /**
     * Write, then swap. The swap is [Files.move], NOT [File.renameTo].
     *
     * THIS COST THE OWNER HIS TOKEN. `File.renameTo` is documented as platform
     * dependent and on Windows it does NOT replace an existing destination — it
     * simply returns false. So the first save (no file yet) worked and every save
     * after it silently did nothing, because the boolean result was ignored and
     * the whole block sat inside a `runCatching`. On Linux the same code is
     * correct, which is exactly why no amount of testing here would have found
     * it. `Files.move(REPLACE_EXISTING)` is correct on both.
     *
     * The failure is no longer swallowed either: a settings file that cannot be
     * written is worth a line in the log, since the alternative is a person
     * typing a token in three times and being told nothing.
     */
    private fun save(value: Stored) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(Stored.serializer(), value))
            restrictToOwner(tmp)
            try {
                Files.move(
                    tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Some filesystems (and some network mounts) cannot do it
                // atomically. A replaced file beats a file that never changes.
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            restrictToOwner(file)
        } catch (t: Throwable) {
            System.err.println("[huginn] could not write settings to ${file.absolutePath}: $t")
        }
    }

    /**
     * Owner-only, without making the file read-only.
     *
     * `setWritable(false, false)` sets the READ-ONLY ATTRIBUTE on Windows rather
     * than clearing a group/other bit, which is the second half of how the token
     * was lost: a read-only destination cannot be replaced. On Windows the file
     * already sits under the user's own profile, so the POSIX dance is skipped
     * entirely rather than being run for a permission model that is not there.
     */
    private fun restrictToOwner(f: File) {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
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

        /**
         * ⚠ THE RULE MOVED TO `:core`, AND THAT IS THE POINT.
         *
         * This used to be four literal hosts — the tailnet address, the VLAN-2
         * address, `localhost` and `127.0.0.1` — which cannot survive routes the
         * owner adds themselves. What could not be allowed to move with it is
         * the REASON: one bearer token follows the base URL on every request, so
         * an unvalidated address field hands a root-equivalent daemon token to
         * whoever owns the address. [RouteGuard] keeps that reason and replaces
         * the list with a shape rule, and — the real prize — the PHONE is now
         * behind it too, having had no guard at all.
         *
         * Kept as aliases because the refusal string is shown verbatim in the UI
         * and this store's own tests name it.
         */
        const val REFUSED: String = RouteGuard.REFUSED

        fun isAllowedBaseUrl(raw: String): Boolean = RouteGuard.isAllowed(raw)

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
