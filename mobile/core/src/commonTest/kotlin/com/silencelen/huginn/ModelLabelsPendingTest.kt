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
 * Pending models: Claude models the newest Claude Code offers and the host's
 * installed CLI does not. They are SHOWN (greyed, with the version they need)
 * and NEVER offered — a pickable row the pane refuses is the fake control
 * ModelLabels exists to forbid.
 */
class ModelLabelsPendingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private val wire = """
        {"models":[
          {"id":"claude-fable-5-2","display":"Fable 5.2","family":"fable"},
          {"id":"claude-opus-5-5","display":"Opus 5.5","family":"opus"},
          {"id":"local-gpubox-qwen3-8b","display":"Qwen3 8B - gpubox-llm","family":"local","available":true,"host":"d-1"}
        ],
        "pending":[
          {"id":"claude-fable-5-3","display":"Fable 5.3","family":"fable","needs":"2.1.330"},
          {"id":"claude-opus-5-5","display":"Opus 5.5","family":"opus","needs":"2.1.330"},
          {"id":"","display":"blank","family":"opus","needs":"2.1.330"}
        ]}
    """.trimIndent()

    private val models = json.decodeFromString<ModelList>(wire).choices()

    @Test
    fun anOlderDaemonsListParsesWithNoPendingRows() {
        val old = json.decodeFromString<ModelList>("""{"models":[{"id":"opus","display":"Opus","family":"opus"}]}""")
        assertTrue(old.pending.isEmpty())
        assertEquals(listOf("opus"), old.choices().map { it.id })
        assertFalse(ModelLabels.isPending(old.choices().single()))
    }

    @Test
    fun choicesAppendPendingRowsMarkedAndDropDuplicatesAndBlanks() {
        // Opus 5.5 is already offered, so its pending twin is dropped; the blank id is junk.
        assertEquals(
            listOf("claude-fable-5-2", "claude-opus-5-5", "local-gpubox-qwen3-8b", "claude-fable-5-3"),
            models.map { it.id },
        )
        assertEquals("2.1.330", models.last().needs)
        assertTrue(ModelLabels.isPending(models.last()))
    }

    @Test
    fun aPendingRowIsNeverAnOptionAtAnySite() {
        for (site in PickerSite.entries) {
            for (started in listOf(false, true)) {
                val ids = ModelLabels.options(models, site, current = "claude-opus-5-5", started = started).map { it.first }
                assertFalse("claude-fable-5-3" in ids, "$site started=$started offered a pending row: $ids")
            }
        }
        assertEquals(
            listOf("claude-fable-5-2", "claude-opus-5-5"),
            ModelLabels.options(models, PickerSite.SESSION).map { it.first },
        )
    }

    @Test
    fun pendingRowsReadAsTheModelAndTheVersionItNeeds() {
        assertEquals(listOf("Fable 5.3 · needs Claude Code 2.1.330"), ModelLabels.pendingRows(models))
        assertEquals(
            listOf("Fable 5.3 · needs Claude Code 2.1.330"),
            ModelLabels.pendingRows(models, current = "claude-opus-5-5", started = true),
            "a started Claude chat still shows them",
        )
    }

    @Test
    fun aStartedLocalChatShowsNoPendingRows() {
        assertTrue(ModelLabels.pendingRows(models, current = "local-gpubox-qwen3-8b", started = true).isEmpty())
    }

    @Test
    fun noPendingRowsWhenTheCliIsCurrent() {
        val current = listOf(ModelChoice(id = "claude-fable-5-2", display = "Fable 5.2", family = "fable"))
        assertTrue(ModelLabels.pendingRows(current).isEmpty())
    }

    @Test
    fun aPendingRowWithNoDisplayFallsBackToItsId() {
        val rows = listOf(ModelChoice(id = "claude-sonnet-6", family = "sonnet", needs = "2.2.0"))
        assertEquals(listOf("claude-sonnet-6 · needs Claude Code 2.2.0"), ModelLabels.pendingRows(rows))
    }
}
