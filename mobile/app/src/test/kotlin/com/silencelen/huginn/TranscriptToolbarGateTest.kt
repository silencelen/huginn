package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE GATE HAS TO BE WHERE THE SELECTION CAN SEE IT.
 *
 * 3.1.1 added GatedTextToolbar so one long press stops raising two toolbars, and
 * the walk on the owner's Fold running 3.5.0 photographed two toolbars anyway:
 * Android's Copy / Select all floating over the conversation with the app's five
 * verbs drawn below it. The gate was not broken — it was installed somewhere it
 * could never be consulted.
 *
 * `SelectionContainer` reads `LocalTextToolbar.current` in ITS OWN composition
 * scope (`manager.textToolbar = LocalTextToolbar.current`, before it invokes the
 * content lambda — verified in the foundation bytecode), so both screens
 * providing the gate AMONG its children provided it to nobody. The rule is
 * positional and invisible at runtime, which is exactly the kind a source gate
 * is for: the provider must OPEN before `SelectionContainer {`.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class TranscriptToolbarGateTest {

    /** Gradle runs a test with the MODULE dir as its working dir; `mobile/` is up. */
    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    /** The two screens that draw a transcript the reader can long-press. */
    private val transcriptScreens = listOf("SessionScreen.kt", "ChatScreen.kt")

    private fun source(name: String): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/$name")
        assertTrue("$name not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$name read as ${text.length} chars — wrong file", text.length > 5_000)
        return text
    }

    @Test
    fun `both transcript screens provide the gated toolbar`() {
        for (name in transcriptScreens) {
            assertTrue(
                "$name draws a selectable transcript with the platform's own toolbar",
                source(name).contains("rememberGatedTextToolbar("),
            )
        }
    }

    @Test
    fun `the gate is provided around the selection container, not inside it`() {
        for (name in transcriptScreens) {
            val text = source(name)
            val container = text.indexOf("SelectionContainer {")
            assertTrue("$name has no SelectionContainer — this gate is scanning the wrong file", container > 0)
            val provider = text.indexOf("rememberGatedTextToolbar(")
            assertTrue(
                "$name provides the gate INSIDE the SelectionContainer, where it reaches nothing",
                provider in 1 until container,
            )
        }
    }

    @Test
    fun `the composer keeps its own toolbar`() {
        // Exactly one provider per screen: gating the text FIELD as well would
        // take paste away from the one control on the screen that can use it.
        for (name in transcriptScreens) {
            val calls = Regex("""rememberGatedTextToolbar\s*\(""").findAll(source(name)).count()
            assertTrue("$name provides the gate $calls times; the transcript is the only place for it", calls == 1)
        }
    }
}
