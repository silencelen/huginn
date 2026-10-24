package com.silencelen.huginn.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.silencelen.huginn.desktop.TerminalLaunch
import com.silencelen.huginn.ui.system.LocalRowMetrics
import com.silencelen.huginn.ui.system.Scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.HuginnSettings
import com.silencelen.huginn.data.Autoswitch
import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.LoginSession
import com.silencelen.huginn.data.LoginState
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.View
import com.silencelen.huginn.desktop.core.CoreSetup
import androidx.compose.ui.window.isTraySupported
import com.silencelen.huginn.desktop.diag.AppLog
import com.silencelen.huginn.desktop.notify.Notifiers
import com.silencelen.huginn.desktop.ui.Muted
import com.silencelen.huginn.desktop.ui.common.openInBrowser
import com.silencelen.huginn.desktop.update.UpdateState
import com.silencelen.huginn.desktop.update.installThenQuit
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.Surface as SettingsSurface
import com.silencelen.huginn.settings.scopeOn
import com.silencelen.huginn.ui.HeadroomSettingsSection
import com.silencelen.huginn.ui.settings.AccountsEditor
import com.silencelen.huginn.ui.settings.AccountsIo
import com.silencelen.huginn.ui.settings.QuickActionsEditor
import com.silencelen.huginn.ui.settings.SettingsActionRow
import com.silencelen.huginn.ui.settings.SettingsFieldRow
import com.silencelen.huginn.ui.settings.SettingsNavRow
import com.silencelen.huginn.ui.settings.RouteListActions
import com.silencelen.huginn.ui.settings.SettingsReadOnlyRow
import com.silencelen.huginn.ui.settings.SettingsRouteListRow
import com.silencelen.huginn.ui.settings.SettingsScopeLabel
import com.silencelen.huginn.ui.settings.SettingsToggleRow
import com.silencelen.huginn.ui.settings.SettingsRowStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One page per category — the nine drawers of the redesigned Settings, each
 * holding only what it holds.
 *
 * WHY NINE FUNCTIONS AND NOT ONE SCREEN. The screen this replaces was 1200 lines
 * of flat scroll under eleven headers, and its defining property was that adding
 * a twelfth cost nothing: no file said what Settings contained, so nothing could
 * say it was getting crowded. The catalog in `:core` now says it, and this file
 * is the other half — each page takes only the corner of the store it needs, so
 * a row cannot quietly acquire a dependency on the whole app the way the old
 * screen's single `SettingsView(store)` did.
 *
 * EVERY ROW CARRIES ITS CATALOG ID. That id is what search hits on, what the
 * arrival mark names, and what the redesign's regression test counts — a row
 * drawn with an id the catalog does not know is invisible to all three, which is
 * exactly the state the redesign was called in to fix.
 *
 * ⚠ NOTHING HERE CHANGES WHAT A CONTROL DOES. The headroom form, the accounts
 * editor, the quick-actions editor, both device forms and the updater are the
 * same code with the same calls and the same refusals; this file decides only
 * which page they are drawn on and in what order.
 */

/**
 * The scope heading over the rows that follow ([SettingsScopeLabel]): on huginn
 * for every app, huginn core on this computer, or this app alone.
 *
 * It names a ROW, not a scope: the words are the catalog's answer for
 * [firstId], so a page cannot file a row under a scope the catalog does not give
 * it. `SettingsPagesTest` renders every page and checks each row it finds
 * against the nearest heading above it.
 */
@Composable
internal fun ScopeOf(firstId: String, store: AppStore) {
    val item = SettingsCatalog.item(firstId) ?: return
    SettingsScopeLabel(item.scopeOn(SettingsSurface.DESKTOP, store.viaCore != null), SettingsSurface.DESKTOP)
}

// ------------------------------------------------------------ host & sign-in

/**
 * Where huginn is and which Claude login serves it.
 *
 * ⚠ AN ADDRESS IS REFUSED rather than saved and failed later: the bearer token
 * follows the base URL on every request, so an arbitrary address is a one-field
 * path to handing a root-equivalent daemon token to a stranger. The rule now
 * lives in `:core` as [com.silencelen.huginn.data.RouteGuard] rather than as
 * this client's own four-host list — it had to stop being a list once the owner
 * could add routes, and moving it put the PHONE behind the same guard for the
 * first time. The refusal is that guard's sentence, shown verbatim.
 *
 * WHAT USED TO BE HERE: a read-only row printing `known routes: Tailscale
 * http://… Yggdrasil http://…` as a flat string, and a Base URL box with its own
 * Save. One could not be changed and the other could not be named.
 *
 * ⚠ THROUGH HUGINN CORE THIS PAGE STOPPED AFTER THE ROUTES (2026-10-23 audit).
 * It returned as soon as the core's route book was drawn, so on the standard
 * path the saved logins, adding a login and Run setup again were never drawn —
 * all three found by search, and the first two the desktop's only way to manage
 * the host's Claude logins. The LINK is the core's; the rest of the page is not.
 */
