package com.silencelen.huginn

import com.silencelen.huginn.ui.TranscriptGroups.plannedAgents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Owner report, 2026-07-29: the work sheet counted only agents that had started,
 * so it disagreed with the pane's own "N/M agents done" — a planned agent has no
 * file on huginn until it runs. The denominator comes from the TUI row instead.
 *
 * Shared, because both clients show the same fan-out and must say the same number
 * about it. NOTE kotlin.test's argument order is (expected, actual, message) —
 * the reverse of JUnit's, which the phone's copy of this suite uses.
 */
class PlannedAgentsTest {

    @Test
    fun `reads the total off a real workflow row`() {
        // Shape taken from a live pane capture.
        val lines = listOf(
            "◯ andvari-polish-wave3  Wave 3   0/4 agents done · 7m 39s · ↓ 562.4k tokens",
        )
        assertEquals(4, plannedAgents(lines))
    }

    @Test
    fun `takes the widest total when several rows are present`() {
        val lines = listOf(
            "◯ inner   1/3 agents done",
            "◯ outer   2/9 agents done",
        )
        assertEquals(9, plannedAgents(lines))
    }

    @Test
    fun `tolerates the spacing and singular the TUI may use`() {
        assertEquals(1, plannedAgents(listOf("Running  0 / 1  agent done")))
        assertEquals(12, plannedAgents(listOf("phase two 11/12 agents done · 2m")))
    }

    @Test
    fun `says nothing when no row reports agents`() {
        // Must be null, not 0: the caller falls back to the file count, and a 0
        // would render "3 of 0 agents done".
        assertNull(plannedAgents(emptyList()))
        assertNull(plannedAgents(listOf("Running 2 shell commands", "Searching for 1 pattern")))
    }
}
