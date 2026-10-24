package com.silencelen.huginn.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What disappears, and when.
 *
 * HIDDEN, NEVER DISABLED. A greyed row is a promise the reader cannot cash, and
 * the audit found six of them across the two screens. The rule this asserts is
 * that a category with nothing reachable in it is not listed at all — a pre-3.0
 * daemon simply has no *Usage & headroom* row, rather than one that opens onto
 * an apology.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SettingsAvailabilityTest {

    private val nothing = SettingsProbe()

    private val all = SettingsProbe(
        headroom = true, quickActions = true, alerts = true, padsAvailable = true,
        localServe = true, appLockAvailable = true, enrolable = true,
        savedAccounts = 3, diagnostics = true, selfUpdate = true, keepAwake = true,
        accountSwitchOn = true, deviceOn = true, workScope = true, coreInstalled = true,
        apps = true, projects = true, signedIn = true, alertsRelayOn = true,
        deviceNotify = true, pushKnown = true, appLockOn = true,
    )

    /**
     * The degraded case: an unreachable or ancient host. What is left is the
     * honest answer — how to connect, what the token is, and what this program
     * is. Everything else would be a drawer that cannot do anything.
     */
    @Test
    fun anAllFalseProbeLeavesOnlyHostPrivacyAndAbout() {
        assertEquals(
            listOf("host", "privacy", "about"),
            SettingsCatalog.visibleCategories(nothing, Surface.BOTH).map { it.id },
        )
    }

    /** With everything answered, all nine are worth listing on both shells. */
    @Test
    fun aHealthyHostShowsAllNineOnBothShells() {
        for (surface in listOf(Surface.PHONE, Surface.DESKTOP)) {
            assertEquals(
                SettingsCatalog.categories.map { it.id },
                SettingsCatalog.visibleCategories(all, surface).map { it.id },
                "on $surface",
            )
        }
    }

    /** No headroom endpoint hides every usage row AND the category itself. */
    @Test
    fun headroomFalseHidesTheWholeUsageCategory() {
        val old = all.copy(headroom = false)
        assertTrue(
            SettingsCatalog.itemsOf("usage", old, Surface.PHONE).isEmpty(),
            "usage rows survived a daemon with no headroom endpoint",
        )
        assertTrue(
            SettingsCatalog.itemsOf("usage", old, Surface.DESKTOP).isEmpty(),
        )
        assertTrue("usage" !in SettingsCatalog.visibleCategories(old, Surface.PHONE).map { it.id })
        // …and with it, the category is gone rather than empty.
        assertTrue(SettingsCatalog.itemsOf("usage", all, Surface.PHONE).isNotEmpty())
    }

    /**
     * Rotation needs somewhere to rotate TO. One saved login is not a setting
     * that can do anything, and the phone already gated its toggle this way.
     */
    @Test
    fun accountSwitchingNeedsTwoLogins() {
        val one = all.copy(savedAccounts = 1)
        val rotation = listOf("usage.account-switch", "usage.switch-at", "usage.switch-margin")
        val shown = SettingsCatalog.itemsOf("usage", one, Surface.PHONE).map { it.id }
        assertTrue(rotation.none { it in shown }, "rotation offered with one login: $shown")
        assertTrue(shown.isNotEmpty(), "the rest of usage must survive")

        val two = all.copy(savedAccounts = 2)
        assertTrue(rotation.all { it in SettingsCatalog.itemsOf("usage", two, Surface.PHONE).map { i -> i.id } })
    }

    /**
     * The keep-awake details are gated on the SWITCH, not just on the endpoint:
     * the shared form draws them `if (draft.keepAwake)`, so a catalog that
     * offered them regardless put a search hit in front of a row its own page
     * hides.
     */
    @Test
    fun keepAwakeDetailsNeedTheSwitch() {
        val off = all.copy(keepAwake = false)
        val shown = SettingsCatalog.itemsOf("usage", off, Surface.PHONE).map { it.id }
        assertTrue("usage.keep-awake" in shown, "the switch itself has to stay reachable: $shown")
        assertTrue("usage.keep-awake-model" !in shown, "$shown")
        assertTrue("usage.keep-awake-quiet" !in shown, "$shown")
        assertTrue(shown.size > 3, "the rest of usage must survive: $shown")
    }

    @Test
    fun quickActionsNeedTheDaemonsTemplates() {
        // A daemon from before the templates is from before Apps and Projects
        // too, which now answer to their own routes rather than to this shelf.
        val old = all.copy(quickActions = false, apps = false, projects = false)
        assertTrue(SettingsCatalog.itemsOf("chats", old, Surface.DESKTOP).isEmpty())
        assertTrue("chats" !in SettingsCatalog.visibleCategories(old, Surface.DESKTOP).map { it.id })
    }

    @Test
    fun localServingIsOnlyOfferedWhereItCanHappen() {
        val no = all.copy(localServe = false)
        assertTrue("devices.local-ai" !in SettingsCatalog.itemsOf("devices", no, Surface.DESKTOP).map { it.id })
        // The rest of Devices is untouched.
        assertTrue("devices.this-machine" in SettingsCatalog.itemsOf("devices", no, Surface.DESKTOP).map { it.id })
        // A phone never serves models, so it never sees the row either.
        assertTrue("devices.local-ai" !in SettingsCatalog.itemsOf("devices", all, Surface.PHONE).map { it.id })
    }

    /**
     * "Keep act mode while locked" — the row that decides whether a lock screen
     * withdraws Act on this machine.
     *
     * Gated exactly like the scope row beside it, and for the same reason: both
     * describe what THIS computer will let a remote request do to it, so a shell
     * that cannot enrol has nothing for either of them to be about. A row that
     * outlived its siblings would be a switch with no machine behind it.
     */
    @Test
    fun actWhileLockedIsGatedLikeTheScopeRowItBelongsBeside() {
        for (probe in listOf(all, all.copy(localServe = false), all.copy(enrolable = false))) {
            val shown = SettingsCatalog.itemsOf("devices", probe, Surface.DESKTOP).map { it.id }
            assertEquals(
                "devices.scope" in shown,
                "devices.act-while-locked" in shown,
                "it must appear exactly where the scope it modifies does: $shown",
            )
        }
        // A phone is not a device. It never runs work, so it never answers this.
        assertTrue("devices.act-while-locked" !in SettingsCatalog.itemsOf("devices", all, Surface.PHONE).map { it.id })
    }

    /**
     * Findable by the words somebody would actually type — including "rdp",
     * which is the question that sends them looking: a machine reached over
     * remote desktop used to read as locked and refuse every Act.
     */
    @Test
    fun actWhileLockedIsSearchableByWhatWentWrong() {
        for (query in listOf("locked", "act while locked", "unattended", "rdp", "remote desktop")) {
            val hits = SettingsSearch.hits(query, all, Surface.DESKTOP).map { it.item.id }
            assertTrue("devices.act-while-locked" in hits, "searching \"$query\" found $hits")
        }
    }

    @Test
    fun appLockIsOnlyOfferedWhereTheOsCanDoIt() {
        val no = all.copy(appLockAvailable = false)
        val shown = SettingsCatalog.itemsOf("privacy", no, Surface.PHONE).map { it.id }
        assertTrue("privacy.app-lock" !in shown, "offered a lock the OS cannot do: $shown")
        assertTrue("privacy.lock-now" !in shown)
        // Privacy stays: the token explanation is always worth reading.
        assertTrue("privacy.token" in shown)
    }

    /** A store build has no update channel, so it has no update rows. */
    @Test
    fun aBuildWithNoUpdateChannelHasNoUpdateRows() {
        val store = all.copy(selfUpdate = false)
        assertTrue("updates.check" !in SettingsCatalog.itemsOf("updates", store, Surface.PHONE).map { it.id })
        // Diagnostics is a different question and survives.
        assertTrue("updates.copy-diagnostics" in SettingsCatalog.itemsOf("updates", store, Surface.PHONE).map { it.id })
    }

    /** Each shell sees its own rows and the shared ones; never the other's. */
    @Test
    fun surfaceFiltersBothWays() {
        val phone = SettingsCatalog.itemsOf("privacy", all, Surface.PHONE).map { it.id }
        val desktop = SettingsCatalog.itemsOf("privacy", all, Surface.DESKTOP).map { it.id }
        assertEquals(listOf("privacy.app-lock", "privacy.lock-now", "privacy.token"), phone)
        assertEquals(listOf("privacy.remove-this-computer", "privacy.token"), desktop)
    }

    /**
     * [Surface.BOTH] as a QUESTION asks for the universal half only. It is the
     * question a shared test or a shared summary wants, and reading it as a
     * union would make every "does this appear on both" check pass trivially.
     */
    @Test
    fun surfaceBothMeansTheUniversalHalf() {
        val shared = SettingsCatalog.itemsOf("privacy", all, Surface.BOTH).map { it.id }
        assertEquals(listOf("privacy.token"), shared)
    }

    @Test
    fun anUnknownCategoryIsEmptyRatherThanAThrow() {
        assertTrue(SettingsCatalog.itemsOf("nope", all, Surface.PHONE).isEmpty())
        assertEquals(null, SettingsCatalog.category("nope"))
        assertEquals(null, SettingsCatalog.item("nope.nope"))
    }

    // ------------------------------------------------ the 2026-10-23 audit
    //
    // The owner: "they are searchable but when clicking the result its not
    // present on the directed to settings page". Each test below is one row a
    // page draws only under a condition the catalog did not know, so search
    // offered it onto a page that had hidden it. (The desktop's own pages are
    // rendered and checked row by row in `SettingsPagesTest`, `:app-desktop`.)

    private fun shown(category: String, probe: SettingsProbe, surface: Surface) =
        SettingsCatalog.itemsOf(category, probe, surface).map { it.id }

    /** The form draws "Switch at" and the margin only while switching is on. */
    @Test
    fun switchingsThresholdsFollowTheSavedSwitch() {
        for (surface in listOf(Surface.PHONE, Surface.DESKTOP)) {
            val off = shown("usage", all.copy(accountSwitchOn = false), surface)
            assertTrue("usage.account-switch" in off, "the switch itself stays: $off")
            assertTrue("usage.switch-at" !in off && "usage.switch-margin" !in off, "on $surface: $off")
            val on = shown("usage", all, surface)
            assertTrue("usage.switch-at" in on && "usage.switch-margin" in on, "on $surface: $on")
        }
    }

    /**
     * Devices draws what this machine may do, act-while-locked and the path to
     * claude only while "Available to huginn" is on, and the Work folder only in
     * Work.
     */
    @Test
    fun theRowsUnderAvailableToHuginnFollowItsSwitch() {
        val off = shown("devices", all.copy(deviceOn = false), Surface.DESKTOP)
        assertTrue("devices.this-machine" in off, "the switch itself stays: $off")
        for (id in listOf("devices.scope", "devices.act-while-locked", "devices.claude-path", "devices.work-folder")) {
            assertTrue(id !in off, "$id offered while this machine is off: $off")
        }
        val look = shown("devices", all.copy(workScope = false), Surface.DESKTOP)
        assertTrue("devices.scope" in look && "devices.claude-path" in look, "$look")
        assertTrue("devices.work-folder" !in look, "the Work folder offered outside Work: $look")
        assertTrue("devices.work-folder" in shown("devices", all, Surface.DESKTOP))
    }

    @Test
    fun theTrayIconSwitchNeedsAnInstalledCore() {
        assertTrue("appearance.tray-icon" !in shown("appearance", all.copy(coreInstalled = false), Surface.DESKTOP))
        assertTrue("appearance.tray-icon" in shown("appearance", all, Surface.DESKTOP))
    }

    /**
     * Apps and Projects were gated on the status shelf while both shells draw
     * them only when their own route answers — and the desktop not at all.
     */
    @Test
    fun appsAndProjectsFollowTheirOwnRoutes() {
        for (surface in listOf(Surface.PHONE, Surface.DESKTOP)) {
            val none = shown("chats", all.copy(apps = false, projects = false), surface)
            assertTrue("chats.apps" !in none && "chats.projects" !in none, "on $surface: $none")
            assertTrue("chats.quick-actions" in none, "the rest of the drawer stays: $none")
            val both = shown("chats", all, surface)
            assertTrue("chats.apps" in both && "chats.projects" in both, "on $surface: $both")
        }
    }

    /** The phone's conditional rows: each one's own switch, never the host's. */
    @Test
    fun thePhonesConditionalRowsFollowWhatItsPagesTest() {
        assertTrue("host.sign-out" !in shown("host", all.copy(signedIn = false), Surface.PHONE), "nobody to sign out")
        assertTrue("host.sign-out" in shown("host", all, Surface.PHONE))

        val noRelay = shown("notify", all.copy(alertsRelayOn = false), Surface.PHONE)
        assertTrue("notify.host-alerts" in noRelay && "notify.host-alerts-mode" !in noRelay, "$noRelay")

        val quiet = shown("notify", all.copy(deviceNotify = false), Surface.PHONE)
        assertTrue("notify.device-watch" in quiet, "the switch itself stays: $quiet")
        for (id in listOf("notify.background", "notify.permission", "notify.delivery")) {
            assertTrue(id !in quiet, "$id offered while this phone does not notify: $quiet")
        }
        assertTrue("notify.delivery" !in shown("notify", all.copy(pushKnown = false), Surface.PHONE))

        val unlocked = shown("privacy", all.copy(appLockOn = false), Surface.PHONE)
        assertTrue("privacy.app-lock" in unlocked && "privacy.lock-now" !in unlocked, "$unlocked")
    }

    /**
     * ⚠ STILL NEVER THE HOST'S. The rows under the phone's own switch were left
     * ungated on purpose so an old or unreachable daemon could not hide the
     * switch that decides whether the phone notifies; gating them on the
     * switch must not have quietly made them the host's again.
     */
    @Test
    fun thePhonesOwnRowsSurviveADeadHost() {
        val dead = SettingsProbe(deviceNotify = true, pushKnown = true)
        val ids = shown("notify", dead, Surface.PHONE)
        assertTrue(ids.containsAll(listOf("notify.device-watch", "notify.background", "notify.permission", "notify.delivery")), "$ids")
    }

    /**
     * Every gated item's availability must actually READ the probe — a row that
     * ignores it is the greyed-out row this rule exists to prevent, wearing a
     * different hat.
     */
    @Test
    fun theCatalogAgreesWithItselfAboutWhatIsGated() {
        for (c in SettingsCatalog.categories) {
            for (item in c.items) {
                if (!item.availability(all)) {
                    // An item nothing can ever show is dead weight in the catalog.
                    assertTrue(false, "${item.id} is unreachable even with everything available")
                }
            }
        }
    }
}
