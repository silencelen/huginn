package com.silencelen.huginn

import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The autosave behind the overview's two editors. Every case here is a way work
 * could be lost silently, which is the only kind of bug an editor with no Save
 * button can have.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionMetaSaverTest {

    private class Recorder {
        val calls = mutableListOf<Triple<String, String?, String?>>()

        /** Which writes came BACK, in the order they did. Not the same list. */
        val landed = mutableListOf<String?>()
        var fail: String? = null

        /**
         * How long each successive write takes. Descending on purpose: it is the
         * only way to tell a serialized queue from two requests in the air, since
         * a launch queue delivers them in order either way.
         */
        var delays: List<Long> = emptyList()
        var goals = ""
        var notes = ""

        /** Holds a write open, so a test can type or switch tabs during one. */
        private var gate: CompletableDeferred<Unit>? = null
        fun hold() { gate = CompletableDeferred() }
        fun release() { gate?.complete(Unit); gate = null }

        suspend fun save(name: String, g: String?, n: String?): SessionMeta {
            val nth = calls.size
            calls += Triple(name, g, n)
            gate?.await()
            delays.getOrNull(nth)?.let { delay(it) }
            fail?.let { throw IllegalStateException(it) }
            g?.let { goals = it }
            n?.let { notes = it }
            landed += g
            return SessionMeta(goals = goals, notes = notes, updatedAt = 1)
        }
    }

    /**
     * The lane is the SCOPE's dispatcher here, so the virtual clock drives the
     * bookkeeping too. In the app it is a single-parallelism dispatcher of its
     * own; what these tests assert is the ORDERING that confinement guarantees.
     *
     * ⚠ Every entry point below lands on that lane, so a test must let it run —
     * `open` then `setGoals` in the same breath needs an `advanceUntilIdle`
     * between them, exactly as the app's own fetch-then-open does.
     */
    private fun TestScope.saver(rec: Recorder) =
        SessionMetaSaver(this, rec::save, lane = EmptyCoroutineContext)

    @Test
    fun `typing writes once, after the pause`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()
        saver.setGoals("l")
        saver.setGoals("la")
        saver.setGoals("land the walker")
        advanceTimeBy(300)
        assertEquals(0, rec.calls.size, "still typing")
        advanceUntilIdle()
        assertEquals(1, rec.calls.size, "one write per pause, not one per character")
        assertEquals("land the walker", rec.calls.single().second)
        assertEquals(SessionMetaSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `only the field that was typed is sent`() = runTest(StandardTestDispatcher()) {
        // Two editors on one screen. Sending both would write whatever THIS client
        // last read into the field somebody is not editing — on the other device,
        // over the paragraph they just saved.
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta(goals = "g", notes = "n"))
        advanceUntilIdle()
        saver.setNotes("n2")
        advanceUntilIdle()
        assertEquals(null, rec.calls.single().second, "goals were not touched, so goals are not sent")
        assertEquals("n2", rec.calls.single().third)
    }

    @Test
    fun `both fields typed inside one pause travel together`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()
        saver.setGoals("g")
        saver.setNotes("n")
        advanceUntilIdle()
        assertEquals(1, rec.calls.size)
        assertEquals("g", rec.calls.single().second)
        assertEquals("n", rec.calls.single().third)
    }

    @Test
    fun `leaving the view lands the sentence that was still in the air`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()
        saver.setNotes("half a thought")
        saver.flush()
        advanceUntilIdle()
        assertEquals("half a thought", rec.calls.single().third)
    }

    @Test
    fun `switching sessions lands the old one under the OLD name`() = runTest(StandardTestDispatcher()) {
        // Leaving the tab is how you switch sessions, so this is the ordinary case
        // and not the exceptional one. A write that landed under the new name would
        // put one session's notes on another's page.
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()
        saver.setGoals("for dev")
        saver.open("build", SessionMeta(goals = "for build"))
        advanceUntilIdle()
        assertEquals("dev", rec.calls.single().first)
        assertEquals("for dev", rec.calls.single().second)
        assertEquals("for build", saver.goals.value, "the new session's own text is on screen")
        // Published so a caller can tell "open this" from "you already have it":
        // re-opening a session already held resets the editors to the last meta
        // the poll returned, which after a save from this client is the text as
        // it read before it was typed.
        assertEquals("build", saver.session.value)
    }

    @Test
    fun `a failed write is held, and the next flush retries it`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()
        rec.fail = "no route to host"
        saver.setNotes("keep this")
        advanceUntilIdle()
        assertEquals(SessionMetaSaver.State.FAILED, saver.state.value)
        assertEquals("no route to host", saver.note.value)
        assertEquals("keep this", saver.notes.value, "the text is still on screen")

        rec.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals(2, rec.calls.size, "the retry went out on its own")
        assertEquals("keep this", rec.calls.last().third)
        assertEquals(SessionMetaSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `a poll never types back over a field being edited`() = runTest(StandardTestDispatcher()) {
        // The overview is polled every few seconds while somebody may be
        // mid-sentence. Adopting the server's copy of a dirty field would delete
        // what they are writing a keystroke at a time.
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta(goals = "old", notes = "old notes"))
        advanceUntilIdle()
        saver.setGoals("being typed right now")
        saver.refresh("dev", SessionMeta(goals = "old", notes = "notes from the other device"))
        assertEquals("being typed right now", saver.goals.value)
        advanceUntilIdle()
        assertEquals("notes from the other device", saver.notes.value, "the field nobody is in does update")
    }

    @Test
    fun `a poll for another session is ignored outright`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta(goals = "dev goals"))
        advanceUntilIdle()
        saver.refresh("build", SessionMeta(goals = "build goals"))
        advanceUntilIdle()
        assertEquals("dev goals", saver.goals.value)
    }

    @Test
    fun `a poll read before the save landed is ignored`() = runTest(StandardTestDispatcher()) {
        // ⚠ THE ONE NO FIELD-LEVEL GUARD CAN SEE. The poll is issued while the
        // field is quiet, the daemon reads the OLD text, and the answer arrives
        // after the save has landed — by which point nothing is dirty, the state
        // is SAVED rather than SAVING, and every guard says "safe to adopt". The
        // sentence somebody typed is then replaced by the one it replaced, and the
        // next keystroke saves that back over it.
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta(goals = "old"))
        advanceUntilIdle()

        // The poll goes out HERE: the caller captures the generation first.
        val at = saver.generation()
        val asTheDaemonReadIt = SessionMeta(goals = "old")

        saver.setGoals("land the walker")
        advanceUntilIdle()
        assertEquals(SessionMetaSaver.State.SAVED, saver.state.value, "the save landed and nothing is dirty")

        saver.refresh("dev", asTheDaemonReadIt, at)
        advanceUntilIdle()
        assertEquals("land the walker", saver.goals.value, "a poll older than the save typed over it")
    }

    @Test
    fun `a poll issued after the save still lands`() = runTest(StandardTestDispatcher()) {
        // The generation must not become a permanent refusal: the whole point of
        // the poll is that the OTHER device's edits arrive.
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta(goals = "old"))
        advanceUntilIdle()
        saver.setGoals("mine")
        advanceUntilIdle()

        val at = saver.generation()
        saver.refresh("dev", SessionMeta(goals = "from the desktop"), at)
        advanceUntilIdle()
        assertEquals("from the desktop", saver.goals.value)
    }

    @Test
    fun `a failed write for a session you have left is still owed`() = runTest(StandardTestDispatcher()) {
        // The failure arrives after the tab has been left, which is exactly when
        // nobody is looking at the notes about to be dropped.
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()

        rec.hold()
        rec.fail = "no route to host"
        saver.setNotes("worth keeping")
        saver.flush()
        advanceUntilIdle()
        assertEquals(1, rec.calls.count { it.first == "dev" })

        saver.open("build", SessionMeta())
        advanceUntilIdle()
        rec.release()
        advanceUntilIdle()

        rec.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals(2, rec.calls.count { it.first == "dev" }, "the failure was dropped with the tab")
        assertEquals("worth keeping", rec.calls.last { it.first == "dev" }.third)
    }

    @Test
    fun `a failed write never replaces text typed after it went out`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()

        rec.hold()
        rec.fail = "no route to host"
        saver.setNotes("first")
        saver.flush()
        advanceUntilIdle()
        saver.setNotes("first and second")
        rec.release()
        advanceUntilIdle()

        rec.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals("first and second", rec.calls.last().third, "the retry wrote the older snapshot")
    }

    @Test
    fun `switching sessions does not leave the new one saying Saving`() = runTest(StandardTestDispatcher()) {
        // The write for the OLD session is issued BY the switch, so its "Saving…"
        // used to be published while the new tab was already on screen — and the
        // answer, which is about a session nobody is looking at, declined to clear
        // it.
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()

        rec.hold()
        saver.setGoals("still typing")
        saver.open("build", SessionMeta())
        advanceUntilIdle()
        assertEquals("build", saver.session.value)
        assertEquals(SessionMetaSaver.State.IDLE, saver.state.value, "dev's write is not build's business")

        rec.release()
        advanceUntilIdle()
        assertEquals(SessionMetaSaver.State.IDLE, saver.state.value)
    }

    @Test
    fun `re-opening a session that is still holding text shows the held copy`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()
        rec.fail = "no route to host"
        saver.setNotes("held for dev")
        saver.flush()
        advanceUntilIdle()
        saver.open("build", SessionMeta())
        advanceUntilIdle()

        // The poll's copy of dev is whatever the daemon last stored, which is
        // nothing — the write never landed.
        saver.open("dev", SessionMeta())
        advanceUntilIdle()
        assertEquals("held for dev", saver.notes.value, "the unsent text is newer than the server's copy")
        assertEquals(SessionMetaSaver.State.FAILED, saver.state.value)
    }

    @Test
    fun `forget drops the write in the air, close does not`() = runTest(StandardTestDispatcher()) {
        // Recreating notes for a session that has ended, out of a timer, is the one
        // outcome worse than losing the last sentence.
        val rec = Recorder()
        val gone = saver(rec)
        gone.open("dev", SessionMeta())
        advanceUntilIdle()
        gone.setNotes("about to vanish")
        gone.forget()
        advanceUntilIdle()
        assertTrue(rec.calls.isEmpty(), "nothing was written for a session that is gone")
        assertEquals(null, gone.session.value, "and nothing is held, so nothing re-opens it")

        val left = saver(rec)
        left.open("dev", SessionMeta())
        advanceUntilIdle()
        left.setNotes("worth keeping")
        left.close()
        advanceUntilIdle()
        assertEquals("worth keeping", rec.calls.single().third)
    }

    @Test
    fun `nothing typed is nothing written`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.open("dev", SessionMeta(goals = "g"))
        advanceUntilIdle()
        saver.setGoals("g")
        saver.flush()
        advanceUntilIdle()
        assertTrue(rec.calls.isEmpty(), "re-setting the same text is not an edit")
        assertEquals(SessionMetaSaver.State.IDLE, saver.state.value, "and IDLE says nothing at all")
    }

    @Test
    fun `writes are single file, so two never race the same session`() = runTest(StandardTestDispatcher()) {
        // Two PATCHes in the air against one session is how the LAST one typed
        // loses: whichever the daemon happens to finish second is what stays on
        // disk. The slow-then-fast delays are what makes that visible — with the
        // writes queued the answers come back in order however long each takes.
        val rec = Recorder()
        rec.delays = listOf(500, 1)
        val saver = saver(rec)
        saver.open("dev", SessionMeta())
        advanceUntilIdle()
        saver.setGoals("one")
        saver.flush()
        saver.setGoals("two")
        saver.flush()
        advanceUntilIdle()
        assertEquals(listOf("one", "two"), rec.calls.map { it.second }, "issued in order")
        assertEquals(listOf<String?>("one", "two"), rec.landed, "and answered in order, which only a queue guarantees")
    }
}
