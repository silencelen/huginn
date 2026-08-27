package com.silencelen.huginn

import com.silencelen.huginn.data.Watchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who still needs a shared poll running. NOTE kotlin.test's argument order is
 * (expected, actual, message).
 *
 * The failure this prevents is invisible: a list that has simply stopped
 * refreshing looks exactly like a list where nothing has changed.
 */
class WatchersTest {

    @Test
    fun `the first watcher starts it and the last one stops it`() {
        val w = Watchers()
        assertTrue(w.enter("list"), "the first watcher is the one that starts the poll")
        assertFalse(w.enter("editor"), "the second must not restart it")
        assertFalse(w.leave("editor"), "and leaving while somebody is still reading must not stop it")
        assertTrue(w.leave("list"), "the last one out turns it off")
        assertFalse(w.any)
    }

    @Test
    fun `closing the editor beside the list leaves the list watching`() {
        // ⚠ THE TWO-PANE BUG. On a wide screen the pages list and the pages editor
        // are on screen together and share one poll. The editor's dispose used to
        // stop it outright, so the list sat frozen — showing sizes and "edited 4
        // minutes ago" that never moved again, with nothing looking wrong.
        val w = Watchers()
        w.enter("list")
        w.enter("editor")
        assertFalse(w.leave("editor"), "the list is still on screen")
        assertTrue(w.any)
    }

    @Test
    fun `entering twice under one name is entering once`() {
        // Lifecycle effects re-run: LifecycleStartEffect fires again on every
        // return to the foreground, and a counter would drift upward one leak at a
        // time until the poll could never be stopped at all.
        val w = Watchers()
        w.enter("list")
        w.enter("list")
        assertTrue(w.leave("list"), "one name, one watcher")
        assertFalse(w.any)
    }

    @Test
    fun `a stray release cannot take down a poll it never started`() {
        val w = Watchers()
        w.enter("list")
        assertFalse(w.leave("editor"), "an unbalanced stop must not answer for somebody else")
        assertTrue(w.any)
    }

    @Test
    fun `clear takes everything, for a feature being torn down`() {
        val w = Watchers()
        w.enter("list")
        w.enter("editor")
        w.clear()
        assertFalse(w.any)
        assertTrue(w.enter("list"), "and the next arrival starts it again")
    }
}
