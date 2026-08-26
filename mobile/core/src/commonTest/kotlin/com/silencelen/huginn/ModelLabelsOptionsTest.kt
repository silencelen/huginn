package com.silencelen.huginn

import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.data.ModelList
import com.silencelen.huginn.ui.ModelLabels
import com.silencelen.huginn.ui.ModelLabels.PickerSite
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The local-model picker rules: which rows exist at which site. These are the
 * fences that keep a session chip from offering a model it can never run, and
 * a chat picker from hiding the feature.
 */
class ModelLabelsOptionsTest {

    private val models = listOf(
        ModelChoice(id = "fable", display = "Fable 5", family = "fable"),
        ModelChoice(id = "opus", display = "Opus 5", family = "opus"),
        ModelChoice(id = "local-gpubox-qwen3-8b", display = "Qwen3 8B - gpubox-llm", family = "local", available = true, host = "d-1"),
        ModelChoice(id = "local-gpubox-nomic-embed", display = "Nomic Embed - gpubox-llm", family = "local", available = false, host = "d-1"),
    )

    @Test
    fun sessionSitesNeverOfferLocalRows() {
        // A session chip types /model into a live Claude pane; a local row there
        // is a fake control by definition.
        val ids = ModelLabels.options(models, PickerSite.SESSION).map { it.first }
        assertEquals(listOf("fable", "opus"), ids)
    }

    @Test
    fun chatSitesOfferAvailableLocalRowsAfterClaude() {
        val ids = ModelLabels.options(models, PickerSite.CHAT).map { it.first }
        assertEquals(listOf("fable", "opus", "local-gpubox-qwen3-8b"), ids)
    }

    @Test
    fun anUnreachableMachineRowIsAbsentNotDoomed() {
        // A pickable row that always fails is a fake control; absence is how an
        // offline machine already reads everywhere else.
        val ids = ModelLabels.options(models, PickerSite.CHAT).map { it.first }
        assertFalse("local-gpubox-nomic-embed" in ids)
    }

    @Test
    fun anEmptyListFallsBackAtBothSites() {
        assertEquals(ModelLabels.FALLBACK_MODELS, ModelLabels.options(emptyList(), PickerSite.SESSION))
        assertEquals(ModelLabels.FALLBACK_MODELS, ModelLabels.options(emptyList(), PickerSite.CHAT))
    }

    @Test
    fun isLocalMatchesByPrefixAndByListedId() {
        assertTrue(ModelLabels.isLocal("local-gpubox-qwen3-8b", models))
        assertTrue(ModelLabels.isLocal("local-anything-at-all"), "the prefix alone answers when the list is stale")
        assertFalse(ModelLabels.isLocal("opus", models))
        assertFalse(ModelLabels.isLocal(null, models))
        assertFalse(ModelLabels.isLocal("  ", models))
    }

    @Test
    fun theModelLabelPrefersTheDisplayForALocalId() {
        assertEquals("Qwen3 8B - gpubox-llm", ModelLabels.model("local-gpubox-qwen3-8b", models))
        assertEquals("opus", ModelLabels.model("opus"), "without a list the id stands as before")
    }

    // ------------------------------------------------------- wire tolerance

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun aNewerDaemonRowParses() {
        val wire = """{"models":[{"id":"local-x-y","display":"Y - x","family":"local","available":false,"host":"d-9","surprise":1}]}"""
        val row = json.decodeFromString<ModelList>(wire).models.single()
        assertEquals(false, row.available)
        assertEquals("d-9", row.host)
    }

    @Test
    fun anOlderDaemonRowParsesToTheOldBehaviour() {
        val wire = """{"models":[{"id":"opus","display":"Opus 5","family":"opus"}]}"""
        val row = json.decodeFromString<ModelList>(wire).models.single()
        assertEquals(true, row.available, "absent means available — nothing degrades")
        assertEquals(null, row.host)
    }

    // ------------------------------------------------- the started-chat menus
    //
    // The daemon pins a chat WITH HISTORY to its family and machine, so a menu
    // that keeps offering the other side is a menu of permanent 409s — the
    // audit's exact finding. An unstarted chat may re-decide freely (daemon
    // 2.77.0), so only `started` shrinks anything.

    private val twoMachines = models + listOf(
        ModelChoice(id = "local-prestige-qwen3-4b", display = "Qwen3 4B - PRESTIGE", family = "local", available = true, host = "d-2"),
    )

    @Test
    fun aStartedLocalChatOffersOnlyItsOwnMachine() {
        val ids = ModelLabels.options(twoMachines, PickerSite.CHAT, current = "local-gpubox-qwen3-8b", started = true)
            .map { it.first }
        assertEquals(listOf("local-gpubox-qwen3-8b"), ids, "no Claude rows, no other machines, no offline sibling")
    }

    @Test
    fun aStartedClaudeChatOffersNoMachinesAtAll() {
        val ids = ModelLabels.options(twoMachines, PickerSite.CHAT, current = "opus", started = true).map { it.first }
        assertEquals(listOf("fable", "opus"), ids)
    }

    @Test
    fun anUnstartedChatKeepsTheWholeMenu() {
        val ids = ModelLabels.options(twoMachines, PickerSite.CHAT, current = "opus", started = false).map { it.first }
        assertEquals(listOf("fable", "opus", "local-gpubox-qwen3-8b", "local-prestige-qwen3-4b"), ids)
    }

    @Test
    fun aStartedLocalChatWhoseMachineVanishedOffersOnlyItself() {
        // The machine was unenrolled: the catalog has no rows for it, and the
        // only honest menu is the current model with nowhere to go.
        val gone = listOf(ModelChoice(id = "opus", display = "Opus 5", family = "opus"))
        val rows = ModelLabels.options(gone, PickerSite.CHAT, current = "local-gpubox-qwen3-8b", started = true)
        assertEquals(listOf("local-gpubox-qwen3-8b"), rows.map { it.first })
    }
}
