package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Scratchpad
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE HOUSE MENU (pr/03-row-system.md): one menu, the phone's ⋮ menus and the
 * desktop's ⋮ button all drawing it. The rows are data and asserted directly;
 * the drawing has no harness here, so the two rules that ARE the drawing — the
 * tier-3 surface and the tone ink — are gated on the source.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class HouseMenuTest {

    private fun pad(id: String) = Scratchpad(id = id, name = "page $id")

    @Test
    fun `the page picker marks the current page and offers the way back to none`() {
        var picked: String? = "unset"
        val rows = scratchpadPickerItems(listOf(pad("a"), pad("b")), "b") { picked = it }
        assertEquals(listOf("page a", "page b", "No page"), rows.map { it.label })
        assertEquals(listOf(false, true, false), rows.map { it.selected })
        assertTrue(rows.last().dividerBefore, "the none row is set apart from the list")
        rows.last().onClick()
        assertEquals(null, picked)
    }

    @Test
    fun `an empty page picker says so and cannot be pressed`() {
        val rows = scratchpadPickerItems(emptyList(), null) {}
        assertEquals(listOf("No pages yet"), rows.map { it.label })
        assertFalse(rows.single().enabled)
    }

    @Test
    fun `the attach chooser keeps every row, its icon and its action`() {
        var ran = ""
        val rows = attachChooserItems(
            listOf(AttachRow("a", "Photo", null) { ran = "a" }, AttachRow("b", "File", null) { ran = "b" }),
        )
        assertEquals(listOf("Photo", "File"), rows.map { it.label })
        rows[1].onClick()
        assertEquals("b", ran)
    }

    @Test
    fun `the menu is the tier-3 surface and inks every row by its tone`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val f = File(root, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/system/HouseMenu.kt")
        assertTrue(f.isFile, "HouseMenu.kt not found at ${f.absolutePath}")
        val text = f.readText()
        assertTrue(text.length > 2_000, "read as ${text.length} chars — wrong file")
        assertTrue(text.contains("containerColor = scheme.surfaceContainerHigh"), "not the tier-3 surface")
        assertTrue(text.contains("RoundedCornerShape(Scale.radiusCard)"), "not the card radius")
        assertTrue(text.contains("verbInk(item.tone, scheme)"), "a row's ink no longer follows its tone")
        assertTrue(text.contains("Modifier.size(Scale.iconLarge)"), "the leading icon left the scale")
    }
}
