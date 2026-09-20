package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.window.isTraySupported
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.ui.common.PaneScrollbar
import com.silencelen.huginn.desktop.ui.settings.AboutPage
import com.silencelen.huginn.desktop.ui.settings.AppearancePage
import com.silencelen.huginn.desktop.ui.settings.ChatsPage
import com.silencelen.huginn.desktop.ui.settings.DevicesPage
import com.silencelen.huginn.desktop.ui.settings.HostPage
import com.silencelen.huginn.desktop.ui.settings.NotifyPage
import com.silencelen.huginn.desktop.ui.settings.PrivacyPage
import com.silencelen.huginn.desktop.ui.settings.LocalSettingsReveal
import com.silencelen.huginn.desktop.ui.settings.SettingsFacts
import com.silencelen.huginn.desktop.ui.settings.SettingsPaneState
import com.silencelen.huginn.desktop.ui.settings.SettingsSummaries
import com.silencelen.huginn.desktop.ui.settings.UpdatesPage
import com.silencelen.huginn.desktop.ui.settings.UsagePage
import com.silencelen.huginn.desktop.ui.settings.desktopProbe
import com.silencelen.huginn.desktop.ui.settings.rememberSettingsReveal
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.Surface as SettingsSurface
import com.silencelen.huginn.ui.settings.SettingsCategoryPage
import com.silencelen.huginn.ui.settings.SettingsListPane
import com.silencelen.huginn.ui.settings.SettingsScaffoldRules
import com.silencelen.huginn.ui.settings.SettingsSearchField

/**
 * Settings, as the app's own list-plus-detail: nine drawers on the left, one page
 * at a time on the right.
 *
 * WHAT THIS REPLACED. One 1200-line composable drawing eleven `SectionHeader`
 * bands down a single scroll — 82 interactive controls and ~54 read-only rows
 * between the two clients, with account switching rendered three times in three
 * vocabularies and six settings-shaped controls living outside Settings
 * altogether. The owner's words: *"we keep adding different options / sections
 * and now it not only is crowded but the UI is hard to navigate."* A flat screen
 * cannot be told it is getting crowded; a catalog can, and `:core` holds it.
 *
 * WHY TWO ENTRY POINTS RATHER THAN ONE TWO-PANE COMPOSABLE. `:ui`'s
 * [com.silencelen.huginn.ui.settings.SettingsScaffold] can draw both halves and
 * the phone uses it that way. This shell does not, because its list pane is not a
 * column in a Row — it is the animated, clipped pane behind the seam, with the
 * notch, `Ctrl+B`, the persisted width and the under-700dp fold all hanging off
 * it. Drawing a SECOND two-pane idiom inside the first would mean Settings had a
 * seam that did nothing and a notch that hid the wrong thing. So [SettingsNavPane]
 * goes where every other list goes, [SettingsView] goes where every other detail
 * goes, [Splitter.showsList] says Settings has a list, and the narrow-window
 * collapse to list→detail arrives for free.
 *
 * WHAT THEY SHARE is [SettingsPaneState], held by the shell above both: the
 * selected drawer (written through to the settings file), the search query, and
 * the arrival mark a search hit leaves on one row.
 */

/**
 * The detail pane: one category's page.
 *
 * Also the pane that LOADS — it is composed for `View.SETTINGS` at every window
 * width, while the list is not (a narrow window folds it away), so an effect
 * living in the list would never run for the reader who most needs the summary.
 */
