package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.desktop.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHAT THE RAIL OFFERS, and the three doors a feature probe has to shut at once.
 *
 * ⚠⚠ A 404 ON `GET /v1/projects` OR `GET /v1/consoles` MEANS THE FEATURE IS NOT
 * THERE, not that it is empty. A daemon older than Wave 3 answers exactly that,
 * and a client that drew the rail item anyway would offer a door whose only
 * outcome is an error — with no way for the reader to tell a broken app from an
 * older host. The rail, the palette rows and the Ctrl+Shift+J chord all read this
 * one list, which is the only reason they cannot disagree.
 *
 * ⚠ NULL HIDES TOO, and that is the half worth a test. Null is "the probe has not
 * answered yet"; a rail that guessed optimistically would draw an item for a
 * second and then take it away, moving every icon below it while somebody was
 * reaching for one. Appearing a second late is invisible. Disappearing under the
 * pointer is not.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class RailViewsTest {

    @Test
    fun `a daemon that 404s both probes gets neither rail item`() {
        val rail = railViews(padsAvailable = true, projectsAvailable = false, consolesAvailable = false)
        assertFalse(View.PROJECTS in rail, "a 404 is the route saying it is not there: $rail")
        assertFalse(View.CONSOLES in rail, rail.toString())
        // And nothing else moved: the five unconditional views are still there,
        // in order, which is what makes this a hidden item rather than a broken rail.
        assertEquals(
            listOf(View.CHATS, View.SESSIONS, View.ROUNDS, View.DEVICES, View.SCRATCHPADS, View.STATUS, View.SETTINGS),
            rail,
        )
    }

    @Test
    fun `an unanswered probe hides them exactly as a refusal does`() {
        val rail = railViews(padsAvailable = null, projectsAvailable = null, consolesAvailable = null)
        assertFalse(View.PROJECTS in rail, "null is 'not answered yet', and a guess here moves icons: $rail")
        assertFalse(View.CONSOLES in rail, rail.toString())
        assertFalse(View.SCRATCHPADS in rail, "the pages item has always read null the same way: $rail")
    }

    @Test
    fun `a host that has them puts them where the reading says`() {
        val rail = railViews(padsAvailable = true, projectsAvailable = true, consolesAvailable = true)
        assertTrue(View.PROJECTS in rail && View.CONSOLES in rail, rail.toString())
        // Projects under Sessions, because a project IS a set of sessions; Settings
        // last, because it is the one item that is not a list of anything.
        assertEquals(
            listOf(
                View.CHATS,
                View.SESSIONS,
                View.PROJECTS,
                View.ROUNDS,
                View.DEVICES,
                View.CONSOLES,
                View.SCRATCHPADS,
                View.STATUS,
                View.SETTINGS,
            ),
            rail,
        )
    }

    @Test
    fun `the two probes are independent`() {
        val onlyProjects = railViews(padsAvailable = false, projectsAvailable = true, consolesAvailable = false)
        assertTrue(View.PROJECTS in onlyProjects, onlyProjects.toString())
        assertFalse(View.CONSOLES in onlyProjects, onlyProjects.toString())

        val onlyConsoles = railViews(padsAvailable = false, projectsAvailable = false, consolesAvailable = true)
        assertFalse(View.PROJECTS in onlyConsoles, onlyConsoles.toString())
        assertTrue(View.CONSOLES in onlyConsoles, onlyConsoles.toString())
    }

    /**
     * The palette is the OTHER door, and the easier one to forget: Ctrl+K is where
     * somebody who cannot find a feature goes looking for it, which is exactly the
     * reader a row onto a 404 would strand.
     */
    @Test
    fun `the palette offers the same two verbs the rail does, and no others`() {
        val none = verbsFor(hasProjects = false, hasConsoles = false).map { it.label }
        assertFalse("Projects" in none, none.toString())
        assertFalse("Consoles" in none, none.toString())

        val both = verbsFor(hasProjects = true, hasConsoles = true).map { it.label }
        assertTrue("Projects" in both && "Consoles" in both, both.toString())
        assertEquals(none.size + 2, both.size, "exactly two rows appear, and nothing else changes")
    }

    /** And the chord is on Shift, because Ctrl+J is a line feed in every tmux pane. */
    @Test
    fun `Ctrl Shift J opens projects and a bare Ctrl J is not a shortcut at all`() {
        assertEquals(Shortcut.VIEW_PROJECTS, match(ctrl = true, shift = true, alt = false, key = "J"))
        assertEquals(null, match(ctrl = true, shift = false, alt = false, key = "J"))
        assertEquals(null, match(ctrl = false, shift = false, alt = false, key = "J"))
    }
}
