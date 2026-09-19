package com.silencelen.huginn

import com.silencelen.huginn.data.Alerts
import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.HeadroomSettings
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.data.StatusHeadroom
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.Surface
import com.silencelen.huginn.ui.settings.PhoneSettingsFacts
import com.silencelen.huginn.ui.settings.diagnosticsBundle
import com.silencelen.huginn.ui.settings.phoneProbe
import com.silencelen.huginn.ui.settings.phoneSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three phone-side rules the redesign added, and all three are rules that
 * can be WRONG rather than merely ugly.
 *
 * The composables around them are not tested here and deliberately so: this host
 * has no device and no `/dev/kvm`, so the assertable half was pulled out into
 * pure functions and the shells were left holding nothing but wiring. That is
 * the same trade `HuginnViewModelTest` already makes.
 */
class SettingsPhoneTest {

    private companion object {
        const val NOW = 1_789_460_000_000L
        /** Planted in every field a leak could ride out on. */
        const val TOKEN = "s3cr3t-bearer-do-not-publish"
    }

    private fun category(id: String) = SettingsCatalog.category(id)!!

    // ------------------------------------------------------------ summaries

    @Test
    fun `the home screen says what each drawer is set to`() {
        val f = PhoneSettingsFacts(
            baseUrl = AppdRoutes.TAILSCALE.url,
            routeName = "Tailscale",
            connected = true,
            accountEmail = "jacob@monahanhosting.com",
            savedAccounts = 2,
            headroomSettings = HeadroomSettings(),
            worst = StatusHeadroom(worstPercent = 37.0, worstLabel = "Fable"),
            quickActions = QuickActions(rev = 3, explain = "e {selection}", execute = "x {selection}",
                askInNewChat = "a {selection}", quote = "look at this"),
            alerts = Alerts(enabled = true, mode = "fallback", channel = "telegram"),
            notifyEnabled = true,
            notificationsAllowed = true,
            pushConfigured = true,
            pushRegistered = true,
            deviceCount = 3,
            servingCount = 1,
            appLock = true,
            appLockAvailable = true,
            fontScale = 13f,
            appVersion = "3.1.0",
            appdVersion = "3.0.5",
            updateWord = "up to date",
        )
        assertEquals("jacob@monahanhosting.com · 2 saved logins", phoneSummary(category("host"), f))
        assertEquals("Fable 37% · ladder set", phoneSummary(category("usage"), f))
        assertEquals("4 quick actions", phoneSummary(category("chats"), f))
        assertEquals("On · push · Telegram fallback", phoneSummary(category("notify"), f))
        assertEquals("3 machines · 1 serving", phoneSummary(category("devices"), f))
        assertEquals("lock on", phoneSummary(category("privacy"), f))
        assertEquals("3.1.0 · up to date", phoneSummary(category("updates"), f))
        assertEquals("3.1.0 · appd 3.0.5", phoneSummary(category("about"), f))
    }

    @Test
    fun `an unreachable host leads with the connection, not a remembered identity`() {
        // The account line survives a dead connection because it is the last
        // answer, and leading with it is how "the tunnel is down" reads as "my
        // account is broken" for ten minutes.
        val f = PhoneSettingsFacts(
            baseUrl = AppdRoutes.YGGDRASIL.url,
            routeName = "Yggdrasil",
            connected = false,
            accountEmail = "jacob@monahanhosting.com",
            savedAccounts = 2,
        )
        assertEquals("not connected · Yggdrasil", phoneSummary(category("host"), f))
    }

    /**
     * ⚠ THE NAME IS THE OWNER'S, NOT THIS APP'S. Routes are pinned and renamable
     * now, so the summary says whatever the reader called the path — and falls
     * back to its address rather than to a word this app invented.
     */
    @Test
    fun `the host line says the owner's name for the route`() {
        val renamed = PhoneSettingsFacts(
            baseUrl = AppdRoutes.YGGDRASIL.url,
            routeName = "the mesh",
            connected = false,
        )
        assertEquals("not connected · the mesh", phoneSummary(category("host"), renamed))

        val unnamed = PhoneSettingsFacts(baseUrl = "http://10.0.0.9:8787", connected = false)
        assertEquals("not connected · 10.0.0.9:8787", phoneSummary(category("host"), unnamed))
    }

