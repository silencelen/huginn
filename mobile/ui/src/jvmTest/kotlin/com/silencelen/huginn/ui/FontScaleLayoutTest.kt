package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.ui.work.PulseDot
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Layouts that were measured on the Fold at large font scales on 2026-10-02
 * (1080 px wide, density 2.625) and came apart. Rendered here at the same size.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FontScaleLayoutTest {

    private fun render(fontScale: Float, height: Int = 600, content: @Composable () -> Unit): ImageComposeScene {
        val scene = ImageComposeScene(width = 1080, height = height, density = Density(2.625f, fontScale)) {
            MaterialTheme { content() }
        }
        scene.render()
        return scene
    }

    /**
     * The Rounds verdict line shared a Row with Pause / Run now / Edit as a
     * weighted, single-line Text, so the buttons were measured first and the
     * verdict got the rest: 456 px at 1.0, 370 at 1.3, 210 at 2.0, where
     * "Worth a look · 13h ago · 1 item" read "Worth a…".
     */
    @Test
    fun theRoundVerdictIsReadableAtLargeFontScales() {
        for (fs in listOf(1.0f, 1.3f, 2.0f)) for (v in listOf("Worth a look · 13h ago · 1 item", "Needs you · 4 days ago · 7 items")) {
            var layout: TextLayoutResult? = null
            render(fs) {
                // The card's own insets: 8 dp outside, 14/6 inside.
                Box(Modifier.width((1080 / 2.625f - 16 - 20).dp)) {
                    RoundVerdictRow(AnnotatedString(v), onVerdictLayout = { layout = it }) {
                        TextButton(onClick = {}) { Text("Pause") }
                        TextButton(onClick = {}) { Text("Run now") }
                        TextButton(onClick = {}) { Text("Edit") }
                    }
                }
            }.close()
            val l = layout ?: error("verdict never laid out at $fs")
            assertFalse(l.hasVisualOverflow, "fs=$fs '$v' is cut: ${l.size}")
        }
    }

    /** And the Round card draws its verdict through that row, not a Row of its own. */
    @Test
    fun theRoundCardUsesTheVerdictRow() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        val row = java.io.File(root, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/RoundsView.kt").readText()
            .substringAfter("private fun RoundRow(").substringBefore("internal fun RoundVerdictRow(")
        assertTrue("RoundVerdictRow(" in row, "RoundRow lays out its verdict itself again")
    }

    /**
     * And at normal size it stays one line beside the buttons, as designed.
     */
    @Test
    fun atNormalSizeTheVerdictStillSharesTheButtonsLine() {
        var buttonY = -1f
        render(1.0f) {
            Box(Modifier.width((1080 / 2.625f - 16 - 20).dp)) {
                RoundVerdictRow(AnnotatedString("OK · 2h ago"),
                    onVerdictLayout = {}) {
                    TextButton(onClick = {}, modifier = Modifier.onGloballyPositioned { buttonY = it.boundsInRootTop() }) { Text("Pause") }
                    TextButton(onClick = {}) { Text("Run now") }
                    TextButton(onClick = {}) { Text("Edit") }
                }
            }
        }.close()
        assertTrue(buttonY >= 0f && buttonY < 40f, "buttons dropped to a second line at 1.0 (y=$buttonY)")
    }

    /**
     * The Sessions card's "ctx 48%" was squeezed to three lines at 2.0. The badge
     * is a single token: it never wraps.
     */
    @Test
    fun theContextBadgeNeverWraps() {
        var h = 0
        var one = 0
        render(2.0f) {
            Box(Modifier.width(30.dp)) {
                ContextBadge(48, Modifier.onGloballyPositioned { h = it.size.height })
            }
        }.close()
        render(2.0f) {
            ContextBadge(48, Modifier.onGloballyPositioned { one = it.size.height })
        }.close()
        assertTrue(one > 0 && h == one, "the badge wrapped: $h px against one line of $one px")
    }

    /**
     * 2026-10-02 on the Fold: the Sessions tab drew ~100 frames a second for as
     * long as a session was working, every other tab 0. The breathing dot was an
     * infinite tween, which asks for a frame on every vsync. After the first
     * frames it must leave the frame clock alone between its steps.
     */
    @Test
    fun theBreathingDotDoesNotAskForEveryFrame() {
        // Counted at the parent: with no layer of its own in between, a dot that
        // invalidates its drawing redraws this Box too. (scene.hasInvalidations()
        // cannot tell the two apart: any live effect counts as pending work.)
        var draws = 0
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(2.625f, 1f)) {
            MaterialTheme {
                Box(Modifier.drawBehind { draws++ }) { PulseDot(Color.Red) }
            }
        }
        val started = System.nanoTime()
        var t = 0L
        repeat(60) {
            scene.render(t)
            t += 16_666_667L
        }
        val realMs = (System.nanoTime() - started) / 1_000_000
        scene.close()
        // Steps run on real time, so allow one per 160 ms that really passed.
        val allowed = 3 + (realMs / 160).toInt()
        assertTrue(draws <= allowed, "the dot redrew $draws of 60 consecutive frames (allowed $allowed in ${realMs}ms)")
    }
}

private fun androidx.compose.ui.layout.LayoutCoordinates.boundsInRootTop(): Float = boundsInRoot().top
