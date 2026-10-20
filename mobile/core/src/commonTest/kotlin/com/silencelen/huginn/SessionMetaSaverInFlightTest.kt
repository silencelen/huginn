package com.silencelen.huginn

import com.silencelen.huginn.data.SessionMeta
import com.silencelen.huginn.data.SessionMetaSaver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coming back to a session while its goals or notes are still being written.
 *
 * Both shells re-open the saver with an EMPTY placeholder whenever it holds
 * another session, and let the server's copy arrive underneath through
 * refresh(). Sending a write empties the hold, so for as long as that write is in
 * the air the words exist nowhere but inside the request. A return in that window
 * used to show the placeholder over them, and to stamp the field as changed —
 * after which a failure of the write no longer counted as the newest text and
 * was never put back. Notes typed, the tab left, a slow network: gone, with the
 * line saying "Not saved" about text that no flush would ever send.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionMetaSaverInFlightTest {

    private class Recorder {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        var fail: String? = null
        var goals = ""
        var notes = ""

        /** Holds a write open, so a test can switch sessions while it is out. */
        private var gate: CompletableDeferred<Unit>? = null
        fun hold() { gate = CompletableDeferred() }
        fun release() { gate?.complete(Unit); gate = null }

        suspend fun save(name: String, g: String?, n: String?): SessionMeta {
            calls += Triple(name, g, n)
            gate?.await()
            fail?.let { throw IllegalStateException(it) }
            g?.let { goals = it }
            n?.let { notes = it }
            return SessionMeta(goals = goals, notes = notes, updatedAt = 1)
        }
    }

    /** The lane is the scope's dispatcher, as in SessionMetaSaverTest. */
    private fun TestScope.saver(rec: Recorder) =
        SessionMetaSaver(this, rec::save, lane = EmptyCoroutineContext)

    /**
     * What both shells do on arriving at a session's Overview: open it with an
     * empty placeholder unless the saver already holds it.
     */
    private fun SessionMetaSaver.arrive(name: String) {
        if (session.value != name) open(name, SessionMeta())
    }

    /** Notes typed on "dev", the tab left, and the write held in the air. */
    private fun TestScope.notesInTheAir(rec: Recorder, saver: SessionMetaSaver) {
        saver.arrive("dev")
        advanceUntilIdle()
        saver.setNotes("worth keeping")
        rec.hold()
        saver.flush()
        advanceUntilIdle()
        assertEquals(1, rec.calls.size, "the write is out and has not answered")
    }

    @Test
    fun `coming back while the notes are being written shows them, not the placeholder`() =
        runTest(StandardTestDispatcher()) {
            val rec = Recorder()
            val saver = saver(rec)
            notesInTheAir(rec, saver)

            saver.arrive("build")
            advanceUntilIdle()
            saver.arrive("dev")
            advanceUntilIdle()
            assertEquals("worth keeping", saver.notes.value, "the editor came back blank over notes still being written")
            assertEquals(SessionMetaSaver.State.SAVING, saver.state.value)

            // The header the shell fetches next still delivers the field nobody is
            // writing — the open stamp is for the placeholder, and it still works.
            val at = saver.generation()
            saver.refresh("dev", SessionMeta(goals = "from the server", notes = ""), at)
            advanceUntilIdle()
            assertEquals("from the server", saver.goals.value)
            assertEquals("worth keeping", saver.notes.value, "a header read before the write landed typed over it")

            rec.release()
            advanceUntilIdle()
            assertEquals("worth keeping", saver.notes.value)
            assertEquals(SessionMetaSaver.State.SAVED, saver.state.value)
            assertEquals("worth keeping", rec.notes)
        }

    @Test
    fun `a write that fails after you came back is held and retried`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        notesInTheAir(rec, saver)
        rec.fail = "no route to host"

        saver.arrive("build")
        advanceUntilIdle()
        saver.arrive("dev")
        advanceUntilIdle()
        rec.release()
        advanceUntilIdle()
        assertEquals(SessionMetaSaver.State.FAILED, saver.state.value)
        assertEquals("worth keeping", saver.notes.value)

        rec.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals(2, rec.calls.count { it.first == "dev" }, "the failed notes were never put back for a retry")
        assertEquals("worth keeping", rec.calls.last().third)
        assertEquals("worth keeping", rec.notes)
        assertEquals(SessionMetaSaver.State.SAVED, saver.state.value)
    }

    /**
     * The same hole, reached without any slow network: the return itself sends
     * what the session was holding, so a retry that fails again is a write the
     * open has just put in the air.
     */
    @Test
    fun `a retry sent by the return itself that fails again is still held`() = runTest(StandardTestDispatcher()) {
        val rec = Recorder()
        val saver = saver(rec)
        saver.arrive("dev")
        advanceUntilIdle()
        rec.fail = "no route to host"
        saver.setNotes("held for dev")
        saver.flush()
        advanceUntilIdle()
        saver.arrive("build")
        advanceUntilIdle()

        saver.arrive("dev")
        advanceUntilIdle()
        assertEquals("held for dev", saver.notes.value)
        assertEquals(SessionMetaSaver.State.FAILED, saver.state.value)

        rec.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals("held for dev", rec.notes, "the notes were dropped once the retry sent by the return failed")
        assertEquals(SessionMetaSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `typing after coming back still outranks the write in the air`() = runTest(StandardTestDispatcher()) {
        // The failure path's other half must survive the fix: a failed snapshot is
        // re-held only when nothing newer has been typed since it went out.
        val rec = Recorder()
        val saver = saver(rec)
        notesInTheAir(rec, saver)
        rec.fail = "no route to host"

        saver.arrive("build")
        advanceUntilIdle()
        saver.arrive("dev")
        advanceUntilIdle()
        saver.setNotes("worth keeping, and more")
        rec.release()
        advanceUntilIdle()
        assertEquals("worth keeping, and more", saver.notes.value)

        rec.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals("worth keeping, and more", rec.calls.last().third, "the retry wrote the older snapshot")
        assertEquals("worth keeping, and more", rec.notes)
    }
}
