package com.silencelen.huginn

import com.silencelen.huginn.data.Device
import com.silencelen.huginn.ui.DeviceRules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHETHER A MACHINE IS REACHABLE — ONE ANSWER, because there were two.
 *
 * ⚠⚠ THE RAIL AND THE CARD CONTRADICTED EACH OTHER. A box that both runs claude
 * work and serves local models holds TWO enrolments on purpose (authority is
 * per-row, and the serving credential must never gain claude reach), and they go
 * offline independently: the serving row beats every 30 seconds from a service
 * that is always up, while the claude row is only there when somebody has the
 * client open. `rows.any { it.online }` therefore called a machine reachable on
 * the strength of its `-llm` row while the card under it read "not reachable ·
 * last seen yesterday" off the claude row. The rail tooltip said "4 devices · 4
 * reachable" over a list showing two that were not.
 *
 * The claude row is the one that answers the question a person is asking —
 * "could huginn run something here" — so it is the one that decides. A machine
 * that ONLY serves has no claude row, and there its serving row is the honest
 * answer rather than a permanent "offline".
 */
class DeviceRulesTest {

    private fun row(id: String, scope: String, online: Boolean) =
        Device(id = id, name = id, scope = scope, online = online, machine = "box")

    @Test
    fun `a serving row does not make an offline machine reachable`() {
        val rows = listOf(row("datatreex", "own", online = false), row("datatreex-llm", "generate", online = true))
        assertFalse(
            DeviceRules.reachable(rows),
            "the rail said 4 reachable while the card said 'not reachable' — same fact, two rules",
        )
    }

    @Test
    fun `the claude row decides when it is up`() {
        assertTrue(DeviceRules.reachable(listOf(row("brokkr", "work", online = true), row("brokkr-llm", "generate", online = false))))
    }

    @Test
    fun `a serve-only machine is judged on what it has`() {
        assertTrue(
            DeviceRules.reachable(listOf(row("ragnar-llm", "generate", online = true))),
            "a box with no claude row is not permanently offline — its serving row is all there is",
        )
        assertFalse(DeviceRules.reachable(listOf(row("ragnar-llm", "generate", online = false))))
    }

    @Test
    fun `no rows is not reachable`() {
        assertFalse(DeviceRules.reachable(emptyList()))
    }
}
