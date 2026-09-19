package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.ArchivedSession
import com.silencelen.huginn.desktop.ui.common.ARCHIVE_MATCH_SLACK_MS
import com.silencelen.huginn.desktop.ui.common.archiveFor
import com.silencelen.huginn.desktop.ui.common.sessionEndedCopy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WHEN THE CONVERSATION YOU ARE READING ENDS.
 *
 * ⚠⚠ IT USED TO JUST GO. The auto-end fired while the transcript was open and
 * the detail pane fell back to the first-run empty state — "No session open /
 * Every tmux session on the host is on the left…" plus the keyboard hints — over
 * something somebody was reading a second earlier. Nothing said the session had
 * ended. And a wrapped-up session is NOT archived (only `Archive…` writes a
 * row), so afterwards the conversation was reachable from nowhere in the UI: not
 * under Archived, and not as a session, because there was no longer one.
 *
 * ⚠ WHAT THE CARD MAY OFFER IS DECIDED BY WHAT THE HOST KEPT, and that is three
 * different answers — not one sentence with the buttons hidden. A resume command
 * that opens a BLANK conversation in the right directory looks exactly like a
 * success, which is the trap `ArchiveRules.startsFresh` exists for.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionEndedCardTest {

    private fun archive(
        id: String = "cs-1",
        tmux: String = "sql",
        at: Long = ENDED,
        present: Boolean = true,
        resume: String? = "cd '/root/netplan' && claude --resume cs-1",
    ) = ArchivedSession(
        id = id,
        tmuxName = tmux,
        title = "Status page flap",
        resumeCommand = resume,
        archivedAt = at,
        transcriptPresent = present,
    )

    @Test
    fun `an archived session offers the transcript and the host's own resume command`() {
        val copy = sessionEndedCopy("sql", "Status page flap", archive())
        assertEquals("“Status page flap” ended", copy.headline)
        assertEquals("cd '/root/netplan' && claude --resume cs-1", copy.resume)
        assertEquals("cs-1", copy.archiveId, "the transcript is readable, so it is offered")
        assertTrue(copy.sentence.contains("still here to read"), copy.sentence)
    }

    @Test
    fun `a swept transcript says so instead of promising a conversation`() {
        val copy = sessionEndedCopy("sql", "Status page flap", archive(present = false))
        assertNull(copy.archiveId, "there is nothing to open, so no button may offer to open it")
        assertTrue(
            copy.sentence.contains("empty conversation"),
            "a resume past cleanupPeriodDays reports success and comes back blank: ${copy.sentence}",
        )
        assertEquals("cd '/root/netplan' && claude --resume cs-1", copy.resume, "the command is still true")
    }

    @Test
    fun `a wrapped-up session invents neither a transcript nor a command`() {
        val copy = sessionEndedCopy("sql", "Status page flap", archived = null)
        assertNull(copy.resume, "the host built no resume command, and this client must not build one")
        assertNull(copy.archiveId)
        assertTrue(copy.sentence.contains("was not archived"), copy.sentence)
        assertTrue(copy.sentence.contains("Archive"), "it should name the verb that would have kept it")
    }

    @Test
    fun `the headline falls back to the tmux name when there is no title`() {
        assertEquals("“sql” ended", sessionEndedCopy("sql", null, null).headline)
        assertEquals("“sql” ended", sessionEndedCopy("sql", "   ", null).headline)
    }

    /**
     * ⚠⚠ A TMUX NAME IS REUSED WITHIN HOURS — the archive list is keyed on the
     * Claude session uuid for exactly that reason. Matching on the name alone
     * would hand the reader last week's `sql` and its resume command.
     */
    @Test
    fun `an old archive of the same name is not this session's archive`() {
        val stale = archive(id = "cs-old", at = ENDED - 7 * 24 * 60 * 60 * 1000)
        assertNull(archiveFor(listOf(stale), "sql", ENDED))
        assertEquals("cs-1", archiveFor(listOf(stale, archive()), "sql", ENDED)?.id)
        assertNull(archiveFor(listOf(archive(tmux = "other")), "sql", ENDED), "a different session")
    }

    /**
     * A graceful archive returns while the session is still winding down, so the
     * row can be stamped slightly BEFORE the client notices the session is gone.
     */
    @Test
    fun `a row stamped just before the client noticed still counts`() {
        val early = archive(at = ENDED - ARCHIVE_MATCH_SLACK_MS + 1)
        assertEquals("cs-1", archiveFor(listOf(early), "sql", ENDED)?.id)
        val tooEarly = archive(at = ENDED - ARCHIVE_MATCH_SLACK_MS - 1)
        assertNull(archiveFor(listOf(tooEarly), "sql", ENDED))
    }

    /**
     * And the pane really draws it. The whole defect was a branch that did not
     * exist: `SessionView` closed itself and the shell fell through to the empty
     * state, which is correct code for the wrong question.
     */
    @Test
    fun `the shell draws the card instead of the empty state, and the view raises it`() {
        val shell = File("src/main/kotlin/com/silencelen/huginn/desktop/ui/Shell.kt").readText()
        val sessions = shell.substringAfter("View.SESSIONS -> {").substringBefore("View.SCRATCHPADS")
        assertTrue(sessions.length in 1..6_000, "the SESSIONS branch was not found")
        assertTrue("SessionEndedCard(" in sessions, "the ended card is not drawn:\n$sessions")
        // The CALL, not the words: the branch's own comment quotes the empty
        // state it replaced, and a grep for the sentence finds that first.
        assertTrue(
            sessions.indexOf("SessionEndedCard(") < sessions.indexOf("NothingOpen(\"No session open\""),
            "the ended card must be chosen BEFORE the generic empty state",
        )

        val view = File("src/main/kotlin/com/silencelen/huginn/desktop/ui/SessionView.kt").readText()
        val onGone = view.substringAfter("LaunchedEffect(gone) {").substringBefore("\n    }")
        assertTrue(onGone.length in 1..1_500, "the gone effect was not found")
        assertTrue(
            "noteSessionEnded(" in onGone,
            "closing the pane without saying why is the bug:\n$onGone",
        )
    }

    private companion object {
        const val ENDED = 1_800_000_000_000
    }
}
