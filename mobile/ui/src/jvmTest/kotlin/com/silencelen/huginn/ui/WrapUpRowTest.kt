package com.silencelen.huginn.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ⚠⚠ THE DAEMON'S WRAP-UP PHRASE WAS DRAWN AS THE READER'S OWN WORDS (P-34/D-28).
 *
 * `/v1/sessions/:name/soft-end` TYPES the phrase into the pane, so the record it
 * leaves is an ordinary typed prompt with no `origin` and no `isMeta` — nothing
 * structural to key on — and both shells rendered it as a right-aligned user
 * bubble. On an archived transcript that bubble is the only account of how the
 * session ended.
 *
 * The classification itself is `:core` ([com.silencelen.huginn.ui.TranscriptVoice],
 * with its own tests). What this file gates is the two halves that make it work
 * at all and are invisible in a unit test of the rule: the row asks, and both
 * shells hand down the HOST's phrase rather than a literal of their own.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class WrapUpRowTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(path: String): String {
        val f = File(root(), path)
        assertTrue(f.isFile, "$path not found at ${f.absolutePath}")
        val text = f.readText()
        assertTrue(text.length > 2_000, "$path read as ${text.length} chars — wrong file")
        return text
    }

    @Test
    fun `the user branch asks whether this is the daemon talking`() {
        val body = source("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/TranscriptView.kt")
            .substringAfter("fun TranscriptEventItem(")
            .substringBefore("fun TranscriptRowItem(")
        assertTrue(body.length > 1_000, "TranscriptEventItem read as ${body.length} chars — wrong slice")
        val ask = body.indexOf("TranscriptVoice.isWrapUp(")
        val bubble = body.indexOf("UserBubble(")
        assertTrue(ask > 0, "the user branch draws every user record as a bubble again")
        assertTrue(ask < bubble, "the bubble is chosen before the question is asked")
        assertTrue("SystemNote(TranscriptVoice.wrapUpNote(" in body, "a wrap-up has to become a system row")
    }

    /**
     * ⚠ THE PHRASE IS A DEPLOYMENT SETTING (`HUGINN_APPD_SOFT_END_PHRASE`), which
     * is exactly why `/v1/status` publishes it — "so clients can show the exact
     * wording instead of carrying a copy that drifts from the host". A literal in
     * either shell would be that copy, and it would stop matching the day anybody
     * changed the setting.
     */
    @Test
    fun `both shells hand down the host's phrase and neither carries its own`() {
        val shells = mapOf(
            "the phone" to "app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt",
            "the desktop" to "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/Main.kt",
        )
        for ((who, path) in shells) {
            val text = source(path)
            assertTrue("LocalWrapUpPhrase provides" in text, "$who does not hand the phrase down")
            assertTrue("softEndPhrase" in text, "$who reads the phrase from somewhere other than the daemon")
        }
        for (path in listOf(
            "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/TranscriptView.kt",
            "core/src/commonMain/kotlin/com/silencelen/huginn/ui/TranscriptVoice.kt",
        ) + shells.values) {
            assertTrue(
                "Finish outstanding items" !in source(path),
                "$path carries its own copy of the phrase — it will drift from the host's",
            )
        }
    }

    /**
     * A daemon too old to publish a phrase, or a shell that has not wired it,
     * must behave exactly as it did before: a user bubble. Guessing at a
     * wrap-up with no host wording is how a person's real message becomes a
     * system note.
     */
    @Test
    fun `the composition local defaults to knowing nothing`() {
        val text = source("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/TranscriptView.kt")
        assertTrue(
            "staticCompositionLocalOf<String?> { null }" in text,
            "LocalWrapUpPhrase must default to null, not to a built-in phrase",
        )
    }
}
