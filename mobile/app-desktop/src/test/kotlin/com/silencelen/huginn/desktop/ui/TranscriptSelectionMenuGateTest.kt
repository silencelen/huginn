package com.silencelen.huginn.desktop.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * THE SELECTION MENU HAS TO BE INSTALLED WHERE THE SELECTION CAN SEE IT.
 *
 * ⚠⚠ THE RULE IS POSITIONAL AND INVISIBLE AT RUNTIME. `SelectionContainer` reads
 * the selection's menu/toolbar local IN ITS OWN COMPOSITION SCOPE — the phone
 * proved it in the foundation 1.7.6 bytecode (`manager.textToolbar =
 * LocalTextToolbar.current`, evaluated before the content lambda is invoked) —
 * so a provider placed AMONG the container's children reaches nobody. Nothing
 * throws, nothing logs; the app simply draws the platform's own menu and the
 * five verbs the product added are silently absent, or worse, both appear. The
 * phone shipped exactly that for four releases (`TranscriptToolbarGateTest`).
 *
 * This client has always had it the right way round — `WithTranscriptSelectionMenu`
 * opens BEFORE `SelectionContainer {` in both transcript views — and there is
 * nothing in the code that says so or that would stop a tidy-up from moving one
 * line inside the other. That is what this is.
 *
 * ⚠ AND NOT ANY HIGHER EITHER. The provider is `LocalTextContextMenu`, which
 * every `TextField` in the app reads, so hoisting it above the transcript would
 * put "Explain" in the right-click menu of the composer the text is being staged
 * INTO. The gate below checks the order, and the count keeps it to one site per
 * view.
 */
class TranscriptSelectionMenuGateTest {

    /** Gradle runs a test with the MODULE dir as its working dir; `mobile/` is up. */
    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    /** The two views that draw a transcript a pointer can drag a selection across. */
    private val transcriptViews = listOf("SessionView.kt", "ChatView.kt")

    private fun source(name: String): String {
        val f = File(mobileRoot(), "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/$name")
        assertTrue(f.isFile, "$name not found at ${f.absolutePath}")
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue(text.length > 5_000, "$name read as ${text.length} chars — wrong file")
        return text
    }

    @Test
    fun `both transcript views install the selection menu`() {
        for (name in transcriptViews) {
            assertTrue(
                source(name).contains("WithTranscriptSelectionMenu("),
                "$name draws a selectable transcript with the toolkit's bare menu",
            )
        }
    }

    @Test
    fun `it is provided around the selection container, never inside it`() {
        for (name in transcriptViews) {
            val text = source(name)
            val container = text.indexOf("SelectionContainer {")
            assertTrue(container > 0, "$name has no SelectionContainer — this gate is scanning the wrong file")
            val provider = text.indexOf("WithTranscriptSelectionMenu(")
            assertTrue(
                provider in 1 until container,
                "$name provides the selection menu INSIDE the SelectionContainer, " +
                    "which reads the local in its own scope and would never see it",
            )
        }
    }

    @Test
    fun `one site per view, so no text field inherits the transcript's verbs`() {
        for (name in transcriptViews) {
            val calls = Regex("""WithTranscriptSelectionMenu\s*\(""").findAll(source(name)).count()
            assertTrue(calls == 1, "$name installs it $calls times; the transcript is the only place for it")
        }
    }
}
