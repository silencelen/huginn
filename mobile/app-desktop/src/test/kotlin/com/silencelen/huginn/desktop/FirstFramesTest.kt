package com.silencelen.huginn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window settles itself (one real resize) only on the launch after an
 * install, a reinstall or an update: the launch that showed garbled or black
 * areas until the person re-maximized it.
 */
class FirstFramesTest {
    private val installed = FirstFrames.build("2.6.2", 1_792_300_000_000)

    @Test
    fun `a fresh install settles once`() {
        assertTrue(FirstFrames.needsSettle(lastLaunchBuild = "", build = installed))
    }

    @Test
    fun `an update settles once`() {
        assertTrue(FirstFrames.needsSettle(FirstFrames.build("2.6.1", 1_792_100_000_000), installed))
    }

    @Test
    fun `a reinstall of the same version settles too`() {
        assertTrue(FirstFrames.needsSettle(FirstFrames.build("2.6.2", 1_792_200_000_000), installed))
    }

    @Test
    fun `an ordinary launch is left alone`() {
        assertFalse(FirstFrames.needsSettle(installed, installed))
    }

    @Test
    fun `a development run keys on the version alone`() {
        assertEquals("2.6.2", FirstFrames.build(" 2.6.2 ", null))
        assertFalse(FirstFrames.needsSettle("2.6.2", FirstFrames.build("2.6.2", null)))
    }

    @Test
    fun `an unknown version never moves the window`() {
        assertFalse(FirstFrames.needsSettle("2.6.1", FirstFrames.build("", null)))
        assertFalse(FirstFrames.needsSettle("2.6.1", FirstFrames.build("", 5)))
    }
}
