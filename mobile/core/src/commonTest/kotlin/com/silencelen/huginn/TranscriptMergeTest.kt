package com.silencelen.huginn

import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.MAX_TRANSCRIPT_EVENTS
import com.silencelen.huginn.ui.mergeTranscript
import com.silencelen.huginn.ui.isTranscriptRestart
import com.silencelen.huginn.ui.mergeTranscriptPage
import com.silencelen.huginn.ui.mergeTranscriptTail
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
    fun aTailPollDoesNotUndoLoadEarlier() {
        // "Load earlier" prepends older events, legitimately growing the window
        // past the cap. The tail poll that fires ≤2.5s later usually has NOTHING
        // new — and used to `takeLast(cap)` the extended window straight back down,
        // discarding exactly the history the reader just loaded. A tail merge must
        // never shrink a reader-extended window.
        val extended = TranscriptPage(
            events = (0..4).map { ev(it, "e$it") },   // 5 events, past the cap of 3
            claudeSessionId = "s",
        )
        val emptyTail = TranscriptPage(events = emptyList(), claudeSessionId = "s", nextOffset = 900)
        val merged = mergeTranscriptPage(extended, emptyTail, cap = 3)
        assertEquals(
            listOf("e0", "e1", "e2", "e3", "e4"), merged.events.map { it.text },
            "the loaded-earlier prefix must survive an empty tail poll",
        )

        // ⚠ AND A TAIL **WITH** NEW EVENTS MUST NOT TRIM EITHER. The old guard
        // (`effectiveCap = max(cap, current.size)`) let the window slide: five
        // loaded events plus one new one is six, `takeLast(5)` drops "e0", and
        // `historyStart` still points before it — a hole of N records with no gap
        // marker, silently, on every poll that carries anything. `size >= before`
        // held the whole time, which is why this test passed over the defect.
        val withNew = TranscriptPage(events = listOf(ev(0, "new")), claudeSessionId = "s")
        val merged2 = mergeTranscriptPage(extended, withNew, cap = 3)
        assertEquals("e0", merged2.events.first().text, "the front of a reader-extended window stays put")
        assertEquals(6, merged2.events.size)
        assertEquals("new", merged2.events.last().text)
    }

    /**
     * The window cannot grow for ever, so there IS a ceiling — four times the
     * cap — and when it bites the caller is TOLD how many events went, because
     * only the caller can move its own `historyStart` past them.
     */
    @Test
    fun aReaderExtendedWindowStillHasACeiling() {
        val big = TranscriptPage(events = (0 until 12).map { ev(it, "e$it") }, claudeSessionId = "s")
        val tail = TranscriptPage(events = (0 until 5).map { ev(it, "n$it") }, claudeSessionId = "s")
        val merged = mergeTranscriptTail(big, tail, cap = 3)
        assertEquals(12, merged.page.events.size, "4x the cap is the ceiling")
        assertEquals(5, merged.droppedEarlier, "and the five that went are counted")
        assertEquals("e5", merged.page.events.first().text)

        val under = mergeTranscriptTail(big, TranscriptPage(claudeSessionId = "s"), cap = 3)
        assertEquals(0, under.droppedEarlier, "nothing dropped, nothing to report")
    }

    /**
     * ⚠ `windowStart` IS THE HANDLE FOR READING FURTHER BACK, and the daemon's
     * empty tail result reports it as the CURRENT offset. Taking it fresh reverted
     * a reader who had paged all the way to byte 0 back to a nonzero start: the
     * "Load earlier" affordance reappeared and was dead, because `historyStart`
     * was 0 and the load bailed.
     */
    @Test
    fun aTailPollDoesNotRewindTheHistoryHandle() {
        val paged = TranscriptPage(events = listOf(ev(0, "old")), claudeSessionId = "s", windowStart = 0)
        val tail = TranscriptPage(events = emptyList(), claudeSessionId = "s", windowStart = 5_000)
        assertEquals(0L, mergeTranscriptPage(paged, tail).windowStart)

        val partway = TranscriptPage(events = listOf(ev(0, "old")), claudeSessionId = "s", windowStart = 100)
        assertEquals(100L, mergeTranscriptPage(partway, tail).windowStart)
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
