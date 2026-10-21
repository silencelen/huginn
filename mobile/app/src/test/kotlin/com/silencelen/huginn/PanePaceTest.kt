package com.silencelen.huginn

import com.silencelen.huginn.ui.PanePace
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ BEHIND THE SCREEN TAB THE PANE POLL WAITS A SECOND PER NEW FRAME.
 *
 * A working session's pane changes on every tick (Claude Code's spinner and
 * timer) and the long poll answers each change, so the phone asked again at
 * once: about four captures a second from the Conversation tab, measured
 * against a live daemon. [PanePace] is the wait. The view model's loop and the
 * Screen tab's report are held against the source, since the view model cannot
 * be built here (see HistoryFirstTest).
 *
 * NOTE this module is on org.junit: `assertEquals(message, expected, actual)`.
 */
class PanePaceTest {

    @Test
    fun `on the Screen tab the next ask goes at once`() = runTest {
        PanePace.afterNewFrame(MutableStateFlow(true))
        assertEquals(0L, currentTime)
    }

    @Test
    fun `behind it the next ask waits a second`() = runTest {
        PanePace.afterNewFrame(MutableStateFlow(false))
        assertEquals(PanePace.BEHIND_MS, currentTime)
    }

    @Test
    fun `arriving on the Screen tab ends the wait`() = runTest {
        val onTab = MutableStateFlow(false)
        var doneAt = -1L
        launch { PanePace.afterNewFrame(onTab); doneAt = currentTime }
        advanceTimeBy(300)
        runCurrent()
        assertEquals("still waiting at 300 ms", -1L, doneAt)
        onTab.value = true
        runCurrent()
        assertEquals(300L, doneAt)
    }

    @Test
    fun `a busy pane behind the tab is asked for about once a second`() = runTest {
        // The loop's shape, with every answer a new frame (the spinner moved).
        val onTab = MutableStateFlow(false)
        var asks = 0
        val loop = launch {
            while (true) {
                asks++
                delay(30) // the capture
                PanePace.afterNewFrame(onTab)
            }
        }
        advanceTimeBy(10_000)
        runCurrent()
        loop.cancel()
        assertTrue("$asks asks in 10 s", asks in 9..11)
    }

    @Test
    fun `the view model's pane poll waits after each new frame`() {
        val vm = source("app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt")
        val start = vm.indexOf("fun startScreenPolling(")
        assertTrue("startScreenPolling not found", start >= 0)
        val body = vm.substring(start, vm.indexOf("fun stopScreenPolling(", start))
        val newFrame = body.indexOf("_screen.value = s\n")
        assertTrue("the new-frame branch is not where it was", newFrame >= 0)
        val wait = body.indexOf("PanePace.afterNewFrame(screenOnTab)")
        assertTrue("the poll no longer paces itself after a new frame", wait > newFrame)
        assertTrue("the wait is in the answer's branch, not the failure's", wait < body.indexOf(".onFailure"))
    }

    @Test
    fun `the Screen tab reports itself, and the report reaches the view model`() {
        val screen = source("app/src/main/kotlin/com/silencelen/huginn/ui/SessionScreen.kt")
        val terminal = screen.indexOf("TerminalScreen(\n")
        assertTrue("the Screen tab's TerminalScreen not found", terminal >= 0)
        val on = screen.lastIndexOf("onScreenTab(true)", terminal)
        assertTrue("the Screen tab does not report itself", on >= 0)
        val off = screen.indexOf("onStopOrDispose { onScreenTab(false) }", on)
        assertTrue("leaving the tab is not reported", off in on until terminal)
        val main = source("app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt")
        assertTrue("the report does not reach the view model", main.contains("onScreenTab = { vm.screenTabShown(it) }"))
    }

    private fun source(path: String): String = File(mobileRoot(), path).readText()

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
}
