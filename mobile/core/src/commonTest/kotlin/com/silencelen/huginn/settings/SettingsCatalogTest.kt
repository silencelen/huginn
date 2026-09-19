package com.silencelen.huginn.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE GATE AGAINST THE NEXT SIX MONTHS OF ACCRETION.
 *
 * The redesign was called in because "we keep adding different options and
 * sections": 82 interactive controls across two flat scrolls, account switching
 * drawn three times in three vocabularies, six settings-shaped controls living
 * outside Settings, and a persisted `fontScale` with zero readers and zero
 * writers. None of that was visible from inside either screen — a flat Column
 * cannot be told it is getting crowded.
 *
 * So the catalog is asserted rather than trusted: every control the inventory
 * found belongs to exactly one item, every item to exactly one category, and no
 * category may be empty on a shell that lists it.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SettingsCatalogTest {

    /** Everything the probe can say yes to, for "does this row exist at all". */
    private val all = SettingsProbe(
        headroom = true, quickActions = true, alerts = true, padsAvailable = true,
        localServe = true, appLockAvailable = true, enrolable = true,
        savedAccounts = 3, diagnostics = true, selfUpdate = true,
    )

    /**
     * The redesign's Part 1 inventory numbered the two Settings screens 1-61.
     * Every one of those controls has a home, and exactly one.
     */
    @Test
    fun everyInventoriedControlAppearsExactlyOnce() {
        val seen = HashMap<Int, MutableList<String>>()
        for (item in SettingsCatalog.items) {
            for (n in item.inventory) seen.getOrPut(n) { ArrayList() } += item.id
        }

        val missing = (1..61).filter { it !in seen }
        assertTrue(missing.isEmpty(), "inventory numbers with no catalog item: $missing")

        val twice = seen.filterValues { it.size > 1 }
        assertTrue(twice.isEmpty(), "inventory numbers claimed by more than one item: $twice")

        val invented = seen.keys.filter { it !in 1..61 }
        assertTrue(invented.isEmpty(), "inventory numbers outside the 1-61 the audit found: $invented")
    }

    @Test
    fun noDuplicateItemIds() {
        val ids = SettingsCatalog.items.map { it.id }
        val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(dupes.isEmpty(), "an id names one setting: $dupes")
    }

    @Test
    fun noDuplicateCategoryIds() {
        val ids = SettingsCatalog.categories.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate category ids in $ids")
    }

    /**
     * A category with nothing in it on either shell is a drawer nobody can open,
     * and it is how the tidy-up that preceded this one left three dead headers.
     */
    @Test
    fun noCategoryIsEmptyOnBothSurfaces() {
        for (c in SettingsCatalog.categories) {
            val phone = SettingsCatalog.itemsOf(c.id, all, Surface.PHONE)
            val desktop = SettingsCatalog.itemsOf(c.id, all, Surface.DESKTOP)
            assertTrue(
                phone.isNotEmpty() || desktop.isNotEmpty(),
                "${c.id} draws nothing on either shell",
            )
        }
    }

    /** The nine the owner signed off. Eight would be a different decision. */
    @Test
    fun theNineCategoriesInOrder() {
        assertEquals(
            listOf("host", "usage", "chats", "notify", "devices", "privacy", "appearance", "updates", "about"),
            SettingsCatalog.categories.map { it.id },
        )
    }

    /**
     * An id is `<category>.<slug>`. Not decoration: the phone saves
     * `settings:<category>` as a destination and a search hit carries the item
     * id into it, so an id that does not name its own category cannot be routed.
     */
    @Test
    fun everyItemIdIsPrefixedByItsCategory() {
        for (c in SettingsCatalog.categories) {
            for (item in c.items) {
                assertTrue(
                    item.id.startsWith("${c.id}."),
                    "${item.id} sits in ${c.id} but does not say so",
                )
                assertTrue(
                    item.id.removePrefix("${c.id}.").let { it.isNotEmpty() && '.' !in it },
                    "${item.id} is not a single dotted slug",
                )
            }
        }
    }

    @Test
    fun categoryOfFindsTheOwnerAndNothingElse() {
        assertEquals("host", SettingsCatalog.categoryOf("host.token")?.id)
        assertEquals("privacy", SettingsCatalog.categoryOf("privacy.app-lock")?.id)
        assertEquals(null, SettingsCatalog.categoryOf("host.nonexistent"))
    }

    /**
     * ⚠ IDS ARE IMMUTABLE, AND THIS IS HOW THAT PROMISE SURVIVES A MERGE.
     * `host.base-url` named its own field until routes made the address a
     * property of a pin. A saved destination or an old search hit carrying that
     * id has to keep arriving somewhere real rather than onto a blank page — so
     * it resolves to the row that absorbed it rather than to nothing.
     */
    @Test
    fun aRetiredIdStillResolvesToTheRowThatAbsorbedIt() {
        assertEquals("host.route", SettingsCatalog.resolveId("host.base-url"))
        assertEquals("host.route", SettingsCatalog.item("host.base-url")?.id)
        assertEquals("host", SettingsCatalog.categoryOf("host.base-url")?.id)
        // An alias is a redirect, not a second row: the inventory count above
        // would catch a duplicate, and this catches a resurrection.
        assertTrue(SettingsCatalog.items.none { it.id == "host.base-url" })
        for ((from, to) in SettingsCatalog.aliases) {
            assertTrue(SettingsCatalog.items.any { it.id == to }, "$from points at $to, which does not exist")
        }
    }

    /**
     * ⚠ SUMMARIES SAY WHAT A SETTING DOES. The owner's standing note is that copy
     * must not narrate the UI, and the phone screen had a whole paragraph
     * ("What this app can do") doing exactly that. A summary that tells the
     * reader to tap something is describing the screen they are already looking
     * at.
     */
    @Test
    fun noSummaryNarratesTheInterface() {
        val narration = listOf("tap ", "click ", "press ", "the button below", "this screen", "scroll ")
        val offences = SettingsCatalog.items.filter { item ->
            narration.any { item.summary.lowercase().contains(it) }
        }.map { it.id }
        assertTrue(offences.isEmpty(), "summaries narrating the UI: $offences")
    }

    @Test
    fun everyItemHasWordsToShow() {
        for (item in SettingsCatalog.items) {
            assertTrue(item.title.isNotBlank(), "${item.id} has no title")
            assertTrue(item.summary.isNotBlank(), "${item.id} has no summary")
        }
        for (c in SettingsCatalog.categories) {
            assertTrue(c.title.isNotBlank(), "${c.id} has no title")
            assertTrue(c.blurb.isNotBlank(), "${c.id} has no blurb")
        }
    }

    /**
     * The three settings the redesign says may NOT be invented, asserted so a
     * later round cannot quietly add them: there is no update channel and by
     * explicit security decision never will be, the theme is hardcoded in both
     * shells, and the default model is a headroom field rather than a second
     * copy of one.
     */
    @Test
    fun theForbiddenSettingsWereNotInvented() {
        val ids = SettingsCatalog.items.map { it.id }.toSet()
        assertTrue("updates.channel" !in ids, "there is no update channel and never will be")
        assertTrue(ids.none { it.startsWith("appearance.theme") }, "the theme is hardcoded, not a setting")
        assertTrue("chats.default-model" !in ids, "the default model lives in usage, once")
    }

    /**
     * Keep-awake is reachable from Settings at all, on BOTH shells.
     *
     * ⚠ A CONTROL WITH NO CATALOG ITEM IS INVISIBLE to search and to the summary
     * line, which is the exact state the redesign was called in to fix — and it
     * matters more for this one than for any other row, because this is the
     * setting that spends the owner's quota. A feature you cannot find is a
     * feature you cannot switch back off.
     */
    @Test
    fun keepAwakeIsInTheCatalogOnBothShells() {
        val ids = SettingsCatalog.items.map { it.id }.toSet()
        for (id in listOf("usage.keep-awake", "usage.keep-awake-model", "usage.keep-awake-quiet")) {
            assertTrue(id in ids, "$id is drawn by the form but missing from the catalog")
            val item = SettingsCatalog.items.first { it.id == id }
            assertEquals(Surface.BOTH, item.surface, "$id is drawn on both shells")
            assertEquals("usage", SettingsCatalog.categoryOf(id)?.id, "$id belongs with the other usage rows")
        }
    }

    /**
     * ⚠ THE CATALOG'S OWN RULE: AN UNAVAILABLE ROW IS HIDDEN, AND A HIDDEN ROW
     * MUST NOT BE FINDABLE. `availability` false means the row does not exist —
     * "a hit that opens onto a hidden row is worse than no hit". Keep-awake's two
     * detail rows are drawn only `if (draft.keepAwake)`, but both claimed to be
     * available on any headroom host, so searching "quiet hours" on a phone with
     * keep-awake OFF offered a row and then opened a page that does not contain
     * it.
     */
    @Test
    fun theKeepAwakeDetailsExistOnlyWhileKeepAwakeIsOn() {
        val on = SettingsProbe(headroom = true, keepAwake = true)
        val off = SettingsProbe(headroom = true, keepAwake = false)
        for (id in listOf("usage.keep-awake-model", "usage.keep-awake-quiet")) {
            val item = SettingsCatalog.items.first { it.id == id }
            assertTrue(item.availability(on), "$id is drawn when keep-awake is on")
            assertFalse(item.availability(off), "$id is NOT drawn when keep-awake is off")
        }
        // The toggle itself stays — it is how keep-awake gets switched on at all.
        val toggle = SettingsCatalog.items.first { it.id == "usage.keep-awake" }
        assertTrue(toggle.availability(off), "the switch has to be reachable to be switched")
    }

    /**
     * The toggle's summary says what it COSTS, which is the one thing a reader
     * needs before switching it on and the one thing a title cannot carry.
     */
    @Test
    fun theKeepAwakeSummarySaysWhatItSpends() {
        val item = SettingsCatalog.items.first { it.id == "usage.keep-awake" }
        val words = item.summary.lowercase()
        assertTrue(
            listOf("cent", "spend", "cost").any { it in words },
            "a setting that spends quota has to say so: ${item.summary}",
        )
    }

    /** Account switching collapses to ONE rendering: same verb, one control. */
    @Test
    fun accountSwitchingIsNamedOnce() {
        val switching = SettingsCatalog.items.filter {
            it.id.contains("switch") || it.keywords.contains("autoswitch")
        }.map { it.id }
        assertEquals(
            listOf("usage.account-switch", "usage.switch-at", "usage.switch-margin"),
            switching,
            "account switching must live in usage and nowhere else",
        )
    }
}
