package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.desktop.ui.settings.LocalLog
import kotlin.test.Test
import kotlin.test.assertEquals

/** The local-AI card's log: one line per download, updated in place. */
class LocalLogTest {
    @Test
    fun `a download's progress replaces its own line, with or without size and rate`() {
        var log = listOf("fetch   Qwen3-4B-Q4_K_M.gguf (2382 MB)")
        for (l in listOf(
            "Qwen3-4B-Q4_K_M.gguf: 0%",
            "Qwen3-4B-Q4_K_M.gguf: 1%  24 of 2382 MB, 2.1 MB/s",
            "Qwen3-4B-Q4_K_M.gguf: 31%  740 of 2382 MB, 2.3 MB/s",
        )) log = LocalLog.append(log, l)
        assertEquals(listOf("fetch   Qwen3-4B-Q4_K_M.gguf (2382 MB)", "Qwen3-4B-Q4_K_M.gguf: 31%  740 of 2382 MB, 2.3 MB/s"), log)
        // Another file's progress is its own line.
        log = LocalLog.append(log, "nomic-embed-text-v1.5.Q8_0.gguf: 4%  6 of 139 MB, 3.0 MB/s")
        assertEquals(3, log.size)
    }
}
