package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.Autoswitch
import com.silencelen.huginn.data.LoginSession
import com.silencelen.huginn.data.LoginState
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.notify.Heartbeat
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.Surface as SettingsSurface
import com.silencelen.huginn.ui.HuginnViewModel
import com.silencelen.huginn.ui.groupByMachine
import com.silencelen.huginn.update.AppUpdateState

/**
 * Settings on the phone: nine drawers, a search field pinned above them, and one
 * drawer open at a time.
 *
 * WHAT THIS REPLACED. One 911-line composable behind a 41-parameter signature,
 * drawn as a single flat scroll under nine inline headers — 26 interactive
 * controls and 24 read-only rows with no hierarchy, which is why the owner could
 * not find anything in it and why nobody could see it getting worse. The
 * signature is deleted. Each page below takes only what it needs, the catalog in
 * `:core` decides what exists, and the frame is `:ui`'s and shared with the
 * desktop, so the two shells can no longer disagree about what a setting is
 * called or where it lives.
 *
 * WHY IT TAKES THE VIEW MODEL. Because everything on this screen is live state
 * owned by one object, and the alternative is the parameter list this redesign
 * exists to delete. The PAGES take plain values; only the shell, which is where
 * the state already is, knows the view model.
 *
 * ⚠ SEARCH IS ALWAYS VISIBLE (owner decision, Q1). Nine drawers is exactly the
 * count where search stops being decoration; an action icon costs a tap to
 * discover the one thing that helps somebody who does not know where a setting
 * lives. The query lives in the caller's `rememberSaveable`, so a fold does not
 * discard it.
 */
