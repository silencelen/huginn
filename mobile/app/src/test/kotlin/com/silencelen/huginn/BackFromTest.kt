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
