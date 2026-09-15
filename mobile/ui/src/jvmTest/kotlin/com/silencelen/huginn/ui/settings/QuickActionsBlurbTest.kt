package com.silencelen.huginn.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ONE BLURB, AND IT IS PLATFORM-NEUTRAL.
 *
 * `:ui` draws the quick-actions editor for both shells. The phone's settings page
 * introduced it with a sentence of its own and the editor printed another one
 * two lines below — and the editor's said "when you right-click selected text",
 * which is the desktop's gesture described to a phone that long-presses. Two
 * blurbs, one of them wrong for the reader looking at it.
 *
 * The phone's copy is gone and the survivor is [QUICK_ACTIONS_BLURB], which names
 * no gesture at all.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class QuickActionsBlurbTest {

    @Test
    fun `the shared blurb names no platform gesture`() {
        val lower = QUICK_ACTIONS_BLURB.lowercase()
        val named = PLATFORM_GESTURE_WORDS.filter { it in lower }
        assertEquals(emptyList(), named, "a shared blurb cannot name one shell's gesture")
        assertTrue("select text" in lower, "it still has to say when the actions appear")
    }

    @Test
    fun `it says what the actions do and that nothing is sent`() {
        // The two facts a reader needs before editing a template: where the text
        // lands, and that editing one cannot fire anything at a session.
        assertTrue("composer" in QUICK_ACTIONS_BLURB)
        assertTrue("{selection}" in QUICK_ACTIONS_BLURB)
        assertTrue("Nothing is ever sent" in QUICK_ACTIONS_BLURB)
    }

    @Test
    fun `no shell prints a second copy of it`() {
        // A source grep, because the duplicate was two files apart and neither
        // knew about the other. Matched on the distinctive opening clause rather
        // than on the whole sentence, since a paraphrase is the failure mode.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val sources = listOf("core", "ui", "app", "app-desktop")
            .map { File(root, "$it/src") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
        assertTrue(sources.size > 100, "only ${sources.size} Kotlin files found — the root is wrong")

        val clause = "What Explain, Execute and Ask in a new chat put in the composer"
        // Two legitimate occurrences: the constant's own declaration, and the
        // SettingsCatalog row, which is the SEARCH index — a hit for "quote" has
        // to say what it found and is never drawn on the page beside the editor.
        val allowed = setOf("QuickActionsEditor.kt", "QuickActionsBlurbTest.kt", "SettingsCatalog.kt")
        val offenders = sources.filter { f -> f.name !in allowed && clause in f.readText() }
        assertEquals(emptyList(), offenders.map { it.name }, "a second copy of the blurb")
    }
}
