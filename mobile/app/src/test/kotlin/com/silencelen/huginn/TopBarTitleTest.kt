package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT THE PHONE'S TOP BAR IS ALLOWED TO CONTAIN — asserted against the source,
 * because nothing else in this module can.
 *
 * Owner decision 29 (2026-09-15): the headroom pill comes out of the bar on every
 * screen. It had been first in the action slot everywhere, and the walk on the
 * owner's Fold measured what that cost: "Usage & headr…", "Appearance & …" and a
 * session titled "Main documentation…" all truncated so that "Fable 47% · resets
 * 5d" could sit beside them. The same reading is already on the Status page in
 * full and already under the Status icon as a 2px fill, so the bar was giving up
 * the one thing it exists to say in order to be the third place that said
 * something else.
 *
 * WHY A SOURCE GREP AND NOT A COMPOSE TEST. There is no compose-ui-test in this
 * module, and the failure mode is a measured width — a test that asserted "the
 * title composable was called" passes against exactly the broken bar. The source
 * text IS the rule: [com.silencelen.huginn.ui.HeadroomPill] called from this
 * file's `TopAppBar` is always wrong now, whatever it is passed.
 *
 * The composable itself STAYS in `:ui`; the desktop drops its own use separately
 * and it is deleted when no caller remains. This gate is about one call site.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class TopBarTitleTest {

    /** Gradle runs a test with the MODULE dir as its working dir; `mobile/` is up. */
    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun mainActivity(): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt")
        assertTrue("MainActivity.kt not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A glob that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("MainActivity.kt read as ${text.length} chars — wrong file", text.length > 20_000)
        return text
    }

    @Test
    fun `the top bar draws no headroom pill`() {
        val text = mainActivity()
        // Call sites only: the ⚠ comment that replaced it names the composable on
        // purpose, so match the CALL — the name followed by its open paren. NOT
        // anchored on a word boundary to the left, because the next person to
        // reach for it will reach for the fully-qualified spelling.
        val calls = Regex("""HeadroomPill\s*\(""").findAll(text).count()
        assertEquals("the pill is back in the bar — see owner decision 29", 0, calls)
    }

    @Test
    fun `the chats tab is titled for the tab and not for the app`() {
        // "Huginn" was the title on one of four tabs inside an app already called
        // Huginn: it named nothing and told a reader nothing about where they were.
        val text = mainActivity()
        assertTrue(
            "the Chats destination should title itself Chats",
            text.contains("""is Dest.Chats -> "Chats""""),
        )
        assertTrue(
            "the old app-name title is back",
            !text.contains("""is Dest.Chats -> "Huginn""""),
        )
    }
}
