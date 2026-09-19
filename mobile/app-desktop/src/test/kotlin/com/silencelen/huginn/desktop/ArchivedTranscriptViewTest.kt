package com.silencelen.huginn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ AN ARCHIVED CONVERSATION HAS NOTHING TO SEND TO — asserted against the
 * source tree, because the failure is not a crash and not a wrong pixel: it is a
 * control that looks exactly like the live one and addresses a session that no
 * longer exists.
 *
 * An archive's tmux session is GONE. The daemon reads the conversation from a
 * copy on disk with no session gate at all, precisely because there is no
 * session to gate on. A composer in that view would either do nothing, or — much
 * worse — type at whatever tmux has since given that name to, and this host
 * reuses session names within hours.
 *
 * THE RULE: the archive destination composes exactly ONE composable,
 * `ArchivedTranscriptView`, and neither that view nor either shell's branch for
 * it names a composer, a send, a key, a queue line or a revive.
 *
 * ⚠ REVIVE IS EXCLUDED TOO, AND NOT BECAUSE IT WOULD FAIL. It would work, which
 * is the problem: it starts a Claude on this transcript and can only be done
 * once, and a second Claude appending to one jsonl is how a conversation becomes
 * unreadable to both of them. That decision lives on the archived ROW, beside the
 * warning about a transcript Claude Code may have swept — one place for it, not
 * two.
 *
 * WHY A SOURCE SCAN, AND WHY HERE. There is no compose-ui-test in `:ui`, `:app`
 * or `:app-desktop`, and "this screen offers no way to type" is a statement
 * about what was WRITTEN rather than about what renders. This module already
 * carries the tree-wide source gate ([OverlaysDisableSelectionTest]), so a
 * phone-only breach surfaces in this suite — which is a feature of where the
 * instrument lives, not an accident.
 */
class ArchivedTranscriptViewTest {

    /**
     * Words that mean "you can act on this from here". Matched as substrings
     * against source with its comments removed, so the prose above — and the
     * long ⚠ blocks at both call sites — cannot trip it.
     */
    private val forbidden = listOf(
        "TextField",
        "Composer",
        "composerFor",
        "onSend",
        "sendKeys",
        "SendQueue",
        "LiveInput",
        "LiveKeyboard",
        "SessionScreen",
        "SessionView(",
        "reviveArchive",
        "onRevive",
        "QuickActions",
        "ScreenTab",
    )

    @Test
    fun `the shared view offers nothing to type into`() {
        val src = strip(read("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/ArchivedTranscriptView.kt"))
        assertClean("ArchivedTranscriptView.kt", src)
        assertTrue("TranscriptRowItem" in src, "it must still draw the rows")
    }

    /**
     * The phone's child destination. Sliced from its declaration to the next
     * top-level `val` in the same block, which is how every pane in that file is
     * declared.
     */
    @Test
    fun `the phone destination composes the read-only view and nothing else`() {
        val file = read("app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt")
        val start = file.indexOf("val archiveTranscriptPane")
        if (start < 0) fail("the phone's archive destination has been renamed — this gate is now scanning nothing")
        val end = file.indexOf("\n        val ", start + 1).let { if (it < 0) file.length else it }
        val body = strip(file.substring(start, end))
        assertTrue("ArchivedTranscriptView(" in body, "it must compose the shared read-only view")
        assertClean("MainActivity.kt archiveTranscriptPane", body)
    }

    /** The desktop's detail-pane branch, sliced between its `else if` and the next `else`. */
    @Test
    fun `the desktop detail pane composes the read-only view and nothing else`() {
        val file = read("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Shell.kt")
        val start = file.indexOf("} else if (archive != null) {")
        if (start < 0) fail("the desktop's archive branch has been renamed — this gate is now scanning nothing")
        val end = file.indexOf("} else {", start + 1).let { if (it < 0) file.length else it }
        val body = strip(file.substring(start, end))
        assertTrue("ArchivedTranscriptView(" in body, "it must compose the shared read-only view")
        assertClean("Shell.kt archive branch", body)
    }

    /**
     * ⚠ AND THE LIVE SESSION VIEW MUST STILL HAVE ONE. A gate that passed because
     * the words moved, or because the composer was removed from the app, would be
     * worse than no gate — it would read like coverage.
     */
    @Test
    fun `the live session view is not caught by the same rule`() {
        val live = strip(read("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SessionView.kt"))
        assertTrue(
            forbidden.any { it in live },
            "the live session view has no composer either — the scan is looking at the wrong thing",
        )
    }

    private fun assertClean(what: String, src: String) {
        val hits = forbidden.filter { it in src }
        assertTrue(
            hits.isEmpty(),
            "$what must offer no way to act on a session that no longer exists, but names: $hits",
        )
    }

    private fun read(rel: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val f = File(root, rel)
        if (!f.isFile) fail("$rel is not where this gate expects it — it is now scanning nothing")
        return f.readText()
    }

    /** Prose about a rule is not a use of it. */
    private fun strip(src: String): String = src
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")
}