@Composable
fun SettingsPhoneScreen(
    vm: HuginnViewModel,
    /** null = the list of drawers. Non-null = one open, by catalog id. */
    section: String?,
    onSelectCategory: (String?) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    /** List and detail side by side. The Fold's inner display; a phone passes false. */
    twoPane: Boolean,
    nowMs: Long,
    appVersion: String,
    appdVersion: String?,
    appLockAvailable: Boolean,
    notificationsAllowed: Boolean,
    onOpenFleet: () -> Unit,
    /** The Projects list. Null when the daemon has no projects route. */
    onOpenProjects: (() -> Unit)? = null,
    projectCount: Int = 0,
    onOpenStatus: () -> Unit,
    onLockNow: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    openLink: (String) -> Boolean,
) {
    // Re-read on every visit rather than once: the Doze exemption is held by the
    // system and can be revoked outside this app, so a cached "granted" would go
    // on reassuring long after it stopped being true. The update check is
    // check-only (no download), so it never spends data.
    LaunchedEffect(Unit) {
        vm.refreshAccount(); vm.refreshDelivery(); vm.refreshAutoswitch(); vm.checkForUpdate()
        vm.refreshHeadroom(); vm.refreshModels()
    }

    val baseUrl by vm.baseUrl.collectAsStateWithLifecycle()
    val token by vm.token.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val routeBook by vm.routeBook.collectAsStateWithLifecycle()
    val routeHealth by vm.routeHealth.collectAsStateWithLifecycle()
    val routeNote by vm.routeNote.collectAsStateWithLifecycle()
    val resolvingRoute by vm.resolvingRoute.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val savedAccounts by vm.savedAccounts.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val headroom by vm.headroom.collectAsStateWithLifecycle()
    val headroomSaving by vm.headroomSaving.collectAsStateWithLifecycle()
    val headroomNote by vm.headroomNote.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val quickActionsSaving by vm.quickActionsSaving.collectAsStateWithLifecycle()
    val quickActionsNote by vm.quickActionsNote.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val notifyEnabled by vm.notifyEnabled.collectAsStateWithLifecycle()
    val watchEnabled by vm.watchEnabled.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val push by vm.push.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val appLock by vm.appLock.collectAsStateWithLifecycle()
    val fontScale by vm.fontScale.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val padsAvailable by vm.scratchpadsAvailable.collectAsStateWithLifecycle()

    // Machines, and only the ones the sentence is TRUE of: "can run work" is the
    // claude capability, which a serve-only machine does not have.
    val machines = groupByMachine(devices)
    val deviceCount = machines.count { g -> g.rows.any { r -> r.scope != "generate" } }
    val servingCount = machines.count { g -> g.rows.any { r -> r.scope == "generate" } }

    val facts = PhoneSettingsFacts(
        baseUrl = baseUrl,
        routeName = routeBook.activeName,
        connected = connected,
        accountEmail = account?.takeIf { it.loggedIn }?.email,
        savedAccounts = savedAccounts.size,
        headroomSettings = headroom?.settings,
        worst = status?.headroom,
        quickActions = status?.quickActions,
        alerts = alerts,
        notifyEnabled = notifyEnabled,
        watchEnabled = watchEnabled,
        notificationsAllowed = notificationsAllowed,
        dozeExempt = health.dozeExempt,
        pushConfigured = push?.configured == true,
        pushRegistered = push?.devices?.isNotEmpty() == true,
        pushesSent = health.pushesSent,
        pushesReceived = health.pushesReceived,
        heartbeatIntervalMs = Heartbeat.intervalFor(health.pushesSent, health.pushesReceived),
        deviceCount = deviceCount,
        servingCount = servingCount,
        appLock = appLock,
        appLockAvailable = appLockAvailable,
        fontScale = fontScale,
        appVersion = appVersion,
        appdVersion = appdVersion ?: status?.appdVersion,
        updateWord = updateWord(updateState),
        updateRepo = vm.updateSourceRepo,
        lastContactAt = health.lastContactAt,
        lastAlarmAt = health.lastAlarmAt,
        lastError = health.lastError,
        lastErrorAt = health.lastErrorAt,
        hostSawUsSeconds = clients?.clients?.firstOrNull()?.ageSeconds,
        hostSawUsKind = clients?.clients?.firstOrNull()?.kind,
        padsAvailable = padsAvailable,
    )
    val probe = phoneProbe(facts)
    val accountsIo = remember(vm) { PhoneAccountsIo(vm) }
    // One bundle rather than eight lambdas threaded through HostPage, and
    // remembered on the view model so the list does not rebuild its callbacks on
    // every recomposition of a page it is not even on.
    val routeActions = remember(vm) {
        RouteListActions(
            activate = { vm.activateRoute(it) },
            rename = { id, name -> vm.renameRoute(id, name) },
            setUrl = { id, url -> vm.setRouteUrl(id, url) },
            move = { id, delta -> vm.moveRoute(id, delta) },
            remove = { vm.removeRoute(it) },
            add = { name, url -> vm.addRoute(name, url) },
            setAutoSwitch = { vm.setAutoSwitch(it) },
            findLive = { vm.resolveRoute(force = true) },
            // One Save, one book operation, and a refusal that stays on the form (edge #64/#81).
            editBoth = { id, name, url -> vm.editRoute(id, name, url) },
            clearNote = { vm.clearRouteNote() },
        )
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        SettingsScaffold(
            probe = probe,
            surface = SettingsSurface.PHONE,
            selectedCategory = section,
            onSelectCategory = onSelectCategory,
            query = query,
            onQuery = onQuery,
            summaryOf = { c -> phoneSummary(c, facts) },
            twoPane = twoPane,
        ) { categoryId, highlight ->
            val category = SettingsCatalog.category(categoryId)
            SettingsCategoryPage(
                title = category?.title ?: "Settings",
                blurb = category?.blurb,
            ) {
                when (categoryId) {
                    "host" -> HostPage(
                        routeBook = routeBook,
                        routeHealth = routeHealth,
                        routeNote = routeNote,
                        nowMs = nowMs,
                        token = token,
                        connected = connected,
                        resolvingRoute = resolvingRoute,
                        routeActions = routeActions,
                        onSave = { t -> vm.saveSettings(t) },
                        accountsIo = accountsIo,
                        openLink = openLink,
                        signedIn = account?.loggedIn == true,
                        onSignOut = { vm.logout() },
                        highlight = highlight,
                    )

                    "usage" -> UsagePage(
                        settings = headroom?.settings,
                        models = models,
                        busy = headroomSaving,
                        note = headroomNote,
                        onSave = { vm.saveHeadroomSettings(it) },
                        onOpenStatus = onOpenStatus,
                        highlight = highlight,
                    )

                    "chats" -> ChatsPage(
                        quickActions = status?.quickActions,
                        busy = quickActionsSaving,
                        note = quickActionsNote,
                        onSaveQuickActions = { vm.setQuickActions(it) },
                        softEndPhrase = status?.softEndPhrase,
                        softEndAuto = status?.softEndAuto == true,
                        highlight = highlight,
                        onOpenProjects = onOpenProjects,
                        projectCount = projectCount,
                    )

                    "notify" -> NotifyPage(
                        alerts = alerts,
                        onAlertsEnabled = { vm.setAlertsEnabled(it) },
                        onAlertsMode = { vm.setAlertsMode(it) },
                        notifyEnabled = notifyEnabled,
                        onNotifyEnabled = { vm.setNotifyEnabled(it) },
                        watchEnabled = watchEnabled,
                        onWatchEnabled = { vm.setWatchEnabled(it) },
                        health = health,
                        push = push,
                        clients = clients,
                        nowMs = nowMs,
                        onRequestDozeExemption = { vm.requestDozeExemption() },
                        onRefreshDelivery = { vm.refreshDelivery() },
                        notificationsAllowed = notificationsAllowed,
                        onRequestNotifications = onRequestNotifications,
                        onOpenSystemNotificationSettings = onOpenSystemNotificationSettings,
                        highlight = highlight,
                    )

                    "devices" -> DevicesPage(
                        deviceCount = deviceCount,
                        servingCount = servingCount,
                        onOpenFleet = onOpenFleet,
                        highlight = highlight,
                    )

                    "privacy" -> PrivacyPage(
                        appLock = appLock,
                        appLockAvailable = appLockAvailable,
                        onAppLock = { vm.setAppLock(it) },
                        onLockNow = onLockNow,
                        highlight = highlight,
                    )

                    "appearance" -> AppearancePage(
                        fontScale = fontScale,
                        onFontScale = { vm.setFontScale(it) },
                        highlight = highlight,
                    )

                    "updates" -> UpdatesPage(
                        state = updateState,
                        installedVersion = appVersion.ifBlank { vm.installedVersion },
                        repo = vm.updateSourceRepo,
                        onCheck = { vm.checkForUpdate() },
                        onDownload = { vm.downloadUpdate() },
                        onInstall = { vm.installUpdate() },
                        // Built at the moment it is pressed: a bundle assembled
                        // from a cached snapshot describes a phone that no longer
                        // exists, and the whole point is the facts as they are.
                        onCopyDiagnostics = {
                            vm.copy(diagnosticsBundle(facts, System.currentTimeMillis()), "huginn diagnostics")
                        },
                        highlight = highlight,
                    )

                    "about" -> AboutPage(
                        appVersion = appVersion.ifBlank { vm.installedVersion },
                        appdVersion = appdVersion ?: status?.appdVersion,
                        repo = vm.updateSourceRepo,
                        highlight = highlight,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The updater's state in the words a summary line and a diagnostics bundle use. */
internal fun updateWord(state: AppUpdateState): String = when (state) {
    is AppUpdateState.Idle -> ""
    is AppUpdateState.Checking -> "checking"
    is AppUpdateState.UpToDate -> "up to date"
    is AppUpdateState.Available -> "${state.versionName} available"
    is AppUpdateState.Downloading -> "downloading"
    is AppUpdateState.Ready -> "${state.versionName} ready to install"
    is AppUpdateState.Error -> "update check failed"
}

/**
 * [AccountsIo] over this phone's daemon connection. Nothing but forwarding.
 *
 * It goes through the view model rather than holding a client of its own so that
 * a switch made in this editor invalidates the same caches every other switch
 * does — the editor reloads itself, and the rest of the app hears about it.
 */
private class PhoneAccountsIo(private val vm: HuginnViewModel) : AccountsIo {
    override suspend fun account(): Account = vm.fetchAccount()

    // withPlan: the weekly headroom per saved login is the only number that makes
    // the list worth reading — it is what says which one to switch TO.
    override suspend fun savedAccounts(): List<SavedAccount> = vm.fetchSavedAccounts()
    override suspend fun autoswitch(): Autoswitch = vm.fetchAutoswitch()
    override suspend fun refreshAccount(slug: String): String = vm.refreshAccountToken(slug)
    override suspend fun activateAccount(slug: String) { vm.activateAccountNow(slug) }
    override suspend fun forgetAccount(slug: String) { vm.forgetAccountNow(slug) }
    override suspend fun startLogin(email: String?): LoginSession = vm.startLoginNow(email)
    override suspend fun submitLoginCode(code: String): LoginState = vm.submitLoginCodeNow(code)
}
