package com.silencelen.huginn.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ EVERY CHIP ROW SCROLLED; NONE OF THEM SAID SO (P-27/D-17).
 *
 * The model chips cut "resumes on reset" at the bezel, the suggestion chips cut
 * the second chip mid-word, and the Screen key pad sliced `PgUp` on both shells.
 * All three work; what they looked like was broken. The desktop had a one-sided
 * unconditional wash of its own that only the suggestion strip used, so the two
 * clients disagreed about a row they draw from one composable.
 *
 * Two things are gated. The DECISION is a pure function, because the bug it
 * guards against is invisible: `ScrollState.maxValue` is `Int.MAX_VALUE` until
 * the row is measured, and a naive `value < maxValue` fades every row in the app
 * — including the ones that fit, which is the very appearance being fixed. The
 * PLACEMENT is a source grep, because there is no compose-ui-test in this module
 * and the failure is a measured width — the [MarkdownTableTest] precedent.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class EdgeFadeTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(path: String, floor: Int = 1_000): String {
        val f = File(root(), path)
        assertTrue(f.isFile, "$path not found at ${f.absolutePath}")
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue(text.length > floor, "$path read as ${text.length} chars — wrong file")
        return text
    }

    // ---------------------------------------------------------- the decision

    @Test
    fun `an unmeasured row fades nothing`() {
        // The whole reason this is a function: Int.MAX_VALUE means "not measured
        // yet", not "miles of content to the right".
        assertEquals(EdgeFade.Fades(start = false, end = false), EdgeFade.edgeFades(0, Int.MAX_VALUE))
    }

    @Test
    fun `a row whose content fits fades nothing`() {
        assertEquals(EdgeFade.Fades(start = false, end = false), EdgeFade.edgeFades(0, 0))
    }

    @Test
    fun `a row scrolled to the start fades only its end`() {
        assertEquals(EdgeFade.Fades(start = false, end = true), EdgeFade.edgeFades(0, 400))
    }

    @Test
    fun `a row scrolled to the end fades only its start`() {
        assertEquals(EdgeFade.Fades(start = true, end = false), EdgeFade.edgeFades(400, 400))
    }

    @Test
    fun `a row scrolled to the middle fades both`() {
        assertEquals(EdgeFade.Fades(start = true, end = true), EdgeFade.edgeFades(200, 400))
    }

    @Test
    fun `an overscrolled value is clamped rather than believed`() {
        assertEquals(EdgeFade.Fades(start = true, end = false), EdgeFade.edgeFades(900, 400))
        assertEquals(EdgeFade.Fades(start = false, end = true), EdgeFade.edgeFades(-20, 400))
    }

    // --------------------------------------------------------- the placement

    @Test
    fun `the fade is applied before the scroll, where the viewport is`() {
        val body = source("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/EdgeFadeRow.kt", 2_000)
        val row = body.substringAfter("fun EdgeFadeRow(")
        val fade = row.indexOf("horizontalEdgeFade(")
        val scroll = row.indexOf("horizontalScroll(")
        assertTrue(fade > 0 && scroll > 0, "the gate lost its subject: fade=$fade scroll=$scroll")
        assertTrue(
            fade < scroll,
            "after the scroll the modifier sees the CONTENT, which has no visible edge to fade",
        )
        // Without an offscreen layer the DstIn mask has no destination and takes
        // the window with it.
        assertTrue("CompositingStrategy.Offscreen" in body, "the mask needs its own layer")
        assertTrue("BlendMode.DstIn" in body, "a colour wash would need to know the ground it sits on")
    }

    @Test
    fun `every row that scrolls sideways draws the affordance`() {
        val rows = mapOf(
            "the shared suggestion chips" to
                "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/SuggestionChips.kt",
            "the phone's model chips and chat options" to
                "app/src/main/kotlin/com/silencelen/huginn/ui/SessionControls.kt",
            "the phone's Screen key pad" to
                "app/src/main/kotlin/com/silencelen/huginn/ui/TerminalScreen.kt",
            "the desktop's Screen key pad" to
                "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SessionView.kt",
        )
        for ((what, path) in rows) {
            assertTrue("EdgeFadeRow(" in source(path), "$what has no scroll affordance")
        }
    }

    /**
     * ⚠ AND THE DESKTOP'S OWN ONE IS GONE. It washed the trailing edge with the
     * window background — a tint over the composer's half-alpha surface — always,
     * on one side, on one shell. Two edge fades stacked would be worse than the
     * hard clip was.
     */
    @Test
    fun `the desktop no longer carries a second, private edge fade`() {
        for (path in listOf(
            "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SessionView.kt",
            "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/ChatView.kt",
        )) {
            assertTrue(
                "rememberEdgeFade(" !in source(path),
                "$path still calls its own edge fade over a row that already has one",
            )
        }
    }
}
