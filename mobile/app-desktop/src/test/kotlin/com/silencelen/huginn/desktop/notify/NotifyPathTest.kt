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

/**
 * THE TEST NOTIFICATION, AND THE QUESTION IT MUST NOT ASK.
 *
 * ⚠⚠ THE STEP ASKED A READER TO CONFIRM SOMETHING THAT NEVER HAPPENED. On a
 * machine with no tray and no libnotify the chosen backend is [NoNotifier],
 * whose `post` is a no-op — so setup posted nothing, then printed *"A test
 * notification has just been posted. Did it appear on this screen?"* with a
 * "Yes, I saw it" button. Pressing it records a PASS for a route that cannot
 * deliver, and the daemon then holds Telegram back for a client that will never
 * show anything. The app knew the whole time: it had already logged "nowhere to
 * post" at startup.
 *
 * A step that cannot be attempted fails with the reason, and the two-choice
 * question is not asked at all.
 */
class NotifyTestRefusalTest {

    private class Anywhere(override val name: String = "libnotify") : Notifier {
        override fun post(request: NotifyRequest) = Unit
        override fun withdraw(key: String) = Unit
    }

    private class Broken(override val name: String = "libnotify") : Notifier {
        override val healthy: Boolean = false
        override fun post(request: NotifyRequest) = Unit
        override fun withdraw(key: String) = Unit
    }

    @Test
    fun `nowhere to post is a refusal, not a question`() {
        val why = Notifiers.testRefusal(NoNotifier)
        assertTrue(why != null, "a no-op post must not be followed by 'did it appear?'")
        assertTrue(why.contains("tray") && why.contains("libnotify"), why)
        assertTrue(why.contains("Telegram"), "and where attention goes instead: $why")
    }

    @Test
    fun `a backend that has already proven itself dead is refused too`() {
        assertTrue(Notifiers.testRefusal(Broken()) != null, "unhealthy means it has failed a real post")
    }

    @Test
    fun `a live backend is asked rather than refused`() {
        assertEquals(null, Notifiers.testRefusal(Anywhere()))
    }
}
