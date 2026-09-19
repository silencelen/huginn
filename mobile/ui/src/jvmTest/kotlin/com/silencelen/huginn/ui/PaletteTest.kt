package com.silencelen.huginn.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NOTHING IN THIS PRODUCT DRAWS ITSELF IN A COLOUR THE THEME NEVER CHOSE.
 *
 * ⚠⚠ `secondaryContainer` IS NOT IN EITHER SCHEME. `Theme.kt` names primary,
 * surface, surfaceVariant, outline and error and stops; every role it leaves out
 * falls back to Material's OWN baseline, and `secondaryContainer` there is a
 * violet that appears nowhere else in a warm rune-gold palette. The sliders were
 * already caught by this — their inactive track is pinned to `surfaceVariant`
 * with a comment saying exactly why — and then `PickerButton` was written with
 * `secondaryContainer` as its ground, so the ladder rungs and the default-model
 * picker were six violet pills in the middle of the Usage page.
 *
 * The rule is not "never use that role", it is "a role the theme does not define
 * is not a colour anyone chose". If a future palette defines it, delete this.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class PaletteTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun sources(): List<File> {
        val files = listOf("core", "ui", "app", "app-desktop")
            .map { File(root(), "$it/src") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
        // A glob that matches nothing exits 0, and this project has been bitten
        // by exactly that.
        assertTrue(files.size > 100, "only ${files.size} Kotlin files found — the root is wrong")
        return files
    }

    /** The roles `Theme.kt` deliberately leaves to Material's baseline. */
    private val undefined = listOf(
        "colorScheme.secondaryContainer",
        "colorScheme.onSecondaryContainer",
        "colorScheme.tertiaryContainer",
        "colorScheme.onTertiaryContainer",
    )

    @Test
    fun `the theme still leaves these roles undefined, so this gate has a subject`() {
        val theme = File(root(), "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/theme/Theme.kt").readText()
        assertTrue(theme.contains("surfaceVariant = "), "Theme.kt read wrong — it defines no surfaceVariant")
        for (role in undefined) {
            val name = role.removePrefix("colorScheme.")
            assertTrue(
                !theme.contains("$name = "),
                "$name is defined in the theme now — this gate is stale, delete the role from it",
            )
        }
    }

    @Test
    fun `no control paints itself from a role the theme never chose`() {
        val offenders = sources()
            .filter { it.name != "PaletteTest.kt" }
            .flatMap { f -> undefined.filter { it in f.readText() }.map { "${f.name}: $it" } }
        assertEquals(emptyList(), offenders, "a colour nobody in this product picked")
    }
}
