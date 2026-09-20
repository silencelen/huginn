package com.silencelen.huginn.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ⚠⚠ THE LAST THING BETWEEN A MIS-TARGETED TAP AND AN ENDED SESSION (P-02).
 *
 * The session list sorts by `activityAt` and re-answers every poll, so a row menu
 * opened on one row can fire its verb at another: the walker tapped **Kill
 * session** on row 2 and was asked **"Wrap up rv-desktop-1?"**. `OrderLock`
 * stops the re-target; this file guards the fallback — that when a dialog DOES
 * name the wrong thing, the name is the first thing the eye lands on rather than
 * a word inside a sentence at button size.
 *
 * WHY A SOURCE GREP: there is no compose-ui-test in these modules, and the
 * failure is a size relationship on screen. See [ScrollOwnerTest] and
 * [DisclosureHeightOnlyTest], which guard the same files the same way. A title
 * that quietly goes back to `Text("Kill $name?")` is the regression, and it is
 * spelled exactly that way in the source.
 */
class ConfirmTitleTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun read(rel: String): String {
        val f = File(root(), rel)
        assertTrue(f.isFile, "not scanned: ${f.absolutePath}")
        val text = f.readText()
        // A glob that matches nothing exits 0, and this project has been bitten
        // by exactly that. The floor is asserted before the finding is.
        assertTrue(text.length > 2_000, "$rel read as ${text.length} chars — wrong file")
        return text
    }

    private val shared = "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/ConfirmTitle.kt"
    private val phoneSessions = "app/src/main/kotlin/com/silencelen/huginn/ui/SessionsScreen.kt"
    private val desktopShell = "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Shell.kt"
    private val desktopLists = "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Lists.kt"

    /**
     * The target outranks every other style in the dialog. `headlineSmall` is
     * larger than the `bodyMedium`/`rowMeta` of the explanation and the
     * `labelLarge` of both buttons; the verb above it is deliberately smaller
     * than the name below it.
     */
    @Test
    fun `the target is the largest text the component draws`() {
        val text = read(shared)
        assertTrue("headlineSmall" in text, "the name must be drawn at the dialog's largest size")
        assertTrue("labelLarge" in text, "and the verb above it at a label size")
        val verbAt = text.indexOf("style = MaterialTheme.typography.labelLarge")
        val nameAt = text.indexOf("style = MaterialTheme.typography.headlineSmall")
        assertTrue(verbAt in 0 until nameAt, "the small verb comes first, the big name second")
        assertTrue("FontFamily.Monospace" in text, "a session name is monospace everywhere else in the product")
        assertTrue("maxLines = 2" in text, "two lines, because the TAIL of a name is what distinguishes it")
    }

    /**
     * ⚠ EVERY DIALOG THAT ENDS SOMETHING USES IT. The one that keeps the old
     * single-line title is the one that ends a session nobody chose.
     */
    @Test
    fun `both shells name their target through ConfirmTitle`() {
        val phone = read(phoneSessions)
        assertTrue("ConfirmTitle(EndVerbs.HARD, name)" in phone, "Kill")
        assertTrue("""ConfirmTitle("Archive", name)""" in phone, "Archive")
        assertTrue("ConfirmTitle(EndVerbs.SOFT, name)" in phone, "Wrap up")

        val desktop = read(desktopShell)
        assertTrue("ConfirmTitle(words.lead, one)" in desktop, "the desktop's one confirm dialog draws it")
        assertTrue("ConfirmWords(" in desktop, "and the words carry the target apart from the lead")
    }

    /** The old shape, spelled out, so a revert is caught rather than reviewed. */
    @Test
    fun `no destructive dialog goes back to a one-line title`() {
        val phone = read(phoneSessions)
        for (bad in listOf("""Text("Kill ${'$'}name?")""", """Text("Archive ${'$'}name?")""")) {
            assertTrue(bad !in phone, "the single-line title is back: $bad")
        }
    }

    /**
     * ⚠ THE OTHER HALF OF P-02. The list must still re-sort once nothing is open
     * — a frozen list is a worse bug, because nothing about it looks wrong — and a
     * row that teleports when it does is a row nobody can follow.
     */
    @Test
    fun `both session lists hold their order under a menu and animate the moves`() {
        for (rel in listOf(phoneSessions, desktopLists)) {
            val text = read(rel)
            assertTrue("OrderLock.order(" in text, "$rel must hold the order while something is open")
            assertTrue("OrderLock.keysOf(" in text, "$rel must record what it drew")
            assertTrue("Modifier.animateItem()" in text, "$rel must animate a row that moves")
        }
    }

    /**
     * ⚠ REMOVE ASKS FIRST (P-13 / D-18, decision 60). "Remove" on an app and on
     * a route deleted instantly — no dialog, no undo — while Kill session and
     * Archive both confirm by name. Two verbs in one product answering the same
     * question differently is the finding.
     */
    @Test
    fun `apps and routes confirm their removal, naming the item`() {
        val shared = read("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/RemoveConfirm.kt")
        assertTrue("ConfirmTitle(verb, name)" in shared, "the name is the headline, like Kill and Archive")

        for (rel in listOf(
            "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/settings/SettingsRows.kt",
            "app/src/main/kotlin/com/silencelen/huginn/ui/AppsScreen.kt",
            "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/AppsPane.kt",
        )) {
            val text = read(rel)
            assertTrue("RemoveConfirmDialog(" in text, "$rel removes without asking")
            assertTrue(
                "confirmRemove = true" in text,
                "$rel wires the button straight to the delete rather than to the dialog",
            )
        }
    }
}
