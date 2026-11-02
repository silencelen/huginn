package com.silencelen.huginn

import com.silencelen.huginn.data.RecentDirs
import com.silencelen.huginn.ui.StartDirRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** NOTE kotlin.test's argument order is (expected, actual, message). */
class StartDirRulesTest {

    @Test
    fun `blank is the server default, not a problem`() {
        assertNull(StartDirRules.cwdProblem(""))
        assertNull(StartDirRules.cwdProblem("   "))
        assertNull(StartDirRules.cwdOrNull("  "), "blank is OMITTED from the body, never sent as an empty path")
    }

    @Test
    fun `an absolute path passes and a relative one is refused before the round trip`() {
        assertNull(StartDirRules.cwdProblem("/srv/work"))
        assertEquals("/srv/work", StartDirRules.cwdOrNull("  /srv/work  "))
        assertNotNull(StartDirRules.cwdProblem("work/here"), "relative to what? the daemon would 400")
        assertNotNull(StartDirRules.cwdProblem("~/work"), "the server does not expand a tilde for you")
        assertNotNull(StartDirRules.cwdProblem("/srv\n/work"))
    }

    @Test
    fun `the hint names the default when the daemon did`() {
        assertEquals(
            "Leave empty for the server's default (/srv).",
            StartDirRules.hint(RecentDirs(dirs = listOf("/srv/a"), default = "/srv")),
        )
        assertEquals("Leave empty for the server's default directory.", StartDirRules.hint(null))
    }

    @Test
    fun `the dropdown puts the default first, drops duplicates and the path already typed`() {
        val recent = RecentDirs(dirs = listOf("/srv/a", "/srv", "/srv/b", "/srv/a"), default = "/srv")
        assertEquals(listOf("/srv", "/srv/a", "/srv/b"), StartDirRules.choices(recent, ""))
        assertEquals(listOf("/srv", "/srv/b"), StartDirRules.choices(recent, "/srv/a"))
        assertEquals(emptyList(), StartDirRules.choices(null, ""), "no route on an older daemon: no dropdown")
    }
}