@Composable
fun ColumnScope.HostPage(store: AppStore, mark: String?) {
    val via = store.viaCore
    ScopeOf("host.route", store)
    if (via != null) {
        // Through huginn core, the link is the core's: its route book, and its
        // token, which this app never holds — said here, where it is looked for.
        CoreHostSection(store, via, mark)
        SettingsReadOnlyRow(
            id = "host.token",
            title = "Token",
            value = "kept by huginn core",
            summary = "huginn core holds it and sends it on its own proven link; this app never sees it. " +
                "To replace it, run huginn core pair in a terminal.",
            highlighted = SettingsRowStyle.isHighlighted("host.token", mark),
            modifier = revealMark("host.token"),
        )
    } else {
        OwnLinkRows(store, mark)
    }

    // The saved logins and the three-step add, from `:ui`. `showAutoswitch =
    // false`: account switching now has ONE rendering, in Usage & headroom,
    // where its threshold and margin are also editable. They live on huginn,
    // whichever link reaches it.
    ScopeOf("host.saved-logins", store)
    AccountsEditor(
        io = remember(store) { DesktopAccountsIo(store) },
        openLink = ::openInBrowser,
        showAutoswitch = false,
        // Section-accurate, like the usage form: the editor draws both rows and
        // takes no mark of its own.
        modifier = revealSection { it == "host.saved-logins" || it == "host.sign-in" }.padding(top = 10.dp),
    )

    // The door back into the first-run flow. It lives HERE, under the address
    // and the token, because those two are the steps somebody comes to this
    // page to fix — and re-checking them is what the flow does first.
    ScopeOf("host.run-setup", store)
    RunSetupAgainRow(mark)
}

/**
 * This app's OWN link: the route list and the token field. Only a run that huginn
 * core does not carry draws these — an install from before the core, or
 * `HUGINN_DESKTOP_DIRECT=1` — and only then are they this app's to keep.
 */
@Composable
private fun ColumnScope.OwnLinkRows(store: AppStore, mark: String?) {
    val settings = store.settings
    val scope = rememberCoroutineScope()
    val book by store.routeBook.collectAsState()
    val health by store.routeHealth.collectAsState()
    val resolving by store.resolvingRoute.collectAsState()
    val routeNote by store.routeNote.collectAsState()
    val routeCandidate by store.routeCandidate.collectAsState()

    var token by remember { mutableStateOf(settings.tokenNow()) }
    var message by remember { mutableStateOf<String?>(null) }

    SettingsRouteListRow(
        book = book,
        actions = remember(store) {
            RouteListActions(
                activate = { store.activateRoute(it) },
                rename = { id, name -> store.renameRoute(id, name) },
                setUrl = { id, url -> store.setRouteUrl(id, url) },
                move = { id, delta -> store.moveRoute(id, delta) },
                remove = { store.removeRoute(it) },
                add = { name, url -> store.addRoute(name, url) },
                setAutoSwitch = { store.setAutoSwitch(it) },
                findLive = { store.findLiveRoute() },
                // One Save, one book operation, and a refusal that stays on the form (edge #64/#81).
                editBoth = { id, name, url -> store.editRoute(id, name, url) },
                clearNote = { store.clearRouteNote() },
                useCandidate = { store.useRouteCandidate(it) },
            )
        },
        health = health,
        nowMs = System.currentTimeMillis(),
        summary = "The addresses that reach huginn, tried in this order.",
        highlighted = SettingsRowStyle.isHighlighted("host.route", mark),
        modifier = revealMark("host.route"),
        finding = resolving,
        note = routeNote,
        suggestedUrl = HuginnSettings.ROUTE_URL_PLACEHOLDER,
        candidate = routeCandidate,
    )
    SettingsFieldRow(
        id = "host.token",
        title = "Token",
        value = token,
        onValueChange = { token = it },
        secret = true,
        summary = "huginn's appd token (on the huginn host: sudo cat /etc/huginn-appd/token). " +
            "Connecting pairs huginn core with huginn at the address above: the core keeps the token, this app keeps none.",
        highlighted = SettingsRowStyle.isHighlighted("host.token", mark),
        modifier = revealMark("host.token"),
        trailing = {
            Button(onClick = {
                scope.launch {
                    message = store.connectThroughCore(token).fold({ it }, { "not connected: ${it.message}" })
                }
            }) { Text("Connect") }
        },
    )
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp),
        )
    }
}

/** [AccountsIo] over this client's daemon connection. Nothing but forwarding. */
private class DesktopAccountsIo(private val store: AppStore) : AccountsIo {
    override suspend fun account(): Account = store.client.account()

    // plan=1: the weekly headroom per saved login is the only number that makes
    // the list worth reading — it is what says which one to switch to.
    override suspend fun savedAccounts(): List<SavedAccount> = store.client.savedAccounts(withPlan = true)
    override suspend fun autoswitch(): Autoswitch = store.client.autoswitch()
    override suspend fun refreshAccount(slug: String): String = store.client.refreshAccount(slug)
    override suspend fun activateAccount(slug: String) { store.client.activateAccount(slug) }
    override suspend fun forgetAccount(slug: String) { store.client.forgetAccount(slug) }
    override suspend fun startLogin(email: String?): LoginSession = store.client.startLogin(email)
    override suspend fun submitLoginCode(code: String): LoginState = store.client.submitLoginCode(code)
}

// --------------------------------------------------------- usage & headroom

/**
 * When huginn warns, when it moves a session down the ladder, and whether it
 * picks one back up.
 *
 * The form itself is `:ui`'s — both clients get the same fields and the same
 * refusals — and everything this adds is the frame: what to load it from, what to
 * do with a Save, and how to report a 400 the daemon raised that the form did not.
 */