@Composable
fun SettingsView(store: AppStore, state: SettingsPaneState) {
    LaunchedEffect(store) {
        // `/v1/status` ALONE, not [AppStore.refreshStatus]: this only wants the
        // shelf that says whether the host has quick actions, a soft-end phrase
        // and which appd it is, and the full refresh drags `/v1/usage` behind it,
        // which walks every transcript on the host.
        store.refreshStatusShelf()
        state.loadAccounts(store)
    }

    val facts = settingsFacts(store, state)
    val shown = SettingsScaffoldRules.shown(desktopProbe(facts), SettingsSurface.DESKTOP, state.query)
    // The remembered drawer, unless it stopped existing — a daemon that answered
    // /v1/headroom on Monday and 404s on Tuesday must not strand the pane on an
    // empty page.
    val landing = SettingsScaffoldRules.landing(shown, state.selected)
    val category = landing?.let { SettingsCatalog.category(it) } ?: return

    val scroll = rememberScrollState()
    // ⚠ D-23. THE MARK IS HOISTED so the reveal can be keyed on it. A search hit
    // already decides which row matched and already tells this page to mark it;
    // what nothing did was move the pane to it. See [SettingsReveal].
    val mark = state.markFor(category.id)
    val reveal = rememberSettingsReveal(mark, state.arrival, scroll)
    Box(
        Modifier.fillMaxSize()
            // The scrolling pane's own top, which is the other half of the sum:
            // the row reports where it landed, this says where "the top of the
            // page" is, and the difference is how far to scroll.
            .onGloballyPositioned { reveal.anchorPane(it.positionInRoot().y) },
    ) {
        CompositionLocalProvider(LocalSettingsReveal provides reveal) {
            SettingsCategoryPage(title = category.title, blurb = category.blurb, scroll = scroll) {
                when (category.id) {
                    "host" -> HostPage(store, mark)
                    "usage" -> UsagePage(store, mark)
                    "chats" -> ChatsPage(store, mark)
                    "notify" -> NotifyPage(store, mark)
                    "devices" -> DevicesPage(store, mark)
                    "privacy" -> PrivacyPage(store, mark)
                    "appearance" -> AppearancePage(store, mark)
                    "updates" -> UpdatesPage(store, mark)
                    "about" -> AboutPage(store, mark)
                }
            }
        }
        // The pane whose content most often runs off the bottom with nothing
        // saying so — now on the page's own scroll state rather than a second one.
        PaneScrollbar(scroll)
    }
}

/**
 * The list pane: the search field, then the drawers — or, once something is
 * typed, the matches across all of them.
 */
@Composable
fun SettingsNavPane(store: AppStore, state: SettingsPaneState) {
    val facts = settingsFacts(store, state)
    val shown = SettingsScaffoldRules.shown(desktopProbe(facts), SettingsSurface.DESKTOP, state.query)

    Column(Modifier.fillMaxSize()) {
        SettingsSearchField(state.query, { state.query = it })
        SettingsListPane(
            shown = shown,
            selected = SettingsScaffoldRules.landing(shown, state.selected),
            summaryOf = { SettingsSummaries.of(it.id, facts) },
            onOpenCategory = state::open,
            onOpenHit = state::openHit,
        )
    }
}

/**
 * Everything the list needs to know about the live world, collected once per
 * pane from flows the store already keeps.
 *
 * The two that are NOT already kept — the signed-in account and the saved logins
 * — are fetched once into [SettingsPaneState] rather than re-fetched here, so
 * the two panes cannot ask the daemon the same question twice per frame.
 */
@Composable
private fun settingsFacts(store: AppStore, state: SettingsPaneState): SettingsFacts {
    val status by store.status.collectAsState()
    val headroom by store.headroom.collectAsState()
    val devices by store.devices.collectAsState()
    val route by store.route.collectAsState()
    val token by store.settings.tokenState.collectAsState()
    val present by store.presence.present.collectAsState()
    val notifyEnabled by store.settings.notifyEnabled.collectAsState(initial = true)
    val closeToTray by store.settings.closeToTray.collectAsState()
    val deviceEnabled by store.settings.deviceEnabled.collectAsState()
    val update by store.updater.state.collectAsState()

    return SettingsFacts(
        account = state.account,
        savedAccounts = state.savedAccounts,
        status = status,
        headroom = headroom,
        devices = devices,
        route = route,
        tokenSet = token.isNotBlank(),
        present = present,
        notifyEnabled = notifyEnabled,
        closeToTray = closeToTray,
        traySupported = isTraySupported,
        deviceEnabled = deviceEnabled,
        update = update,
        installedVersion = store.updater.installedVersion,
    )
}
