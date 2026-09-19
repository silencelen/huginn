package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHICH FACE A SESSION OPENS ON.
 *
 * The tab is remembered across sessions, which is right for one that already
 * exists — you come back to the face you were using. It is wrong for a session
 * this app has just created: somebody who last looked at Overview created
 * `rv_phone_2` from the FAB and landed on a stats page for a session that has
 * done nothing, with no composer and nothing to overview, and had to go find the
 * Conversation tab before they could type a word.
 *
 * The rule is a function so it can be asserted; the second test is the source
 * grep that holds the one call site that matters, because "the rule exists but
 * nothing calls it" is the shape this fix could silently regress into.
 *
 * NOTE this module is on org.junit, whose three-argument order is (message,
 * expected, actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class SessionOpenTabTest {

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    @Test
    fun `a brand-new session opens on Conversation whatever was last used`() {
        assertEquals(SESSION_TAB_CONVERSATION, sessionTabOnOpen(2, brandNew = true))
        assertEquals(SESSION_TAB_CONVERSATION, sessionTabOnOpen(1, brandNew = true))
        assertEquals(SESSION_TAB_CONVERSATION, sessionTabOnOpen(0, brandNew = true))
    }

    @Test
    fun `an existing session opens on the face that was last used`() {
        assertEquals(2, sessionTabOnOpen(2, brandNew = false))
        assertEquals(1, sessionTabOnOpen(1, brandNew = false))
        assertEquals(0, sessionTabOnOpen(0, brandNew = false))
    }

    @Test
    fun `Conversation is the tab with the composer`() {
        // SessionScreen draws the composer under `tab == 0`; a constant that
        // drifted from that would land new sessions on Screen instead.
        assertEquals(0, SESSION_TAB_CONVERSATION)
    }

    @Test
    fun `the session FAB actually applies the rule`() {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt")
        assertTrue("MainActivity.kt not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("MainActivity read as ${text.length} chars — wrong file", text.length > 10_000)
        assertTrue(
            "the create callback no longer picks the opening tab",
            text.contains("sessionTabOnOpen(sessionTab, brandNew = true)"),
        )
    }
}