@Composable
fun ColumnScope.UsagePage(store: AppStore, mark: String?) {
    val scope = rememberCoroutineScope()
    val headroom by store.headroom.collectAsState()
    var models by remember { mutableStateOf<List<ModelChoice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // The pane may be opened before the 30s poll has run once.
        store.refreshHeadroom()
        runCatching { store.client.models() }.onSuccess { models = it }
    }

    // Every rule on this page is kept on huginn: one heading for all of it.
    ScopeOf("usage.plan", store)
    // The numbers themselves live on Status — this page is where the RULES are,
    // and a reader who wants the figure should be sent rather than shown a copy
    // that is as old as the last visit to the other pane.
    SettingsNavRow(
        id = "usage.plan",
        title = "Plan usage and tokens",
        onOpen = { store.openView(View.STATUS) },
        summary = "What the plan is and how much of it is spent.",
        highlighted = SettingsRowStyle.isHighlighted("usage.plan", mark),
        modifier = revealMark("usage.plan"),
        trailingText = "Status",
    )

    val h = headroom
    if (h == null) {
        // Belt and braces: the category is HIDDEN against a daemon with no
        // headroom, so this only shows if /v1/headroom died between the list
        // being drawn and this page being opened.
        Muted("This host has no headroom subsystem — it is running a daemon older than 3.0.", maxLines = 2)
        return
    }
    Muted(
        "The host validates these too; holding a foreground Agent call freezes the turn that made it.",
        Modifier.padding(top = 10.dp),
        maxLines = 3,
    )
    HeadroomSettingsSection(
        settings = h.settings,
        models = models,
        busy = busy,
        note = note,
        // ⚠ D-23, THE SECTION RATHER THAN THE ROW. Seventeen catalog rows are
        // drawn in here and its signature has nowhere to put "and mark this one",
        // so a hit on any of them — `usage.keep-awake` is the one the review
        // searched for — reveals the FORM. That is section-accurate rather than
        // row-accurate and says so; making it exact means giving the shared
        // section a mark, which is the phone's screen too.
        modifier = revealSection { it != "usage.plan" }.padding(top = 8.dp),
        onSave = { edited ->
            scope.launch {
                busy = true
                note = null
                runCatching { store.client.setHeadroomSettings(patchOf(edited)) }
                    .fold(
                        onSuccess = { note = "saved" },
                        // The daemon's 400 NAMES the rule it refused. Shown as it
                        // came: a form that says "invalid" about a sentence the
                        // host already explained is throwing away the answer.
                        onFailure = { note = it.message ?: "could not save" },
                    )
                store.refreshHeadroom()
                busy = false
            }
        },
    )
}

/**
 * The whole settings object as a PATCH body.
 *
 * Whole rather than a diff, deliberately: the route is a PATCH so that two open
 * forms cannot clobber each other's UNTOUCHED fields, and this form edits every
 * field it can see. Sending what is on screen is therefore exactly what the
 * reader asked for, and computing a diff would only add a second place for the
 * two to disagree about what changed.
 */
private fun patchOf(s: HeadroomSettings): JsonObject = buildJsonObject {
    put("headsUpPct", JsonPrimitive(s.headsUpPct))
    put("ladderPct", JsonPrimitive(s.ladderPct))
    put("ladderUpBelowPct", JsonPrimitive(s.ladderUpBelowPct))
    put("stopPct", JsonPrimitive(s.stopPct))
    put("stopFablePct", JsonPrimitive(s.stopFablePct))
    put("clearBelowPct", JsonPrimitive(s.clearBelowPct))
    put("cooldownMs", JsonPrimitive(s.cooldownMs))
    put("ladder", JsonArray(s.ladder.map { JsonPrimitive(it) }))
    put("defaultModel", JsonPrimitive(s.defaultModel))
    put("autoResume", JsonPrimitive(s.autoResume))
    put("resumePhrase", JsonPrimitive(s.resumePhrase))
    put("headsUpText", JsonPrimitive(s.headsUpText))
    put("accountSwitch", buildJsonObject {
        put("enabled", JsonPrimitive(s.accountSwitch.enabled))
        put("threshold", JsonPrimitive(s.accountSwitch.threshold))
        put("margin", JsonPrimitive(s.accountSwitch.margin))
    })
    put("keepAwake", JsonPrimitive(s.keepAwake))
    put("keepAwakeModel", JsonPrimitive(s.keepAwakeModel))
    // null, not omitted: "no quiet hours" is a value the form has to be able to
    // SEND, and a key left out of a PATCH means "leave it alone" — so clearing
    // the field would silently keep the old span.
    put("keepAwakeQuietHours", s.keepAwakeQuietHours?.let { JsonPrimitive(it) } ?: JsonNull)
}

// ----------------------------------------------------------- chats & sessions

/**
 * The wording each right-click verb puts in the composer, and the phrase a soft
 * end types into a session.
 *
 * EDITED HERE BECAUSE IT LIVES ON THE HOST, which is the whole point of the
 * feature: one copy of the phrasing, so the phone and this window stage the same
 * text and neither can drift. The REFUSALS are the daemon's — `{selection}`
 * exactly once, never in the quote lead-in, 400 characters each — and this
 * reports its words rather than growing a second copy of the rules.
 */
