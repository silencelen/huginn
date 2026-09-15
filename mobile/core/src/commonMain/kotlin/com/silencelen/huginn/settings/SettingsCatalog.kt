package com.silencelen.huginn.settings

/**
 * ONE list of every setting this product has, in :core, so the two shells stop
 * disagreeing about what settings exist.
 *
 * The complaint this answers is not "the settings look wrong", it is "we keep
 * adding different options and sections": 82 interactive controls and ~54
 * read-only rows across two flat scrolls, with account switching rendered three
 * times in three vocabularies and six settings-shaped controls living outside
 * Settings entirely. A flat screen cannot be told it is getting crowded. A
 * catalog can — [SettingsCatalogTest] fails the moment a control is added
 * twice, lands in no category, or leaves a category empty on a shell.
 *
 * WHAT THIS IS NOT. It is not a renderer and holds no Compose import: an item
 * carries its identity, its words and the question "is this reachable right
 * now", and the shell draws whatever control that setting actually is. It is
 * also not a registry the shells read to build themselves — a toggle still
 * needs its state and its callback, which only a shell has. What the catalog
 * owns is the IA: which nine drawers exist, what is in each, in what order, and
 * what makes a row disappear.
 *
 * ⚠ ADDING A SETTING MEANS ADDING AN ITEM HERE FIRST. A control drawn by a shell
 * with no item in this file is invisible to search and to the summary line, and
 * that is the state the redesign was called in to fix.
 */

/**
 * Which shell draws a control.
 *
 * As a property of an item it says where the control exists. As the argument to
 * [SettingsCatalog.visibleCategories] it is a QUESTION, and [Surface.BOTH] asks
 * a narrower one than it looks: "the universal half only" — the rows both
 * shells draw — rather than "everything either shell draws". That is the useful
 * question (it is what a shared test, a shared doc or a shared summary wants),
 * and the union has no caller.
 */
enum class Surface { PHONE, DESKTOP, BOTH }

/**
 * Every fact the catalog may ask about the world before deciding a row exists.
 *
 * NULLABLE-WITH-DEFAULT, like the wire models: a field that is absent reads as
 * "not there" rather than throwing, so an older daemon loses a row instead of
 * opening a category onto an apology. The defaults are deliberately the
 * PESSIMISTIC answers — a shell that forgets to fill one hides a setting, which
 * is recoverable, rather than showing one that cannot work, which is not.
 *
 * @param headroom `/v1/headroom` answered (not 404) — the whole 3.0 usage tier.
 * @param quickActions `status.quickActions != null` — the daemon holds the
 *   composer templates AND the soft-end phrase on that same shelf.
 * @param alerts `/v1/alerts` answered — huginn knows how to reach you at all.
 * @param padsAvailable the host serves scratchpads. Nullable because a daemon
 *   that predates them says nothing rather than "no"; no catalog row reads it
 *   yet, and it stays because the probe's shape is agreed across the three
 *   implementers rather than grown per-row.
 * @param localServe this machine can serve local models (desktop only).
 * @param appLockAvailable the OS offers a device credential to lock behind.
 * @param enrolable there is a fleet to show — this client may enrol, or the
 *   host already answers `/v1/devices`.
 * @param savedAccounts how many Claude logins the host has saved. An Int rather
 *   than a Boolean because one login and two logins are different settings:
 *   rotation cannot mean anything below two.
 * @param diagnostics a diagnostics report can be produced and copied here.
 * @param selfUpdate this build has an update channel. Compile-time on both
 *   clients and deliberately never a setting (`AppStore.kt:96-104`,
 *   `DesktopUpdater.kt:52-68`), so a store build simply has no update rows.
 */
data class SettingsProbe(
    val headroom: Boolean = false,
    val quickActions: Boolean = false,
    val alerts: Boolean = false,
    val padsAvailable: Boolean? = null,
    val localServe: Boolean = false,
    val appLockAvailable: Boolean = false,
    val enrolable: Boolean = false,
    val savedAccounts: Int = 0,
    val diagnostics: Boolean = false,
    val selfUpdate: Boolean = false,
)

