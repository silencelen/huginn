package com.silencelen.huginn

import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        suspend fun save(name: String, g: String?, n: String?): SessionMeta {
            val nth = calls.size
            calls += Triple(name, g, n)
            delays.getOrNull(nth)?.let { delay(it) }
            fail?.let { throw IllegalStateException(it) }
            g?.let { goals = it }
            n?.let { notes = it }
            landed += g
            return SessionMeta(goals = goals, notes = notes, updatedAt = 1)
        }
    }

    @Test
    fun `typing writes once, after the pause`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta())
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
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta(goals = "g", notes = "n"))
        saver.setNotes("n2")
        advanceUntilIdle()
        assertEquals(null, rec.calls.single().second, "goals were not touched, so goals are not sent")
        assertEquals("n2", rec.calls.single().third)
    }

    @Test
    fun `both fields typed inside one pause travel together`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta())
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
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta())
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
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta())
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
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta())
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
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta(goals = "old", notes = "old notes"))
        saver.setGoals("being typed right now")
        saver.refresh("dev", SessionMeta(goals = "old", notes = "notes from the other device"))
        assertEquals("being typed right now", saver.goals.value)
        assertEquals("notes from the other device", saver.notes.value, "the field nobody is in does update")
        advanceUntilIdle()
    }

    @Test
    fun `a poll for another session is ignored outright`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta(goals = "dev goals"))
        saver.refresh("build", SessionMeta(goals = "build goals"))
        assertEquals("dev goals", saver.goals.value)
    }

    @Test
    fun `forget drops the write in the air, close does not`() = runTest(StandardTestDispatcher()) {
        // Recreating notes for a session that has ended, out of a timer, is the one
        // outcome worse than losing the last sentence.
        val rec = Recorder()
        val gone = SessionMetaSaver(this, rec::save)
        gone.open("dev", SessionMeta())
        gone.setNotes("about to vanish")
        gone.forget()
        advanceUntilIdle()
        assertTrue(rec.calls.isEmpty(), "nothing was written for a session that is gone")
        assertEquals(null, gone.session.value, "and nothing is held, so nothing re-opens it")

        val left = SessionMetaSaver(this, rec::save)
        left.open("dev", SessionMeta())
        left.setNotes("worth keeping")
        left.close()
        advanceUntilIdle()
        assertEquals("worth keeping", rec.calls.single().third)
    }

    @Test
    fun `nothing typed is nothing written`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta(goals = "g"))
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
        val saver = SessionMetaSaver(this, rec::save)
        saver.open("dev", SessionMeta())
        saver.setGoals("one")
        saver.flush()
        saver.setGoals("two")
        saver.flush()
        advanceUntilIdle()
        assertEquals(listOf("one", "two"), rec.calls.map { it.second }, "issued in order")
        assertEquals(listOf<String?>("one", "two"), rec.landed, "and answered in order, which only a queue guarantees")
    }
}
