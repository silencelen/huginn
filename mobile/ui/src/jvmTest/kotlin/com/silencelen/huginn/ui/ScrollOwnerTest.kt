package com.silencelen.huginn.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EVERY DESTINATION THAT HOSTS A SHARED LIST SCROLLS EXACTLY ONCE.
 *
 * ⚠⚠ THE BUG THIS EXISTS FOR. `AppsView` is a plain `Column` and the phone
 * hosted it in a `Box(fillMaxSize())`, so the page could not scroll AT ALL: the
 * rows past the fold — and, on a failing one, its whole inline fix with its only
 * control, **Copy fix** — sat off the bottom of the owner's Fold with no
 * gesture that would reach them. A control that is unreachable is a feature that
 * is not there. The same shape on `ProjectsListView` cuts off every
 * project past the fold.
 *
 * ⚠ AND EXACTLY ONCE IS THE OTHER HALF. The desktop wraps both views in
 * `ReadingPane`, which is itself a `verticalScroll` — so the shared view may not
 * scroll unconditionally. It takes `scroll`, each shell answers it once, and a
 * nested scroll (which on Compose swallows the gesture rather than throwing) is
 * what this gate is watching for.
 *
 * WHY A SOURCE GREP: there is no compose-ui-test in any of these modules and the
 * failure is a gesture that goes nowhere — see [DisclosureHeightOnlyTest], which
 * guards the same files for the same reason. The source text IS the bug.
 */
class ScrollOwnerTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    /** The shared views that take a [scroll] answer, and the two shells that give one. */
    private val shared = listOf(
        "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/AppsView.kt",
        "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/ProjectsListView.kt",
    )
    private val phone = listOf(
        "app/src/main/kotlin/com/silencelen/huginn/ui/AppsScreen.kt",
        "app/src/main/kotlin/com/silencelen/huginn/ui/ProjectsScreen.kt",
    )
    private val desktop = listOf(
        "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/AppsPane.kt",
        "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/ProjectsPane.kt",
    )

    private fun read(rel: String): String {
        val f = File(root(), rel)
        assertTrue(f.isFile, "not scanned: ${f.absolutePath}")
        val text = f.readText()
        // A glob that matches nothing exits 0, and this project has been bitten
        // by exactly that. The floor is asserted before the finding is.
        assertTrue(text.length > 2_000, "$rel read as ${text.length} chars — wrong file")
        return text
    }

    @Test
    fun `the shared list views take a scroll answer and give it one owner`() {
        for (rel in shared) {
            val text = read(rel)
            assertTrue("scroll: Boolean" in text, "$rel does not take a scroll answer")
            assertEquals(
                1,
                Regex("""verticalScroll\(""").findAll(text).count(),
                "$rel must have exactly one verticalScroll — the one the shell asked for",
            )
            assertTrue(
                Regex("""if\s*\(scroll\)""").containsMatchIn(text),
                "$rel scrolls unconditionally, which double-scrolls inside the desktop's ReadingPane",
            )
        }
    }

    @Test
    fun `the phone asks the shared view to scroll, and adds none of its own`() {
        for (rel in phone) {
            val text = read(rel)
            assertTrue("scroll = true" in text, "$rel hosts a list that cannot scroll")
            assertTrue(
                "verticalScroll(" !in text,
                "$rel wraps the shared view in a second scroll",
            )
        }
    }

    @Test
    fun `the desktop keeps its own reading scroll and asks for no second one`() {
        for (rel in desktop) {
            val text = read(rel)
            assertTrue(
                "scroll = true" !in text,
                "$rel asks the shared view to scroll inside a pane that already does",
            )
        }
    }
}