/**
 * One setting, named once.
 *
 * @param id stable and dotted — `host.base-url`. It is what a search hit
 *   carries, what a shell marks when arriving from one, and what the phone puts
 *   in `Dest.SettingsSection`'s saved key. It NEVER changes: renaming a title is
 *   copy, renaming an id breaks a restored destination.
 * @param title the row's label, in the shell's own words.
 * @param summary one line saying what the setting DOES. Never how the UI works
 *   — "Which address reaches huginn", not "tap to open the address editor" —
 *   and never a narration of mechanics the owner asked not to narrate.
 * @param keywords the words somebody would type looking for this that are not
 *   in the title. "tailscale" for the route; "expired" for saved logins.
 * @param surface which shell draws it.
 * @param availability asked of the live probe. False means the row is HIDDEN —
 *   not greyed — and excluded from search, because a hit that opens onto a
 *   hidden row is worse than no hit.
 * @param inventory the numbers this item covers from the redesign's Part 1
 *   inventory of the two screens (1-61). Empty for a row that has no control
 *   today: the shared `:ui` sections, the six stranded controls, and the
 *   read-only rows. [SettingsCatalogTest] asserts 1..61 appear exactly once
 *   across the catalog, which is the regression that stops the accretion.
 */
data class SettingsItem(
    val id: String,
    val title: String,
    val summary: String,
    val keywords: List<String> = emptyList(),
    val surface: Surface = Surface.BOTH,
    val availability: (SettingsProbe) -> Boolean = { true },
    val inventory: List<Int> = emptyList(),
)

/**
 * One drawer. Shown when it has at least one available item for this surface —
 * so a pre-3.0 daemon has no *Usage & headroom* row at all, rather than a
 * category that opens onto "this host has no headroom settings".
 */
data class SettingsCategory(
    val id: String,
    val title: String,
    val blurb: String,
    val surface: Surface = Surface.BOTH,
    val items: List<SettingsItem>,
)

/** True when [this] item or category is drawn by the shell [surface] asks about. */
fun Surface.matches(surface: Surface): Boolean = this == Surface.BOTH || this == surface

object SettingsCatalog {

    // ------------------------------------------------------------------ host

    private val host = SettingsCategory(
        id = "host",
        title = "Host & sign-in",
        blurb = "Which huginn this app talks to, and which Claude login serves it.",
        items = listOf(
            SettingsItem(
                id = "host.route",
                title = "Route",
                summary = "Which named address reaches huginn, and whether it stays pinned to that one.",
                keywords = listOf("tailscale", "yggdrasil", "network", "address", "pin", "unpin", "auto", "connection"),
                inventory = listOf(1, 2, 3),
            ),
            SettingsItem(
                id = "host.base-url",
                title = "Base URL",
                summary = "The address every request goes to.",
                keywords = listOf("server", "host", "url", "address", "connection", "allowlist"),
                inventory = listOf(4, 29, 30),
            ),
            SettingsItem(
                id = "host.token",
                title = "Token",
                summary = "The bearer this app sends with every request.",
                keywords = listOf("bearer", "secret", "auth", "password", "key"),
                inventory = listOf(5, 6, 31, 32),
            ),
            SettingsItem(
                id = "host.connect",
                title = "Save and connect",
                summary = "Stores the address and token together and reconnects with them.",
                keywords = listOf("save", "connect", "reconnect"),
                surface = Surface.PHONE,
                inventory = listOf(7),
            ),
            SettingsItem(
                id = "host.saved-logins",
                title = "Saved logins",
                summary = "The Claude logins kept on the host, which one is serving, and how usable each token is.",
                keywords = listOf("accounts", "claude", "switch", "use", "forget", "refresh", "expired", "duplicate", "freshness"),
                inventory = listOf(14, 15, 16, 33, 34, 35),
            ),
            SettingsItem(
                id = "host.sign-in",
                title = "Add a login",
                summary = "Signs another Claude account in on the host, through the browser and back.",
                keywords = listOf("sign in", "login", "add account", "authorize", "code", "browser", "email"),
                inventory = listOf(17, 36, 37, 38, 39, 40, 41),
            ),
            SettingsItem(
                id = "host.sign-out",
                title = "Sign out",
                summary = "Signs the host out of the Claude login that is serving now.",
                keywords = listOf("logout", "sign out", "claude"),
                surface = Surface.PHONE,
                availability = { it.savedAccounts > 0 },
                inventory = listOf(18),
            ),
        ),
    )

