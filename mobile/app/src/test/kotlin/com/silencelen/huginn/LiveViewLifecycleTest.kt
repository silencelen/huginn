package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE LIVE VIEW'S TWO LIFECYCLE PROPERTIES, HELD BY A SOURCE GREP.
 *
 * WHY A GREP: there is no compose-ui-test and no emulator on this host, and both
 * failures are properties of WHEN an effect runs rather than of a value anything
 * can return. What can be asserted is that the effects are the lifecycle-aware
 * kind and that the pin exists — which is exactly the thing that was wrong, and
 * exactly the thing a later edit would quietly revert while the screen still
 * looked right in a preview.
 *
 * **The lease (P-09).** Backgrounding the app stops the screen poll, and
 * `stopScreenPolling` drops the live flag and the reported geometry on the view
 * model — but the composition survives a background/resume, so a
 * `LaunchedEffect(session, liveTyping)` never ran again. The phone came back
 * still showing "every key goes to the pane" while holding no lease at all
 * (`sizeLeased:false` through four polls over thirty seconds) and still
 * delivering keystrokes: the surface sending keys was no longer the surface
 * holding the window, which is the state owner decision 52 exists to prevent.
 *
 * **The cursor (P-08).** The soft keyboard and the live banner take the
 * composer's place, so the pane viewport loses about four of its rows. Nothing
 * re-scrolled it, so the `❯` prompt and the cursor sat behind the key row — the
 * one mode whose whole promise is "every key goes to the pane" was hiding the
 * place the keys land.
 *
 * NOTE this module is on org.junit, whose three-argument order is (message,
 * expected, actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class LiveViewLifecycleTest {

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun terminalScreen(): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/TerminalScreen.kt")
        assertTrue("TerminalScreen.kt not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("TerminalScreen read as ${text.length} chars — wrong file", text.length > 5_000)
        assertTrue(
            "this gate is now scanning a file with no live keyboard in it",
            text.contains("LiveKeyboardField("),
        )
        return text
    }

    @Test
    fun `the live claim is re-made every time the screen comes back to the foreground`() {
        val text = terminalScreen()
        assertTrue(
            "the live flag is asserted once per composition, so a resume never re-leases",
            text.contains("LifecycleStartEffect(session, liveTyping)"),
        )
        assertFalse(
            "LaunchedEffect(session, liveTyping) does not re-run on ON_START — that IS the bug",
            text.contains("LaunchedEffect(session, liveTyping)"),
        )
    }

    @Test
    fun `the geometry is re-reported on resume, not only when it changes`() {
        val text = terminalScreen()
        assertTrue(
            "stopScreenPolling clears wantCols/wantRows, so a resume must re-report them",
            text.contains("LifecycleStartEffect(cols, rows, promptUp)"),
        )
        assertFalse(
            "keyed on the measurement alone, an unchanged resume reports nothing",
            text.contains("LaunchedEffect(cols, rows, promptUp)"),
        )
    }

    @Test
    fun `live mode pins the bottom of the pane so the cursor row stays visible`() {
        val text = terminalScreen()
        assertTrue(
            "nothing re-scrolls the pane when the keyboard shrinks its viewport",
            text.contains("LaunchedEffect(liveTyping, rows, vScroll.maxValue)"),
        )
        assertTrue(
            "the pin must scroll to the END of the pane, where the prompt row is",
            text.contains("if (liveTyping) vScroll.scrollTo(vScroll.maxValue)"),
        )
    }
}
