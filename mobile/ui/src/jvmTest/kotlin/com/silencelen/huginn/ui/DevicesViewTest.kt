package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one line a person reads to decide whether a machine is doing what they
 * think it is. Both clients draw it from here.
 */
class DevicesViewTest {

    private fun device(
        scope: String = "own",
        effective: String = scope,
        online: Boolean = true,
        running: Boolean = false,
        queued: Int = 0,
        version: String? = null,
    ) = Device(
        id = "d1", name = "PRESTIGE", platform = "windows",
        scope = scope, effectiveScope = effective, online = online,
        running = running, queued = queued, version = version,
    )

    @Test
    fun aLockedMachineSaysBothWhatItIsAndWhatItIsDoingNow() {
        // The load-bearing case. Showing only "look" makes a correctly-configured
        // machine read as misconfigured, and the owner goes looking for a setting
        // that is doing exactly what it should.
        val line = describeDevice(device(scope = "own", effective = "look"))
        assertTrue(line.contains("own"), line)
        assertTrue(line.contains("look while locked"), line)
    }

    @Test
    fun anUnlockedMachineSaysItsScopeOnce() {
        val line = describeDevice(device(scope = "work"))
        assertTrue(line.contains("work"), line)
        assertFalse(line.contains("while locked"), line)
    }

    @Test
    fun stateIsWhatItIsDoing() {
        assertTrue(describeDevice(device(running = true)).contains("running something"))
        assertTrue(describeDevice(device(online = false)).contains("not reachable"))
        assertTrue(describeDevice(device(queued = 3)).contains("3 queued"))
        assertTrue(describeDevice(device()).contains("idle"))
    }

    @Test
    fun notReachableBeatsQueued() {
        // A machine that left the building with work waiting for it is offline
        // first and busy second; saying "2 queued" would read as progress.
        val line = describeDevice(device(online = false, queued = 2))
        assertTrue(line.contains("not reachable"), line)
        assertFalse(line.contains("queued"), line)
    }

    @Test
    fun theVersionIsOmittedRatherThanShownEmpty() {
        assertFalse(describeDevice(device(version = null)).contains("v"))
        assertFalse(describeDevice(device(version = "  ")).contains("· v"))
        assertTrue(describeDevice(device(version = "0.8.3")).endsWith("v0.8.3"))
    }

    @Test
    fun theWholeLineReadsAsOneSentenceOfFacts() {
        assertEquals(
            "windows · own, look while locked · idle · v0.8.3",
            describeDevice(device(scope = "own", effective = "look", version = "0.8.3")),
        )
    }
}
