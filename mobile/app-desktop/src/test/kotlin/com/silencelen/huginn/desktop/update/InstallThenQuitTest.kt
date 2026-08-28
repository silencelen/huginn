package com.silencelen.huginn.desktop.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The caller's half of [DesktopUpdater.install]: quit, but only if the installer
 * is really running.
 *
 * Every case here is one the window cannot show. Quitting on a launch that never
 * happened looks IDENTICAL to an update that worked — the app disappears either
 * way — and the difference only surfaces when nothing comes back, by which point
 * the error line that said why is gone with the process that was drawing it.
 */
class InstallThenQuitTest {

    @Test
    fun `quits once the installer is running`() {
        var quits = 0
        val asked = installThenQuit(install = { true }, quit = { quits++ })
        assertTrue(asked)
        assertEquals(1, quits, "the installer cannot replace files this process holds open")
    }

    @Test
    fun `stays running when the installer never started`() {
        var quits = 0
        val asked = installThenQuit(install = { false }, quit = { quits++ })
        assertFalse(asked)
        assertEquals(0, quits, "nothing is installing, so this client is still the only thing that can say so")
    }

    @Test
    fun `stays running when the launch throws`() {
        var quits = 0
        // install() promises never to throw into a click handler; this is the
        // belt to that brace, because the cost of being wrong is the window
        // going away on a machine where nothing was ever spawned to bring it
        // back.
        val asked = installThenQuit(install = { throw IllegalStateException("no such file") }, quit = { quits++ })
        assertFalse(asked)
        assertEquals(0, quits)
    }
}
