package com.silencelen.huginn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the window opens.
 *
 * The owner's report was "the sessions tab is not being regarded as default" — the
 * client opened on Chats every time, because that is the first enum constant and
 * nothing was ever stored. The answer is to REOPEN WHERE HE LEFT IT, with Sessions
 * as the answer for a file that has never recorded one, which is every install
 * that predates this field.
 *
 * NOTE the kotlin.test argument order: (expected, actual, message).
 */
class LandingTest {

    @Test
    fun `an install that has never recorded a view lands on sessions`() {
        // The owner's next launch after this ships takes THIS branch: his settings
        // file has no such field, so the fix must not depend on him visiting
        // Sessions once first.
        assertEquals(View.SESSIONS, Landing.parse(null))
        assertEquals(View.SESSIONS, Landing.parse(""))
        assertEquals(View.SESSIONS, Landing.DEFAULT)
    }

    @Test
    fun `a recorded view round trips`() {
        for (view in listOf(View.CHATS, View.SESSIONS)) {
            assertEquals(view, Landing.parse(Landing.encode(view)), "round trip for $view")
        }
    }

    @Test
    fun `a value the app cannot read is the default, never a throw`() {
        // This is parsed before there is a window, so a hand-edited file or one
        // written by a newer build must not be able to stop the app launching.
        assertEquals(View.SESSIONS, Landing.parse("wat"))
        assertEquals(View.SESSIONS, Landing.parse("status"))
        assertEquals(View.SESSIONS, Landing.parse("settings"))
        assertEquals(View.CHATS, Landing.parse("  CHATS  "), "case and padding are not decisions")
    }

    @Test
    fun `status and settings are errands, not places to reopen into`() {
        assertTrue(Landing.persistable(View.CHATS))
        assertTrue(Landing.persistable(View.SESSIONS))
        // One glance at the host's disk usage must not decide tomorrow's landing.
        assertFalse(Landing.persistable(View.STATUS))
        assertFalse(Landing.persistable(View.SETTINGS))
    }

    @Test
    fun `pages encode and parse, and are still not a place to reopen into`() {
        // Landing.kt's own rule: encode and parse must BOTH learn a new view even
        // when it is not persistable, or they stop being mutual inverses and the
        // file becomes unreadable as a record of where anyone was.
        assertEquals(View.SCRATCHPADS, Landing.parse(Landing.encode(View.SCRATCHPADS)))
        // A window that opens on somebody's notes answers a question nobody asked
        // first thing — the same argument Rounds is excluded on.
        assertFalse(Landing.persistable(View.SCRATCHPADS))
    }

    @Test
    fun `every view encodes to something distinguishable`() {
        // An encoder that silently produced "chats" for Settings would be a
        // landing bug that could not be read off the settings file.
        val encoded = View.entries.map { Landing.encode(it) }
        assertEquals(View.entries.size, encoded.toSet().size, "encodings collided: $encoded")
        assertTrue(encoded.none { it.isBlank() }, "a blank encoding reads as 'never recorded'")
    }
}
