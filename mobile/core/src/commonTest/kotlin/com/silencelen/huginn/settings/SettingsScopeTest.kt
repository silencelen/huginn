package com.silencelen.huginn.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Where each setting is kept, and so what changing it reaches.
 *
 * The owner (2026-10-23): *"we have to think about 'cloud settings'(which apply
 * to the 'server'/ to all the other huginn clients), the local settings for the
 * device itself, and the core settings for the core cli install"*. The scope is
 * what the pages head their rows with (`SettingsScopeLabel`), so a wrong one is
 * a page telling the reader that flipping a switch here flips it everywhere, or
 * the opposite. The desktop's pages are checked against these answers row by
 * row in `SettingsPagesTest`.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SettingsScopeTest {

    private fun item(id: String) = SettingsCatalog.item(id) ?: error("no $id")

    /** Every rule on the Usage page lives on huginn: one heading, on both shells. */
    @Test
    fun everyUsageRuleIsKeptOnHuginn() {
        val usage = SettingsCatalog.category("usage")!!.items
        for (i in usage) for (surface in listOf(Surface.PHONE, Surface.DESKTOP)) {
            assertEquals(SettingScope.SERVER, i.scopeOn(surface, viaCore = true), "${i.id} on $surface")
        }
    }

    /** So are the logins, the wording and the pages it hosts — the things every app shares. */
    @Test
    fun theSharedThingsAreKeptOnHuginn() {
        for (id in listOf(
            "host.saved-logins", "host.sign-in", "host.sign-out", "chats.quick-actions", "chats.soft-end",
            "chats.apps", "chats.projects", "chats.fast-default", "notify.host-alerts", "notify.host-alerts-mode",
            "devices.fleet", "about.host-version",
        )) {
            assertEquals(SettingScope.SERVER, item(id).scope, id)
        }
    }

    /**
     * The link. A phone keeps its own routes and token; a desktop that huginn
     * core carries keeps neither — the core does — and only a desktop on the old
     * direct link keeps them itself.
     */
    @Test
    fun theLinkIsTheCoresOnADesktopTheCoreCarries() {
        for (id in listOf("host.route", "host.token", "privacy.token", "privacy.remove-this-computer")) {
            val i = item(id)
            assertEquals(SettingScope.CORE, i.scopeOn(Surface.DESKTOP, viaCore = true), "$id through the core")
            assertEquals(SettingScope.DEVICE, i.scopeOn(Surface.DESKTOP, viaCore = false), "$id on the direct link")
            if (i.surface.matches(Surface.PHONE)) {
                assertEquals(SettingScope.DEVICE, i.scopeOn(Surface.PHONE, viaCore = true), "$id on the phone")
            }
        }
    }

    /** This machine's work, its local AI and its tray icon are huginn core's. */
    @Test
    fun thisMachinesWorkIsTheCores() {
        for (id in listOf(
            "devices.this-machine", "devices.scope", "devices.act-while-locked", "devices.work-folder",
            "devices.claude-path", "devices.local-ai", "appearance.tray-icon", "updates.core",
        )) {
            assertEquals(SettingScope.CORE, item(id).scopeOn(Surface.DESKTOP, viaCore = true), id)
        }
    }

    /** A phone has no core, so nothing it shows can say it is kept by one. */
    @Test
    fun aPhoneNeverShowsTheCoreScope() {
        for (i in SettingsCatalog.items) {
            if (!i.surface.matches(Surface.PHONE)) continue
            assertNotEquals(SettingScope.CORE, i.scopeOn(Surface.PHONE, viaCore = true), "${i.id} on the phone")
        }
    }

    /** The override is only for the link: nothing else moves when the core carries the app. */
    @Test
    fun onlyTheLinkMovesWithTheCore() {
        val moved = SettingsCatalog.items.filter { it.scopeOn(Surface.DESKTOP, true) != it.scopeOn(Surface.DESKTOP, false) }
        assertEquals(
            setOf("host.route", "host.token", "privacy.token", "privacy.remove-this-computer"),
            moved.map { it.id }.toSet(),
        )
    }

    /** Three headings on each shell, each different, and the device one names the device. */
    @Test
    fun theHeadingsSayWhichIsWhich() {
        for (surface in listOf(Surface.PHONE, Surface.DESKTOP)) {
            val words = SettingScope.entries.map { it.heading(surface) }
            assertEquals(words.size, words.toSet().size, "two scopes read the same on $surface: $words")
        }
        assertTrue("huginn" in SettingScope.SERVER.heading(Surface.DESKTOP))
        assertTrue("core" in SettingScope.CORE.heading(Surface.DESKTOP))
        assertEquals("This phone", SettingScope.DEVICE.heading(Surface.PHONE))
        assertEquals("This app", SettingScope.DEVICE.heading(Surface.DESKTOP))
    }
}