    /**
     * A fresh install has pinned nothing. ⚠ It says so only once a failed
     * connection has PROVED it — "no route yet" on a launching app that has three
     * pinned would be a lie told at the moment somebody is already worried.
     */
    @Test
    fun `with no route pinned the host line asks for one, but only once it knows`() {
        assertNull("silent while the facts land", phoneSummary(category("host"), PhoneSettingsFacts()))
        assertEquals(
            "not connected · no route yet",
            phoneSummary(category("host"), PhoneSettingsFacts(connected = false)),
        )
    }

    @Test
    fun `a drawer with nothing to report says nothing rather than guessing`() {
        val empty = PhoneSettingsFacts()
        assertNull(phoneSummary(category("usage"), empty))
        assertNull(phoneSummary(category("chats"), empty))
        assertNull(phoneSummary(category("appearance"), empty))
        // One saved login is not "1 saved logins", and no lock is not "lock off".
        assertEquals(
            "jacob@x · 1 saved login",
            phoneSummary(category("host"), PhoneSettingsFacts(accountEmail = "jacob@x", savedAccounts = 1)),
        )
        assertEquals(
            "no screen lock on this phone",
            phoneSummary(category("privacy"), PhoneSettingsFacts(appLockAvailable = false)),
        )
    }

    // ---------------------------------------------------------------- probe

    @Test
    fun `a 3-0-0 daemon loses Usage and Chats rather than opening onto an apology`() {
        // 3.0.0 answers /v1/status without quickActions and has no /v1/headroom.
        val old = PhoneSettingsFacts(
            connected = true,
            accountEmail = "jacob@x",
            savedAccounts = 1,
            alerts = Alerts(enabled = true, channel = "telegram"),
            appLockAvailable = true,
        )
        val probe = phoneProbe(old)
        assertFalse("headroom must read absent, not false-y", probe.headroom)
        assertFalse(probe.quickActions)

        val shown = SettingsCatalog.visibleCategories(probe, Surface.PHONE).map { it.id }
        assertFalse("usage must be gone entirely: $shown", "usage" in shown)
        assertFalse("chats must be gone entirely: $shown", "chats" in shown)
        // And the drawers that do not depend on the daemon are all still there.
        assertEquals(listOf("host", "notify", "devices", "privacy", "appearance", "updates", "about"), shown)
    }

    @Test
    fun `a current daemon shows all nine`() {
        val now = PhoneSettingsFacts(
            headroomSettings = HeadroomSettings(),
            quickActions = QuickActions(),
            alerts = Alerts(channel = "telegram"),
            appLockAvailable = true,
            savedAccounts = 2,
        )
        assertEquals(9, SettingsCatalog.visibleCategories(phoneProbe(now), Surface.PHONE).size)
    }

    /**
     * ⚠ THE FACT THE CATALOG NEEDS COMES OFF THE SAVED SETTING, because that is
     * what the form branches on: the model picker and the quiet-hours field are
     * drawn `if (draft.keepAwake)`. Without this fact the catalog claimed both
     * rows existed on any headroom host, and searching "quiet hours" with
     * keep-awake off opened a page that has no such field.
     */
    @Test
    fun `keep-awake's detail rows follow the saved switch`() {
        assertFalse(
            "a host with keep-awake off draws no model picker",
            phoneProbe(PhoneSettingsFacts(headroomSettings = HeadroomSettings(keepAwake = false))).keepAwake,
        )
        assertTrue(
            "and a host with it on draws both",
            phoneProbe(PhoneSettingsFacts(headroomSettings = HeadroomSettings(keepAwake = true))).keepAwake,
        )
        assertFalse(
            "a daemon with no headroom at all has no keep-awake either",
            phoneProbe(PhoneSettingsFacts()).keepAwake,
        )
        val ids = SettingsCatalog.itemsOf(
            "usage",
            phoneProbe(PhoneSettingsFacts(headroomSettings = HeadroomSettings(keepAwake = false))),
            Surface.PHONE,
        ).map { it.id }
        assertTrue("the switch itself stays: $ids", "usage.keep-awake" in ids)
        assertFalse("$ids", "usage.keep-awake-quiet" in ids)
        assertFalse("$ids", "usage.keep-awake-model" in ids)
    }

