package com.silencelen.huginn

import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.ScratchpadSave
import com.silencelen.huginn.data.ScratchpadSaver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The autosave. NOTE kotlin.test's argument order is (expected, actual, message).
 *
 * There is no Save button, so every one of these is a way work could be lost
 * silently: a write that fires after you switched pages, a rev that stops
 * advancing, a conflict that vanishes, a failure that drops the text it was
 * holding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScratchpadSaverTest {

    private class Wire {
        val calls = mutableListOf<Triple<String, Int, String>>()

        /** Bumped by a save; a test can raise it by hand to stage a conflict. */
        val revs = mutableMapOf<String, Int>()
        var fail: String? = null

        /**
         * Holds a write open, so a test can type WHILE one is in flight. That
         * window is where two of the rules below actually live, and a wire that
         * always answers instantly never opens it.
         */
        private var gate: CompletableDeferred<Unit>? = null
        fun hold() { gate = CompletableDeferred() }
        fun release() { gate?.complete(Unit); gate = null }

        suspend fun save(id: String, rev: Int, content: String): ScratchpadSave {
            calls += Triple(id, rev, content)
            gate?.await()
            fail?.let { throw IllegalStateException(it) }
            val server = revs.getOrElse(id) { rev }
            if (rev != server) {
                return ScratchpadSave(
                    Scratchpad(id = id, name = "Main", content = "from the desktop", rev = server),
                    conflict = true,
                )
            }
            val next = server + 1
            revs[id] = next
            return ScratchpadSave(Scratchpad(id = id, name = "Main", content = content, rev = next), false)
        }
    }

    private fun pad(id: String = "p1", content: String = "", rev: Int = 1) =
        Scratchpad(id = id, name = if (id == "p1") "Main" else id, content = content, rev = rev, main = id == "p1")

    @Test
    fun `typing writes once, after the pause`() = runTest {
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad())

        "hello".forEachIndexed { i, _ -> saver.set("hello".take(i + 1)) }
        assertEquals(0, wire.calls.size, "a keystroke must not reach the wire")
        assertEquals(ScratchpadSaver.State.PENDING, saver.state.value)

        // advanceTimeBy and NOT advanceUntilIdle: the latter runs the clock out to
        // the last scheduled task, which is the very delay under test.
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS - 1)
        assertEquals(0, wire.calls.size, "the write must WAIT for the pause, not merely be launched after it")

        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(1, wire.calls.size, "five keystrokes, one write")
        assertEquals("hello", wire.calls.last().third)
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `the rev advances, so the next save is not a conflict with our own write`() = runTest {
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad(rev = 1))

        saver.set("one")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(2, saver.pad.value?.rev)

        saver.set("two")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(2, wire.calls[1].second, "the second write must carry the rev the first one produced")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `leaving the view lands what is still in the air`() = runTest {
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad())

        saver.set("half a thought")
        saver.flush()
        advanceUntilIdle()
        assertEquals("half a thought", wire.calls.last().third)
    }

    @Test
    fun `switching pages writes the old one under the OLD id`() = runTest {
        // The one case where reading current state at write time is wrong: the
        // timer fires after the editor has moved on, and the text belongs to the
        // page it was typed into, not to the page now on screen.
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad(id = "p1"))
        saver.set("belongs to p1")
        saver.open(pad(id = "p2"))
        advanceUntilIdle()

        assertEquals(1, wire.calls.size)
        assertEquals("p1", wire.calls.last().first)
        assertEquals("belongs to p1", wire.calls.last().third)
        assertEquals("p2", saver.pad.value?.id)
    }

    @Test
    fun `a deleted page is never recreated by a timer`() = runTest {
        // forget() is the one path that DROPS the pending write. Writing it would
        // put back a page somebody had just deleted, which is worse than losing
        // the last sentence typed into it.
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad())
        saver.set("about to be deleted")
        saver.forget()
        advanceUntilIdle()

        assertEquals(0, wire.calls.size)
        assertNull(saver.pad.value)
    }

    @Test
    fun `a conflict adopts the other device's text and says so`() = runTest {
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad(rev = 1))
        // The other device saved while this one was typing.
        wire.revs["p1"] = 5

        saver.set("from the phone")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertEquals("from the desktop", saver.pad.value?.content, "the winner's text is what is now true")
        assertEquals(5, saver.pad.value?.rev)
        assertEquals(ScratchpadSaver.OTHER_DEVICE, saver.note.value)
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value, "a conflict is an answer, not a failure")
    }

    @Test
    fun `typing after a conflict saves against the adopted rev`() = runTest {
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad(rev = 1))
        wire.revs["p1"] = 5
        saver.set("from the phone")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        saver.set("and now this")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(5, wire.calls.last().second, "a second conflict in a row would be an editor nobody can use")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `a failed write HOLDS the text and retries on the next flush`() = runTest {
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad())

        wire.fail = "network is down"
        saver.set("worth keeping")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(ScratchpadSaver.State.FAILED, saver.state.value)
        assertEquals("network is down", saver.note.value)

        wire.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals("worth keeping", wire.calls.last().third, "the held text never reached the daemon")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `text typed while a write is in flight is not lost by its answer`() = runTest {
        // The answer to a save carries the SERVER's copy of the content, which by
        // then is already behind the screen. Adopting it here would delete live
        // typing a keystroke at a time — and only in the window nobody tests.
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad())

        wire.hold()
        saver.set("first")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(1, wire.calls.size, "the write is out and has not answered")

        saver.set("first and more")
        wire.release()
        advanceUntilIdle()
        assertEquals("first and more", saver.pad.value?.content)
    }

    @Test
    fun `a write scheduled while another is in flight carries the NEW rev`() = runTest {
        // The rev on that queued write was captured before the first one answered,
        // so it is one behind by the time it goes out. Left uncorrected, every
        // burst of typing that outruns a round trip 409s against its own previous
        // save — and the editor's answer to a 409 is to throw the typing away.
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad(rev = 1))

        wire.hold()
        saver.set("first")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        saver.set("first and more")
        wire.release()
        advanceUntilIdle()
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertEquals(2, wire.calls.size)
        assertEquals(2, wire.calls.last().second, "the second write went out on the rev the first one produced")
        assertEquals("first and more", saver.pad.value?.content)
        assertNull(saver.note.value, "it conflicted with its own previous save")
    }

    @Test
    fun `an unchanged page is not written at all`() = runTest {
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad(content = "already this"))

        saver.set("already this")
        saver.flush()
        advanceUntilIdle()
        assertEquals(0, wire.calls.size, "opening a page must not rewrite it")
    }

    @Test
    fun `closing lands the last write and then holds no page`() = runTest {
        val wire = Wire()
        val saver = ScratchpadSaver(this, wire::save)
        saver.open(pad())

        saver.set("last thought")
        saver.close()
        advanceUntilIdle()
        assertTrue(wire.calls.any { it.third == "last thought" }, "the last thought was dropped on close")
        assertNull(saver.pad.value)
    }
}
