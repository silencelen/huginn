package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.ui.common.Frame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The round-3 desktop findings whose fix is a CALL SITE rather than a function.
 *
 * WHY SOURCE GREPS. These modules have no compose-ui-test, and every one of
 * these failures is a measured width, a live switch or a truncated string —
 * nothing throws, and the only place any of them was ever visible is a
 * screenshot of the running app. A gate that reads the source the way a reviewer
 * would is the difference between "fixed" and "fixed until somebody tidies the
 * argument away"; `CapBeforeFillTest` is the same shape and the same reason.
 */
class DeskLowFindingsTest {

    private fun module(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?.let { File(it, "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop") }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(vararg path: String): String {
        val f = File(module(), path.joinToString("/"))
        assertTrue(f.isFile, "the file this gate reads has moved: $f")
        return f.readText()
    }

    // ------------------------------------------------------------------ D-21

    /**
     * ⚠ D-21. AN ON, LIVE SWITCH OVER ITS OWN "THERE IS NO TRAY HERE".
     *
     * The summary already followed the machine — `Main.kt` quits whatever this is
     * set to, and the row says so — while the switch beside it stayed fully
     * interactive. A control whose only possible effect is to persist a
     * preference nothing will read is indistinguishable from a broken one.
     */
    @Test
    fun `close to tray is dead on a machine with no tray`() {
        val src = source("ui", "settings", "SettingsPages.kt")
        val row = src.substringAfter("id = \"appearance.close-to-tray\"").substringBefore("SettingsNavRow(")
        assertTrue(
            row.contains("enabled = isTraySupported"),
            "the switch must follow the machine, like its summary already does",
        )
        assertTrue(
            row.contains("There is no system tray on this computer"),
            "and the reason has to stay on the row that is greyed out",
        )
    }

    // ------------------------------------------------------------------ D-24

    /**
     * ⚠ D-24. THE USER'S WORDS WERE CAPPED AND THE ANSWER WAS NOT.
     *
     * At a 2196px window an assistant paragraph measured ~1840px beside a 770px
     * user bubble on the same row — two halves of one conversation set to two
     * different measures, and the longer one the half with the paragraphs in it.
     */
    @Test
    fun `both transcripts hold their rows to the reading measure`() {
        for (view in listOf("ChatView.kt", "SessionView.kt")) {
            val src = source("ui", view)
            assertTrue(
                src.contains("TranscriptColumn"),
                "$view draws transcript rows and must cap the column they are in",
            )
        }
    }

    /**
     * ⚠ AND THE THIRD CONVERSATION IN THE APP. `ArchivedTranscriptView` owns its
     * own `LazyColumn` in `:ui`, so the shell cannot reach the rows — the cap
     * goes on the whole view, at the one place the shell hosts it. Forgetting
     * this leaves exactly the bug D-24 describes, on the one screen nobody
     * re-checks.
     */
    @Test
    fun `the archived conversation is capped where the shell hosts it`() {
        val call = source("ui", "Shell.kt")
            .substringAfter("ArchivedTranscriptView(")
            .substringBefore("\n                                }")
        assertTrue(call.contains("widthIn(max = Frame.transcript)"), call)
        assertTrue(
            call.indexOf("widthIn") < call.indexOf("fillMaxWidth"),
            "cap before fill, or the cap is swallowed",
        )
    }

    /**
     * The cap has to be able to hold the widest thing in the column. A
     * conversation measure narrower than the user bubble's own ceiling would cut
     * the bubble instead of the prose, which is the same bug facing the other
     * way.
     */
    @Test
    fun `the conversation measure is at least the user bubble's ceiling`() {
        assertEquals(Frame.reading, Frame.transcript, "a conversation is a pane that is read")
        val main = source("Main.kt")
        val bubble = Regex("""userBubbleMaxWidth = (\d+)\.dp""").find(main)
            ?.groupValues?.get(1)?.toInt()
            ?: error("Main.kt no longer sets userBubbleMaxWidth")
        assertTrue(
            Frame.transcript.value >= bubble,
            "the column (${Frame.transcript}) must hold the bubble (${bubble}dp)",
        )
    }

    // ------------------------------------------------------------------ D-30

    /**
     * ⚠ D-30. "CLI sync" ENDED IN "huginn-l…" AND THAT WAS THE WHOLE ROW.
     *
     * The value names the files this app replaced on the reader's own machine.
     * At the default two lines it was cut mid-name with no tooltip, no expand and
     * nothing to copy.
     */
    @Test
    fun `the CLI sync report can be read and copied`() {
        val row = source("ui", "settings", "SettingsPages.kt")
            .substringAfter("id = \"updates.cli-sync\"")
            .substringBefore("// -")
        assertTrue(row.contains("maxLines = 4"), "a report that cannot be read is not a report")
        assertTrue(row.contains("onCopy ="), "and these are paths, which belong in a paste buffer")
    }

    // ------------------------------------------------------------------ D-22

    /**
     * ⚠ D-22. "USE" HAD A SECOND EFFECT NOBODY ANNOUNCED.
     *
     * Pressing it on a route row also wrote `autoSwitch = false`. The pairing is
     * correct — a hand-picked route that the resolver then moves off is the bug
     * the pin exists to prevent — so what was missing was the sentence.
     */
    @Test
    fun `pinning a route says what it turned off and where to turn it back on`() {
        val said = AppStore.autoSwitchOffNote("Tailscale")
        assertTrue(said.contains("Tailscale"), "it names the route that was chosen: $said")
        assertTrue(said.contains("Switch automatically"), "and the setting it changed: $said")
        assertTrue(
            said.contains("below"),
            "and points at the switch already on screen rather than growing a second one: $said",
        )
    }

    @Test
    fun `a nameless route still gets a sentence`() {
        assertTrue(AppStore.autoSwitchOffNote("").contains("Switch automatically"))
        assertTrue(AppStore.autoSwitchOffNote("").isNotBlank())
    }

    /**
     * Said only when it CHANGED something: a book that was already pinned gets no
     * note, because nothing happened to report.
     */
    @Test
    fun `the note is only raised when auto-switch was actually on`() {
        val src = source("AppStore.kt")
        val fn = src.substringAfter("fun activateRoute(id: String)").substringBefore("fun useRouteCandidate")
        assertTrue(fn.contains("val wasAuto = _routeBook.value.autoSwitch"), fn)
        assertTrue(fn.contains("if (wasAuto)"), "an already-pinned book has nothing to announce")
    }
}
