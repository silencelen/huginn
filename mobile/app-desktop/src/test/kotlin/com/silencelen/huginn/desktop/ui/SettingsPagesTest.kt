package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.AccountSwitch
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.Presence
import com.silencelen.huginn.desktop.core.CoreBoot
import com.silencelen.huginn.desktop.ui.settings.SettingsPaneState
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.SettingsItem
import com.silencelen.huginn.settings.Surface
import com.silencelen.huginn.settings.matches
import com.silencelen.huginn.settings.scopeOn
import com.silencelen.huginn.ui.settings.scopeTag
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every settings search hit on the desktop opens a page that DRAWS what it found,
 * under the right scope heading.
 *
 * ⚠ THE BUG (owner, 2026-10-23): *"im finding multiple instances of settings
 * that should be appearing in our desktop app but dont, they are searchable but
 * when lcicking the result its not present on the directed to settings page."*
 * The catalog in `:core` decides what search offers and the pages are written by
 * hand, and nothing held the two together: through huginn core the Host page
 * stopped after the routes (no saved logins, no add-login, no Run setup again),
 * Apps and Projects were never drawn, "CLI sync" named a feature that was gone,
 * and five rows drawn only under a switch were offered with the switch off.
 *
 * So this test does what the owner did, for every row the catalog has, in
 * several worlds: type the row's title into the real search field, click the
 * real hit, and look at the real page that opens. The row must be there — by
 * its catalog id, which every settings row carries as its testTag — and the
 * nearest scope heading above it must be the one the catalog gives it.
 *
 * A world where every fact is yes must offer EVERY desktop row (or this test
 * would pass by finding nothing), and a world where the facts are no must offer
 * none of the rows they gate (or the real Settings wiring has lost a fact).
 *
 * Off the network and away from the machine: the store is never started, its
 * link is a socket path that does not exist, `user.home` points into a temp
 * directory for the run (the local-AI manager, the core binary and the autostart
 * entry are all looked for under it), and the host's answers are seeded.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class SettingsPagesTest {

    private data class World(
        val name: String,
        val viaCore: Boolean = true,
        val host: Boolean = true,
        val keepAwake: Boolean = true,
        val switching: Boolean = true,
        val logins: Int = 3,
        val deviceOn: Boolean = true,
        val deviceScope: String = "work",
        val apps: Boolean = true,
        val projects: Boolean = true,
        val coreInstalled: Boolean = true,
    )

    /** What happened to each catalog row in one world. */
    private class Seen {
        val offered = LinkedHashSet<String>()
        val notOffered = LinkedHashSet<String>()
        val problems = ArrayList<String>()
    }

    private val desktopItems: List<SettingsItem> = SettingsCatalog.items.filter { it.surface.matches(Surface.DESKTOP) }

    // ------------------------------------------------------------- the worlds

    /** Through huginn core, every fact yes. Every desktop row must be offered and drawn. */
    @Test
    fun `with everything on, every desktop row search offers is on the page it opens`() {
        val seen = check(World("everything on, through huginn core"))
        assertEquals(desktopItems.map { it.id }.toSet(), seen.offered, "search did not offer: ${seen.notOffered}")
    }

    /** The old direct link: the route list and the token field are this app's own again. */
    @Test
    fun `on the old direct link every row is drawn too`() {
        val seen = check(World("the direct link", viaCore = false))
        assertEquals(desktopItems.map { it.id }.toSet(), seen.offered, "search did not offer: ${seen.notOffered}")
    }

    /**
     * Every switch that hides rows, off. None of the rows it hides may be
     * offered — which is what checks that Settings feeds the catalog each fact
     * the pages draw under — and every row that IS offered must be drawn.
     */
    @Test
    fun `with every switch off, nothing hidden is offered and everything offered is drawn`() {
        val seen = check(
            World(
                "everything that can be off, off", keepAwake = false, switching = false, logins = 1,
                deviceOn = false, deviceScope = "look", apps = false, projects = false, coreInstalled = false,
            ),
        )
        val hidden = listOf(
            "usage.keep-awake-model", "usage.keep-awake-quiet", "usage.account-switch", "usage.switch-at",
            "usage.switch-margin", "devices.scope", "devices.act-while-locked", "devices.work-folder",
            "devices.claude-path", "appearance.tray-icon", "chats.apps", "chats.projects",
        )
        assertTrue(hidden.none { it in seen.offered }, "offered while hidden: ${hidden.filter { it in seen.offered }}")
        assertTrue("devices.this-machine" in seen.offered && "usage.keep-awake" in seen.offered, "${seen.offered}")
    }

    /** Machine on but not in Work: the folder is Work's alone. */
    @Test
    fun `outside Work the folder is neither offered nor drawn`() {
        val seen = check(World("looking only", deviceScope = "look"))
        assertTrue("devices.work-folder" in seen.notOffered, "${seen.offered}")
        assertTrue("devices.scope" in seen.offered && "devices.claude-path" in seen.offered, "${seen.offered}")
    }

    /** A host that does not answer leaves this computer's drawers, and they still hold. */
    @Test
    fun `against a dead host the local drawers still hold what they offer`() {
        val seen = check(World("a host that does not answer", host = false, logins = 0, apps = false, projects = false))
        assertTrue("updates.check" in seen.offered && "appearance.close-to-tray" in seen.offered, "${seen.offered}")
        assertTrue("usage.plan" in seen.notOffered && "notify.claim-route" in seen.notOffered, "${seen.offered}")
    }

    // ------------------------------------------------------------- the check

    private fun check(w: World): Seen {
        val seen = Seen()
        val tmp = Files.createTempDirectory("settings-pages").toFile()
        val oldHome = System.getProperty("user.home")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val home = File(tmp, "home").apply { mkdirs() }
            System.setProperty("user.home", home.path)
            if (w.coreInstalled) {
                File(home, ".local/bin/huginn").apply { parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
            }
            val settings = DesktopSettings(File(tmp, "settings.json"))
            settings.setDeviceEnabled(w.deviceOn)
            settings.setDeviceScope(w.deviceScope)
            val link = if (w.viaCore) {
                CoreBoot.Outcome.ViaCore(File(tmp, "no-core.sock").toPath(), "2.6.4", "https://huginn.invalid:8787")
            } else {
                CoreBoot.Outcome.Direct("a test")
            }
            // NEVER STARTED: nothing here may reach a daemon or a core.
            val store = AppStore(settings, Presence(), scope, link)
            seed(store, w)
            val state = SettingsPaneState(initial = null) { }
            seedLogins(state, w)

            runComposeUiTest { visit(w, store, state, seen) }
        } finally {
            scope.cancel()
            System.setProperty("user.home", oldHome)
            tmp.deleteRecursively()
        }
        assertTrue(seen.problems.isEmpty(), "${w.name}:\n" + seen.problems.joinToString("\n"))
        return seen
    }

    private fun ComposeUiTest.visit(w: World, store: AppStore, state: SettingsPaneState, seen: Seen) {
        // ⚠ THE CLOCK BY HAND. These pages hold endless work — huginn core's route
        // book is re-read every 10 s for as long as the Host page is open — and an
        // auto-advancing clock walks straight through it: waitForIdle never
        // returned. Frames are pushed a few per step instead; nothing here needs
        // time to pass for a row to be drawn.
        mainClock.autoAdvance = false
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag(NAV)) { SettingsNavPane(store, state) }
                    Box(Modifier.width(900.dp).fillMaxHeight().testTag(PAGE)) { SettingsView(store, state) }
                }
            }
        }
        settle()
        for (item in desktopItems) {
            runOnIdle { state.query = item.title }
            settle()
            // The hit's own title line in the result list — not the search field,
            // which holds the same words.
            val hit = hasText(item.title) and hasAnyAncestor(hasTestTag(NAV)) and !hasSetTextAction()
            val hits = onAllNodes(hit, useUnmergedTree = true)
            if (hits.fetchSemanticsNodes().isEmpty()) {
                seen.notOffered += item.id
                continue
            }
            seen.offered += item.id
            hits[0].performClick()
            settle()

            val tags = ArrayList<String>()
            onNodeWithTag(PAGE, useUnmergedTree = true).fetchSemanticsNode().walk(tags)
            val at = tags.indexOf(item.id)
            if (at < 0) {
                seen.problems += "${item.id} (\"${item.title}\") was offered, but ${state.selected} does not draw it"
                continue
            }
            val heading = tags.subList(0, at).lastOrNull { it.startsWith(SCOPE_PREFIX) }
            val want = scopeTag(item.scopeOn(Surface.DESKTOP, viaCore = w.viaCore))
            if (heading != want) {
                seen.problems += "${item.id} sits under ${heading ?: "no scope heading"}, the catalog says $want"
            }
        }
    }

    /** A few frames, then whatever layout they left pending. */
    private fun ComposeUiTest.settle() {
        repeat(3) { mainClock.advanceTimeByFrame() }
        waitForIdle()
    }

    /** Every testTag under [this], in the order the page lays them out. */
    private fun SemanticsNode.walk(out: MutableList<String>) {
        config.getOrNull(SemanticsProperties.TestTag)?.let { out += it }
        children.forEach { it.walk(out) }
    }

    // ------------------------------------------------------------- seeding

    /**
     * The host's answers, put where the poll would have put them. Reflection on
     * purpose: the store has no setter for what only the daemon may say, and a
     * renamed field fails here loudly rather than seeding nothing.
     */
    private fun seed(store: AppStore, w: World) {
        if (!w.host) return
        flow<Status?>(store, "_status").value = Status(
            appdVersion = "3.13.9",
            quickActions = QuickActions(rev = 1, explain = "e", execute = "x", askInNewChat = "a", quote = "q"),
        )
        flow<Headroom?>(store, "_headroom").value = Headroom(
            settings = HeadroomSettings(keepAwake = w.keepAwake, accountSwitch = AccountSwitch(enabled = w.switching)),
        )
        flow<List<Device>>(store, "_devices").value = listOf(Device(id = "1", machine = "m1", scope = "work", online = true))
        flow<Boolean?>(store, "_appsAvailable").value = w.apps
        flow<Boolean?>(store, "_projectsAvailable").value = w.projects
    }

    private fun seedLogins(state: SettingsPaneState, w: World) {
        delegate<List<SavedAccount>>(state, "savedAccounts").value =
            (1..w.logins).map { SavedAccount(slug = "s$it", email = "a$it@example.org", isActive = it == 1) }
        delegate<Account?>(state, "account").value = if (w.logins > 0) Account(loggedIn = true, email = "a1@example.org") else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(store: AppStore, field: String): MutableStateFlow<T> =
        AppStore::class.java.getDeclaredField(field).apply { isAccessible = true }.get(store) as MutableStateFlow<T>

    @Suppress("UNCHECKED_CAST")
    private fun <T> delegate(state: SettingsPaneState, property: String): MutableState<T> =
        SettingsPaneState::class.java.getDeclaredField("$property\$delegate").apply { isAccessible = true }.get(state) as MutableState<T>

    private companion object {
        const val NAV = "test.settings.nav"
        const val PAGE = "test.settings.page"
        const val SCOPE_PREFIX = "settings.scope."
    }
}
