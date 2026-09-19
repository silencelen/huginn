package com.silencelen.huginn

import androidx.compose.ui.unit.dp
import com.silencelen.huginn.ui.SUGGESTION_CHIP_FRACTION
import com.silencelen.huginn.ui.suggestionChipMaxWidth
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NO SUGGESTION CHIP IS WIDER THAN THE ROW IT SITS IN.
 *
 * 3.1.0's D13, still there in 3.5.0: on the owner's 1080 px Fold the second
 * suggested reply began at x=978 and was otherwise off the world. The row does
 * scroll — but nothing on screen said so, because the first chip filled it edge
 * to edge, and a scroll nobody can see is a scroll nobody uses.
 *
 * The cause is a Compose layout fact rather than a missing modifier: inside a
 * `horizontalScroll` the incoming maxWidth is INFINITE, so the chip's own
 * `maxLines = 1, TextOverflow.Ellipsis` can never fire and the chip grows to the
 * length of its sentence. A chip has to be given a width to ellipsise against.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class SuggestionChipsTest {

    @Test
    fun `a chip leaves room for the next one to peek`() {
        assertTrue("a chip may not fill the row", SUGGESTION_CHIP_FRACTION < 1f)
        assertTrue("a chip that small is not readable either", SUGGESTION_CHIP_FRACTION > 0.5f)
        // The Fold's cover screen, in dp.
        val widest = suggestionChipMaxWidth(360.dp)
        assertTrue("a 360dp row caps a chip at $widest", widest > 270.dp && widest < 290.dp)
        assertTrue(
            "the widest chip must leave a visible strip of the next one",
            360.dp - suggestionChipMaxWidth(360.dp) > 48.dp,
        )
    }

    @Test
    fun `the cap follows the row rather than being a fixed number`() {
        // A fold-out, a tablet and the desktop all draw this row.
        assertTrue(suggestionChipMaxWidth(900.dp) > suggestionChipMaxWidth(360.dp))
    }

    /** Gradle runs a test with the MODULE dir as its working dir; `mobile/` is up. */
    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    @Test
    fun `neither phone screen writes its own chip row`() {
        // Both screens hand-rolled the Row, which is how one cap in `:ui` failed
        // to reach either of them. The shared composable is the only one that
        // knows the rule, so it has to be the only one drawing a chip.
        for (name in listOf("SessionScreen.kt", "ChatScreen.kt")) {
            val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/$name")
            assertTrue("$name not found at ${f.absolutePath}", f.isFile)
            val text = f.readText()
            assertTrue("$name read as ${text.length} chars — wrong file", text.length > 5_000)
            assertTrue(
                "$name draws suggestion chips itself instead of calling SuggestionChips",
                !text.contains("SuggestionChip("),
            )
            assertTrue(
                "$name no longer offers suggestions at all — this gate is scanning the wrong file",
                text.contains("SuggestionChips("),
            )
        }
    }
}
