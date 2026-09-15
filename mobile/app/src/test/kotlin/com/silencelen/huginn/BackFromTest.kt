package com.silencelen.huginn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where back goes, for both the arrow and the system gesture.
 *
 * There is one rule because there are two ways to ask. Before this, the arrow had
 * the logic inline and the gesture had none — confirmed on the device, where two
 * back presses from a session left the activity Terminated.
 */
class BackFromTest {

    @Test
    fun `a child screen goes up to its list`() {
        assertEquals(Dest.Sessions, backFrom(Dest.SessionView("huginnapp"), tab = 1))
        assertEquals(Dest.Chats, backFrom(Dest.Chat("abc"), tab = 0))
    }

    @Test
    fun `a page goes up to the list of pages`() {
        assertEquals(Dest.Scratchpads, backFrom(Dest.Scratchpad("pad-1"), tab = 0))
    }

    @Test
    fun `the pages list returns to the section it was opened from`() {
        // Pages are reachable from four places, so "up" cannot name the one it was
        // opened from without a destination that carries it. The section is the
        // honest answer, and it is the trade Settings already made.
        assertEquals(Dest.Chats, backFrom(Dest.Scratchpads, tab = 0))
        assertEquals(Dest.Sessions, backFrom(Dest.Scratchpads, tab = 1))
        assertEquals(Dest.Rounds, backFrom(Dest.Scratchpads, tab = 3))
        assertEquals(Dest.Status, backFrom(Dest.Scratchpads, tab = 2))
    }

    @Test
    fun `settings returns to the tab it was opened from`() {
        assertEquals(Dest.Chats, backFrom(Dest.Settings, tab = 0))
        assertEquals(Dest.Sessions, backFrom(Dest.Settings, tab = 1))
        assertEquals(Dest.Status, backFrom(Dest.Settings, tab = 2))
    }

    @Test
    fun `a settings drawer goes up to the list of drawers`() {
        // The redesign's whole shape: nine drawers, one open at a time, and back
        // returns to the nine rather than out of Settings entirely.
        assertEquals(Dest.Settings, backFrom(Dest.SettingsSection("usage"), tab = 0))
        assertEquals(Dest.Settings, backFrom(Dest.SettingsSection("about"), tab = 2))
        // And it does NOT depend on the tab — that is the Settings home's trade,
        // not this screen's.
        assertEquals(Dest.Settings, backFrom(Dest.SettingsSection("host"), tab = 3))
    }

    @Test
    fun `devices goes up to the drawer that named it`() {
        // Devices is a destination in its own right now rather than "a child of
        // Settings", but it is still opened from one row, so up is that drawer —
        // returning to the Settings home would land a step above where the
        // reader came from.
        assertEquals(Dest.SettingsSection("devices"), backFrom(Dest.Devices, tab = 0))
        assertEquals(Dest.SettingsSection("devices"), backFrom(Dest.Devices, tab = 2))
    }

    @Test
    fun `a root screen has no up, so back still leaves the app`() {
        // Deliberate: leaving from a root IS what back means on Android, and a
        // handler that swallowed it would trap the user in the app.
        assertNull(backFrom(Dest.Chats, tab = 0))
        assertNull(backFrom(Dest.Sessions, tab = 1))
        assertNull(backFrom(Dest.Status, tab = 2))
    }

    @Test
    fun `a session's identity is not lost on the way up`() {
        // Regression shape: returning Dest.Sessions is right, but returning the
        // WRONG list (chats) would look like it worked until you noticed the tab.
        assertEquals(Dest.Sessions, backFrom(Dest.SessionView("a-name.with.dots"), tab = 0))
    }
}
