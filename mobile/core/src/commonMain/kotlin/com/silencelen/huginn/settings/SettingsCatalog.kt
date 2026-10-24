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
 *
 * ⚠ AND SEARCH MUST NEVER OFFER WHAT A PAGE DOES NOT DRAW (2026-10-23 audit:
 * "they are searchable but when clicking the result its not present on the
 * directed to settings page"). An item's availability is the condition its page
 * draws it under, every such condition is a [SettingsProbe] fact, and every item
 * says where it is kept ([SettingScope]), which the desktop pages head their
 * rows with. `SettingsPagesTest` in `:app-desktop` opens every hit through the
 * real search and checks the page draws it under that heading.
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
 * Where a setting is KEPT, and so what changing it reaches.
 *
 * The owner's words (2026-10-23): *"we have to think about 'cloud settings'
 * (which apply to the 'server'/ to all the other huginn clients), the local
 * settings for the device itself, and the core settings for the core cli
 * install"*. A page that mixes the three without saying which is which leaves
 * the reader to guess whether a switch they flip here also flips on the phone.
 *
 * - [SERVER]: stored on huginn (the daemon). Change it in any app and every
 *   app — the phone, each desktop, the console — sees the new value.
 * - [CORE]: kept by huginn core on this computer, the background service that
 *   holds the link to huginn, runs this machine's work and serves its local AI.
 *   The `huginn` command on the same machine reads it too. A phone has no core.
 * - [DEVICE]: this app's own, on this device — its settings file, its OS
 *   permissions, its window. Nothing else sees it.
 */
enum class SettingScope { SERVER, CORE, DEVICE }

/**
 * The heading a settings page draws over a run of rows kept in this scope. In
 * `:core` with the scope itself, so both shells, the pages and the tests read
 * one set of words.
 */
fun SettingScope.heading(surface: Surface): String = when (this) {
    SettingScope.SERVER -> "On huginn · every app"
    SettingScope.CORE -> "huginn core · this computer"
    SettingScope.DEVICE -> if (surface == Surface.PHONE) "This phone" else "This app"
}

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
 * @param keepAwake the SAVED keep-awake switch is on. Two rows — the model for
 *   that request, and quiet hours — are drawn by the shared form only
 *   `if (draft.keepAwake)`, so without this fact the catalog claimed them on
 *   every headroom host and search offered a row that its own page hides.
 *
 * ⚠ THE REST ARE THE SAME RULE, FOUND BY THE 2026-10-23 AUDIT (owner: "they are
 * searchable but when clicking the result its not present on the directed to
 * settings page"). Each one is a condition a page draws a row under, and the
 * catalog did not know it:
 *
 * @param accountSwitchOn the SAVED account-switch toggle is on. The shared form
 *   draws "Switch at" and "Only to an account this much freer" only under it.
 * @param deviceOn this computer is available to huginn (desktop). What it may
 *   do, the act-while-locked switch and the path to claude are drawn only then.
 * @param workScope … and it may Work: the folder for Work runs is that scope's.
 * @param coreInstalled huginn core is installed here (desktop): its tray icon's
 *   switch is drawn only then.
 * @param apps the host serves its Apps page (`/v1/apps` or `/v1/consoles`).
 * @param projects the host serves projects (`/v1/projects`).
 * @param signedIn the host is signed in to a Claude login now (phone: Sign out).
 * @param alertsRelayOn huginn's own alerts are on AND it has a relay to send
 *   them over (phone: "Only when the app is out of contact" modifies that).
 * @param deviceNotify this phone raises its own notifications. Background use,
 *   the Android permission and the delivery details are about that, and are
 *   drawn only while it is on.
 * @param pushKnown the host said whether it can push (phone: delivery details).
 * @param appLockOn the app lock is on (phone: "Lock now" locks something).
 * @param terminalChoice this machine has more than one terminal "Open in
 *   terminal" could start (desktop: Windows and Linux; macOS has the one), so
 *   the Terminal row is drawn.
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
    val keepAwake: Boolean = false,
    val accountSwitchOn: Boolean = false,
    val deviceOn: Boolean = false,
    val workScope: Boolean = false,
    val coreInstalled: Boolean = false,
    val apps: Boolean = false,
    val projects: Boolean = false,
    val signedIn: Boolean = false,
    val alertsRelayOn: Boolean = false,
    val deviceNotify: Boolean = false,
    val pushKnown: Boolean = false,
    val appLockOn: Boolean = false,
    val terminalChoice: Boolean = false,
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
 * @param scope where it is kept — see [SettingScope]. No default on purpose:
 *   a new setting cannot be added without somebody deciding what it reaches.
 * @param scopeViaCore where the DESKTOP keeps it while huginn core carries the
 *   app's link (the standard path), when that differs from [scope]: the core,
 *   not the app, then holds the routes and the token. Null = same as [scope].
 */
data class SettingsItem(
    val id: String,
    val title: String,
    val summary: String,
    val keywords: List<String> = emptyList(),
    val surface: Surface = Surface.BOTH,
    val availability: (SettingsProbe) -> Boolean = { true },
    val inventory: List<Int> = emptyList(),
    val scope: SettingScope,
    val scopeViaCore: SettingScope? = null,
)

/**
 * Where [this] is kept on [surface]: [SettingsItem.scope], or on a desktop that
 * huginn core carries ([viaCore]), [SettingsItem.scopeViaCore] when it has one.
 */
fun SettingsItem.scopeOn(surface: Surface, viaCore: Boolean = false): SettingScope =
    if (surface == Surface.DESKTOP && viaCore) scopeViaCore ?: scope else scope

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
                title = "Routes",
                summary = "The addresses that reach huginn, in the order they are tried, and which one is in use.",
                keywords = listOf(
                    "tailscale", "yggdrasil", "network", "address", "pin", "unpin", "auto", "connection",
                    "route", "routes", "server", "host", "url", "base url", "switch", "vpn", "allowlist",
                ),
                inventory = listOf(1, 2, 3, 4, 29, 30),
                scope = SettingScope.DEVICE,
                scopeViaCore = SettingScope.CORE,
            ),
            SettingsItem(
                id = "host.token",
                title = "Token",
                // Neutral about WHO sends it: through huginn core (the desktop's
                // standard path) the core holds it and this app never sees it.
                summary = "The bearer huginn accepts from this device, and where it is kept.",
                keywords = listOf("bearer", "secret", "auth", "password", "key"),
                inventory = listOf(5, 6, 31, 32),
                scope = SettingScope.DEVICE,
                scopeViaCore = SettingScope.CORE,
            ),
            SettingsItem(
                id = "host.connect",
                title = "Save and connect",
                summary = "Stores the address and token together and reconnects with them.",
                keywords = listOf("save", "connect", "reconnect"),
                surface = Surface.PHONE,
                inventory = listOf(7),
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "host.saved-logins",
                title = "Saved logins",
                summary = "The Claude logins kept on the host, which one is serving, and how usable each token is.",
                keywords = listOf("accounts", "claude", "switch", "use", "forget", "refresh", "expired", "duplicate", "freshness"),
                inventory = listOf(14, 15, 16, 33, 34, 35),
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "host.sign-in",
                title = "Add a login",
                summary = "Signs another Claude account in on the host, through the browser and back.",
                keywords = listOf("sign in", "login", "add account", "authorize", "code", "browser", "email"),
                inventory = listOf(17, 36, 37, 38, 39, 40, 41),
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "host.sign-out",
                title = "Sign out",
                summary = "Signs the host out of the Claude login that is serving now.",
                keywords = listOf("logout", "sign out", "claude"),
                surface = Surface.PHONE,
                // The page draws it while somebody IS signed in, which saved
                // logins only suggested: a host can keep logins and serve none.
                availability = { it.signedIn },
                inventory = listOf(18),
                scope = SettingScope.SERVER,
            ),
            // NO INVENTORY NUMBER, and that is the honest reading: the Part 1
            // audit numbered the controls that EXISTED, and there was no setup
            // flow to number. Its absence from 1-61 is the finding, not a gap
            // in the mapping.
            SettingsItem(
                id = "host.run-setup",
                title = "Run setup again",
                summary = "Walks the address, the token, claude, this computer, local AI, notifications " +
                    "and autostart, checking each one against the real thing.",
                keywords = listOf(
                    "setup", "set up", "wizard", "first run", "onboarding", "check", "validate",
                    "test", "connection", "diagnose", "again",
                ),
                surface = Surface.DESKTOP,
                scope = SettingScope.DEVICE,
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

    /**
     * ⚠ THE TWO ROWS THE FORM DRAWS ONLY WHEN THE SWITCH IS ON.
     * `HeadroomSettingsView` puts the model picker and the quiet-hours field
     * behind `if (draft.keepAwake)`; a catalog item that said "available" anyway
     * broke the catalog's own rule — an unavailable row is HIDDEN and excluded
     * from search, "because a hit that opens onto a hidden row is worse than no
     * hit". Searching *quiet hours* with keep-awake off did exactly that.
     */
    private val keepAwakeOn: (SettingsProbe) -> Boolean = { it.headroom && it.keepAwake }
    private val canRotate: (SettingsProbe) -> Boolean = { it.headroom && it.savedAccounts > 1 }

    /**
     * ⚠ AND THE TWO ROWS IT DRAWS ONLY WHILE ACCOUNT SWITCHING IS ON — the same
     * rule as [keepAwakeOn], missed for these: the form puts "Switch at" and the
     * margin under `if (draft.accountSwitch.enabled)`, so searching *switch at*
     * with switching off opened Usage onto a form without it.
     */
    private val switchingOn: (SettingsProbe) -> Boolean = { canRotate(it) && it.accountSwitchOn }

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
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.ladder-pct",
                title = "Move down the ladder at",
                summary = "How full a window gets before a live session is moved to the next model.",
                keywords = listOf("threshold", "percent", "downgrade", "model", "ladder"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.ladder-up-pct",
                title = "Move back up below",
                summary = "How empty a window has to be before a moved session goes back to its own model.",
                keywords = listOf("threshold", "percent", "upgrade", "restore", "ladder"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.stop-pct",
                title = "Hold new subagents at",
                summary = "How full a window gets before new subagents stop being spawned.",
                keywords = listOf("threshold", "percent", "agents", "subagent", "spawn", "hold"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.stop-fable-pct",
                title = "Hold Fable subagents at",
                summary = "The same hold, at its own level, for subagents on Fable.",
                keywords = listOf("threshold", "percent", "fable", "subagent", "spawn", "hold"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.clear-below-pct",
                title = "Treat a window as cleared below",
                summary = "How empty a window has to read before huginn counts it as reset.",
                keywords = listOf("threshold", "percent", "reset", "cleared"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.ladder",
                title = "Ladder",
                summary = "The order a live session is moved through when its window runs out.",
                keywords = listOf("models", "fable", "opus", "sonnet", "haiku", "order", "fallback"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.auto-resume",
                title = "Auto-resume",
                summary = "Whether a stopped session picks itself back up when the window resets.",
                keywords = listOf("resume", "restart", "continue", "automatic"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.resume-phrase",
                title = "What to type on resume",
                summary = "The message typed into a session when it picks itself back up.",
                keywords = listOf("resume", "phrase", "continue", "prompt"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.default-model",
                title = "Default model",
                summary = "The model a new chat starts on.",
                keywords = listOf("model", "fable", "opus", "sonnet", "default"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.heads-up-text",
                title = "Heads-up message",
                summary = "What is typed into a session before it is moved down the ladder.",
                keywords = listOf("message", "warning", "text", "handoff", "pct"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.account-switch",
                title = "Account switching",
                summary = "Whether huginn rotates to another saved login when this one runs out.",
                keywords = listOf("autoswitch", "rotate", "accounts", "switch", "automatic"),
                availability = canRotate,
                inventory = listOf(13),
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.switch-at",
                title = "Switch at",
                summary = "How full the active login gets before huginn rotates away from it.",
                keywords = listOf("autoswitch", "threshold", "percent", "rotate"),
                availability = switchingOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.switch-margin",
                title = "Only to an account this much freer",
                summary = "How much more room another login needs before it is worth switching to.",
                keywords = listOf("autoswitch", "margin", "percent", "rotate"),
                availability = switchingOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.keep-awake",
                title = "Keep a window rotating",
                summary = KeepAwakeCopy.WHAT,
                keywords = listOf("keep awake", "keepawake", "window", "5-hour", "session", "rotating", "ping", "cost", "spend", "idle"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.keep-awake-model",
                title = "Model for that request",
                summary = "Which model the keep-awake request runs on. The cheapest one that counts.",
                keywords = listOf("keep awake", "haiku", "model", "cheap", "cost"),
                availability = keepAwakeOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.keep-awake-quiet",
                title = "Quiet hours",
                summary = "A span of the day when huginn spends nothing keeping a window open.",
                keywords = listOf("keep awake", "quiet", "hours", "night", "overnight", "schedule", "pause"),
                availability = keepAwakeOn,
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "usage.plan",
                title = "Plan usage",
                summary = "Where the live percentages for every saved login are shown.",
                keywords = listOf("plan", "percent", "limit", "week", "session", "status", "numbers"),
                availability = headroomOn,
                scope = SettingScope.SERVER,
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
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "chats.soft-end",
                title = "Soft end",
                summary = "The phrase huginn types to wind a session down, and whether it does so on its own.",
                keywords = listOf("soft end", "wrap up", "finish", "phrase", "end"),
                // The same `/v1/status` shelf the templates come from.
                availability = { it.quickActions },
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "chats.apps",
                title = "Apps",
                summary = "The pages huginn hosts itself, whether each one answers, and whether " +
                    "your devices can reach it.",
                keywords = listOf("app", "apps", "console", "consoles", "dashboard", "page", "link", "retrofit"),
                // ⚠ ITS OWN FACT, NOT THE STATUS SHELF (2026-10-23 audit). It was
                // gated on `quickActions` while the shells drew it only when
                // `/v1/apps` or `/v1/consoles` answered — and the desktop did not
                // draw it at all, so a search for "apps" opened Chats & sessions
                // onto nothing. `console` stays in the keywords: this row is where
                // somebody searching the old word has to land.
                availability = { it.apps },
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "chats.projects",
                title = "Projects",
                summary = "Clusters of sessions with roles: the tree, each project's dashboard, and a lead's proposals.",
                keywords = listOf("project", "projects", "cluster", "members", "lead", "spawn", "proposal", "manifest"),
                // Reached from the Sessions screen too; the row exists so search finds it.
                // Gated on `/v1/projects` itself, which is what both shells draw
                // the row under (the desktop did not draw it at all until the
                // 2026-10-23 audit).
                availability = { it.projects },
                scope = SettingScope.SERVER,
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
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "notify.host-alerts-mode",
                title = "Only when the app is out of contact",
                summary = "Holds huginn's own messages back while this app is awake and watching.",
                keywords = listOf("alerts", "telegram", "fallback", "quiet", "mode"),
                surface = Surface.PHONE,
                // Drawn only while huginn's alerts are on AND it has a relay to hold
                // back: with either missing there is nothing for this to modify.
                availability = { it.alerts && it.alertsRelayOn },
                inventory = listOf(9),
                scope = SettingScope.SERVER,
            ),
            // ⚠ The four rows below are THIS PHONE's own — its watch, its doze
            // exemption, its delivery witnesses, Android's permission. None of
            // them needs the host's /v1/alerts route, and the old screen always
            // showed them; gating them on `alerts` (as the first cut did) made an
            // old or unreachable daemon hide the switch that decides whether the
            // phone notifies at all. Only the two host-alert rows above are the
            // host's to have or lack.
            //
            // The three under the switch are gated on the SWITCH (2026-10-23
            // audit): the page stops after it while it is off, so a search for
            // "battery" or "permission" opened Notifications onto a page without
            // them. Still this phone's own fact, still never the host's.
            SettingsItem(
                id = "notify.device-watch",
                title = "Tell me when a session needs me",
                summary = "Whether this device raises its own notification, and whether it keeps watching between them.",
                keywords = listOf("push", "notification", "watch", "continuously", "attention"),
                surface = Surface.PHONE,
                availability = { true },
                inventory = listOf(19, 20),
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "notify.background",
                title = "Background use",
                summary = "Lets Android keep this app's watch awake while the screen is off.",
                keywords = listOf("doze", "battery", "background", "android", "exemption"),
                surface = Surface.PHONE,
                availability = { it.deviceNotify },
                inventory = listOf(21),
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "notify.delivery",
                title = "Delivery details",
                summary = "Which route the last notifications actually took, and when each was last heard from.",
                keywords = listOf("push", "fcm", "delivery", "witness", "cadence", "diagnostics"),
                surface = Surface.PHONE,
                availability = { it.deviceNotify && it.pushKnown },
                inventory = listOf(22, 23),
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "notify.permission",
                title = "Notification permission",
                summary = "Whether Android lets this app show notifications at all.",
                keywords = listOf("permission", "allow", "android", "system", "blocked"),
                surface = Surface.PHONE,
                availability = { it.deviceNotify },
                inventory = listOf(24, 25),
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "notify.path",
                title = "How notifications reach this computer",
                summary = "Which backend a notification is posted through here, or that there is none.",
                keywords = listOf(
                    "path", "backend", "libnotify", "tray", "toast", "route", "telegram", "none", "desktop",
                ),
                surface = Surface.DESKTOP,
                // Gated with the claim row rather than always-on: a notification
                // only ever originates at the daemon, so with no host there is no
                // delivery to report. Keeping it ungated would also make the whole
                // Notifications drawer appear against a dead daemon, which is the
                // one thing the five local drawers are carefully NOT.
                availability = { it.alerts },
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "notify.claim-route",
                title = "Claim the notification route",
                summary = "Sends attention here instead of Telegram while this window is attended.",
                keywords = listOf("claim", "telegram", "route", "desktop", "attended"),
                surface = Surface.DESKTOP,
                availability = { it.alerts },
                inventory = listOf(47),
                scope = SettingScope.DEVICE,
            ),
        ),
    )

    // --------------------------------------------------------------- devices

    /**
     * ⚠ THE ROWS UNDER "AVAILABLE TO HUGINN" (2026-10-23 audit). The page draws
     * what this machine may do, the act-while-locked switch and the path to
     * claude only while the machine is on, and the Work folder only in Work —
     * the catalog offered all four whenever there was a host, so search opened
     * Devices onto a page without them.
     */
    private val deviceOn: (SettingsProbe) -> Boolean = { it.enrolable && it.deviceOn }

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
                scope = SettingScope.CORE,
            ),
            SettingsItem(
                id = "devices.scope",
                title = "What it may do",
                summary = "Whether huginn may only read here, may change and run things, or owns the machine.",
                keywords = listOf("scope", "look", "work", "own", "permission", "lock"),
                surface = Surface.DESKTOP,
                availability = deviceOn,
                inventory = listOf(49),
                scope = SettingScope.CORE,
            ),
            SettingsItem(
                id = "devices.act-while-locked",
                title = "Keep act mode while locked",
                summary = "Lets huginn change and run things here while the screen is locked. " +
                    "Off by default: a locked machine is read-only until someone unlocks it.",
                keywords = listOf(
                    "act", "lock", "locked", "unlock", "screen", "persistent", "unattended",
                    // What somebody types when they arrive here from the symptom
                    // rather than from the feature: a box they reach over remote
                    // desktop used to read as locked and refuse every Act.
                    "rdp", "remote desktop", "remote", "headless", "3am", "always",
                ),
                surface = Surface.DESKTOP,
                // The same gate as the scope it modifies, and no narrower: both
                // describe what this computer will let a remote request do to
                // it, so they appear and disappear together.
                availability = deviceOn,
                scope = SettingScope.CORE,
            ),
            SettingsItem(
                id = "devices.work-folder",
                title = "Folder for Work runs",
                summary = "Where a run starts on this machine.",
                keywords = listOf("folder", "directory", "path", "root", "work", "cwd"),
                surface = Surface.DESKTOP,
                availability = { deviceOn(it) && it.workScope },
                inventory = listOf(50),
                scope = SettingScope.CORE,
            ),
            SettingsItem(
                id = "devices.claude-path",
                title = "Path to claude",
                summary = "Which claude binary this machine runs, when PATH is not the right answer.",
                keywords = listOf("claude", "binary", "path", "executable", "cli"),
                surface = Surface.DESKTOP,
                availability = deviceOn,
                inventory = listOf(51),
                scope = SettingScope.CORE,
            ),
            SettingsItem(
                id = "devices.local-ai",
                title = "Local AI",
                summary = "Whether this computer downloads and serves models locally for huginn, " +
                    "and whether it keeps serving while you are logged out.",
                keywords = listOf(
                    "local", "llm", "serve", "models", "kvasir", "offline", "download",
                    // What somebody types when they want the feature this page
                    // actually decides: a machine that serves when nobody is at it.
                    "persistent", "always on", "logout", "logged out", "linger", "service", "adopt",
                ),
                surface = Surface.DESKTOP,
                availability = { it.localServe },
                inventory = listOf(52, 53, 54, 55, 56, 57),
                scope = SettingScope.CORE,
            ),
            SettingsItem(
                id = "devices.fleet",
                title = "All devices",
                summary = "The machines enrolled with huginn, what each is doing, and which may be asked for work.",
                keywords = listOf("fleet", "machines", "devices", "manage", "ask", "act", "forget"),
                availability = { it.enrolable },
                inventory = listOf(10),
                scope = SettingScope.SERVER,
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
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "privacy.lock-now",
                title = "Lock now",
                summary = "Locks huginn immediately instead of waiting for the next time it is opened.",
                keywords = listOf("lock", "now", "immediately"),
                surface = Surface.PHONE,
                availability = { it.appLockAvailable && it.appLockOn },
                inventory = listOf(12),
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "privacy.remove-this-computer",
                title = "Remove this computer's access",
                summary = "Unenrols this machine from huginn and forgets its token here. Nothing on huginn is deleted.",
                keywords = listOf("remove", "unenrol", "unenroll", "revoke", "forget", "access", "uninstall"),
                surface = Surface.DESKTOP,
                inventory = listOf(61),
                scope = SettingScope.DEVICE,
                scopeViaCore = SettingScope.CORE,
            ),
            SettingsItem(
                id = "privacy.token",
                title = "What the token is",
                summary = "Where the bearer is kept on this device and what holding it lets somebody do.",
                keywords = listOf("token", "bearer", "secret", "stored", "keystore", "risk"),
                scope = SettingScope.DEVICE,
                scopeViaCore = SettingScope.CORE,
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
                scope = SettingScope.DEVICE,
            ),
            // One icon (owner, 2026-10-17): huginn core's, which "Hide this icon"
            // takes away. This is the way to bring it back, and to hide it.
            SettingsItem(
                id = "appearance.tray-icon",
                title = "Tray icon",
                summary = "Whether huginn core's icon shows in the notification area.",
                keywords = listOf("tray", "icon", "notification area", "system tray", "hide", "show", "bring back"),
                surface = Surface.DESKTOP,
                // The page draws it only where huginn core is installed; elsewhere
                // this app's own icon follows Close to tray.
                availability = { it.coreInstalled },
                scope = SettingScope.CORE,
            ),
            SettingsItem(
                id = "appearance.terminal-text-size",
                title = "Terminal text size",
                summary = "How large the terminal sets its text.",
                keywords = listOf("font", "text", "size", "zoom", "terminal", "scale"),
                surface = Surface.PHONE,
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "appearance.shortcuts",
                title = "Keyboard shortcuts",
                summary = "Every key this window answers to.",
                keywords = listOf("keyboard", "shortcuts", "keys", "f1", "hotkey"),
                surface = Surface.DESKTOP,
                scope = SettingScope.DEVICE,
            ),
            // The single most-expected missing setting for an always-on tray
            // app, and it was missing everywhere: the recon grep over the whole
            // desktop source, the packaging and the scripts found no Run key, no
            // Startup shortcut and no autostart file of any kind.
            SettingsItem(
                id = "appearance.autostart",
                title = "Start with your session",
                summary = "Whether huginn starts when you sign in, so the window is already watching.",
                keywords = listOf(
                    "autostart", "auto start", "startup", "login", "boot", "sign in", "launch",
                    "run at startup", "shortcut",
                ),
                surface = Surface.DESKTOP,
                scope = SettingScope.DEVICE,
            ),
            // Which terminal "Open in terminal" starts. Auto picks the way the
            // launcher always has (Windows Terminal, then PowerShell; $TERMINAL,
            // then xdg-terminal-exec, then the first one found); this is the one
            // place to say otherwise.
            SettingsItem(
                id = "appearance.terminal",
                title = "Terminal",
                summary = "Which terminal opens a session when you ask for one from a row or the palette.",
                keywords = listOf(
                    "terminal", "powershell", "pwsh", "windows terminal", "wt", "shell", "console",
                    "open in terminal", "kitty", "alacritty", "gnome-terminal", "konsole", "xterm",
                ),
                surface = Surface.DESKTOP,
                availability = { it.terminalChoice },
                scope = SettingScope.DEVICE,
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
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "updates.copy-diagnostics",
                title = "Copy diagnostics",
                summary = "Puts this client's version, connection, delivery and recent log on the clipboard. Carries no token.",
                keywords = listOf("diagnostics", "report", "copy", "log", "support", "paste", "debug"),
                availability = { it.diagnostics },
                inventory = listOf(60),
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "updates.log-path",
                title = "Log file",
                summary = "Where this client writes its own log.",
                keywords = listOf("log", "file", "path", "debug"),
                surface = Surface.DESKTOP,
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "updates.install-path",
                title = "This install",
                summary = "Where this copy lives and whether it is a packaged build or running from source.",
                keywords = listOf("install", "path", "packaged", "source", "build"),
                surface = Surface.DESKTOP,
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "updates.client-id",
                title = "Client id",
                summary = "The identity this copy enrols and claims notifications under.",
                keywords = listOf("client", "id", "identity", "enrolment", "enrollment"),
                surface = Surface.DESKTOP,
                scope = SettingScope.DEVICE,
            ),
            // ⚠ WAS "CLI SYNC" (`updates.cli-sync`, aliased below): the launch-time
            // sync of the huginn command, retired when huginn core took the
            // command over. Search kept offering it onto a page that had not drawn
            // it since (2026-10-23 audit). The core is what answers the same
            // question now, and the one row that says so.
            SettingsItem(
                id = "updates.core",
                title = "huginn core",
                summary = "The background service on this computer that holds the link to huginn and runs " +
                    "its work, which the huginn command uses too: its version, and whether it starts at boot.",
                keywords = listOf(
                    // Not "daemon": in this product that word is huginn's own appd.
                    "core", "cli", "sync", "command", "huginn", "terminal", "service", "boot", "background",
                    "version",
                ),
                surface = Surface.DESKTOP,
                scope = SettingScope.CORE,
            ),
            // The notice outside Settings (the lists' card, the rail dot, the
            // tray row). Off keeps fetching and verifying; this page then stays
            // the only place that says one is ready.
            SettingsItem(
                id = "updates.notify",
                title = "Tell me when an update is ready",
                summary = "Whether a downloaded, verified update is announced in the session and chat lists, " +
                    "on the rail and in the tray menu.",
                keywords = listOf(
                    "update", "notice", "notify", "remind", "reminder", "ready", "card", "dismiss",
                    "don't remind", "later", "silence", "announce",
                ),
                surface = Surface.DESKTOP,
                availability = { it.selfUpdate },
                scope = SettingScope.DEVICE,
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
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "about.host-version",
                title = "Host version",
                summary = "Which version of the huginn daemon is answering.",
                keywords = listOf("appd", "daemon", "server", "host", "version"),
                scope = SettingScope.SERVER,
            ),
            SettingsItem(
                id = "about.repo",
                title = "Source",
                summary = "Where this app's source and releases live.",
                keywords = listOf("repo", "github", "source", "open source", "licence", "license"),
                scope = SettingScope.DEVICE,
            ),
            SettingsItem(
                id = "about.what-this-is",
                title = "What huginn is",
                summary = "A front end for Claude Code sessions running on your own machine.",
                keywords = listOf("what", "about", "help", "claude", "sessions"),
                scope = SettingScope.DEVICE,
            ),
        ),
    )

    /** The nine, in the order both shells list them. */
    val categories: List<SettingsCategory> =
        listOf(host, usage, chats, notify, devices, privacy, appearance, updates, about)

    /** Every item, flattened, in catalog order — the order search ranks stably by. */
    val items: List<SettingsItem> = categories.flatMap { it.items }

    /** The category an item belongs to, or null for an id nothing owns. */
    fun categoryOf(itemId: String): SettingsCategory? {
        val id = resolveId(itemId)
        return categories.firstOrNull { c -> c.items.any { it.id == id } }
    }

    /**
     * Ids a previous version handed out that now resolve to the row which
     * absorbed them.
     *
     * Ids are declared immutable two hundred lines up, and this is how that
     * promise is kept when two settings become one: `host.base-url` was its own
     * field until routes made the address a property of a pin, and a search hit
     * or a saved destination carrying that id has to keep arriving somewhere
     * real rather than onto a blank page. `updates.cli-sync` named the CLI
     * sync that huginn core replaced, so it arrives at the core's row.
     */
    val aliases: Map<String, String> = mapOf(
        "host.base-url" to "host.route",
        "updates.cli-sync" to "updates.core",
    )

    /** The id that actually names a row today — see [aliases]. */
    fun resolveId(itemId: String): String = aliases[itemId] ?: itemId

    fun item(itemId: String): SettingsItem? {
        val id = resolveId(itemId)
        return items.firstOrNull { it.id == id }
    }

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

/**
 * ⚠⚠ P-20. THE ONE TRUTHFUL DESCRIPTION OF WHAT KEEP-AWAKE COSTS.
 *
 * It was described two contradictory ways, four taps apart. The Usage page said
 * *"Keep a window rotating — **Nothing is spent.** A session that starts after an
 * idle spell begins a fresh 5-hour window and gets all of it"*; a settings search
 * for "keep" said *"Keep a window rotating — **Spends a fraction of a cent**
 * starting a 5-hour window when none is running."* Both were drawn from the same
 * toggle; the first was the copy for the OFF state, read as the description of
 * the feature. Given the owner's standing rule on spending, one of those is a
 * promise this product must not make.
 *
 * So there is one sentence, here, used by the catalog row and by the page — and
 * the off-state copy no longer opens with a claim that reads as the feature's.
 */
object KeepAwakeCopy {

    /** What it DOES and what it costs. The row, the search hit, and the on-state. */
    const val WHAT: String =
        "Sends one tiny request when no 5-hour window is running, at most once per window — " +
            "a fraction of a cent, and a sliver of the weekly pool."

    /**
     * What turning it OFF means. It still says nothing is spent, because that is
     * true of the off state — but it says it about the SWITCH rather than about
     * the feature, which is the whole of P-20.
     */
    const val OFF: String =
        "Off — huginn sends nothing. A session that starts after an idle spell begins a fresh " +
            "5-hour window and gets all of it."
}
