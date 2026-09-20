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
        lastSeen: Long? = null,
        locked: Boolean = false,
        actWhileLocked: Boolean? = null,
    ) = Device(
        id = "d1", name = "PRESTIGE", platform = "windows",
        scope = scope, effectiveScope = effective, online = online,
        running = running, queued = queued, version = version, lastSeen = lastSeen,
        locked = locked, actWhileLocked = actWhileLocked,
    )

    /** A fixed clock; `lastSeen` is epoch MILLIseconds, like the daemon sends it. */
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun aLockedMachineSaysBothWhatItIsAndWhatItIsDoingNow() {
        // The load-bearing case. Showing only "look" makes a correctly-configured
        // machine read as misconfigured, and the owner goes looking for a setting
        // that is doing exactly what it should.
        val line = describeDevice(device(scope = "own", effective = "look"))
        assertTrue(line.contains("own"), line)
        assertTrue(line.contains("look while locked"), line)
    }

    /**
     * A machine whose owner turned "Keep act mode while locked" on.
     *
     * ⚠ THE SCOPES NOW MATCH, AND THAT IS THE TRAP. `own`/`own` is what an
     * UNLOCKED machine looks like too, so with nothing added the fleet list would
     * describe a locked box exactly as it describes one somebody is sitting at —
     * and the one fact a reader wants here ("is that thing running unattended?")
     * would be the one fact the line dropped.
     */
    @Test
    fun aMachineThatKeepsActingWhileLockedSaysSo() {
        val line = describeDevice(device(scope = "own", effective = "own", locked = true, actWhileLocked = true))
        assertTrue(line.contains("own"), line)
        assertTrue(line.contains("acting while locked"), line)
        assertFalse(line.contains("look"), line)   // never the drop it did NOT take
    }

    @Test
    fun andSaysNothingExtraWhenNobodyChangedTheRule() {
        // Locked with the setting off already says "look while locked" above;
        // unlocked with it ON is an ordinary machine and must read as one.
        val line = describeDevice(device(scope = "own", effective = "own", locked = false, actWhileLocked = true))
        assertFalse(line.contains("locked"), line)
        // And a row from a daemon that never heard of the field says nothing.
        val old = describeDevice(device(scope = "own", effective = "own", locked = true, actWhileLocked = null))
        assertFalse(old.contains("acting while locked"), old)
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
        persistent: Boolean? = null,
    ) = Device(
        id = "d2", name = "datatreex-llm", platform = "windows",
        scope = "generate", effectiveScope = "generate",
        online = online, running = running, version = "0.12.1",
        llmSlug = "datatreex-llm", models = models, persistent = persistent,
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
    fun aServingRowSaysWhetherItKeepsServingWhenNobodyIsLoggedIn() {
        // The whole reason a serving row is interesting to ANOTHER client: a
        // machine that only answers while its owner is sitting at it is a
        // different offer from one that answers at 3am, and the two rendered
        // identically. The informative half is the negative one.
        assertTrue(describeDevice(servingDevice(persistent = true)).contains("always on"))
        assertTrue(
            describeDevice(servingDevice(persistent = false))
                .contains("only while someone is logged in"),
        )
    }

    @Test
    fun aServingRowThatNeverSaidRendersNoClaimAtAll() {
        // ⚠ A machine enrolled by a CLI older than the facet sends nothing, and
        // the daemon omits the field. Drawing "only while someone is logged in"
        // there would be a fact this client invented — and it would be wrong
        // about every Windows box, which have been LocalSystem services since
        // the tier shipped.
        val line = describeDevice(servingDevice(persistent = null))
        assertFalse(line.contains("always on"), line)
        assertFalse(line.contains("logged in"), line)
        assertEquals("windows · serves local models · Qwen3 8B · serving · v0.12.1", line)
    }

    @Test
    fun aServingRowWithNoCatalogStillReads() {
        val line = describeDevice(servingDevice(models = emptyList()))
        assertTrue(line.contains("serves local models"), line)
    }

    // ------------------------------------------------------ how long it has been
    //
    // "not reachable" reads identically whether a machine went quiet four minutes
    // ago or four weeks ago, and those are completely different situations: one is
    // a laptop lid, the other is an enrolment nobody ever gave back. Every stale
    // row this workstream exists to remove hid in that gap.

    @Test
    fun anUnreachableMachineSaysHowLongItHasBeenThatWay() {
        val line = describeDevice(device(online = false, lastSeen = now - 3 * day), nowMs = now)
        assertTrue(line.contains("not reachable"), line)
        assertTrue(line.contains("last seen 3 days ago"), line)
    }

    @Test
    fun aReachableMachineDoesNotDateItself() {
        // The reader is looking at a machine that is here NOW; when it last
        // checked in is noise, and it would change every few seconds.
        val line = describeDevice(device(online = true, lastSeen = now - 3 * day), nowMs = now)
        assertFalse(line.contains("last seen"), line)
    }

    // Nullable at BOTH ends: a daemon older than the field sends no lastSeen, and
    // a caller with no clock passes no nowMs. Neither may produce a bare
    // "last seen" with nothing after it.
    @Test
    fun anOlderDaemonThatSendsNoLastSeenSaysNothingAboutIt() {
        val line = describeDevice(device(online = false, lastSeen = null), nowMs = now)
        assertTrue(line.contains("not reachable"), line)
        assertFalse(line.contains("last seen"), line)
    }

    @Test
    fun aCallerWithNoClockSaysNothingAboutIt() {
        val line = describeDevice(device(online = false, lastSeen = now - 3 * day))
        assertTrue(line.contains("not reachable"), line)
        assertFalse(line.contains("last seen"), line)
    }

    @Test
    fun aZeroStampIsNotTheEpoch() {
        // A row persisted before lastSeen was recorded reads 0, and "last seen 55
        // years ago" is worse than saying nothing.
        assertFalse(describeDevice(device(online = false, lastSeen = 0L), nowMs = now).contains("last seen"))
    }

    @Test
    fun theVersionStaysLastEvenWithTheAgeInTheLine() {
        // The version is the tail of this line everywhere else in the app; an age
        // clause appended after it would move it and break the shape.
        val line = describeDevice(
            device(online = false, lastSeen = now - 5 * hour, version = "0.14.0"),
            nowMs = now,
        )
        assertTrue(line.endsWith("v0.14.0"), line)
        assertEquals(
            "windows · own · not reachable · last seen 5h ago · v0.14.0",
            line,
        )
    }

    @Test
    fun aServingRowDatesItselfTheSameWay() {
        val line = describeDevice(servingDevice(online = false).copy(lastSeen = now - 2 * hour), nowMs = now)
        assertTrue(line.contains("not reachable · last seen 2h ago"), line)
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
    fun aMachineIsOnlineWhenItsClaudeFacetIs() {
        // ⚠ THIS USED TO BE `any facet`, and the reading behind it — "the desktop
        // app is closed while the serving SERVICE still answers, so the box is not
        // offline" — is true about the BOX and wrong about the question. The card
        // under this dot says "not reachable · last seen yesterday", because that
        // line is drawn per claude row; the rail tooltip counted this flag and
        // said "4 devices · 4 reachable" over a list showing two that were not.
        // One machine, one second, two answers. The question a person is asking a
        // fleet list is "could huginn run something here", and only the claude
        // facet answers it. See DeviceRulesTest in :core, where the rule now lives.
        val g = groupByMachine(listOf(claudeRow().copy(online = false), servingRow())).single()
        assertFalse(g.online, "an always-up -llm row must not contradict the card beneath it")
        assertTrue(groupByMachine(listOf(claudeRow(), servingRow().copy(online = false))).single().online)
    }

    @Test
    fun aServeOnlyMachineIsStillJudgedOnWhatItHas() {
        // The other half of the rule: a box with no claude row at all is not
        // permanently offline — its serving row is the only thing there is to ask.
        assertTrue(groupByMachine(listOf(servingRow(machine = "lonely"))).single().online)
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

    /**
     * ⚠⚠ THE DOT BELONGS TO THE TITLE, NOT TO THE CARD (P-36).
     *
     * Centred against the whole column it landed ON the name for a machine with
     * one capability line and FLOATED between two lines for a machine with
     * several — `brokkr` and `huginn` marked one way, DATATREEX, PRESTIGE and
     * RAGNAR another, in a list read top to bottom for exactly that mark.
     *
     * A source grep because the failure is an alignment: there is no
     * compose-ui-test in this module (the [MarkdownTableTest] precedent).
     */
    @Test
    fun theStateDotSitsOnTheTitleWhateverTheCardGoesOnToSay() {
        val f = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "settings.gradle.kts").isFile }
            ?.let { java.io.File(it, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/DevicesView.kt") }
        assertTrue(f != null && f.isFile, "DevicesView.kt not found from ${java.io.File("").absolutePath}")
        val text = f!!.readText()
        assertTrue(text.length > 5_000, "DevicesView.kt read as ${text.length} chars — wrong file")
        val card = text.substringAfter("private fun MachineCard(").substringBefore("private fun ")
        assertTrue(card.length > 500, "MachineCard read as ${card.length} chars — wrong slice")
        val row = card.substringAfter("Column(Modifier.padding(start = 14.dp").substringBefore("Column(Modifier.padding(start = 10.dp")
        assertTrue(row.length in 1..1_500, "the title row read as ${row.length} chars — wrong slice")
        assertTrue(
            "Row(verticalAlignment = Alignment.Top)" in row,
            "the dot is centred against the card again, so it moves with the number of lines",
        )
        assertTrue("MACHINE_TITLE_LINE" in row, "the dot has no line box to be centred inside")
    }
}
