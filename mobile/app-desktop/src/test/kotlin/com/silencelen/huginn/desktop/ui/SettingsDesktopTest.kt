package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.HeadroomWorst
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.ui.settings.SettingsFacts
import com.silencelen.huginn.desktop.ui.settings.SettingsPaneState
import com.silencelen.huginn.desktop.ui.settings.SettingsSummaries
import com.silencelen.huginn.desktop.ui.settings.desktopProbe
import com.silencelen.huginn.desktop.update.UpdateState
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.SettingsSearch
import com.silencelen.huginn.settings.Surface
import com.silencelen.huginn.ui.settings.SettingsScaffoldRules
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop half of the Settings redesign, in the parts that can be wrong
 * without anything throwing.
 *
 * THREE KINDS OF FAILURE LIVE HERE, and none of them crash. A SUMMARY that reads
 * the wrong number is a line under a category title that quietly lies — "2 saved
 * logins" over one login, "up to date" over a pending install — and it is drawn
 * at 11sp where nobody proofreads it. A PROBE that is too generous puts a
 * category in the list that opens onto controls whose Save can only 404, which
 * the redesign names as the failure worth preventing. And a SELECTION that does
 * not survive the two panes — or a restart — sends the reader back to the top of
 * a nine-drawer list every time they glance away.
 *
 * All three are pure functions here BECAUSE they were made pure for this: the
 * facts arrive as data the shell already collects, so the decisions can be
 * asserted without a window, an Xvfb or a daemon.
 */
class SettingsDesktopTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun freshFile(): File {
        val dir = Files.createTempDirectory("huginn-settings-desktop").toFile()
        dirs += dir
        return File(dir, "settings.json")
    }

    // ------------------------------------------------------------- summaries

    private fun live() = SettingsFacts(
        account = Account(loggedIn = true, email = "jacob@monahanhosting.com"),
        savedAccounts = listOf(
            SavedAccount(slug = "a", email = "jacob@monahanhosting.com", isActive = true),
            SavedAccount(slug = "b", email = "other@example.com"),
        ),
        status = Status(
            appdVersion = "3.1.0",
            quickActions = QuickActions(rev = 2, explain = "e", execute = "x", askInNewChat = "a", quote = "q"),
        ),
        headroom = Headroom(
            worst = HeadroomWorst(percent = 37.4, label = "Weekly limit (Fable)"),
            settings = HeadroomSettings(),
        ),
        devices = listOf(
            Device(id = "1", machine = "m1", scope = "work", online = true),
            Device(id = "1-llm", machine = "m1", scope = "generate", online = true),
            Device(id = "2", machine = "m2", scope = "look"),
            Device(id = "3", machine = "m3", scope = "own", online = true),
        ),
        route = "http://100.97.198.90:8787",
        tokenSet = true,
        present = true,
        installedVersion = "1.1.0",
        update = UpdateState.UpToDate("1.1.0"),
    )

    @Test
    fun `the host line names the account and counts the logins`() {
        // The wireframe's own line. The email is shortened rather than ellipsised
        // by the Text: a 280dp row would otherwise cut the DOMAIN, which is the
        // half that says which household this is.
        assertEquals("jacob@… · 2 saved logins", SettingsSummaries.of("host", live()))
        assertEquals(
            "1 saved login",
            SettingsSummaries.of("host", live().copy(account = null, savedAccounts = live().savedAccounts.take(1))),
            "no account yet is not a reason to invent one",
        )
        assertEquals("jacob@…", SettingsSummaries.shortEmail("jacob@monahanhosting.com"))
        assertEquals("jo@h.io", SettingsSummaries.shortEmail("jo@h.io"), "a short address is not worth cutting")
    }

    @Test
    fun `the usage line reports the worst window and whether there is a ladder`() {
        assertEquals("Fable 37% · ladder set", SettingsSummaries.of("usage", live()))
        assertNull(
            SettingsSummaries.of("usage", live().copy(headroom = null)),
            "a daemon with no headroom has nothing to say, and 0% would be a lie",
        )
        assertEquals(
            "Fable 37% · no ladder",
            SettingsSummaries.of(
                "usage",
                live().copy(headroom = live().headroom!!.copy(settings = HeadroomSettings(ladder = listOf("fable")))),
            ),
            "one family is not a ladder to step down",
        )
    }

    @Test
    fun `the chats line counts the wordings that are actually set`() {
        assertEquals("4 quick actions", SettingsSummaries.of("chats", live()))
        val three = live().status!!.quickActions!!.copy(quote = "")
        assertEquals("3 quick actions", SettingsSummaries.of("chats", live().copy(status = live().status!!.copy(quickActions = three))))
        assertNull(SettingsSummaries.of("chats", live().copy(status = Status())))
    }

    @Test
    fun `the notifications line says whether this window is claiming`() {
        assertEquals("claiming · this window", SettingsSummaries.of("notify", live()))
        assertEquals(
            "not claiming · Telegram stays live",
            SettingsSummaries.of("notify", live().copy(present = false)),
            "a hidden window is not claiming, and that is not an error",
        )
        assertEquals("off · Telegram", SettingsSummaries.of("notify", live().copy(notifyEnabled = false)))
    }

    @Test
    fun `the devices line counts machines, not credential rows`() {
        // Three machines: m1 wears two enrolments (work + serving) and is ONE box.
        assertEquals("3 machines · 1 serving", SettingsSummaries.of("devices", live()))
        assertEquals(
            "no machines yet",
            SettingsSummaries.of("devices", live().copy(devices = emptyList())),
        )
    }

    @Test
    fun `the last three lines report this install`() {
        assertEquals("token saved here", SettingsSummaries.of("privacy", live()))
        assertEquals("no token saved", SettingsSummaries.of("privacy", live().copy(tokenSet = false)))
        assertEquals("close to tray on", SettingsSummaries.of("appearance", live()))
        assertEquals("closing quits", SettingsSummaries.of("appearance", live().copy(closeToTray = false)))
        // ⚠ THE SETTING IS NOT THE BEHAVIOUR ON A MACHINE WITH NO TRAY. `Main.kt`
        // has always been right — `if (closeToTray && isTraySupported) hide else
        // quit()` — and only the copy was wrong: the summary read "close to tray
        // on" and the row promised "Closing the window leaves huginn running in
        // the tray" on a box where closing the window quits. Verified: the
        // process was gone.
        assertEquals(
            "closing quits (no system tray here)",
            SettingsSummaries.of("appearance", live().copy(traySupported = false)),
            "a toggle that is on but cannot take effect must say so",
        )
        assertEquals(
            "closing quits (no system tray here)",
            SettingsSummaries.of("appearance", live().copy(closeToTray = false, traySupported = false)),
        )
        assertEquals("1.1.0 · up to date", SettingsSummaries.of("updates", live()))
        assertEquals(
            "1.1.0 · 1.2.0 ready to install",
            SettingsSummaries.of(
                "updates",
                live().copy(update = UpdateState.Ready("1.2.0", File("x"), "", installable = true)),
            ),
        )
        assertEquals("1.1.0 · appd 3.1.0", SettingsSummaries.of("about", live()))
    }

    // ----------------------------------------------------------------- probe

    @Test
    fun `a dead daemon leaves only what this machine can answer`() {
        // ⚠ A DEAD DAEMON ALSO BLANKS THE PROBE — every host-held tier reads
        // absent rather than false, which is exactly the nullable-with-default
        // rule the wire models follow. What is left is the five drawers that are
        // about THIS COMPUTER: how to connect, what the token is, how the window
        // behaves, the log and the report, and what this program is. They are
        // deliberately NOT gated on the host — the moment the daemon is the thing
        // that is broken is the moment Copy diagnostics matters most.
        val shown = SettingsCatalog.visibleCategories(desktopProbe(SettingsFacts()), Surface.DESKTOP)
        assertEquals(listOf("host", "privacy", "appearance", "updates", "about"), shown.map { it.id })
    }

    @Test
    fun `a 3-0-0 daemon has no usage and no quick actions`() {
        // The shape the redesign's own screenshot asks for: a host that answers
        // /v1/status but has neither the headroom tier nor the composer templates.
        val old = SettingsFacts(status = Status(appdVersion = "3.0.0"), tokenSet = true)
        val ids = SettingsCatalog.visibleCategories(desktopProbe(old), Surface.DESKTOP).map { it.id }

        assertTrue("usage" !in ids, "no /v1/headroom means no Usage drawer at all: $ids")
        assertTrue("chats" !in ids, "no templates means no Quick actions editor: $ids")
        assertTrue("notify" in ids, "the claim toggle is answerable against any live host: $ids")
        assertTrue("devices" in ids, "the fleet and this machine are both live-host facts: $ids")
        assertTrue(
            SettingsCatalog.itemsOf("usage", desktopProbe(old), Surface.DESKTOP).isEmpty(),
            "the seventeen headroom rows go with the drawer",
        )
    }

    @Test
    fun `rotation needs two logins, not one`() {
        val one = SettingsFacts(
            status = Status(appdVersion = "3.1.0"),
            headroom = Headroom(settings = HeadroomSettings()),
            savedAccounts = listOf(SavedAccount(slug = "a", isActive = true)),
        )
        val ids = SettingsCatalog.itemsOf("usage", desktopProbe(one), Surface.DESKTOP).map { it.id }
        assertTrue("usage.heads-up-pct" in ids)
        assertTrue("usage.account-switch" !in ids, "switching between one account is not a setting: $ids")
    }

    // ------------------------------------------------------------- selection

    @Test
    fun `the chosen category survives a restart`() {
        val file = freshFile()
        DesktopSettings(file).setSettingsSection("usage")
        // A second construction is what a relaunch does: same file, new object.
        assertEquals("usage", DesktopSettings(file).settingsSectionNow())
        assertEquals(
            "",
            DesktopSettings(freshFile()).settingsSectionNow(),
            "a fresh install has no opinion, and the frame lands on the first drawer",
        )
    }

    @Test
    fun `a search hit opens its category with the matched row marked`() {
        val state = SettingsPaneState(initial = null) { }
        val hit = SettingsSearch.hits("token", desktopProbe(live()), Surface.DESKTOP).first()

        state.openHit(hit)

        assertEquals("host", state.selected)
        assertEquals("host.token", state.markFor("host"))
        assertNull(state.markFor("privacy"), "the mark belongs to the drawer it was found in")

        // And opening a category by hand is not an arrival: nothing is marked.
        state.open("about")
        assertEquals("about", state.selected)
        assertNull(state.markFor("about"))
    }

    @Test
    fun `the selection is written through as it is made`() {
        val written = mutableListOf<String>()
        val state = SettingsPaneState(initial = "usage") { written += it }
        assertEquals("usage", state.selected, "a restored section is the opening one")

        state.open("devices")

        assertEquals(listOf("devices"), written, "the persisted section follows the pane, not a Save button")
    }

    @Test
    fun `a category that stopped existing does not strand the detail pane`() {
        // The daemon that answered /v1/headroom on Monday and 404s on Tuesday,
        // with Usage the remembered drawer. The frame lands on the first one that
        // IS there rather than drawing an empty page.
        val shown = SettingsScaffoldRules.shown(desktopProbe(SettingsFacts()), Surface.DESKTOP, "")
        assertEquals("host", SettingsScaffoldRules.landing(shown, "usage"))
        assertEquals("privacy", SettingsScaffoldRules.landing(shown, "privacy"))
        assertNotNull(SettingsScaffoldRules.landing(shown, null))
    }
}
