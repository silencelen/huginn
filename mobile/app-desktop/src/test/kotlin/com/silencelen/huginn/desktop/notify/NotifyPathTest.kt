package com.silencelen.huginn.desktop.notify

import com.silencelen.huginn.settings.SettingsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WHAT SETTINGS SAYS ABOUT WHERE A NOTIFICATION WOULD GO.
 *
 * ⚠ THE APP KNEW AND DID NOT SAY. On a machine with no system tray and no
 * libnotify the app logs `notifications via none … nowhere to post` at startup
 * and `Copy diagnostics` reports `desktop notifier NOT WIRED`, while
 * Settings → Notifications carried exactly one row — a toggle for CLAIMING a
 * route — and said nothing about there being no route to claim. The notification
 * step's own failure text points the reader at this page ("Notifications in
 * Settings shows which path this computer is using"), and the page did not.
 *
 * Read-only, because it is not a choice: nothing in this app can install a
 * notification daemon.
 */
class NotifyPathTest {

    @Test
    fun `a machine with nowhere to post says so plainly`() {
        assertEquals("none", Notifiers.pathWords(null))
        val why = Notifiers.pathSummary(null)
        assertTrue(why.contains("Telegram"), "the honest half is where attention goes instead: $why")
        assertTrue(
            why.contains("tray") && why.contains("libnotify"),
            "and WHY there is no path, in the same words the log uses: $why",
        )
    }

    @Test
    fun `a wired machine names the backend it is using`() {
        assertEquals("libnotify", Notifiers.pathWords("libnotify"))
        assertTrue(Notifiers.pathSummary("libnotify").contains("libnotify"))
        // FallbackNotifier's compound name is the honest one and passes through
        // whole: which backend is live right now is the fact being reported.
        assertEquals(
            "windows-toast→awt-tray(windows-toast)",
            Notifiers.pathWords("windows-toast→awt-tray(windows-toast)"),
        )
    }

    @Test
    fun `a blank name is the same answer as no name`() {
        assertEquals("none", Notifiers.pathWords("  "))
        assertEquals(Notifiers.pathSummary(null), Notifiers.pathSummary(""))
    }

    @Test
    fun `the row is in the catalog, so Settings search can find it`() {
        val item = SettingsCatalog.item(Notifiers.PATH_ROW_ID)
        assertTrue(item != null, "a row the search cannot reach is a row nobody finds")
        assertEquals("notify", SettingsCatalog.categoryOf(Notifiers.PATH_ROW_ID)?.id)
    }
}
