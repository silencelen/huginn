package com.silencelen.huginn

import com.silencelen.huginn.ui.ProjectEnd
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE VERBS THE PHONE WAS MISSING, AND THE ONE IT SPELLED WRONG.
 *
 * P-15 — a project started on the phone could not be wound down on the phone.
 * The project page offered "Add member" and nothing else: no overflow menu, no
 * row menu on the list, no per-member action, so its sessions had to be hunted
 * down one at a time in the Sessions list and killed there. Open-a-member and
 * drop-a-member already existed (the row itself, and inside the member's own
 * disclosure); ENDING THE CLUSTER had nowhere at all.
 *
 * P-22 — "Wrap up" had no leading icon, so its label started in the icon column
 * (x=754) while Rename, Archive and Kill started at x=849.
 *
 * P-23 — the 422 reachability refusal is cut mid-line at the dialog's bottom
 * edge on first render, and the fix lines a person is expected to run on
 * ANOTHER MACHINE could only be copied from a control below that fold.
 *
 * NOTE this module is on org.junit, whose three-argument order is (message,
 * expected, actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class PhoneSurfaceVerbsTest {

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun read(relative: String): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("$relative read as ${text.length} chars — wrong file", text.length > 1_000)
        return text
    }

    // ------------------------------------------------------------- P-15

    /**
     * ⚠⚠ THE SPELLINGS ARE THE DAEMON'S. `DELETE /v1/projects/:id` accepts
     * exactly `"now"` and `"graceful"`; anything else ends NOTHING and deletes
     * the record anyway. A typo would look like it worked, forget the project,
     * and leave every session running with nothing pointing at it.
     */
    @Test
    fun `the end choices carry the wire words the daemon accepts`() {
        assertNull("leaving them running must send no end at all", ProjectEnd.KEEP.wire)
        assertEquals("graceful", ProjectEnd.GRACEFUL.wire)
        assertEquals("now", ProjectEnd.NOW.wire)
    }

    @Test
    fun `there are exactly three outcomes and no fourth`() {
        assertEquals(3, ProjectEnd.entries.size)
    }

    @Test
    fun `a project has an overflow menu with the ending verb in it`() {
        val text = read("MainActivity.kt")
        assertTrue(
            "the top-bar action menu is still session-and-chat only",
            text.contains("dest is Dest.Project) {"),
        )
        assertTrue("no ending verb on the project surface", text.contains("\"End project…\""))
        assertTrue(
            "the menu does not raise the dialog",
            text.contains("endProjectTarget = d.id"),
        )
        assertTrue(
            "the choice is not carried to the daemon",
            text.contains("vm.deleteProject(id, choice.wire)"),
        )
    }

    @Test
    fun `the ending dialog says what happens to the sessions, not only to the record`() {
        val text = read("ui/ProjectDashboardScreen.kt")
        assertTrue(text.contains("\"Leave them running\""))
        assertTrue(text.contains("\"Wind them down\""))
        assertTrue(text.contains("\"End them now\""))
    }

    // ------------------------------------------------------------- P-22

    @Test
    fun `every verb in the session row menu has a leading icon`() {
        val text = read("ui/SessionsScreen.kt")
        // The row's own menu, bounded by the state flag that opens it, so the
        // count cannot drift onto a dialog's buttons elsewhere in the file.
        val open = text.indexOf("DropdownMenu(expanded = menu")
        assertTrue("the session row's menu is gone — this gate is scanning the wrong file", open > 0)
        val menu = text.substring(open)
        val verbs = Regex("""DropdownMenuItem\(""").findAll(menu).count()
        val icons = Regex("""leadingIcon = \{""").findAll(menu).count()
        assertTrue("expected Rename, Wrap up, Archive and Kill; found $verbs", verbs >= 4)
        assertEquals(
            "a menu item with no leading icon starts in the icon column, misaligned with the rest",
            verbs,
            icons,
        )
    }

    // ------------------------------------------------------------- P-23

    @Test
    fun `the app dialog offers Copy fix from its own button row`() {
        val text = read("ui/AppsScreen.kt")
        assertTrue(
            "the fix lines can still only be copied from below the fold",
            text.contains("Text(\"Copy fix\")"),
        )
        assertTrue(
            "the button must carry the same text the refusal panel copies",
            text.contains("AppRules.fixTextOf(form.name, form.unit, form.addresses, form.fix)"),
        )
        assertTrue(
            "a dialog with no refusal must not grow a Copy fix button",
            text.contains("if (fixText.isNotBlank())"),
        )
    }
}
