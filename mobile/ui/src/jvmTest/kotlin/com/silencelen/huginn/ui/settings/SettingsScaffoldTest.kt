package com.silencelen.huginn.ui.settings

import com.silencelen.huginn.settings.SettingsProbe
import com.silencelen.huginn.settings.SettingsSearch
import com.silencelen.huginn.settings.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the shared Settings frame shows, asserted without rendering it.
 *
 * The decisions worth testing here are all list-versus-results and which row
 * arrives marked — none of which needs a pixel. There is no compose-ui-test in
 * these modules (see `CapBeforeFillTest`'s header for why), so the rules live in
 * [SettingsScaffoldRules] as plain functions and the composable is a thin drawing
 * of them.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SettingsScaffoldTest {

    private val all = SettingsProbe(
        headroom = true, quickActions = true, alerts = true, padsAvailable = true,
        localServe = true, appLockAvailable = true, enrolable = true,
        savedAccounts = 3, diagnostics = true, selfUpdate = true,
    )

    @Test
    fun anEmptyFieldShowsTheCategoriesAndNoResults() {
        val shown = SettingsScaffoldRules.shown(all, Surface.DESKTOP, "")
        assertFalse(shown.searching)
        assertEquals(9, shown.categories.size)
        assertTrue(shown.hits.isEmpty())
        assertFalse(shown.emptyResult, "an empty field is not an empty result")
    }

    /** Whitespace is not a query: the field is still empty. */
    @Test
    fun whitespaceIsStillAnEmptyField() {
        assertFalse(SettingsScaffoldRules.shown(all, Surface.PHONE, "   \t").searching)
    }

    @Test
    fun typingSwapsTheListForResults() {
        val shown = SettingsScaffoldRules.shown(all, Surface.DESKTOP, "token")
        assertTrue(shown.searching)
        assertTrue(shown.hits.isNotEmpty())
        assertEquals("host.token", shown.hits.first().item.id)
        assertFalse(shown.emptyResult)
    }

    /**
     * "Nothing matched" is its own screen. Falling back to the category list
     * would read as the search having been ignored.
     */
    @Test
    fun nothingMatchingIsItsOwnState() {
        val shown = SettingsScaffoldRules.shown(all, Surface.DESKTOP, "qqqzzz")
        assertTrue(shown.searching)
        assertTrue(shown.emptyResult)
    }

    /** The frame never lists a drawer the probe has emptied. */
    @Test
    fun aDegradedHostListsThreeDrawers() {
        val shown = SettingsScaffoldRules.shown(SettingsProbe(), Surface.BOTH, "")
        assertEquals(listOf("host", "privacy", "about"), shown.categories.map { it.id })
    }

    @Test
    fun openingAHitNamesBothTheDrawerAndTheRow() {
        val hit = SettingsSearch.hits("close to tray", all, Surface.DESKTOP).first()
        assertEquals("appearance" to "appearance.close-to-tray", SettingsScaffoldRules.open(hit))
    }

    /**
     * A two-pane frame always has something on the right. Opening with nothing
     * selected lands on the first drawer rather than on an empty pane.
     */
    @Test
    fun aTwoPaneFrameLandsSomewhere() {
        val shown = SettingsScaffoldRules.shown(all, Surface.DESKTOP, "")
        assertEquals("host", SettingsScaffoldRules.landing(shown, null))
        assertEquals("usage", SettingsScaffoldRules.landing(shown, "usage"))
    }

    /**
     * ⚠ THE SELECTION CAN STOP EXISTING. A daemon that stops answering
     * `/v1/headroom` while Usage is open takes the category with it, and a frame
     * that kept pointing at it would render an empty pane with no explanation.
     */
    @Test
    fun aSelectionThatVanishesFallsBackRatherThanBlanking() {
        val degraded = SettingsScaffoldRules.shown(all.copy(headroom = false), Surface.DESKTOP, "")
        assertEquals("host", SettingsScaffoldRules.landing(degraded, "usage"))
        assertEquals("host", SettingsScaffoldRules.landing(degraded, "nonsense"))
    }

    @Test
    fun landingOnNothingIsPossibleOnlyWhenThereIsNothing() {
        val empty = SettingsScaffoldRules.Shown(searching = false, categories = emptyList(), hits = emptyList())
        assertEquals(null, SettingsScaffoldRules.landing(empty, "host"))
    }

    /** Each shell's list is its own; the results follow the same surface. */
    @Test
    fun theFrameIsSurfaceAware() {
        val phone = SettingsScaffoldRules.shown(all, Surface.PHONE, "remove")
        assertTrue(phone.hits.none { it.item.id == "privacy.remove-this-computer" })
        val desktop = SettingsScaffoldRules.shown(all, Surface.DESKTOP, "remove")
        assertTrue(desktop.hits.any { it.item.id == "privacy.remove-this-computer" })
    }
}