@Composable
fun ColumnScope.ChatsPage(store: AppStore, mark: String?) {
    val scope = rememberCoroutineScope()
    val status by store.status.collectAsState()
    val actions = status?.quickActions
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val projectsAvailable by store.projectsAvailable.collectAsState()
    val appsAvailable by store.appsAvailable.collectAsState()
    val projects by store.projects.collectAsState()
    val apps by store.apps.collectAsState()

    // All of it is kept on huginn: the wording, the phrase, the pages it hosts.
    ScopeOf("chats.quick-actions", store)
    if (actions != null) {
        QuickActionsEditor(
            // Tagged and revealed as a whole: the editor draws the four
            // wordings and takes no mark of its own.
            modifier = Modifier.testTag("chats.quick-actions").then(revealMark("chats.quick-actions")),
            actions = actions,
            busy = busy,
            note = note,
            onSave = { edited ->
                scope.launch {
                    busy = true
                    note = runCatching {
                        store.client.setQuickActions(
                            explain = edited.explain,
                            execute = edited.execute,
                            askInNewChat = edited.askInNewChat,
                            quote = edited.quote,
                            rev = edited.rev,
                        )
                    }.fold(
                        { store.refreshStatusShelf(); "saved — both clients use this wording now" },
                        { it.message ?: "could not save" },
                    )
                    busy = false
                }
            },
        )
    }

    // READ-ONLY, and honestly so: the phrase and the auto-end flag are on the
    // wire (`Status.softEndPhrase`/`softEndAuto`) with no editor on either
    // client. Showing what the host will type is the half that is true today;
    // a field that wrote nowhere would be the half that lies.
    SettingsReadOnlyRow(
        id = "chats.soft-end",
        title = "Soft end",
        value = if (status?.softEndAuto == true) "ends itself" else "stays open",
        summary = status?.softEndPhrase?.takeIf { it.isNotBlank() }
            ?: "What huginn types into a session that is winding down. Set on the host.",
        highlighted = SettingsRowStyle.isHighlighted("chats.soft-end", mark),
        modifier = revealMark("chats.soft-end").padding(top = 10.dp),
    )

    // ⚠ THE TWO ROWS SEARCH OFFERED AND THIS PAGE NEVER HAD (2026-10-23 audit):
    // "Projects" and "Apps" were catalog rows of this drawer on both shells and
    // drawn only by the phone, so on the desktop the hit opened Chats & sessions
    // onto neither. The phone's doors, in the same place and on the same
    // condition the rail uses for its own items.
    if (projectsAvailable == true) {
        SettingsNavRow(
            id = "chats.projects",
            title = "Projects",
            onOpen = { store.openView(View.PROJECTS) },
            summary = "Clusters of sessions with roles: a lead sizes the work and proposes the members, " +
                "and you approve them before anything starts.",
            highlighted = SettingsRowStyle.isHighlighted("chats.projects", mark),
            modifier = revealMark("chats.projects").padding(top = 10.dp),
            trailingText = if (projects.isEmpty()) "none" else "${projects.size}",
        )
    }
    if (appsAvailable == true) {
        SettingsNavRow(
            id = "chats.apps",
            title = "Apps",
            onOpen = { store.openView(View.APPS) },
            summary = "The pages huginn hosts itself, whether each one answers, and whether your devices can reach it.",
            highlighted = SettingsRowStyle.isHighlighted("chats.apps", mark),
            modifier = revealMark("chats.apps").padding(top = 10.dp),
            trailingText = if (apps.apps.isEmpty()) "none" else "${apps.apps.size}",
        )
    }
}

// ------------------------------------------------------------- notifications

/**
 * Whether this window is the one huginn reaches you through.
 *
 * The reader has to be able to see WHY the claim is off, because "off" is also
 * what a bug looks like — and the two reasons it can be off (turned off here,
 * or this window is not being watched) want different answers.
 */
@Composable
fun ColumnScope.NotifyPage(store: AppStore, mark: String?) {
    val scope = rememberCoroutineScope()
    val settings = store.settings
    val notifyEnabled by settings.notifyEnabled.collectAsState(initial = true)
    val present by store.presence.present.collectAsState()

    ScopeOf("notify.claim-route", store)
    SettingsToggleRow(
        id = "notify.claim-route",
        title = "Claim the notification route",
        checked = notifyEnabled,
        onCheckedChange = { scope.launch { settings.setNotifyEnabled(it) } },
        // ⚠ D-31. TOLD THE SAME FACT AS THE ROW UNDER IT. "claiming: this window
        // has been attended recently" sat directly above "How notifications reach
        // this computer — none". See [Notifiers.claimWords].
        summary = Notifiers.claimWords(notifyEnabled, present, AppLog.notifierName),
        highlighted = SettingsRowStyle.isHighlighted("notify.claim-route", mark),
        modifier = revealMark("notify.claim-route"),
    )
    // ⚠ THE FACT THIS PAGE WAS PROMISED AND DID NOT CARRY. The notification setup
    // step's failure text says "Notifications in Settings shows which path this
    // computer is using", and it did not — a machine with no tray and no
    // libnotify saw the claim toggle above and nothing else, while the startup
    // log and `Copy diagnostics` both already knew. Read-only because it is not a
    // choice: nothing in this app installs a notification daemon.
    SettingsReadOnlyRow(
        id = Notifiers.PATH_ROW_ID,
        title = "How notifications reach this computer",
        value = Notifiers.pathWords(AppLog.notifierName),
        summary = Notifiers.pathSummary(AppLog.notifierName),
        highlighted = SettingsRowStyle.isHighlighted(Notifiers.PATH_ROW_ID, mark),
        modifier = revealMark(Notifiers.PATH_ROW_ID),
    )
}

// ------------------------------------------------------------------ devices

/** This machine's own enrolment and serving, then the way to everything else. */
@Composable
fun ColumnScope.DevicesPage(store: AppStore, mark: String?) {
    val devices by store.devices.collectAsState()

    // The fleet is huginn's list; everything under the second heading is this
    // machine's, kept by the huginn core that runs its work and serves its models.
    ScopeOf("devices.fleet", store)
    SettingsNavRow(
        id = "devices.fleet",
        title = "All devices",
        onOpen = { store.openView(View.DEVICES) },
        summary = "Every machine enrolled with huginn, and what each may do.",
        highlighted = SettingsRowStyle.isHighlighted("devices.fleet", mark),
        modifier = revealMark("devices.fleet"),
        trailingText = devices.takeIf { it.isNotEmpty() }?.let { SettingsSummaries.of("devices", SettingsFacts(devices = it)) },
    )

    ScopeOf("devices.this-machine", store)
    DeviceSection(store, mark = mark)
    LocalServeSection(store)
}

