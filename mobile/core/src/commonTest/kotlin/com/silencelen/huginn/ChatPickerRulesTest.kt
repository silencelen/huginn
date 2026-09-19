package com.silencelen.huginn

import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.ui.ModelLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHAT THE NEW-CHAT PICKER MAY OFFER, AND IN WHAT ORDER (P-03, P-21).
 *
 * The walker's dialog held eleven radios and ten labels, did not scroll, and the
 * eleventh option was unreachable by any gesture — so RAGNAR's three local
 * models, including the fleet's only 30B, could not be chosen at all. In the
 * same dialog `DATATREEX` was correctly greyed as "not reachable" while its own
 * models rendered beneath it at full brightness as selectable, and `Nomic Embed`
 * — an EMBEDDING model — was offered as something to have a conversation with.
 *
 * The scroll is the shells' job. Which rows exist, which can be picked and which
 * come first is this file's.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ChatPickerRulesTest {

    private fun device(
        id: String,
        name: String = id,
        online: Boolean = true,
        scope: String = "own",
        machine: String? = null,
    ) = Device(id = id, name = name, online = online, scope = scope, effectiveScope = scope, machine = machine)

    private fun local(id: String, display: String, host: String, available: Boolean = true) =
        ModelChoice(id = id, display = display, family = "local", available = available, host = host)

    // ------------------------------------------------------------- embedders

    @Test
    fun `an embedding model is never offered as a chat model`() {
        val models = listOf(
            local("local-dtx-qwen3-8b", "Qwen3 8B - DATATREEX", "dtx-llm"),
            local("local-dtx-nomic-embed-text", "Nomic Embed - DATATREEX", "dtx-llm"),
        )
        val rows = ModelLabels.localChatRows(models, listOf(device("dtx-llm", scope = "generate")))
        assertEquals(
            listOf("local-dtx-qwen3-8b"),
            rows.map { it.row.id },
            "an embedder answers a vector, not a sentence — it is absent, not greyed",
        )
    }

    @Test
    fun `the tell is the name, from either field`() {
        assertTrue(ModelLabels.isEmbedding(ModelChoice(id = "local-x-nomic-embed-text", display = "Nomic")))
        assertTrue(ModelLabels.isEmbedding(ModelChoice(id = "local-x-thing", display = "Nomic Embed - RAGNAR")))
        assertFalse(ModelLabels.isEmbedding(ModelChoice(id = "local-x-qwen3-30b-a3b", display = "Qwen3 30B-A3B")))
        assertFalse(ModelLabels.isEmbedding(ModelChoice(id = "opus", display = "Opus")))
    }

    // ---------------------------------------------------------- reachability

    /**
     * ⚠⚠ P-21, EXACTLY. `DATATREEX` is enrolled twice — a claude row that has
     * stopped checking in and a serving row that has not — and the picker greyed
     * the machine while drawing its models live. One machine, one answer.
     */
    @Test
    fun `a model is as reachable as its MACHINE, not as its serving credential`() {
        val devices = listOf(
            device("dtx", name = "DATATREEX", online = false, machine = "datatreex"),
            device("dtx-llm", name = "DATATREEX-llm", online = true, scope = "generate", machine = "datatreex"),
        )
        val rows = ModelLabels.localChatRows(
            listOf(local("local-dtx-qwen3-8b", "Qwen3 8B - DATATREEX", "dtx-llm")),
            devices,
        )
        assertEquals(1, rows.size, "it is SHOWN — hiding it reads as an enrolment that vanished")
        assertFalse(rows.first().reachable, "and it reads the same way the machine above it does")
    }

    @Test
    fun `a machine whose rows are all checking in is reachable`() {
        val devices = listOf(
            device("rag", name = "RAGNAR", online = true, machine = "ragnar"),
            device("rag-llm", online = true, scope = "generate", machine = "ragnar"),
        )
        val rows = ModelLabels.localChatRows(
            listOf(local("local-rag-qwen3-30b-a3b", "Qwen3 30B-A3B - RAGNAR", "rag-llm")),
            devices,
        )
        assertTrue(rows.single().reachable)
    }

    @Test
    fun `the daemon's own availability still decides on its own`() {
        // No machine grouping and no matching device row: the model row's
        // `available` is the only witness there is, and it is respected.
        val rows = ModelLabels.localChatRows(
            listOf(local("local-x-a", "A - X", "x-llm", available = false)),
            emptyList(),
        )
        assertFalse(rows.single().reachable)
    }

    // --------------------------------------------------------------- ordering

    /**
     * ⚠ THE OPTION SOMEBODY WANTS MUST NOT BE THE ONE THEY SCROLL FOR (P-03).
     * The dialog scrolls now, but a list that puts three dead machines above the
     * live one is a list that is still hiding the answer.
     */
    @Test
    fun `reachable machines come first, stable within each half`() {
        val devices = listOf(
            device("a", online = false),
            device("b", online = true),
            device("c", online = false),
            device("d", online = true),
        )
        assertEquals(
            listOf("b", "d", "a", "c"),
            ModelLabels.chatHosts(devices).map { it.row.id },
            "reachable first, and the daemon's order inside each half",
        )
    }

    @Test
    fun `serving rows are not offered as places a chat can run`() {
        // A claude run can never live on the generate credential, and its `-llm`
        // name is shown nowhere else in the product.
        val devices = listOf(device("rag"), device("rag-llm", scope = "generate"))
        assertEquals(listOf("rag"), ModelLabels.chatHosts(devices).map { it.row.id })
    }

    @Test
    fun `reachable local models come first too`() {
        val devices = listOf(
            device("dead-llm", online = false, scope = "generate"),
            device("live-llm", online = true, scope = "generate"),
        )
        val models = listOf(
            local("local-dead-a", "A - DEAD", "dead-llm", available = false),
            local("local-live-b", "B - LIVE", "live-llm"),
        )
        assertEquals(
            listOf("local-live-b", "local-dead-a"),
            ModelLabels.localChatRows(models, devices).map { it.row.id },
        )
    }

    @Test
    fun `claude rows are not local rows`() {
        val models = listOf(
            ModelChoice(id = "opus", display = "Opus", family = "claude"),
            local("local-x-a", "A - X", "x-llm"),
        )
        assertEquals(listOf("local-x-a"), ModelLabels.localChatRows(models, emptyList()).map { it.row.id })
    }

    @Test
    fun `nothing enrolled is not a question worth asking`() {
        assertEquals(emptyList(), ModelLabels.chatHosts(emptyList()))
        assertEquals(emptyList(), ModelLabels.localChatRows(emptyList(), emptyList()))
    }
}
