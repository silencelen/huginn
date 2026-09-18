package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ArchivedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two lines a person reads about an archived session: what it was, and
 * whether it can actually come back.
 *
 * Both shells draw them from here, which is new for a session row — the LIVE row
 * is implemented three times, with three relTimes. The second of these lines is
 * the one that matters: Claude Code deletes its own transcripts after
 * `cleanupPeriodDays`, and past that a revive opens a blank conversation in the
 * right directory and reports success. A row that looks exactly like every other
 * one right up to the moment it disappoints is the worst outcome this feature has.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ArchivedSessionsViewTest {

    private val nowMs = 1_800_000_000_000L
    private val nowSec = nowMs / 1000

    private fun row(
        title: String? = "Archive session feature",
        tmuxName: String? = "jtyper",
        cwd: String? = "/root/netplan",
        agoSec: Long = 7_200,
        live: Boolean = false,
        transcriptPresent: Boolean = true,
        transcriptTruncated: Boolean = false,
    ) = ArchivedSession(
        id = "0123abcd-0000-4000-8000-00000000abcd",
        title = title, tmuxName = tmuxName, cwd = cwd,
        archivedAt = nowSec - agoSec,
        live = live, transcriptPresent = transcriptPresent, transcriptTruncated = transcriptTruncated,
    )

    // ------------------------------------------------------------- subtitle

    @Test
    fun `the line says when, what it was called, and where it ran`() {
        assertEquals(
            "archived 2h ago · jtyper · /root/netplan",
            archivedSubtitle(row(), nowMs),
        )
    }

    @Test
    fun `the tmux name is dropped when it is already the title`() {
        // Two identical strings on two lines is the row wasting half its height —
        // the rule the desktop's live session row already follows.
        assertEquals(
            "archived 2h ago · /root/netplan",
            archivedSubtitle(row(title = "jtyper"), nowMs),
        )
    }

    @Test
    fun `a missing field leaves no dangling separator`() {
        // The device line's rule: an absent fact is absent, not an empty slot
        // between two dots.
        assertEquals("archived 2h ago", archivedSubtitle(row(tmuxName = null, cwd = null), nowMs))
        // A row with no stamp at all — an older daemon, or a record written before
        // archivedAt existed. It must not render as "archived  · jtyper".
        val unstamped = row().copy(archivedAt = 0)
        assertEquals("jtyper · /root/netplan", archivedSubtitle(unstamped, nowMs))
    }

    // -------------------------------------------------------------- warning

    @Test
    fun `a row with nothing left to resume says so, in words about the consequence`() {
        // ⚠ THE ONE THING A ROW MUST NOT BE QUIET ABOUT, and it has to be said
        // where the Revive button is — after the press it is indistinguishable
        // from a session that simply had nothing in it.
        val w = archivedWarning(row(transcriptPresent = false))
        assertTrue(w != null && w.contains("start fresh"), "said: $w")
    }

    @Test
    fun `a row kept only in part says which part`() {
        val w = archivedWarning(row(transcriptTruncated = true))
        assertTrue(w != null && w.contains("end of this conversation"), "said: $w")
    }

    @Test
    fun `a healthy row is quiet, and so is one that is already back`() {
        // Silence is the default: a warning on every row is a warning nobody reads.
        assertNull(archivedWarning(row()))
        // A live row's button already says "Open <name>" — saying it twice is the
        // row arguing with itself about what it is.
        assertNull(archivedWarning(row(live = true, transcriptPresent = false)))
    }

    // ---------------------------------------------------------- empty state

    @Test
    fun `an empty section explains itself rather than showing a blank`() {
        // "Archived (0)" that opens onto nothing reads as a broken feature rather
        // than an unused one, and this text is the only thing that distinguishes
        // the two.
        assertTrue(ARCHIVE_EMPTY.contains("claude --resume"), ARCHIVE_EMPTY)
        assertTrue(ARCHIVE_EMPTY.contains("ends it for good"), ARCHIVE_EMPTY)
    }
}
