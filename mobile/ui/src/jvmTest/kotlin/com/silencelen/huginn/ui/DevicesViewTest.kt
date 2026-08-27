package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.DeviceModel
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

    // ------------------------------------------------------ the serving row

    private fun servingDevice(
        online: Boolean = true,
        running: Boolean = false,
        models: List<DeviceModel> = listOf(DeviceModel("qwen3-8b", "Qwen3 8B")),
    ) = Device(
        id = "d2", name = "datatreex-llm", platform = "windows",
        scope = "generate", effectiveScope = "generate",
        online = online, running = running, version = "0.12.1",
        llmSlug = "datatreex-llm", models = models,
    )

    @Test
    fun aServingRowSaysWhatItIsAndNeverMentionsTheLock() {
        assertEquals(
            "windows · serves local models · Qwen3 8B · serving · v0.12.1",
            describeDevice(servingDevice()),
        )
        // Exclusive scopes ignore the lock drop, so a locked clause would be a
        // claim about behaviour that never changes.
        assertFalse(describeDevice(servingDevice()).contains("locked"))
    }

    @Test
    fun aServingRowIsHonestAboutReachabilityAndWork() {
        assertTrue(describeDevice(servingDevice(online = false)).contains("not reachable"))
        assertTrue(describeDevice(servingDevice(running = true)).contains("generating"))
    }

    @Test
    fun aServingRowWithNoCatalogStillReads() {
        val line = describeDevice(servingDevice(models = emptyList()))
        assertTrue(line.contains("serves local models"), line)
    }

    // -------------------------------------------------- one machine, one card
    //
    // A box wearing two credentials (claude work + local-AI serving) is ONE
    // device to a person. Grouping is by the daemon's machine key; authority
    // stays per-row, which these tests assert by checking the facets keep
    // their own scopes.

    private fun claudeRow(machine: String? = "datatreex") = Device(
        id = "c1", name = "DATATREEX", platform = "windows",
        scope = "own", effectiveScope = "own", online = true, machine = machine,
    )

    private fun servingRow(machine: String? = "datatreex", online: Boolean = true) = Device(
        id = "s1", name = "datatreex-llm", platform = "windows",
        scope = "generate", effectiveScope = "generate", online = online,
        machine = machine, llmSlug = "datatreex-llm",
        models = listOf(DeviceModel("qwen3-8b", "Qwen3 8B")),
    )

    @Test
    fun rowsSharingAMachineFoldIntoOneGroupNamedForItsClaudeRow() {
        val groups = groupByMachine(listOf(claudeRow(), servingRow()))
        assertEquals(1, groups.size, "one box, one card")
        val g = groups.single()
        assertEquals("DATATREEX", g.head.name, "the name a person knows the box by")
        assertEquals(listOf("own"), g.claude.map { it.scope })
        assertEquals(listOf("generate"), g.serving.map { it.scope })
    }

    @Test
    fun aMachineIsOnlineWhenAnyOfItsFacetsIs() {
        // The real shape this catches: the desktop app is closed (claude facet
        // dark) while the serving SERVICE still answers. The box is not offline.
        val g = groupByMachine(listOf(claudeRow().copy(online = false), servingRow())).single()
        assertTrue(g.online)
    }

    @Test
    fun aServeOnlyMachineIsItsOwnGroupHeadedByTheServingRow() {
        val g = groupByMachine(listOf(servingRow(machine = "lonely"))).single()
        assertEquals("datatreex-llm", g.head.name)
        assertTrue(g.claude.isEmpty())
    }

    @Test
    fun rowsWithoutAMachineKeyNeverMergeWithEachOther() {
        // Pre-2.76.0 daemons emit no machine: falling back to the row id must
        // keep every row its own group rather than lumping strangers together.
        val groups = groupByMachine(listOf(claudeRow(machine = null), servingRow(machine = null)))
        assertEquals(2, groups.size)
    }

    @Test
    fun theMergedServingLineDropsThePlatformTheClaudeFacetAlreadySaid() {
        val line = describeDevice(servingDevice(), includePlatform = false)
        assertEquals("serves local models · Qwen3 8B · serving · v0.12.1", line)
    }

    @Test
    fun theReadersOwnMachineKnowsItselfByKeyAndAPhoneMarksNothing() {
        val g = groupByMachine(listOf(claudeRow(), servingRow())).single()
        assertTrue(g.isThisMachine("datatreex"), "the box the reader sits at")
        assertFalse(g.isThisMachine("prestige"), "someone else's box is not this device")
        assertFalse(g.isThisMachine(null), "a phone (never enrolled) marks nothing")
    }
}