    @Test
    fun `this phone never claims to serve local models`() {
        // Serving is a machine's own decision and a phone is not a candidate; the
        // row is desktop-only and the probe must not offer it a way in.
        assertFalse(phoneProbe(PhoneSettingsFacts()).localServe)
    }

    // ---------------------------------------------------------- diagnostics

    @Test
    fun `the diagnostics bundle never carries the token`() {
        // Planted in every field a person could type into. The bundle is meant to
        // be pasted into somebody else's chat window; a secret that travels in a
        // support report has been published.
        val f = PhoneSettingsFacts(
            baseUrl = "http://user:$TOKEN@100.97.198.90:8787/",
            connected = true,
            accountEmail = "jacob@x",
            appVersion = "3.1.0",
            appdVersion = "3.0.5",
            lastError = "401 Unauthorized",
        )
        val text = diagnosticsBundle(f, NOW)
        assertFalse("the bearer reached the clipboard:\n$text", TOKEN in text)
        assertFalse("a userinfo segment leaked the bearer:\n$text", "@" in text.lineSequence()
            .first { it.startsWith("route:") })
    }

    @Test
    fun `the diagnostics bundle always names the route`() {
        // The first question asked of a phone that stopped notifying is which
        // address it was on — the tunnel, not the app, is usually the answer.
        val tailscale = diagnosticsBundle(
            PhoneSettingsFacts(baseUrl = AppdRoutes.TAILSCALE.url, routeName = "Tailscale"), NOW,
        )
        assertTrue(tailscale, "route: Tailscale · 100.97.198.90:8787" in tailscale)

        // The owner's own word for the path, when they have renamed it — this is
        // the line somebody pastes into a chat, and "the mesh" is what they will
        // say out loud in the next message.
        val renamed = diagnosticsBundle(
            PhoneSettingsFacts(baseUrl = AppdRoutes.YGGDRASIL.url, routeName = "the mesh"), NOW,
        )
        assertTrue(renamed, "route: the mesh · 192.168.2.117:8787" in renamed)

        // A pin nobody named still has a name — its own address. "No route line"
        // and "an address nobody recognises" are different problems.
        val unnamed = diagnosticsBundle(PhoneSettingsFacts(baseUrl = "http://10.0.0.9:8787"), NOW)
        assertTrue(unnamed, "route: 10.0.0.9:8787 · 10.0.0.9:8787" in unnamed)
    }

    @Test
    fun `the bundle carries the delivery facts the question is actually about`() {
        val f = PhoneSettingsFacts(
            baseUrl = AppdRoutes.TAILSCALE.url,
            routeName = "Tailscale",
            appVersion = "3.1.0",
            appdVersion = "3.0.5",
            notifyEnabled = true,
            notificationsAllowed = true,
            dozeExempt = false,
            pushConfigured = true,
            pushRegistered = true,
            pushesSent = 41,
            pushesReceived = 38,
            heartbeatIntervalMs = 600_000,
            lastContactAt = NOW - 3_600_000,
            lastAlarmAt = NOW - 600_000,
            hostSawUsSeconds = 120,
            hostSawUsKind = "heartbeat",
        )
        val text = diagnosticsBundle(f, NOW)
        assertTrue(text, "app: 3.1.0" in text)
        assertTrue(text, "appd: 3.0.5" in text)
        // The doze answer is the usual reason notifications stop overnight, so it
        // is stated in the negative rather than omitted.
        assertTrue(text, "NOT exempt from doze" in text)
        assertTrue(text, "pushes: huginn sent 41, this phone received 38" in text)
        assertTrue(text, "background check: every 10 min" in text)
        assertTrue(text, "app last reached huginn: 1h ago" in text)
        assertTrue(text, "huginn last heard from this phone: 2m ago (heartbeat)" in text)
    }

    @Test
    fun `a witness that has never testified says never, not 1970`() {
        val text = diagnosticsBundle(PhoneSettingsFacts(), NOW)
        assertTrue(text, "app last reached huginn: never" in text)
        assertTrue(text, "huginn last heard from this phone: never" in text)
        assertTrue(text, "appd: not answering" in text)
        assertTrue(text, "background check: every —" in text)
    }
}
