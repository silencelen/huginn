package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.desktop.ui.settings.LocalLog
import com.silencelen.huginn.desktop.ui.settings.LocalServeFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `manager lines are read as finished files and partial ones`() {
        assertEquals(LocalLog.Step.Finished("WinSW-x64.exe"), LocalLog.parse("  ok      WinSW-x64.exe sha256 verified"))
        assertEquals(LocalLog.Step.Finished("Qwen3-4B-Q4_K_M.gguf"), LocalLog.parse("kept    Qwen3-4B-Q4_K_M.gguf (already present, hash matches)"))
        assertEquals(LocalLog.Step.Partial("Qwen3-4B-Q4_K_M.gguf", 31, 740, 2.3), LocalLog.parse("Qwen3-4B-Q4_K_M.gguf: 31%  740 of 2382 MB, 2.3 MB/s"))
        assertEquals(LocalLog.Step.Partial("Qwen3-4B-Q4_K_M.gguf", 30, null, null), LocalLog.parse("Qwen3-4B-Q4_K_M.gguf: 30%"), "an older manager")
        assertEquals(null, LocalLog.parse("ok: keyless completions are refused — the API key is enforced"))
        assertEquals(null, LocalLog.parse("fetch   Qwen3-4B-Q4_K_M.gguf (2382 MB)"))
    }

    @Test
    fun `progress is the whole install, with a time left once the speed is known`() {
        assertEquals("0 of 2568 MB", LocalLog.Progress(0, 2568, null).words)
        val p = LocalLog.Progress(1240, 2568, 2.3)
        assertEquals("1240 of 2568 MB, 2.3 MB/s, about 10 min left", p.words)
        assertTrue(p.fraction in 0.48f..0.49f)
        assertEquals("1 h 4 min", LocalLog.duration(3840))
    }

    @Test
    fun `an install counts finished files and the current one against the plan`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<LocalLog.Progress?>()
        val done = java.util.concurrent.CountDownLatch(1)
        LocalServeFlow.run(install = true, downloads = mapOf("llama.zip" to 17L, "Qwen3-4B-Q4_K_M.gguf" to 2382L, "nomic.gguf" to 139L)) {
            for (l in listOf(
                "fetch   llama.zip (17 MB)", "llama.zip: 100%  17 of 17 MB, 4.0 MB/s", "ok      llama.zip sha256 verified",
                "kept    nomic.gguf (already present, hash matches)",
                "fetch   Qwen3-4B-Q4_K_M.gguf (2382 MB)", "Qwen3-4B-Q4_K_M.gguf: 31%  740 of 2382 MB, 2.3 MB/s",
            )) { LocalServeFlow.line(l); seen += LocalServeFlow.progress.value }
            done.countDown()
        }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val last = seen.last()!!
        assertEquals(LocalLog.Progress(17 + 139 + 740, 2538, 2.3), last)
    }
}
