package com.silencelen.huginn.ui.settings

import com.silencelen.huginn.settings.SettingsCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a settings row says "this is the one you searched for".
 *
 * Two things are asserted and they are different kinds of assertion. The first
 * is arithmetic: exactly one row in a page is ever the arrival target, and an
 * unhighlighted row is untinted rather than faintly tinted. The second is the
 * owner's standing rule about the VERNACULAR of that mark, which can only be
 * read off the source — see [SettingsHouseRulesTest] below.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SettingsRowsTest {

    @Test
    fun onlyTheNamedRowIsHighlighted() {
        assertTrue(SettingsRowStyle.isHighlighted("host.token", "host.token"))
        assertFalse(SettingsRowStyle.isHighlighted("host.base-url", "host.token"))
    }

    /** Arriving without a search marks nothing at all. */
    @Test
    fun noArrivalMarksNothing() {
        for (item in SettingsCatalog.items) {
            assertFalse(SettingsRowStyle.isHighlighted(item.id, null), "${item.id} marked with no hit")
        }
    }

    /**
     * An unhighlighted row is fully untinted. A "barely visible" tint on every
     * row is how the one row that matters stops standing out — the same
     * reasoning that keeps the word `fresh` off every account row.
     */
    @Test
    fun theTintIsOnOrOff() {
        assertEquals(0f, SettingsRowStyle.tintAlpha(false))
        assertEquals(SettingsRowStyle.HIGHLIGHT_ALPHA, SettingsRowStyle.tintAlpha(true))
        assertTrue(
            SettingsRowStyle.HIGHLIGHT_ALPHA > 0f && SettingsRowStyle.HIGHLIGHT_ALPHA < 0.3f,
            "a mark, not a selection: ${SettingsRowStyle.HIGHLIGHT_ALPHA}",
        )
    }

    /** The rows cap at the same reading measure the page does. */
    @Test
    fun rowsShareThePagesReadingMeasure() {
        assertEquals(SETTINGS_READING_WIDTH, SettingsRowStyle.ROW_MAX_WIDTH)
    }
}

/**
 * THE OWNER'S HOUSE RULES, asserted against the source, because nothing else
 * can.
 *
 * "Never left accent bars on cards; subtle in-vernacular state marks" is a
 * standing note that has been re-derived from memory more than once, and a
 * comment is not a gate. A left rail is a recognisable shape in Compose — a Box
 * that fills the height and is a few dp wide, with a background — and it reads
 * as tasteful the moment it is written. So this reads the settings files the way
 * a reviewer would and names the file and line the way a reviewer would.
 *
 * Same technique, same reasoning and the same failure mode as `CapBeforeFillTest`
 * in `:app-desktop`, which already scans all four modules (this one included)
 * for fill-before-cap and therefore needs no extension for these files.
 */
class SettingsHouseRulesTest {

    private fun settingsSources(): List<File> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val dir = File(root, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/settings")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * A glob that matches nothing exits 0, and this project has been bitten by
     * exactly that (a gate that ran 58 of 179 tests and read like coverage), so
     * the floor is asserted before the finding is — and by NAME rather than by a
     * count, which would go stale the next time a file is added or split.
     */
    @Test
    fun theSettingsSourcesAreScannedAtAll() {
        val names = settingsSources().map { it.name }.toSet()
        assertTrue("SettingsRows.kt" in names, "the rows were not scanned: $names")
        assertTrue("SettingsScaffold.kt" in names, "the scaffold was not scanned: $names")
    }

    /** The shape of a left accent rail, in either order it gets written. */
    private val accentBar = listOf(
        Regex("""\.fillMaxHeight\(\s*\)\s*\r?\n?\s*\.width\("""),
        Regex("""\.width\(\s*\d+(\.\d+)?\.dp\s*\)\s*\r?\n?\s*\.fillMaxHeight\("""),
    )

    @Test
    fun noLeftAccentBarInAnySettingsFile() {
        val offences = settingsSources().flatMap { file ->
            val text = file.readText()
            accentBar.flatMap { rx ->
                rx.findAll(text).map { m ->
                    val line = text.take(m.range.first).count { it == '\n' } + 1
                    "${file.path}:$line  ${m.value.replace(Regex("\\s+"), " ")}"
                }
            }
        }
        assertTrue(
            offences.isEmpty(),
            "state is a dot and a tint, never a rail down the side:\n" + offences.joinToString("\n"),
        )
    }

    /**
     * ⚠ `LocalTextContextMenu` BELONGS AROUND TRANSCRIPTS ONLY. The desktop
     * deliberately splits the context menu in two — the LOOK at the shell root,
     * the CONTENT tight around transcripts — so that no Settings field grows an
     * "Explain" item pointing at text that is not a transcript.
     */
    @Test
    fun noSettingsFileWrapsATextContextMenu() {
        val offences = settingsSources()
            .filter { it.readText().contains("LocalTextContextMenu") }
            .map { it.path }
        assertTrue(offences.isEmpty(), "quick actions belong around transcripts, not settings: $offences")
    }
}
