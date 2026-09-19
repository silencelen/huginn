package com.silencelen.huginn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three destinations Wave 3 adds, and the two rules every destination here
 * has to obey.
 *
 * ⚠ THE BOTTOM BAR STAYS AT FOUR (delta §4, reaffirming the Devices call of
 * 2026-08-24), so all three of these are CHILD screens — reached from the
 * Sessions list, from a Settings row, from the Status card, or from a
 * notification. That makes both rules below load-bearing rather than tidy:
 *
 * 1. A child screen must survive an activity rebuild, because a fold or a rotate
 *    destroys the activity and `Dest` cannot go in a bundle. A project's ID is
 *    the half that matters — coming back to "some project" is coming back to the
 *    wrong one.
 * 2. A child screen must have an UP, because without one the system back gesture
 *    leaves the app. Returning null here is what "this is a root" means, and none
 *    of these three is a root.
 */
class ProjectNavTest {

    @Test
    fun `the projects and apps destinations survive a rebuild`() {
        val cases = listOf(
            Dest.Projects,
            Dest.Project("6f0d2c41-0000-4000-8000-0000000000b2"),
            Dest.Apps,
        )
        for (d in cases) {
            assertEquals("lost $d across a rebuild", d, keyToDest(destToKey(d)))
        }
    }

    /**
     * A saved key from a build that spelled it differently must land SOMEWHERE
     * real. `project:` with no id is the list, for the reason `settings:` with no
     * id is the settings home: a dashboard with no project behind it is a screen
     * about nothing.
     */
    @Test
    fun `an empty project id lands on the list, not on an empty dashboard`() {
        assertEquals(Dest.Projects, keyToDest("project:"))
    }

    /**
     * Where up goes, and why each answer is the one it is.
     *
     * Projects → SESSIONS, not to whichever tab the reader came from: Projects
     * lives inside Sessions, which IS the placement decision, and going back to
     * Settings from a Settings-opened list would say it was a setting.
     *
     * Apps → STATUS, because the full page is the Status card opened out.
     */
    @Test
    fun `every new destination has an up`() {
        // The tab argument is what the tab-dependent roots use; these three do
        // not consult it, and the pair of calls says so.
        for (tab in listOf(0, 1, 2, 3)) {
            assertEquals(Dest.Projects, backFrom(Dest.Project("p1"), tab))
            assertEquals(Dest.Sessions, backFrom(Dest.Projects, tab))
            assertEquals(Dest.Status, backFrom(Dest.Apps, tab))
        }
    }

    /** The roots are still roots: nothing above was allowed to grow an up. */
    @Test
    fun `the four tabs remain roots`() {
        assertNull(backFrom(Dest.Sessions, 1))
        assertNull(backFrom(Dest.Status, 2))
    }

    /**
     * ⚠ THE RETIRED KEY STILL LANDS SOMEWHERE REAL. A saved destination written
     * by app 3.5 says `consoles`; the page it means is this one. Resolving it to
     * the home screen would be the 3.6 rename costing somebody their place for
     * no reason at all — the same promise the settings catalog's id aliases make.
     */
    @Test
    fun `a destination saved as consoles still opens Apps`() {
        assertEquals(Dest.Apps, keyToDest("consoles"))
        assertEquals("but it is only ever WRITTEN under the new name", "apps", destToKey(Dest.Apps))
    }
}
