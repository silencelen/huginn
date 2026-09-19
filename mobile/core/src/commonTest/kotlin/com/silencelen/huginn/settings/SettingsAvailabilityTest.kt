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
        val old = all.copy(quickActions = false)
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
