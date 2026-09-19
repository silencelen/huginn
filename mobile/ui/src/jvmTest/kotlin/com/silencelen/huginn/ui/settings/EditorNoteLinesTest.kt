package com.silencelen.huginn.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A NOTE THAT ENDS IN THE THING IT WAS WRITTEN TO SAY NEEDS THE LINES TO SAY IT.
 *
 * [EditorNote] defaults to `maxLines = 1` — right for the one-line witness lines
 * it was made for, and a trap for everything else, because the ellipsis lands
 * silently. 3.1.1 fixed the autoswitch note the same way (`maxLines = 2`); the
 * sign-in stepper was the next one along:
 *
 *   1 · Start sign-in    2 · Approve in the browser    3 · Paste the code        (now: step 1)
 *
 * The parenthetical at the END is the whole reason the line exists — it is the
 * difference between "nothing happened" and "it is waiting for you" — and on the
 * owner's Fold it was the part that got cut.
 *
 * WHY A SOURCE GREP: there is no compose-ui-test in these modules and the
 * failure is a measured ellipsis. The same arrangement `QuickActionsBlurbTest`
 * next door uses.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class EditorNoteLinesTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(name: String): String {
        val f = File(root(), "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/settings/$name")
        assertTrue(f.isFile, "not scanned: ${f.absolutePath}")
        val text = f.readText()
        assertTrue(text.length > 2_000, "$name read as ${text.length} chars — wrong file")
        return text
    }

    @Test
    fun `one line is still the default, which is why the long ones must say so`() {
        assertTrue(
            "internal fun EditorNote(text: String, modifier: Modifier = Modifier, maxLines: Int = 1)"
                in source("QuickActionsEditor.kt"),
            "EditorNote's signature moved — this gate is now guarding nothing",
        )
    }

    @Test
    fun `the sign-in stepper is allowed the lines its own sentence needs`() {
        val text = source("AccountsEditor.kt")
        val marker = "(now: step \$step)"
        val at = text.indexOf(marker)
        assertTrue(at > 0, "the stepper note is gone — this gate lost its subject")
        // The call it sits in: from the EditorNote( before it to the closing
        // paren on its own line — NOT the next ")" in the text, which belongs to
        // the `padding(top = 14.dp)` argument in between.
        val end = text.indexOf("\n        )", at)
        assertTrue(end > at, "the stepper's EditorNote call does not close where expected")
        val call = text.substring(text.lastIndexOf("EditorNote(", at), end)
        assertTrue(
            "maxLines = 3" in call,
            "the step marker is the point of the line and it is being ellipsised away: $call",
        )
    }
}
