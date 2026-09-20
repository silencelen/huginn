package com.silencelen.huginn.desktop.setup

import com.silencelen.huginn.desktop.ui.settings.SETUP_OWNS_THE_INTRO
import com.silencelen.huginn.settings.SetupStep
import com.silencelen.huginn.ui.settings.SetupScaffoldRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ⚠ D-13. STEPS 4 AND 5 SAID IT TWICE, ONE LINE APART.
 *
 * `SetupScaffold` draws a step's number, its title and a subtitle saying what
 * the step will do. `DeviceSection` and `LocalServeSection` are the two step
 * BODIES big enough to have brought their own heading and blurb with them — they
 * need both in Settings, where nothing is written above them — and hosted inside
 * the flow they printed the step's own subtitle back with a handful of words
 * changed. Twice in a row, on the two longest steps.
 *
 * `SetupFlowView` already documents the identical fix for the autostart row:
 * *"the row already carries the per-OS sentence as its own summary … so nothing
 * is added around it"*. This is the gate that says the same is now true here.
 *
 * TWO HALVES, because either alone would be a test that cannot fail for the
 * right reason: that the blurbs really DO repeat their step's subtitle (so the
 * suppression has a reason to exist), and that the flow really does suppress
 * them (which is the fix).
 */
class SetupIntroTest {

    /** Words a sentence shares with every other sentence, so they prove nothing. */
    private val noise = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "is", "it", "its",
        "this", "that", "so", "as", "at", "for", "with", "be", "can", "will",
        "you", "your", "from", "by", "not", "no", "only", "here", "them", "they",
    )

    private fun content(s: String): Set<String> =
        Regex("[a-z]+").findAll(s.lowercase()).map { it.value }.filter { it !in noise }.toSet()

    /**
     * How much of the step's subtitle the section's blurb says again. Words
     * rather than characters: the two are deliberate rewrites of one another,
     * not copies, and a diff would score them as unrelated.
     */
    private fun overlap(blurb: String, intro: String): Double {
        val words = content(blurb)
        if (words.isEmpty()) return 0.0
        return words.count { it in content(intro) }.toDouble() / words.size
    }

    @Test
    fun `the two suppressed blurbs really are the step subtitle again`() {
        for ((step, intro) in SETUP_OWNS_THE_INTRO) {
            val score = overlap(SetupScaffoldRules.blurb(step), intro)
            assertTrue(
                score >= 0.5,
                "$step is on the suppression list but its section blurb only repeats " +
                    "${(score * 100).toInt()}% of the step subtitle — take it off the list",
            )
        }
    }

    @Test
    fun `and no OTHER step is quietly repeating itself`() {
        // The list is the claim. A step body that starts duplicating its subtitle
        // has to be added here deliberately, not discovered in a screenshot.
        assertTrue(
            SETUP_OWNS_THE_INTRO.keys == setOf(SetupStep.DEVICE, SetupStep.LOCAL_AI),
            "the two longest steps are the two with their own headings: ${SETUP_OWNS_THE_INTRO.keys}",
        )
    }

    /**
     * WHY A SOURCE GREP. There is no compose-ui-test in these modules and the
     * failure is a heading drawn twice — nothing throws, and the only place it
     * was ever visible is a screenshot of the real flow. The call site IS the
     * bug: hosting either section in the flow without `intro = false` puts the
     * duplicate back.
     */
    @Test
    fun `the flow hosts both sections with their own intro off`() {
        val src = File(flowView()).readText()
        for (call in listOf("DeviceSection(store, intro = false)", "LocalServeSection(store, intro = false)")) {
            assertTrue(
                src.contains(call),
                "SetupFlowView must host the section without its heading: $call",
            )
        }
    }

    private fun flowView(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val f = File(root, "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/setup/SetupFlowView.kt")
        assertTrue(f.isFile, "the file this gate reads has moved: $f")
        return f.path
    }
}
