package com.silencelen.huginn

import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.MAX_TRANSCRIPT_EVENTS
import com.silencelen.huginn.ui.mergeTranscript
import com.silencelen.huginn.ui.isTranscriptRestart
import com.silencelen.huginn.ui.mergeTranscriptPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The merge is where a transcript view's ROW IDENTITY comes from, and identity
 * failures are silent: a duplicated `seq` opens the wrong tool card and nothing
 * logs it. These pin the rules the two shipped clients already agree on.
 */
class TranscriptMergeTest {

    private fun ev(seq: Int, text: String = "x") =
        TranscriptEvent(seq = seq, kind = "assistant", text = text)

    @Test
    fun firstPageIsTakenAsTheServerNumberedIt() {
        val page = listOf(ev(0), ev(1), ev(2))
        assertEquals(listOf(0, 1, 2), mergeTranscript(emptyList(), page, 10).map { it.seq })
    }

    @Test
    fun incomingPageIsRenumberedPastTheLastKeptSeq() {
        // The daemon numbers EVERY tail read from 0, so this is the real wire shape.
        val kept = mergeTranscript(emptyList(), listOf(ev(0), ev(1)), 10)
        val merged = mergeTranscript(kept, listOf(ev(0, "a"), ev(1, "b")), 10)
        assertEquals(listOf(0, 1, 2, 3), merged.map { it.seq })
        assertEquals(listOf("x", "x", "a", "b"), merged.map { it.text })
    }

    @Test
    fun seqsStayUniqueAndClimbingAcrossTrims() {
        // Renumbering is relative to the last KEPT seq, not to the array length —
        // that is what keeps identity unique once the window starts dropping heads.
        var window = mergeTranscript(emptyList(), listOf(ev(0), ev(1), ev(2)), 4)
        repeat(4) {
            window = mergeTranscript(window, listOf(ev(0), ev(1)), 4)
            assertEquals(4, window.size)
            assertEquals(window.map { it.seq }.distinct(), window.map { it.seq })
            assertEquals(window.map { it.seq }.sorted(), window.map { it.seq })
        }
    }

    @Test
    fun keptEventsAreNotMutated() {
        val kept = listOf(ev(7, "keep"))
        val merged = mergeTranscript(kept, listOf(ev(0, "new")), 10)
        assertEquals(7, kept[0].seq)
        assertEquals(listOf(7, 8), merged.map { it.seq })
    }

    @Test
    fun capTrimsFromTheHead() {
        val merged = mergeTranscript(emptyList(), (0..9).map { ev(it, "e$it") }, 3)
        assertEquals(listOf("e7", "e8", "e9"), merged.map { it.text })
    }

    @Test
    fun theWindowCapMatchesTheOtherClients() {
        assertEquals(600, MAX_TRANSCRIPT_EVENTS)
    }

    // ------------------------------------------------------------ page merge

    @Test
    fun firstPagePassesThroughUntouched() {
        val page = TranscriptPage(events = listOf(ev(0)), title = "t")
        assertSame(page, mergeTranscriptPage(null, page))
    }

    @Test
    fun nullableSessionFieldsAreCarriedForward() {
        // A tail read only reports the fields whose records fall inside it. Without
        // the carry-forward every one of these reverts to null seconds after the
        // view opens — which is exactly how the phone's effort control lost its value.
        val first = TranscriptPage(
            events = listOf(ev(0)),
            title = "Fixing the lease",
            model = "opus",
            modelDisplay = "Opus 4.8",
            effort = "high",
            gitBranch = "main",
            permissionMode = "acceptEdits",
            cwd = "/opt/huginn",
            state = "running",
            mode = "act",
            claudeSessionId = "abc",
            lastActivityTs = 42,
        )
        val tail = TranscriptPage(events = listOf(ev(0)), nextOffset = 900)
        val merged = mergeTranscriptPage(first, tail)
        assertEquals("Fixing the lease", merged.title)
        assertEquals("opus", merged.model)
        assertEquals("Opus 4.8", merged.modelDisplay)
        assertEquals("high", merged.effort)
        assertEquals("main", merged.gitBranch)
        assertEquals("acceptEdits", merged.permissionMode)
        assertEquals("/opt/huginn", merged.cwd)
        assertEquals("running", merged.state)
        assertEquals("act", merged.mode)
        assertEquals("abc", merged.claudeSessionId)
        assertEquals(42L, merged.lastActivityTs)
        assertEquals(900L, merged.nextOffset)
    }

    @Test
    fun afresherValueWins() {
        val first = TranscriptPage(title = "old", effort = "low")
        val tail = TranscriptPage(title = "new")
        val merged = mergeTranscriptPage(first, tail)
        assertEquals("new", merged.title)
        assertEquals("low", merged.effort)
    }

    @Test
    fun activityIsNeverCarriedForward() {
        // The server recomputes it every response, so null means "nothing in
        // flight". Carrying it forward freezes a finished tool row on screen.
        val first = TranscriptPage(activity = com.silencelen.huginn.data.Activity(tool = "Bash"))
        val merged = mergeTranscriptPage(first, TranscriptPage())
        assertEquals(null, merged.activity)
    }

    @Test
    fun truncatedIsStickyFromTheFirstPage() {
        // A tail read says nothing about the head that was dropped.
        val first = TranscriptPage(truncated = true)
        assertTrue(mergeTranscriptPage(first, TranscriptPage(truncated = false)).truncated)
    }

    @Test
    fun aDifferentClaudeSessionReplacesTheViewInsteadOfAppending() {
        // The reported bug: a tmux name reused by a NEW session served the dead
        // session's transcript, and the merge welded the two together.
        val dead = TranscriptPage(events = listOf(ev(0, "from the dead session")),
            claudeSessionId = "session-one", nextOffset = 900)
        val fresh = TranscriptPage(events = listOf(ev(0, "from the new one")),
            claudeSessionId = "session-two", nextOffset = 12)

        val merged = mergeTranscriptPage(dead, fresh)
        assertEquals(emptyList(), merged.events,
            "a page read at the OLD file's offset is not the new session's history")
        assertEquals("session-two", merged.claudeSessionId)
        assertEquals(12L, merged.nextOffset)
    }

    @Test
    fun sameClaudeSessionStillAppends() {
        val first = TranscriptPage(events = listOf(ev(0)), claudeSessionId = "session-one")
        val tail = TranscriptPage(events = listOf(ev(0, "more")), claudeSessionId = "session-one")
        assertEquals(2, mergeTranscriptPage(first, tail).events.size)
    }

    @Test
    fun aMissingIdentityIsNotARestart() {
        // A session that has not prompted Claude yet reports no id at all, and a
        // tail read can arrive before the first hook has written one. Treating
        // either as a change would clear the view on an ordinary poll.
        val known = TranscriptPage(events = listOf(ev(0)), claudeSessionId = "session-one")
        val anonymous = TranscriptPage(events = listOf(ev(0, "more")), claudeSessionId = null)
        assertFalse(isTranscriptRestart(known, anonymous))
        assertFalse(isTranscriptRestart(TranscriptPage(claudeSessionId = null), known))
        assertFalse(isTranscriptRestart(null, known))
        assertEquals(2, mergeTranscriptPage(known, anonymous).events.size)
    }

    @Test
    fun restartIsReportedSoCallersCanDropTheirOffset() {
        // The offset is a byte position in the OLD transcript file; carrying it
        // into the new one reads from a position that means nothing there.
        val a = TranscriptPage(claudeSessionId = "session-one")
        val b = TranscriptPage(claudeSessionId = "session-two")
        assertTrue(isTranscriptRestart(a, b))
    }
}