    // ----------------------------------------------------------------- usage

    /**
     * Everything here needs the 3.0 headroom endpoint, and the two switching
     * rows need somewhere to switch TO — rotation with one saved login is a
     * setting that cannot do anything.
     */
    private val headroomOn: (SettingsProbe) -> Boolean = { it.headroom }
    private val canRotate: (SettingsProbe) -> Boolean = { it.headroom && it.savedAccounts > 1 }

    private val usage = SettingsCategory(
        id = "usage",
        title = "Usage & headroom",
        blurb = "When huginn warns, when it moves a session down the ladder, and when it picks one back up.",
        items = listOf(
            SettingsItem(
                id = "usage.heads-up-pct",
                title = "Heads-up at",
                summary = "How full a window gets before huginn says so.",
                keywords = listOf("warn", "warning", "threshold", "percent", "limit"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.ladder-pct",
                title = "Move down the ladder at",
                summary = "How full a window gets before a live session is moved to the next model.",
                keywords = listOf("threshold", "percent", "downgrade", "model", "ladder"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.ladder-up-pct",
                title = "Move back up below",
                summary = "How empty a window has to be before a moved session goes back to its own model.",
                keywords = listOf("threshold", "percent", "upgrade", "restore", "ladder"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.stop-pct",
                title = "Hold new subagents at",
                summary = "How full a window gets before new subagents stop being spawned.",
                keywords = listOf("threshold", "percent", "agents", "subagent", "spawn", "hold"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.stop-fable-pct",
                title = "Hold Fable subagents at",
                summary = "The same hold, at its own level, for subagents on Fable.",
                keywords = listOf("threshold", "percent", "fable", "subagent", "spawn", "hold"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.clear-below-pct",
                title = "Treat a window as cleared below",
                summary = "How empty a window has to read before huginn counts it as reset.",
                keywords = listOf("threshold", "percent", "reset", "cleared"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.ladder",
                title = "Ladder",
                summary = "The order a live session is moved through when its window runs out.",
                keywords = listOf("models", "fable", "opus", "sonnet", "haiku", "order", "fallback"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.auto-resume",
                title = "Auto-resume",
                summary = "Whether a stopped session picks itself back up when the window resets.",
                keywords = listOf("resume", "restart", "continue", "automatic"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.resume-phrase",
                title = "What to type on resume",
                summary = "The message typed into a session when it picks itself back up.",
                keywords = listOf("resume", "phrase", "continue", "prompt"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.default-model",
                title = "Default model",
                summary = "The model a new chat starts on.",
                keywords = listOf("model", "fable", "opus", "sonnet", "default"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.heads-up-text",
                title = "Heads-up message",
                summary = "What is typed into a session before it is moved down the ladder.",
                keywords = listOf("message", "warning", "text", "handoff", "pct"),
                availability = headroomOn,
            ),
            SettingsItem(
                id = "usage.account-switch",
                title = "Account switching",
                summary = "Whether huginn rotates to another saved login when this one runs out.",
                keywords = listOf("autoswitch", "rotate", "accounts", "switch", "automatic"),
                availability = canRotate,
                inventory = listOf(13),
            ),
            SettingsItem(
                id = "usage.switch-at",
                title = "Switch at",
                summary = "How full the active login gets before huginn rotates away from it.",
                keywords = listOf("autoswitch", "threshold", "percent", "rotate"),
                availability = canRotate,
            ),
            SettingsItem(
                id = "usage.switch-margin",
                title = "Only to an account this much freer",
                summary = "How much more room another login needs before it is worth switching to.",
                keywords = listOf("autoswitch", "margin", "percent", "rotate"),
                availability = canRotate,
            ),
            SettingsItem(
                id = "usage.plan",
                title = "Plan usage",
                summary = "Where the live percentages for every saved login are shown.",
                keywords = listOf("plan", "percent", "limit", "week", "session", "status", "numbers"),
                availability = headroomOn,
            ),
        ),
    )

    // ----------------------------------------------------------------- chats

    private val chats = SettingsCategory(
        id = "chats",
        title = "Chats & sessions",
        blurb = "The wording huginn puts in the composer for you, and what it says when it stops.",
        items = listOf(
            SettingsItem(
                id = "chats.quick-actions",
                title = "Quick actions",
                summary = "What Explain, Execute and Ask in a new chat put in the composer for selected text.",
                keywords = listOf("explain", "execute", "ask", "quote", "selection", "composer", "templates", "wording"),
                availability = { it.quickActions },
                inventory = listOf(42, 43, 44, 45, 46),
            ),
            SettingsItem(
                id = "chats.soft-end",
                title = "Soft end",
                summary = "The phrase huginn types to wind a session down, and whether it does so on its own.",
                keywords = listOf("soft end", "wrap up", "finish", "phrase", "end"),
                // The same `/v1/status` shelf the templates come from.
                availability = { it.quickActions },
            ),
        ),
    )

    // ---------------------------------------------------------------- notify

    /**
     * Two named groups in one drawer: what HUGINN sends you, and what THIS
     * device does with it. The audit's finding was that the two read as the
     * same switch one word apart ("Message me when a session needs me" against
     * "Tell me when a session needs me") with nothing saying they are different
     * mechanisms, so the summaries here say which end each one lives at.
     */
    private val notify = SettingsCategory(
        id = "notify",
        title = "Notifications",
        blurb = "What huginn sends when a session needs you, and what this device does with it.",
        items = listOf(
            SettingsItem(
                id = "notify.host-alerts",
                title = "Message me when a session needs me",
                summary = "Whether huginn itself reaches out when a session is waiting on an answer.",
                keywords = listOf("alerts", "telegram", "huginn", "attention", "escalation"),
                surface = Surface.PHONE,
                availability = { it.alerts },
                inventory = listOf(8),
            ),
            SettingsItem(
                id = "notify.host-alerts-mode",
                title = "Only when the app is out of contact",
                summary = "Holds huginn's own messages back while this app is awake and watching.",
                keywords = listOf("alerts", "telegram", "fallback", "quiet", "mode"),
                surface = Surface.PHONE,
                availability = { it.alerts },
                inventory = listOf(9),
            ),
            // ⚠ The four rows below are THIS PHONE's own — its watch, its doze
            // exemption, its delivery witnesses, Android's permission. None of
            // them needs the host's /v1/alerts route, and the old screen always
            // showed them; gating them on `alerts` (as the first cut did) made an
            // old or unreachable daemon hide the switch that decides whether the
            // phone notifies at all. Only the two host-alert rows above are the
            // host's to have or lack.
            SettingsItem(
                id = "notify.device-watch",
                title = "Tell me when a session needs me",
                summary = "Whether this device raises its own notification, and whether it keeps watching between them.",
                keywords = listOf("push", "notification", "watch", "continuously", "attention"),
                surface = Surface.PHONE,
                availability = { true },
                inventory = listOf(19, 20),
            ),
            SettingsItem(
                id = "notify.background",
                title = "Background use",
                summary = "Lets Android keep this app's watch awake while the screen is off.",
                keywords = listOf("doze", "battery", "background", "android", "exemption"),
                surface = Surface.PHONE,
                availability = { true },
                inventory = listOf(21),
            ),
            SettingsItem(
                id = "notify.delivery",
                title = "Delivery details",
                summary = "Which route the last notifications actually took, and when each was last heard from.",
                keywords = listOf("push", "fcm", "delivery", "witness", "cadence", "diagnostics"),
                surface = Surface.PHONE,
                availability = { true },
                inventory = listOf(22, 23),
            ),
            SettingsItem(
                id = "notify.permission",
                title = "Notification permission",
                summary = "Whether Android lets this app show notifications at all.",
                keywords = listOf("permission", "allow", "android", "system", "blocked"),
                surface = Surface.PHONE,
                availability = { true },
                inventory = listOf(24, 25),
            ),
            SettingsItem(
                id = "notify.claim-route",
                title = "Claim the notification route",
                summary = "Sends attention here instead of Telegram while this window is attended.",
                keywords = listOf("claim", "telegram", "route", "desktop", "attended"),
                surface = Surface.DESKTOP,
                availability = { it.alerts },
                inventory = listOf(47),
            ),
        ),
    )

    // --------------------------------------------------------------- devices

    /**
     * Split by OWNERSHIP, not by screen. This drawer holds only THIS machine —
     * whether it is enrolled, what it may do, where work starts. The fleet is a
     * first-class destination on both shells and is reached from the last row,
     * which is what fixes the desktop's split (this machine in Settings, the
     * fleet elsewhere) without demoting the fleet into Settings.
     */
    private val devices = SettingsCategory(
        id = "devices",
        title = "Devices",
        blurb = "Whether huginn may run work on this machine, and what the rest of the fleet may do.",
        items = listOf(
            SettingsItem(
                id = "devices.this-machine",
                title = "Available to huginn",
                summary = "Whether huginn may run work on this computer, in its own context.",
                keywords = listOf("enrol", "enroll", "device", "runner", "machine", "work"),
                surface = Surface.DESKTOP,
                availability = { it.enrolable },
                inventory = listOf(48),
            ),
            SettingsItem(
                id = "devices.scope",
                title = "What it may do",
                summary = "Whether huginn may only read here, may change and run things, or owns the machine.",
                keywords = listOf("scope", "look", "work", "own", "permission", "lock"),
                surface = Surface.DESKTOP,
                availability = { it.enrolable },
                inventory = listOf(49),
            ),
            SettingsItem(
                id = "devices.work-folder",
                title = "Folder for Work runs",
                summary = "Where a run starts on this machine.",
                keywords = listOf("folder", "directory", "path", "root", "work", "cwd"),
                surface = Surface.DESKTOP,
                availability = { it.enrolable },
                inventory = listOf(50),
            ),
            SettingsItem(
                id = "devices.claude-path",
                title = "Path to claude",
                summary = "Which claude binary this machine runs, when PATH is not the right answer.",
                keywords = listOf("claude", "binary", "path", "executable", "cli"),
                surface = Surface.DESKTOP,
                availability = { it.enrolable },
                inventory = listOf(51),
            ),
            SettingsItem(
                id = "devices.local-ai",
                title = "Local AI",
                summary = "Whether this computer downloads and serves models locally for huginn.",
                keywords = listOf("local", "llm", "serve", "models", "kvasir", "offline", "download"),
                surface = Surface.DESKTOP,
                availability = { it.localServe },
                inventory = listOf(52, 53, 54, 55, 56, 57),
            ),
            SettingsItem(
                id = "devices.fleet",
                title = "All devices",
                summary = "The machines enrolled with huginn, what each is doing, and which may be asked for work.",
                keywords = listOf("fleet", "machines", "devices", "manage", "ask", "act", "forget"),
                availability = { it.enrolable },
                inventory = listOf(10),
            ),
        ),
    )

    // --------------------------------------------------------------- privacy

    /**
     * The two irreversible LOCAL things, plus the one sentence nobody can find
     * when they want it. Signing out is NOT here — it belongs beside the
     * account it signs out of, which is the same-verb-one-control rule.
     */
    private val privacy = SettingsCategory(
        id = "privacy",
        title = "Privacy & security",
        blurb = "What this device keeps, what it shows, and how to take its access away.",
        items = listOf(
            SettingsItem(
                id = "privacy.app-lock",
                title = "Lock the app",
                summary = "Asks for your screen lock before showing huginn, and keeps it out of the recents preview.",
                keywords = listOf("lock", "biometric", "fingerprint", "pin", "secure", "flag_secure", "recents"),
                surface = Surface.PHONE,
                availability = { it.appLockAvailable },
                inventory = listOf(11),
            ),
            SettingsItem(
                id = "privacy.lock-now",
                title = "Lock now",
                summary = "Locks huginn immediately instead of waiting for the next time it is opened.",
                keywords = listOf("lock", "now", "immediately"),
                surface = Surface.PHONE,
                availability = { it.appLockAvailable },
                inventory = listOf(12),
            ),
            SettingsItem(
                id = "privacy.remove-this-computer",
                title = "Remove this computer's access",
                summary = "Unenrols this machine from huginn and forgets its token here. Nothing on huginn is deleted.",
                keywords = listOf("remove", "unenrol", "unenroll", "revoke", "forget", "access", "uninstall"),
                surface = Surface.DESKTOP,
                inventory = listOf(61),
            ),
            SettingsItem(
                id = "privacy.token",
                title = "What the token is",
                summary = "Where the bearer is kept on this device and what holding it lets somebody do.",
                keywords = listOf("token", "bearer", "secret", "stored", "keystore", "risk"),
            ),
        ),
    )

    // ------------------------------------------------------------ appearance

    /**
     * Nearly empty, and that is the finding rather than a gap to fill. Its one
     * real member today is `closeToTray`, stranded in a tray menu, and terminal
     * text size finally getting a home — pinch-only on the phone, dead plumbing
     * on the desktop. NO THEME PICKER: the theme is hardcoded in both shells and
     * a picker is a feature, not a reorganisation.
     */
    private val appearance = SettingsCategory(
        id = "appearance",
        title = "Appearance & behaviour",
        blurb = "How this client behaves on its own machine.",
        items = listOf(
            SettingsItem(
                id = "appearance.close-to-tray",
                title = "Close to tray",
                summary = "Whether closing the window leaves huginn running in the tray.",
                keywords = listOf("tray", "close", "minimise", "minimize", "background", "quit"),
                surface = Surface.DESKTOP,
            ),
            SettingsItem(
                id = "appearance.terminal-text-size",
                title = "Terminal text size",
                summary = "How large the terminal sets its text.",
                keywords = listOf("font", "text", "size", "zoom", "terminal", "scale"),
                surface = Surface.PHONE,
            ),
            SettingsItem(
                id = "appearance.shortcuts",
                title = "Keyboard shortcuts",
                summary = "Every key this window answers to.",
                keywords = listOf("keyboard", "shortcuts", "keys", "f1", "hotkey"),
                surface = Surface.DESKTOP,
            ),
        ),
    )

    // --------------------------------------------------------------- updates

    private val updates = SettingsCategory(
        id = "updates",
        title = "Updates & diagnostics",
        blurb = "Which build is installed, and how to hand somebody the facts about it.",
        items = listOf(
            SettingsItem(
                id = "updates.check",
                title = "Check for updates",
                summary = "Looks for a newer build and installs it when you say so.",
                keywords = listOf("update", "upgrade", "version", "download", "install", "release"),
                availability = { it.selfUpdate },
                inventory = listOf(26, 27, 28, 58, 59),
            ),
            SettingsItem(
                id = "updates.copy-diagnostics",
                title = "Copy diagnostics",
                summary = "Puts this client's version, connection, delivery and recent log on the clipboard. Carries no token.",
                keywords = listOf("diagnostics", "report", "copy", "log", "support", "paste", "debug"),
                availability = { it.diagnostics },
                inventory = listOf(60),
            ),
            SettingsItem(
                id = "updates.log-path",
                title = "Log file",
                summary = "Where this client writes its own log.",
                keywords = listOf("log", "file", "path", "debug"),
                surface = Surface.DESKTOP,
            ),
            SettingsItem(
                id = "updates.install-path",
                title = "This install",
                summary = "Where this copy lives and whether it is a packaged build or running from source.",
                keywords = listOf("install", "path", "packaged", "source", "build"),
                surface = Surface.DESKTOP,
            ),
            SettingsItem(
                id = "updates.client-id",
                title = "Client id",
                summary = "The identity this copy enrols and claims notifications under.",
                keywords = listOf("client", "id", "identity", "enrolment", "enrollment"),
                surface = Surface.DESKTOP,
            ),
            SettingsItem(
                id = "updates.cli-sync",
                title = "CLI sync",
                summary = "What the launch-time sync did to the huginn command on this machine.",
                keywords = listOf("cli", "sync", "command", "huginn", "terminal"),
                surface = Surface.DESKTOP,
            ),
        ),
    )

    // ----------------------------------------------------------------- about

    private val about = SettingsCategory(
        id = "about",
        title = "About",
        blurb = "What this is and which pieces are talking to each other.",
        items = listOf(
            SettingsItem(
                id = "about.version",
                title = "Version",
                summary = "Which build of this client is installed.",
                keywords = listOf("version", "build", "release", "number"),
            ),
            SettingsItem(
                id = "about.host-version",
                title = "Host version",
                summary = "Which version of the huginn daemon is answering.",
                keywords = listOf("appd", "daemon", "server", "host", "version"),
            ),
            SettingsItem(
                id = "about.repo",
                title = "Source",
                summary = "Where this app's source and releases live.",
                keywords = listOf("repo", "github", "source", "open source", "licence", "license"),
            ),
            SettingsItem(
                id = "about.what-this-is",
                title = "What huginn is",
                summary = "A front end for Claude Code sessions running on your own machine.",
                keywords = listOf("what", "about", "help", "claude", "sessions"),
            ),
        ),
    )

    /** The nine, in the order both shells list them. */
    val categories: List<SettingsCategory> =
        listOf(host, usage, chats, notify, devices, privacy, appearance, updates, about)

    /** Every item, flattened, in catalog order — the order search ranks stably by. */
    val items: List<SettingsItem> = categories.flatMap { it.items }

    /** The category an item belongs to, or null for an id nothing owns. */
    fun categoryOf(itemId: String): SettingsCategory? =
        categories.firstOrNull { c -> c.items.any { it.id == itemId } }

    fun item(itemId: String): SettingsItem? = items.firstOrNull { it.id == itemId }

    fun category(categoryId: String): SettingsCategory? = categories.firstOrNull { it.id == categoryId }

    /**
     * The items of one category that are reachable right now.
     *
     * Hidden, never disabled: a greyed row is a promise the reader cannot cash,
     * and the audit found six of them.
     */
    fun itemsOf(categoryId: String, probe: SettingsProbe, surface: Surface): List<SettingsItem> {
        val c = category(categoryId) ?: return emptyList()
        if (!c.surface.matches(surface)) return emptyList()
        return c.items.filter { it.surface.matches(surface) && it.availability(probe) }
    }

    /**
     * The categories worth listing: the ones with at least one reachable item.
     *
     * With an empty probe — an unreachable or pre-3.0 host — this is `host`,
     * `privacy` and `about`, which is the honest answer: how to connect, what
     * the token is, and what this program is.
     */
    fun visibleCategories(probe: SettingsProbe, surface: Surface): List<SettingsCategory> =
        categories.filter { it.surface.matches(surface) && itemsOf(it.id, probe, surface).isNotEmpty() }
}
