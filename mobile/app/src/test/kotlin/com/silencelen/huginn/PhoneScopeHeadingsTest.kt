package com.silencelen.huginn

import com.silencelen.huginn.settings.SettingScope
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.Surface
import com.silencelen.huginn.settings.heading
import com.silencelen.huginn.settings.matches
import com.silencelen.huginn.settings.scopeOn
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's settings pages say where each setting is kept, and say it right.
 *
 * The owner (2026-10-23): settings have to be thought of as "cloud settings",
 * which apply to the server and every other huginn client, and the local
 * settings of the device itself. The catalog in `:core` gives every row its
 * [SettingScope]; each page groups its rows under `ScopeOf(…)` captions in the
 * catalog's words ("On huginn · every app", "This phone").
 *
 * This module cannot render Compose on the JVM, so where the desktop's
 * `SettingsPagesTest` reads a rendered page, this reads the page SOURCE in the
 * same order: each page from its `fun …Page(`, every caption, and every row by
 * the catalog id it is drawn with (`id = "…"`, or `isHighlighted("…"` for a row
 * whose id is its default). A row must sit under a caption naming its own
 * scope, and every row the catalog gives the phone must be drawn somewhere.
 *
 * NOTE JUnit's argument order: (message, expected, actual).
 */
class PhoneScopeHeadingsTest {

    private val pages = listOf("PhonePages.kt", "HostPage.kt", "UpdatesPage.kt")

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(name: String): String =
        File(root(), "app/src/main/kotlin/com/silencelen/huginn/ui/settings/$name").readText()

    private val token = Regex("""fun (\w+Page)\(|ScopeOf\("([^"]+)"\)|id = "([^"]+)"|isHighlighted\("([^"]+)"""")

    /** Where the catalog says [id] is kept on the phone, or null when it is not a phone row. */
    private fun phoneScope(id: String): SettingScope? =
        SettingsCatalog.item(id)?.takeIf { it.surface.matches(Surface.PHONE) }?.scopeOn(Surface.PHONE)

    /**
     * Rows a SHARED composable draws inside a phone page, with no id of their
     * own in this module's source: the usage form's fields (`HeadroomSettingsSection`)
     * and the saved logins with their add flow (`AccountsEditor`). Each is
     * captioned by the row the page does draw beside it.
     */
    private val drawnBySharedForm = setOf(
        "usage.heads-up-pct", "usage.ladder-pct", "usage.ladder-up-pct", "usage.stop-pct",
        "usage.stop-fable-pct", "usage.clear-below-pct", "usage.ladder", "usage.auto-resume",
        "usage.resume-phrase", "usage.default-model", "usage.heads-up-text", "usage.account-switch",
        "usage.switch-at", "usage.switch-margin", "usage.keep-awake", "usage.keep-awake-model",
        "usage.keep-awake-quiet", "host.saved-logins", "host.sign-in",
    )

    private class Walk(val rows: Set<String>, val problems: List<String>)

    private fun walk(): Walk {
        val rows = LinkedHashSet<String>()
        val problems = ArrayList<String>()
        for (file in pages) {
            var page = "?"
            var caption: SettingScope? = null
            for (m in token.findAll(source(file))) {
                val (fn, cap, id1, id2) = m.destructured
                when {
                    fn.isNotEmpty() -> { page = fn; caption = null }
                    cap.isNotEmpty() -> {
                        caption = phoneScope(cap)
                        if (caption == null) problems += "$file $page: ScopeOf(\"$cap\") names no phone row"
                    }
                    else -> {
                        val id = id1.ifEmpty { id2 }
                        val want = phoneScope(id) ?: continue
                        rows += id
                        if (caption != want) {
                            problems += "$file $page: $id sits under ${caption?.heading(Surface.PHONE) ?: "no caption"}, " +
                                "the catalog keeps it ${want.heading(Surface.PHONE)}"
                        }
                    }
                }
            }
        }
        return Walk(rows, problems)
    }

    @Test
    fun `every row on a phone settings page sits under a caption naming its own scope`() {
        val w = walk()
        assertTrue(w.problems.joinToString("\n"), w.problems.isEmpty())
        assertTrue("found only ${w.rows.size} rows: the pages moved", w.rows.size >= 20)
    }

    /** The other direction: a phone row the catalog has and no page draws is a hit onto nothing. */
    @Test
    fun `every phone row in the catalog is drawn on a phone page`() {
        val phoneRows = SettingsCatalog.items.filter { it.surface.matches(Surface.PHONE) }.map { it.id }.toSet()
        val missing = phoneRows - walk().rows - drawnBySharedForm
        assertEquals("phone rows no page draws", emptySet<String>(), missing)
    }

    /** Each shared form is drawn under the caption of the row its page draws beside it. */
    @Test
    fun `the shared forms sit under the right caption`() {
        val usage = source("PhonePages.kt").substringAfter("fun UsagePage(")
        assertTrue("UsagePage", usage.indexOf("ScopeOf(\"usage.plan\")") in 0 until usage.indexOf("HeadroomSettingsSection("))
        val host = source("HostPage.kt").substringAfter("fun HostPage(")
        assertTrue("HostPage", host.indexOf("ScopeOf(\"host.saved-logins\")") in 0 until host.indexOf("AccountsEditor("))
        for (id in drawnBySharedForm) {
            assertEquals(id, SettingScope.SERVER, phoneScope(id))
        }
    }

    /** One caption vocabulary: the old hand-written one is gone, not kept beside the new. */
    @Test
    fun `the hand-written captions are gone`() {
        val left = File(root(), "app/src/main/kotlin").walk()
            .filter { it.isFile && it.extension == "kt" && "SettingsGroup(" in it.readText() }
            .map { it.name }
            .toList()
        assertEquals("SettingsGroup still drawn", emptyList<String>(), left)
    }
}
