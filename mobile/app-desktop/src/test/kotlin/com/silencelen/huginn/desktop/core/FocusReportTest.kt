package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.desktop.DesktopSettings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What huginn core hears about this window's focus, and when.
 *
 * The core keeps quiet about whatever the window reports as focused, and a
 * notification it keeps quiet about is CONSUMED rather than deferred. So a
 * report that lags the window is an alert lost: the owner alt-tabs away from
 * session X, X asks a question six seconds later, and the core, still holding
 * "sess:X", stays silent for good. The report has to move when the window does.
 *
 * Runs on `runTest`'s virtual clock; the send is a recorder, so no core is needed.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class FocusReportTest {

    private val dirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun device(): CoreDevice {
        val dir = Files.createTempDirectory("focus-report").toFile()
        dirs += dir
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        return CoreDevice(scope, DesktopSettings(File(dir, "settings.json"))) { null }
    }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.cancel() }
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `walking away is reported at once, not on the next tick`() = runTest {
        val shown = MutableStateFlow<String?>("sess:x")
        val heard = mutableListOf<Pair<Long, String?>>()
        backgroundScope.launch { device().followPresence(shown) { heard += currentTime to it } }
        runCurrent()

        advanceTimeBy(2_000)
        shown.value = null // alt-tab to a browser, or the window hidden to the tray
        runCurrent()

        assertEquals(listOf(0L to "sess:x", 2_000L to null), heard)
    }

    @Test
    fun `opening another session is reported at once`() = runTest {
        val shown = MutableStateFlow<String?>("sess:x")
        val heard = mutableListOf<Pair<Long, String?>>()
        backgroundScope.launch { device().followPresence(shown) { heard += currentTime to it } }
        runCurrent()

        advanceTimeBy(5_000)
        shown.value = "sess:y"
        runCurrent()

        assertEquals(5_000L to "sess:y", heard.last(), "x is no longer on screen, so its alerts must reach the owner")
    }

    @Test
    fun `a focus that holds is repeated inside the core's 45 second memory`() = runTest {
        val shown = MutableStateFlow<String?>("chat:c1")
        val heard = mutableListOf<Pair<Long, String?>>()
        backgroundScope.launch { device().followPresence(shown) { heard += currentTime to it } }
        advanceTimeBy(2 * CoreDevice.PRESENCE_EVERY_MS + 1)

        assertEquals(listOf(0L, CoreDevice.PRESENCE_EVERY_MS, 2 * CoreDevice.PRESENCE_EVERY_MS), heard.map { it.first })
        assertEquals(setOf<String?>("chat:c1"), heard.map { it.second }.toSet())
    }
}
