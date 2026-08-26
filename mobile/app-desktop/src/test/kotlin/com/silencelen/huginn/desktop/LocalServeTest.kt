package com.silencelen.huginn.desktop

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's set-up door decodes what `huginn-local plan --json` says and
 * stages what the elevated side runs. Both halves are pure text contracts, so
 * both are asserted here — the classic failure being a renamed JSON field that
 * silently zeroes a card the owner is about to consent to.
 *
 * NOTE the kotlin.test argument order: (expected, actual, message).
 */
class LocalServeTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // A real answer from a class-C machine, abbreviated only in the sha-less
    // fields the desktop never reads.
    private val planC = """
        {"version":"0.12.3","dir":"/root/.config/huginn-local","platform":"linux",
         "elevated":true,"setup":false,"deviceName":"prestige-llm",
         "services":["huginn-local-llm","huginn-local-runner"],"cls":"C","plan":"C",
         "downloads":[
           {"kind":"llama","name":"llama-b10628-bin-ubuntu-x64.tar.gz","bytes":17000000},
           {"kind":"model","name":"Qwen3-4B-Q4_K_M.gguf","bytes":2497000000}],
         "needBytes":2514000000,
         "gate":{"ok":true,"line":"need 4.3 GiB (incl. 2 GiB headroom), have 39.0 GiB free"}}
    """.trimIndent()

    @Test
    fun `a serving plan decodes into the consent card's fields`() {
        val p = json.decodeFromString<LocalServe.Plan>(planC)
        assertEquals("C", p.cls)
        assertEquals("prestige-llm", p.deviceName)
        assertNull(p.refuse)
        assertEquals(2, p.downloads.size)
        assertEquals(2_497_000_000L, p.downloads[1].bytes)
        assertEquals(2_514_000_000L, p.needBytes)
        assertTrue(p.gate?.ok == true)
        assertFalse(p.setup)
    }

    @Test
    fun `a refusal decodes with no plan half at all`() {
        val p = json.decodeFromString<LocalServe.Plan>(
            """{"version":"0.12.3","dir":"d","platform":"linux","elevated":true,
                "setup":false,"deviceName":"x-llm","services":[],
                "refuse":"this CPU has no AVX2"}""",
        )
        assertEquals("this CPU has no AVX2", p.refuse)
        assertNull(p.cls)
        assertTrue(p.downloads.isEmpty())
        assertNull(p.gate)
    }

    @Test
    fun `unknown fields from a newer manager do not break the decode`() {
        val p = json.decodeFromString<LocalServe.Plan>(
            """{"version":"0.13.0","dir":"d","platform":"win32","elevated":false,
                "setup":true,"deviceName":"y-llm","services":[],"cls":"G8","plan":"G8",
                "futureField":{"nested":true}}""",
        )
        assertEquals("G8", p.cls)
        assertFalse(p.elevated)
        assertTrue(p.setup)
    }

    @Test
    fun `downloads are syntax-checked under a js name`() {
        // `node --check x.tmp` dies with ERR_UNKNOWN_FILE_EXTENSION on modern
        // node (esm/get_format) — reproduced on node 22.23.1 the day the first
        // Node-24 machine hit it in the field. The temp name is the fix.
        assertTrue(LocalServe.fetchTmpName("huginn-local").endsWith(".js"))
        assertTrue(LocalServe.fetchTmpName("huginn-local").startsWith("huginn-local."))
    }

    @Test
    fun `the elevated cmd quotes all three paths and redirects everything to the log`() {
        val text = LocalServe.elevatedCmdText(
            "C:\\Program Files\\nodejs\\node.exe",
            File("C:\\Users\\o o\\.huginn\\huginn-local"),
            listOf("on", "--yes", "--url", "http://100.64.0.1:8787"),
            File("C:\\ProgramData\\huginn-local\\activate.log"),
        )
        assertTrue(text.startsWith("@echo off\r\n"), text)
        // All three paths may carry spaces (Program Files always does); each
        // must be quoted, and stderr must not be lost.
        assertTrue("\"C:\\Program Files\\nodejs\\node.exe\"" in text, text)
        assertTrue("\"C:\\Users\\o o\\.huginn\\huginn-local\"" in text, text)
        assertTrue("> \"C:\\ProgramData\\huginn-local\\activate.log\" 2>&1" in text, text)
        assertTrue(" on --yes --url http://100.64.0.1:8787 " in text, text)
        assertTrue(text.endsWith("\r\n"), "cmd files end their line DOS-style")
    }
}
