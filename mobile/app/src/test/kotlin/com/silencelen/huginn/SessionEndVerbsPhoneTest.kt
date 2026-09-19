package com.silencelen.huginn

import com.silencelen.huginn.ui.EndVerbs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PHONE'S TWO SESSION MENUS DRAW THE SHARED VERBS, NOT THEIR OWN COPIES.
 *
 * The phone builds its menus inside composables — `DropdownMenuItem` with a
 * literal in it — so unlike the desktop's `sessionMenu` there is no pure list to
 * assert. That is exactly how the words drifted in the first place: five literals
 * in three files, and the ellipsis on one of them already disagreed with the rest
 * before anybody renamed anything.
 *
 * So the gate is on the SOURCE, the same shape as `TranscriptToolbarGateTest`:
 * these two files must reach for [EndVerbs] and for the shared tone token, and
 * must hold neither of the old words. A rename that touched the desktop and
 * forgot a phone surface — the failure this is for — fails here.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class SessionEndVerbsPhoneTest {

    /** Gradle runs a test with the MODULE dir as its working dir; `mobile/` is up. */
    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    /** The session row's overflow, and the detail screen's. */
    private val surfaces = mapOf(
        "SessionsScreen.kt" to "app/src/main/kotlin/com/silencelen/huginn/ui/SessionsScreen.kt",
        "MainActivity.kt" to "app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt",
    )

    private fun source(name: String): String {
        val f = File(mobileRoot(), surfaces.getValue(name))
        assertTrue("$name not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$name read as ${text.length} chars — wrong file", text.length > 5_000)
        return text
    }

    @Test
    fun `neither surface still says the old words`() {
        for (name in surfaces.keys) {
            val text = source(name)
            assertTrue("$name still offers \"Wind down\"", !text.contains("Wind down"))
            assertTrue("$name still offers \"End session\"", !text.contains("End session"))
        }
    }

    @Test
    fun `both surfaces take their wording from the shared verbs`() {
        for (name in surfaces.keys) {
            val text = source(name)
            assertTrue("$name writes its own soft verb instead of EndVerbs", text.contains("EndVerbs.soft(1)"))
            assertTrue("$name writes its own hard verb instead of EndVerbs", text.contains("EndVerbs.hard(1)"))
        }
    }

    @Test
    fun `both surfaces draw the pair in the two reds`() {
        // The whole visible half of the change: a wrap-up rendered as an ordinary
        // row beside a red kill is the arrangement people picked wrongly out of.
        // Both tints come from `verbInk`, which is one token in `:ui` — so the
        // phone cannot land on a lighter red of its own.
        for (name in surfaces.keys) {
            val text = source(name)
            assertTrue("$name does not tint its wrap-up", text.contains("verbInk(VerbTone.SOFT"))
            assertTrue("$name does not tint its kill", text.contains("verbInk(VerbTone.DESTRUCTIVE"))
        }
    }

    @Test
    fun `the confirm dialogs are titled with the verb that opened them`() {
        // A row saying "Wrap up" that raises a dialog headed "Wind down andrev?"
        // is the half-rename that reads as two different features.
        val sessions = source("SessionsScreen.kt")
        assertTrue("the kill dialog is not titled for its verb", sessions.contains("Text(\"Kill \$name?\")"))
        for (name in surfaces.keys) {
            assertTrue(
                "$name titles its wrap-up dialog with something other than the verb",
                source(name).contains("\${EndVerbs.SOFT} \$name?"),
            )
        }
    }

    @Test
    fun `the shared verbs are the ones this gate believes they are`() {
        // If the words change again, this line is the one that has to be looked
        // at — not three files' worth of literals.
        assertEquals("Wrap up", EndVerbs.SOFT)
        assertEquals("Kill session", EndVerbs.HARD)
    }
}