// -------------------------------------------------------- privacy & security

/**
 * The two irreversible LOCAL things: taking this computer back out, and what the
 * token it holds can do.
 *
 * QUIET ON PURPOSE — no red, no warning triangle, no capitals. Nothing here is
 * destructive to anything that matters: the chats, sessions and Rounds live on
 * huginn and are untouched. What goes is this computer's ACCESS. Dressing that up
 * as danger would teach the reader to fear a button that is the polite
 * alternative to uninstalling.
 */
@Composable
fun ColumnScope.PrivacyPage(store: AppStore, mark: String?) {
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }

    // Both rows are about the access this computer holds — huginn core's,
    // through the core; this app's own on the old direct link.
    ScopeOf("privacy.token", store)
    SettingsReadOnlyRow(
        id = "privacy.token",
        title = "What the token is",
        value = when {
            store.viaCore != null -> "kept by huginn core, not by this app"
            store.settings.tokenNow().isNotBlank() -> "saved on this computer"
            else -> "not set"
        },
        // Run 4: "kept by huginn core" beside a summary naming this app's
        // settings file as where the token lives.
        summary = if (store.viaCore != null) {
            "One bearer, kept by huginn core in this account's own store and sent only by the core, " +
                "on its proven link to huginn. It is root-equivalent on the daemon; this app never sees it."
        } else {
            "One bearer, kept in ${store.settings.path} at mode 0600 and sent with every " +
                "request. It is root-equivalent on the daemon, which is why the address it travels to " +
                "is on an allowlist."
        },
        highlighted = SettingsRowStyle.isHighlighted("privacy.token", mark),
        modifier = revealMark("privacy.token"),
    )

    SettingsActionRow(
        id = "privacy.remove-this-computer",
        title = "Remove this computer's access",
        actionLabel = "Remove",
        onAction = { confirming = true },
        enabled = !busy,
        destructive = true,
        summary = if (store.viaCore != null) {
            "Unenrols this machine from huginn and unpairs huginn core, which forgets the token. " +
                "Your chats and sessions stay on huginn."
        } else {
            "Unenrols this machine from huginn and forgets the token here. Your chats " +
                "and sessions stay on huginn."
        },
        highlighted = SettingsRowStyle.isHighlighted("privacy.remove-this-computer", mark),
        modifier = revealMark("privacy.remove-this-computer"),
    )
    outcome?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp),
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove this computer's access?") },
            text = {
                Text(
                    "huginn is asked first to drop every enrolment this machine holds — " +
                        "the one that runs work here, and the local-AI one if this box " +
                        "serves models. Then this app forgets its token, its enrolment and " +
                        "any half-written messages, and asks for a server and token again.\n\n" +
                        "Nothing on huginn is deleted: chats, sessions and Rounds are all " +
                        "still there. If huginn cannot be reached, nothing changes here " +
                        "either — try again when it is.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirming = false
                        busy = true
                        outcome = null
                        // The APP's scope, not the composition's: navigating away
                        // from Settings must not cancel a flow that is halfway
                        // through retiring rows at the daemon.
                        store.scope.launch {
                            store.removeThisComputer()
                                .onSuccess {
                                    outcome = when (it) {
                                        0 -> "removed — this computer held no enrolment; " +
                                            "the token here is cleared"
                                        1 -> "removed — 1 enrolment dropped and the token here is cleared"
                                        else -> "removed — $it enrolments dropped and the token here is cleared"
                                    }
                                }
                                .onFailure {
                                    // The core's own sentence when it is the core that did
                                    // not confirm: "could not reach huginn" would send the
                                    // owner after the wrong machine.
                                    outcome = it.message?.takeIf { m -> m.startsWith("huginn core") }
                                        ?: ("could not reach huginn (${it.message}) — " +
                                            "nothing changed here; try again when it answers")
                                }
                            busy = false
                        }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

// ----------------------------------------------------- appearance & behaviour

/**
 * Three rows, and that is the honest size of it.
 *
 * ⚠ CLOSE TO TRAY LIVED IN THE TRAY MENU — a persisted setting reachable only by
 * right-clicking an icon, which the inventory found alongside five other
 * settings-shaped controls outside Settings. The tray checkbox stays where it is
 * (it is where you are when you decide the window should not have closed) and
 * both write the same flow, so neither can go stale.
 *
 * NO THEME PICKER. The theme is hardcoded in both shells; a picker is a feature,
 * not a reorganisation, and this drawer being nearly empty is a finding rather
 * than a gap to fill.
 */
@Composable
fun ColumnScope.AppearancePage(store: AppStore, mark: String?) {
    val closeToTray by store.settings.closeToTray.collectAsState()
    val coreTray by store.coreDevice.coreTray.collectAsState()
    val coreInstalled = remember(coreTray) { com.silencelen.huginn.desktop.core.CoreSetup.installedBinary() != null }
    // Where huginn core is installed its icon is the only one (Main.kt), so a
    // hidden icon means no tray to close to.
    val trayHere = coreTray || (isTraySupported && !coreInstalled)
    val scope = rememberCoroutineScope()
    var trayNote by remember { mutableStateOf<String?>(null) }
    var trayBusy by remember { mutableStateOf(false) }

    // ⚠ GROUPED BY WHERE EACH ONE IS KEPT (2026-10-23): this app's three, then
    // huginn core's icon. Close to tray's "Show the icon below" still holds.
    ScopeOf("appearance.close-to-tray", store)
    SettingsToggleRow(
        id = "appearance.close-to-tray",
        title = "Close to tray",
        checked = closeToTray,
        onCheckedChange = { store.settings.setCloseToTray(it) },
        // ⚠ THE COPY FOLLOWS THE MACHINE, NOT THE TOGGLE. `Main.kt` quits when
        // there is no tray whatever this is set to, and the row used to promise
        // "leaves huginn running in the tray" on a box where closing the window
        // ends the process — verified, it was gone.
        summary = when {
            coreInstalled && !coreTray ->
                "The tray icon is hidden, so closing the window quits Huginn; huginn core keeps running. " +
                    "Show the icon below to close to it."
            !isTraySupported && !coreTray ->
                "There is no system tray on this computer, so closing the window quits huginn " +
                    "and the watch stream stops with it."
            closeToTray -> "Closing the window leaves huginn running in the tray." +
                if (coreTray) " The tray icon's menu has this switch too." else ""
            else -> "Closing the window quits huginn, and the watch stream stops with it."
        },
        highlighted = SettingsRowStyle.isHighlighted("appearance.close-to-tray", mark),
        modifier = revealMark("appearance.close-to-tray"),
        // ⚠ D-21. THE COPY FOLLOWED THE MACHINE AND THE SWITCH DID NOT. The row
        // said "there is no system tray on this computer" and left a live, ON
        // toggle under it — a control whose only possible effect is to persist a
        // preference that `Main.kt` will ignore for the life of this install. A
        // setting that cannot do anything is read as a setting that is broken, so
        // it greys out and the summary above says why. The VALUE is untouched: a
        // laptop that gains a tray (a different desktop session, a Windows
        // install) gets back exactly the answer its owner last gave.
        enabled = trayHere,
    )
    // Beside close-to-tray on purpose: the two of them are the whole answer to
    // "is this thing running when I am not looking at it".
    StartupRow(store, mark)
    SettingsNavRow(
        id = "appearance.shortcuts",
        title = "Keyboard shortcuts",
        onOpen = { store.openCheatsheet() },
        summary = "Every chord this window answers to.",
        highlighted = SettingsRowStyle.isHighlighted("appearance.shortcuts", mark),
        modifier = revealMark("appearance.shortcuts"),
        trailingText = "F1",
    )
    // Which terminal "Open in terminal" starts. Under the same fact the probe
    // reads (`SettingsFacts.terminalChoice`), so search never offers it where it
    // is not drawn — macOS, where there is the one Terminal.
    if (TerminalLaunch.offersChoice()) TerminalRow(store, mark)
    // The way back after "Hide this icon" (owner, 2026-10-17), and the way to
    // hide it from here. Only where huginn core is installed: elsewhere this
    // app's own icon follows Close to tray.
    if (coreInstalled) {
        // The core's own icon, not this app's: kept where the core keeps it.
        ScopeOf("appearance.tray-icon", store)
        SettingsToggleRow(
            id = "appearance.tray-icon",
            title = "Tray icon",
            checked = coreTray,
            enabled = !trayBusy,
            onCheckedChange = { on ->
                trayBusy = true
                trayNote = null
                scope.launch {
                    store.coreDevice.setTrayIcon(on).onFailure { trayNote = it.message }
                    trayBusy = false
                }
            },
            summary = trayNote ?: if (coreTray) {
                "huginn core's icon in the notification area: Open Huginn, what needs you, Close to tray and Quit Huginn."
            } else {
                "Hidden. It comes back at your next sign-in, or when you turn this on."
            },
            highlighted = SettingsRowStyle.isHighlighted("appearance.tray-icon", mark),
            modifier = revealMark("appearance.tray-icon"),
        )
    }
}

/**
 * The Terminal setting: Auto, then every terminal this machine has
 * ([TerminalLaunch.candidates]), as the radio form the Devices page uses for a
 * choice. Auto is the launcher's own order, said in the blurb so the person
 * choosing knows what they are choosing instead of.
 *
 * A chosen terminal that has since gone is still listed, so it can be changed:
 * a setting that names something absent and offers no way off it is a setting
 * that is broken. `TerminalLaunch` skips it and says so at open time.
 */
@Composable
private fun TerminalRow(store: AppStore, mark: String?) {
    val choice by store.settings.terminal.collectAsState()
    // Asked of the machine once per page: a PATH walk per frame would be a poll.
    val candidates = remember { TerminalLaunch.candidates() }
    val highlighted = SettingsRowStyle.isHighlighted("appearance.terminal", mark)
    val metrics = LocalRowMetrics.current
    val windows = TerminalLaunch.os == TerminalLaunch.Os.WINDOWS
    val autoBlurb = if (windows) {
        "Windows Terminal when it is installed, then PowerShell 7, then Windows PowerShell."
    } else {
        "\$TERMINAL when it is set, then xdg-terminal-exec, then the first terminal found on this machine."
    }
    val shown = buildList {
        add(TerminalLaunch.Candidate("", "Auto"))
        addAll(candidates)
        if (choice.isNotBlank() && candidates.none { it.id == choice }) add(TerminalLaunch.Candidate(choice, choice))
    }
    // Framed like the shared field row: cap before fill, the arrival target is
    // the Low surface tier plus a SemiBold title and nothing else.
    Column(
        Modifier
            .testTag("appearance.terminal")
            .then(revealMark("appearance.terminal"))
            .widthIn(max = SettingsRowStyle.ROW_MAX_WIDTH)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Scale.radiusCard))
            .background(if (highlighted) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .padding(horizontal = metrics.inset, vertical = metrics.vertical),
    ) {
        Text(
            "Terminal",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
        )
        Text(
            "Which terminal opens a session when you ask for one from a row, the palette or the session's name.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (c in shown) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                RadioButton(selected = choice == c.id, onClick = { store.settings.setTerminal(c.id) })
                Column(Modifier.padding(start = 6.dp)) {
                    Text(c.title, style = MaterialTheme.typography.bodyMedium)
                    when {
                        c.id.isEmpty() -> Muted(autoBlurb, maxLines = 2)
                        candidates.none { it.id == c.id } -> Muted("not found on this machine; Auto is used until it is back", maxLines = 2)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------- updates & diagnostics

/**
 * What the self-updater knows, and the paste-a-blob button.
 *
 * The updater downloads and VERIFIES on its own; installing is a button, never a
 * background decision, because these builds are unsigned and an update that runs
 * itself is an update nobody chose. The report carries the app version, the
 * connection, the watch stream, the claim state, the update state, the platform
 * and the recent log; it carries NO token, and that is a property of
 * `Diagnostics.Input` having no field for one rather than of anybody remembering.
 */
@Composable
fun ColumnScope.UpdatesPage(store: AppStore, mark: String?) {
    val scope = rememberCoroutineScope()
    val state by store.updater.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    var note by remember { mutableStateOf<String?>(null) }
    // The two answers that quiet the notice outside this page (UpdateReminder).
    val silenced by store.settings.updateSilencedVersion.collectAsState()
    val notify by store.settings.updateNotify.collectAsState()

    ScopeOf("updates.check", store)
    // ⚠ A ROW, WITH ITS CATALOG ID (2026-10-23 audit). The check was a bare
    // sentence and a button, so a search for "update" opened this page with
    // nothing marked and nothing to scroll to; the phone has drawn it as the
    // `updates.check` row all along. Same words, same button, same refusals.
    SettingsActionRow(
        id = "updates.check",
        title = "Check for updates",
        actionLabel = "Check now",
        onAction = { scope.launch { store.updater.check() } },
        enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
        summary = when (val s = state) {
            UpdateState.Idle -> "installed ${store.updater.installedVersion} · not checked yet"
            UpdateState.Checking -> "checking…"
            is UpdateState.UpToDate -> "up to date (${s.version})"
            is UpdateState.Downloading -> "downloading ${s.version}…"
            is UpdateState.Ready -> "${s.version} downloaded and verified" +
                if (silenced == s.version) " · reminders off" else ""
            // Said under the row in the error colour, as it always was.
            is UpdateState.Error -> null
        },
        highlighted = SettingsRowStyle.isHighlighted("updates.check", mark),
        modifier = revealMark("updates.check"),
    )
    (state as? UpdateState.Error)?.let { e ->
        Text(
            "update check failed: ${e.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    (state as? UpdateState.Downloading)?.fraction?.let { f ->
        LinearProgressIndicator(progress = { f }, modifier = Modifier.padding(top = 6.dp, start = 8.dp).width(280.dp))
    }
    (state as? UpdateState.Ready)?.let { ready ->
        if (ready.notes.isNotBlank()) Muted(ready.notes, Modifier.padding(top = 4.dp, start = 8.dp), maxLines = 4)
    }
    (state as? UpdateState.Ready)?.let { ready ->
        Row(Modifier.padding(top = 8.dp, start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // And restart, which until now it only claimed: the installer cannot
            // replace files this process holds open, and it closes this client
            // itself if we do not — killing the process TREE it is standing in,
            // since it is a child of this JVM. So we leave, it installs, and its
            // finish page brings the app back. [installThenQuit] holds the rule
            // that this only happens when the launch really took.
            Button(
                enabled = ready.installable,
                onClick = { installThenQuit(install = store.updater::install, quit = store::requestQuit) },
            ) { Text("Install and restart") }
        }
    }
    (state as? UpdateState.Ready)?.takeIf { !it.installable }?.let {
        Muted(
            it.hint.ifBlank { "downloaded to ${it.file.absolutePath} — install it by hand on this platform" },
            Modifier.padding(top = 4.dp, start = 8.dp),
            maxLines = 3,
        )
    }
    // "Don't remind me" was answered for a version: said here, where the parked
    // update is, with the one way back. Shown whenever it is set, not only while
    // that version is the one parked — a stored answer the page hid would be a
    // card that never comes back for no reason anybody can see.
    silenced?.let { v ->
        Row(Modifier.padding(top = 4.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted("Reminders off for $v")
            TextButton(onClick = { store.updateUnsilence() }, modifier = Modifier.testTag("updates.check.remind")) {
                Text("Remind me")
            }
        }
    }
    SettingsToggleRow(
        id = "updates.notify",
        title = "Tell me when an update is ready",
        checked = notify,
        onCheckedChange = { store.settings.setUpdateNotify(it) },
        summary = if (notify) {
            "A downloaded, verified update is announced at the top of the session and chat lists, " +
                "with a dot on Settings and a row in the tray menu, until you install it or say not to."
        } else {
            "Updates are still fetched and verified; this page is the only place that says one is ready."
        },
        highlighted = SettingsRowStyle.isHighlighted("updates.notify", mark),
        modifier = revealMark("updates.notify"),
    )

    SettingsActionRow(
        id = "updates.copy-diagnostics",
        title = "Copy diagnostics",
        actionLabel = "Copy",
        onAction = {
            scope.launch {
                // Refreshed FIRST: the status snapshot is only fetched while the
                // Status view is open, so a report copied from here otherwise said
                // "appd version unknown" — which is the one line that says whether
                // the client and the daemon are even the same generation.
                runCatching { store.refreshStatus() }
                val text = AppLog.diagnostics(store)
                // Compose's clipboard, not AWT's. The Electron release that denied every
                // permission also denied clipboard writes and broke every copy in the app
                // silently for a whole release; the carry-over list names it.
                clipboard.setText(AnnotatedString(text))
                note = "copied ${text.lineSequence().count()} lines — paste it into a chat"
            }
        },
        summary = note ?: "Everything about this client's state, without the token.",
        highlighted = SettingsRowStyle.isHighlighted("updates.copy-diagnostics", mark),
        modifier = revealMark("updates.copy-diagnostics").padding(top = 12.dp),
    )
    // ⚠ A PATH IS IDENTIFIED BY ITS END. This row ellipsised a long log path at
    // 320dp and offered nothing else — no wrap, no copy, no way to widen it —
    // while "This install" directly beneath it wrapped in full, so the one line
    // somebody wants when they are about to go and read the file was the one
    // line they could not have. Four lines and a Copy.
    SettingsReadOnlyRow(
        id = "updates.log-path",
        title = "Log file",
        value = AppLog.path ?: "memory only",
        summary = if (AppLog.path == null) "The log file could not be opened, so it is kept in memory." else null,
        highlighted = SettingsRowStyle.isHighlighted("updates.log-path", mark),
        modifier = revealMark("updates.log-path"),
        maxLines = 4,
        onCopy = AppLog.path?.let { path -> { clipboard.setText(AnnotatedString(path)) } },
    )
    SettingsReadOnlyRow(
        id = "updates.install-path",
        title = "This install",
        value = if (DesktopSettings.isPackaged()) "packaged build" else "running from source",
        summary = store.settings.path,
        highlighted = SettingsRowStyle.isHighlighted("updates.install-path", mark),
        modifier = revealMark("updates.install-path"),
    )
    SettingsReadOnlyRow(
        id = "updates.client-id",
        title = "Client id",
        value = store.settings.clientIdNow(),
        summary = "What the daemon lists this client's check-ins by.",
        highlighted = SettingsRowStyle.isHighlighted("updates.client-id", mark),
        modifier = revealMark("updates.client-id"),
    )

    ScopeOf("updates.core", store)
    CoreRow(store, mark)
}

/**
 * huginn core as this computer has it: the version carrying this app, and
 * whether it starts at boot. It answers what the retired "CLI sync" row did —
 * the core now lays the huginn command down and keeps it current.
 *
 * Read-only: the core is set up from Devices or `huginn core`, and updated with
 * everything else by `huginn update`.
 */
@Composable
private fun CoreRow(store: AppStore, mark: String?) {
    val via = store.viaCore
    val coreTray by store.coreDevice.coreTray.collectAsState()
    val installed = remember(coreTray) { CoreSetup.installedBinary() != null }
    var atBoot by remember { mutableStateOf<Boolean?>(null) }
    // Off the UI thread: on Windows the question is a `schtasks` call.
    LaunchedEffect(coreTray) { atBoot = withContext(Dispatchers.IO) { CoreSetup.serviceInstalled() } }
    SettingsReadOnlyRow(
        id = "updates.core",
        title = "huginn core",
        value = when {
            via != null -> via.coreVersion
            installed -> "not carrying this app"
            else -> "not installed"
        },
        summary = "Holds the link to huginn and runs this computer's work; the huginn command uses it too." +
            when (atBoot) {
                true -> " Starts at boot."
                false -> " Does not start at boot."
                null -> ""
            },
        highlighted = SettingsRowStyle.isHighlighted("updates.core", mark),
        modifier = revealMark("updates.core"),
    )
}

// -------------------------------------------------------------------- about

/** Four facts, none of them a control. */
@Composable
fun ColumnScope.AboutPage(store: AppStore, mark: String?) {
    val status by store.status.collectAsState()

    // This app's three facts, then the one about huginn: grouped by where
    // each comes from, as every other page now is.
    ScopeOf("about.version", store)
    SettingsReadOnlyRow(
        id = "about.version",
        title = "Version",
        value = store.updater.installedVersion,
        summary = "This app.",
        highlighted = SettingsRowStyle.isHighlighted("about.version", mark),
        modifier = revealMark("about.version"),
    )
    SettingsReadOnlyRow(
        id = "about.repo",
        title = "Source",
        value = "github.com/${store.updater.sourceRepo}",
        // ⚠ NEVER A SETTING. Where an installer may come from is a compile-time
        // constant on both clients by explicit security decision — a Settings
        // typo must not be able to move it.
        summary = "Where this build and its updates come from. Not editable, by design.",
        highlighted = SettingsRowStyle.isHighlighted("about.repo", mark),
        modifier = revealMark("about.repo"),
    )
    SettingsReadOnlyRow(
        id = "about.what-this-is",
        title = "What huginn is",
        summary = "A front end for Claude Code sessions running on your own machine.",
        highlighted = SettingsRowStyle.isHighlighted("about.what-this-is", mark),
        modifier = revealMark("about.what-this-is"),
    )
    ScopeOf("about.host-version", store)
    SettingsReadOnlyRow(
        id = "about.host-version",
        title = "Host version",
        value = status?.appdVersion ?: "not answering",
        summary = "The huginn daemon this client is talking to.",
        highlighted = SettingsRowStyle.isHighlighted("about.host-version", mark),
        modifier = revealMark("about.host-version"),
    )
}
