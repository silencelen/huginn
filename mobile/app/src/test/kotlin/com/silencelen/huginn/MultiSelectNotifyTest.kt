package com.silencelen.huginn

import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.data.PromptOption
import com.silencelen.huginn.notify.answerButtonsFor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ A MULTI-SELECT QUESTION GETS NO ANSWER BUTTONS ON A NOTIFICATION.
 *
 * A notification button is one tap; a checkbox dialog wants a set of answers,
 * and the host refuses a single option there with 409 ("this question takes
 * several answers"). The phone's own watch cycle drew every option as a button
 * anyway, so each tap ended as "Not answered". The notification now carries the
 * question with no buttons and opens the session on a tap, as the desktop's
 * toasts and the host's own push already do.
 *
 * The rule is pure; the post it feeds needs a Context, so that one call is held
 * against the source.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class MultiSelectNotifyTest {

    private val options = listOf(
        PromptOption(number = 1, label = "Lint", checked = false),
        PromptOption(number = 2, label = "Tests", checked = true),
        PromptOption(number = 3, label = "Docs", checked = false),
    )

    @Test
    fun `a multi-select question offers no answer buttons`() {
        val prompt = PanePrompt(question = "Which checks should run?", options = options, multiSelect = true, fingerprint = "fp")
        assertTrue("one checkbox of a set offered as a one-tap answer", answerButtonsFor(prompt).isEmpty())
    }

    @Test
    fun `a single-choice question still offers its options, in the pane's numbering`() {
        val prompt = PanePrompt(
            question = "Proceed?",
            options = listOf(PromptOption(1, "Yes"), PromptOption(2, "No")),
            fingerprint = "fp",
        )
        assertEquals(listOf(1 to "Yes", 2 to "No"), answerButtonsFor(prompt).map { it.number to it.label })
    }

    @Test
    fun `no prompt read, no buttons`() {
        assertTrue(answerButtonsFor(null).isEmpty())
    }

    // ------------------------------------------------------------- the wiring

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun watchNotifier(): String {
        val rel = "app/src/main/kotlin/com/silencelen/huginn/notify/WatchNotifier.kt"
        val f = File(rel).takeIf { it.isAbsolute } ?: File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$rel read as ${text.length} chars — wrong file", text.length > 2_000)
        return text
    }

    @Test
    fun `the needs-you notification takes its buttons from the rule`() {
        val post = watchNotifier().substringAfter("\"\$name needs you\"", "").substringBefore("posted++", "")
        assertTrue("the needs-you post read as ${post.length} chars — wrong slice", post.length in 1..800)
        assertTrue("the buttons are not the rule's", "answerButtonsFor(prompt)" in post)
        assertFalse("every option is drawn as a button again", "prompt?.options?.map" in post)
    }
}
