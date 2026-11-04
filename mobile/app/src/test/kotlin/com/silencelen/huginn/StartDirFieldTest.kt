package com.silencelen.huginn

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.silencelen.huginn.ui.StartDirField
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE NEW-SESSION DIALOG SHOWS THE END OF A LONG PATH.
 *
 * Fold cover screen, 2026-11-03: "Starting directory" showed
 * `/root/projects/huginn-e2e/al` — one line, the cursor at 0, the Recent button
 * taking the rest — and the Recent list cut each row at its END, so two
 * `/root/projects/apv2/assets/src/voice/…` rows were the same row to the eye.
 * The tail is the part that names a directory. The rows' label rule is the
 * desktop's, in `:core` (`StartDirRules.DirLabel`, `DirLabelTest`); this holds
 * the field and the phone's use of that rule.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class StartDirFieldTest {

    @Test
    fun `a picked path lands with the cursor at its end, so the field shows the tail`() {
        val v = StartDirField.picked("/root/projects/huginn-e2e/alpha")
        assertEquals("/root/projects/huginn-e2e/alpha", v.text)
        assertEquals(TextRange(31), v.selection)
        assertEquals("an emptied field is empty", TextRange(0), StartDirField.picked("").selection)
    }

    @Test
    fun `a directory stays one line, and the cursor stays where it was`() {
        // Enter in the wrapping field inserts a newline at the cursor: it is
        // taken out, and the cursor is where it was before the key.
        val entered = TextFieldValue("/srv/exa\nmple", selection = TextRange(9))
        val kept = StartDirField.oneLine(entered)
        assertEquals("/srv/example", kept.text)
        assertEquals(TextRange(8), kept.selection)
        // A pasted path with a trailing line break.
        assertEquals("/srv/x", StartDirField.oneLine(TextFieldValue("/srv/x\r\n", TextRange(8))).text)
        // Ordinary typing passes through untouched.
        val typed = TextFieldValue("/srv/x", TextRange(3))
        assertEquals(typed, StartDirField.oneLine(typed))
    }

    // ------------------------------------------------------------ the dialog

    @Test
    fun `the dialog's field wraps, keeps one line, lands a pick at its end, and cuts rows in the middle`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val f = File(root, "app/src/main/kotlin/com/silencelen/huginn/ui/SessionsScreen.kt")
        assertTrue("SessionsScreen.kt not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("read as ${text.length} chars — wrong file", text.length > 5_000)
        val field = text.substringAfter("value = newDirField,").substringBefore("HouseMenu(")
        assertTrue("the directory field read as ${field.length} chars — wrong slice", field.length in 50..2_000)
        assertFalse("the field is one line again, and a long path shows only its head", field.contains("singleLine = true"))
        assertTrue("the field does not wrap", field.contains("maxLines = 2"))
        assertTrue("a typed line break stays in the path", field.contains("StartDirField.oneLine("))
        assertTrue(
            "a picked path lands with the cursor at 0",
            text.contains("newDirField = StartDirField.picked(d)"),
        )
        // The rows: the shared rule, measured into the house menu's row, and
        // the label is what the row shows — the pick is still the whole path.
        assertTrue("the Recent rows are cut at their end again", text.contains("StartDirRules.DirLabel.fit(d)"))
        assertTrue(
            "the rows are not drawn with their labels",
            text.contains("HouseMenuItem(dirLabels.getOrElse(i) { d })"),
        )
    }
}
