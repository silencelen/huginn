package com.silencelen.huginn.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.silencelen.huginn.desktop.CliSync
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.View
import androidx.compose.ui.window.isTraySupported
import com.silencelen.huginn.desktop.diag.AppLog
import com.silencelen.huginn.desktop.notify.Notifiers
import com.silencelen.huginn.desktop.ui.Muted
import com.silencelen.huginn.desktop.ui.common.openInBrowser
import com.silencelen.huginn.desktop.update.UpdateState
import com.silencelen.huginn.desktop.update.installThenQuit
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
import com.silencelen.huginn.ui.settings.SettingsToggleRow
import com.silencelen.huginn.ui.settings.SettingsRowStyle
import kotlinx.coroutines.launch
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
 */
@Composable
fun ColumnScope.HostPage(store: AppStore, mark: String?) {
    val settings = store.settings
    val scope = rememberCoroutineScope()
    val book by store.routeBook.collectAsState()
    val health by store.routeHealth.collectAsState()
    val resolving by store.resolvingRoute.collectAsState()
    val routeNote by store.routeNote.collectAsState()

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
            )
        },
        health = health,
        nowMs = System.currentTimeMillis(),
        summary = "The addresses that reach huginn, tried in this order.",
        highlighted = SettingsRowStyle.isHighlighted("host.route", mark),
        finding = resolving,
        note = routeNote,
        suggestedUrl = HuginnSettings.DEFAULT_BASE_URL,
    )
    SettingsFieldRow(
        id = "host.token",
        title = "Token",
        value = token,
        onValueChange = { token = it },
        secret = true,
        summary = "The bearer this app sends with every request.",
        highlighted = SettingsRowStyle.isHighlighted("host.token", mark),
        trailing = {
            Button(onClick = { scope.launch { settings.setToken(token); message = "token saved" } }) {
                Text("Save token")
            }
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

    // The saved logins and the three-step add, from `:ui`. `showAutoswitch =
    // false`: account switching now has ONE rendering, in Usage & headroom,
    // where its threshold and margin are also editable.
    AccountsEditor(
        io = remember(store) { DesktopAccountsIo(store) },
        openLink = ::openInBrowser,
        showAutoswitch = false,
        modifier = Modifier.padding(top = 10.dp),
    )

    // The door back into the first-run flow. It lives HERE, under the address
    // and the token, because those two are the steps somebody comes to this
    // page to fix — and re-checking them is what the flow does first.
    RunSetupAgainRow(mark)
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

    // The numbers themselves live on Status — this page is where the RULES are,
    // and a reader who wants the figure should be sent rather than shown a copy
    // that is as old as the last visit to the other pane.
    SettingsNavRow(
        id = "usage.plan",
        title = "Plan usage and tokens",
        onOpen = { store.openView(View.STATUS) },
        summary = "What the plan is and how much of it is spent.",
        highlighted = SettingsRowStyle.isHighlighted("usage.plan", mark),
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
        modifier = Modifier.padding(top = 8.dp),
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

    if (actions != null) {
        QuickActionsEditor(
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
        modifier = Modifier.padding(top = 10.dp),
    )
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

    SettingsToggleRow(
        id = "notify.claim-route",
        title = "Claim the notification route",
        checked = notifyEnabled,
        onCheckedChange = { scope.launch { settings.setNotifyEnabled(it) } },
        summary = if (!notifyEnabled) "off — huginn falls back to Telegram"
        else if (present) "claiming: this window has been attended recently"
        else "not claiming: window hidden or unattended, so Telegram stays live",
        highlighted = SettingsRowStyle.isHighlighted("notify.claim-route", mark),
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
    )
}

// ------------------------------------------------------------------ devices

/** This machine's own enrolment and serving, then the way to everything else. */
@Composable
fun ColumnScope.DevicesPage(store: AppStore, mark: String?) {
    val devices by store.devices.collectAsState()

    SettingsNavRow(
        id = "devices.fleet",
        title = "All devices",
        onOpen = { store.openView(View.DEVICES) },
        summary = "Every machine enrolled with huginn, and what each may do.",
        highlighted = SettingsRowStyle.isHighlighted("devices.fleet", mark),
        trailingText = devices.takeIf { it.isNotEmpty() }?.let { SettingsSummaries.of("devices", SettingsFacts(devices = it)) },
    )

    DeviceSection(store)
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

    SettingsReadOnlyRow(
        id = "privacy.token",
        title = "What the token is",
        value = if (store.settings.tokenNow().isNotBlank()) "saved on this computer" else "not set",
        summary = "One bearer, kept in ${store.settings.path} at mode 0600 and sent with every " +
            "request. It is root-equivalent on the daemon, which is why the address it travels to " +
            "is on an allowlist.",
        highlighted = SettingsRowStyle.isHighlighted("privacy.token", mark),
    )

    SettingsActionRow(
        id = "privacy.remove-this-computer",
        title = "Remove this computer's access",
        actionLabel = "Remove",
        onAction = { confirming = true },
        enabled = !busy,
        destructive = true,
        summary = "Unenrols this machine from huginn and forgets the token here. Your chats " +
            "and sessions stay on huginn.",
        highlighted = SettingsRowStyle.isHighlighted("privacy.remove-this-computer", mark),
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
                                    outcome = "could not reach huginn (${it.message}) — " +
                                        "nothing changed here; try again when it answers"
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
            !isTraySupported ->
                "There is no system tray on this computer, so closing the window quits huginn " +
                    "and the watch stream stops with it."
            closeToTray -> "Closing the window leaves huginn running in the tray."
            else -> "Closing the window quits huginn, and the watch stream stops with it."
        },
        highlighted = SettingsRowStyle.isHighlighted("appearance.close-to-tray", mark),
    )
    SettingsNavRow(
        id = "appearance.shortcuts",
        title = "Keyboard shortcuts",
        onOpen = { store.openCheatsheet() },
        summary = "Every chord this window answers to.",
        highlighted = SettingsRowStyle.isHighlighted("appearance.shortcuts", mark),
        trailingText = "F1",
    )
    // Beside close-to-tray on purpose: the two of them are the whole answer to
    // "is this thing running when I am not looking at it".
    StartupRow(store, mark)
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

    Text(
        when (val s = state) {
            UpdateState.Idle -> "installed ${store.updater.installedVersion} · not checked yet"
            UpdateState.Checking -> "checking…"
            is UpdateState.UpToDate -> "up to date (${s.version})"
            is UpdateState.Downloading -> "downloading ${s.version}…"
            is UpdateState.Ready -> "${s.version} downloaded and verified"
            is UpdateState.Error -> "update check failed: ${s.message}"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 8.dp),
    )
    (state as? UpdateState.Downloading)?.fraction?.let { f ->
        LinearProgressIndicator(progress = { f }, modifier = Modifier.padding(top = 6.dp, start = 8.dp).width(280.dp))
    }
    (state as? UpdateState.Ready)?.let { ready ->
        if (ready.notes.isNotBlank()) Muted(ready.notes, Modifier.padding(top = 4.dp, start = 8.dp), maxLines = 4)
    }
    Row(Modifier.padding(top = 8.dp, start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            onClick = { scope.launch { store.updater.check() } },
        ) { Text("Check now") }
        (state as? UpdateState.Ready)?.let { ready ->
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
            "downloaded to ${it.file.absolutePath} — install it by hand on this platform",
            Modifier.padding(top = 4.dp, start = 8.dp),
            maxLines = 2,
        )
    }

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
        modifier = Modifier.padding(top = 12.dp),
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
        maxLines = 4,
        onCopy = AppLog.path?.let { path -> { clipboard.setText(AnnotatedString(path)) } },
    )
    SettingsReadOnlyRow(
        id = "updates.install-path",
        title = "This install",
        value = if (DesktopSettings.isPackaged()) "packaged build" else "running from source",
        summary = store.settings.path,
        highlighted = SettingsRowStyle.isHighlighted("updates.install-path", mark),
    )
    SettingsReadOnlyRow(
        id = "updates.client-id",
        title = "Client id",
        value = store.settings.clientIdNow(),
        summary = "What the daemon lists this client's check-ins by.",
        highlighted = SettingsRowStyle.isHighlighted("updates.client-id", mark),
    )
    // What the launch-time CLI sync did, when it did anything: the CLI on this
    // machine rides along with the app instead of aging in place.
    CliSync.summary.collectAsState().value?.let {
        SettingsReadOnlyRow(
            id = "updates.cli-sync",
            title = "CLI sync",
            value = it,
            highlighted = SettingsRowStyle.isHighlighted("updates.cli-sync", mark),
        )
    }
}

// -------------------------------------------------------------------- about

/** Four facts, none of them a control. */
@Composable
fun ColumnScope.AboutPage(store: AppStore, mark: String?) {
    val status by store.status.collectAsState()

    SettingsReadOnlyRow(
        id = "about.version",
        title = "Version",
        value = store.updater.installedVersion,
        summary = "This app.",
        highlighted = SettingsRowStyle.isHighlighted("about.version", mark),
    )
    SettingsReadOnlyRow(
        id = "about.host-version",
        title = "Host version",
        value = status?.appdVersion ?: "not answering",
        summary = "The huginn daemon this client is talking to.",
        highlighted = SettingsRowStyle.isHighlighted("about.host-version", mark),
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
    )
    SettingsReadOnlyRow(
        id = "about.what-this-is",
        title = "What huginn is",
        summary = "A front end for Claude Code sessions running on your own machine.",
        highlighted = SettingsRowStyle.isHighlighted("about.what-this-is", mark),
    )
}
