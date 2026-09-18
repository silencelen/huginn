package com.silencelen.huginn.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The search rule, which is the part of the redesign that can be subtly wrong
 * and still look right. Nine categories is exactly the count where search stops
 * being decoration, so "token" finding *Ladder* before *Token* is not a cosmetic
 * failure — it is the reason people go back to scrolling.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SettingsSearchTest {

    private val all = SettingsProbe(
        headroom = true, quickActions = true, alerts = true, padsAvailable = true,
        localServe = true, appLockAvailable = true, enrolable = true,
        savedAccounts = 3, diagnostics = true, selfUpdate = true,
    )

    private fun ids(query: String, probe: SettingsProbe = all, surface: Surface = Surface.DESKTOP) =
        SettingsSearch.hits(query, probe, surface).map { it.item.id }

    /**
     * The load-bearing case, and the one the wireframe draws. "Token" is the
     * exact title of one item and a word inside another; the exact title wins.
     */
    @Test
    fun theExactTitleRanksFirst() {
        val hits = SettingsSearch.hits("token", all, Surface.DESKTOP)
        assertTrue(hits.isNotEmpty(), "no hits for token")
        assertEquals("host.token", hits.first().item.id, "found ${hits.map { it.item.id }}")
        assertEquals(SettingsSearch.Rank.EXACT_TITLE, hits.first().rank)
        // The privacy explanation is a hit too — same word, less exactly.
        assertTrue("privacy.token" in hits.map { it.item.id }, "found ${hits.map { it.item.id }}")
    }

    /** Case and stray whitespace are not a different query. */
    @Test
    fun caseAndWhitespaceCollapse() {
        assertEquals(ids("token"), ids("  TOKEN  "))
        assertEquals(ids("base url"), ids("Base    URL"))
    }

    /**
     * AND across terms: a second word narrows. "quick action" must find the
     * quick-actions editor by prefix-matching "actions" with "action".
     */
    @Test
    fun everyTermMustMatchAndAPrefixCounts() {
        assertEquals("chats.quick-actions", ids("quick action").first(), "found ${ids("quick action")}")
        assertTrue("chats.quick-actions" in ids("quick"), "found ${ids("quick")}")
        // A second word that nothing carries removes the hit rather than widening.
        assertTrue("chats.quick-actions" !in ids("quick zebra"), "AND across terms, not OR")
    }

    /** OR across fields: title, keywords and the category title all count. */
    @Test
    fun aKeywordFindsARowWhoseTitleDoesNotSayIt() {
        // ⚠ "tailscale" STAYS A KEYWORD even though nothing in the interface says
        // it any more: it is what somebody who has used this app for a year will
        // type when their connection dies, and routes are exactly what they want.
        assertTrue("host.route" in ids("tailscale"), "found ${ids("tailscale")}")
        assertTrue("host.route" in ids("yggdrasil"), "found ${ids("yggdrasil")}")
        // And the row that absorbed the Base URL field answers to its old name.
        assertTrue("host.route" in ids("base url"), "found ${ids("base url")}")
        assertTrue("host.route" in ids("server"), "found ${ids("server")}")
        val onPhone = ids("fingerprint", surface = Surface.PHONE)
        assertTrue("privacy.app-lock" in onPhone, "found $onPhone")
    }

    @Test
    fun theCategoryTitleIsTheLastResort() {
        val hits = SettingsSearch.hits("headroom", all, Surface.DESKTOP)
        assertTrue(hits.isNotEmpty(), "the category title must be searchable")
        assertTrue(
            hits.all { it.category.id == "usage" },
            "found ${hits.map { it.item.id }}",
        )
        assertTrue(hits.all { it.rank == SettingsSearch.Rank.CATEGORY }, "ranked ${hits.map { it.rank }}")
    }

    /** Ranks are coarse on purpose, so catalog order survives inside one. */
    @Test
    fun tiesStayInCatalogOrder() {
        val hits = SettingsSearch.hits("percent", all, Surface.DESKTOP)
        val order = SettingsCatalog.items.map { it.id }
        val found = hits.filter { it.rank == hits.first().rank }.map { it.item.id }
        assertEquals(found.sortedBy { order.indexOf(it) }, found, "reshuffled within a rank")
    }

    /**
     * ⚠ A HIDDEN ROW NEVER RETURNS. The probe that hides the row hides the hit;
     * otherwise search teaches the reader that it lies.
     */
    @Test
    fun anUnavailableItemIsNeverAHit() {
        val old = SettingsProbe(selfUpdate = true)   // a pre-3.0 daemon
        assertTrue(ids("ladder", old).isEmpty(), "found ${ids("ladder", old)}")
        assertTrue(ids("quick action", old).isEmpty(), "found ${ids("quick action", old)}")
        assertTrue("host.token" in ids("token", old), "host rows survive an old daemon")
    }

    /** The other shell's rows are not hits either. */
    @Test
    fun theOtherShellsRowsAreNotHits() {
        assertTrue("privacy.remove-this-computer" !in ids("remove", surface = Surface.PHONE))
        assertTrue("privacy.remove-this-computer" in ids("remove", surface = Surface.DESKTOP))
        assertTrue("privacy.app-lock" !in ids("lock", surface = Surface.DESKTOP))
        assertTrue("privacy.app-lock" in ids("lock", surface = Surface.PHONE))
    }

    /**
     * Blank is not a wildcard: the category list underneath already answers
     * "show me everything", and a full dump the moment the field is focused is
     * how a search box becomes noise.
     */
    @Test
    fun blankFindsNothing() {
        assertTrue(SettingsSearch.hits("", all, Surface.DESKTOP).isEmpty())
        assertTrue(SettingsSearch.hits("   ", all, Surface.DESKTOP).isEmpty())
        assertTrue(SettingsSearch.hits("\t\n", all, Surface.DESKTOP).isEmpty())
    }

    /** Past twelve rows a result list is a second index, not an answer. */
    @Test
    fun theCapHolds() {
        assertEquals(12, SettingsSearch.DEFAULT_LIMIT)
        // "a" prefix-matches something almost everywhere; without a cap this is
        // most of the catalog.
        val wide = SettingsSearch.hits("a", all, Surface.DESKTOP)
        assertTrue(
            SettingsCatalog.items.count { it.availability(all) } > SettingsSearch.DEFAULT_LIMIT,
            "the catalog is too small for this test to mean anything",
        )
        assertEquals(SettingsSearch.DEFAULT_LIMIT, wide.size, "the cap did not hold")
        assertEquals(3, SettingsSearch.hits("a", all, Surface.DESKTOP, limit = 3).size)
        assertTrue(SettingsSearch.hits("a", all, Surface.DESKTOP, limit = 0).isEmpty())
    }

    @Test
    fun aHitCarriesTheCategoryToOpen() {
        val hit = SettingsSearch.hits("close to tray", all, Surface.DESKTOP).first()
        assertEquals("appearance.close-to-tray", hit.item.id)
        assertEquals("appearance", hit.category.id)
    }

    @Test
    fun nonsenseFindsNothing() {
        assertTrue(SettingsSearch.hits("qqqzzz", all, Surface.DESKTOP).isEmpty())
    }

    /**
     * "keep awake" finds it, and so does the word somebody would actually reach
     * for. A setting that spends money and cannot be found is one that cannot be
     * switched back off — and the phrase in the owner's own note is two words,
     * which the title spells as three.
     */
    @Test
    fun keepAwakeIsFindable() {
        assertTrue("usage.keep-awake" in ids("keep awake"), "found ${ids("keep awake")}")
        assertTrue("usage.keep-awake" in ids("keepawake"), "found ${ids("keepawake")}")
        assertTrue("usage.keep-awake" in ids("rotating"), "found ${ids("rotating")}")
        assertTrue("usage.keep-awake-quiet" in ids("quiet hours"), "found ${ids("quiet hours")}")
    }
}
