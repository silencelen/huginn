package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.ScratchpadSave
import com.silencelen.huginn.data.ScratchpadSaver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
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
 * holding, a fetch that arrives after the write it was read before.
 *
 * The saver's own invariants are stated in its class header; the cases here are
 * numbered against them in the comments, because each one is a mechanism rather
 * than a preference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScratchpadSaverTest {

    private data class Call(val id: String, val rev: Int, val name: String?, val content: String?)

    private class Wire {
        val calls = mutableListOf<Call>()

        /** Bumped by a save; a test can raise it by hand to stage a conflict. */
        val revs = mutableMapOf<String, Int>()
        var fail: String? = null

        /** The HTTP code a failure carries, when the test cares which. */
        var failCode: Int? = null

        /**
         * Holds a write open, so a test can type WHILE one is in flight. That
         * window is where most of the rules below actually live, and a wire that
         * always answers instantly never opens it.
         */
        private var gate: CompletableDeferred<Unit>? = null
        fun hold() { gate = CompletableDeferred() }
        fun release() { gate?.complete(Unit); gate = null }

        suspend fun save(id: String, rev: Int, name: String?, content: String?): ScratchpadSave {
            calls += Call(id, rev, name, content)
            gate?.await()
            failCode?.let { throw HuginnClient.HuginnException(it, fail ?: "refused") }
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
            return ScratchpadSave(
                Scratchpad(id = id, name = name ?: "Main", content = content ?: "", rev = next),
                false,
            )
        }
    }

    /**
     * The lane is the SCOPE's dispatcher here, so the virtual clock drives the
     * bookkeeping too. In the app it is a single-parallelism dispatcher of its
     * own — see the saver's Threading note; what these tests assert is the
     * ORDERING that confinement guarantees, which a test dispatcher gives for
     * free and which is what every rule below depends on.
     */
    private fun TestScope.saver(wire: Wire) =
        ScratchpadSaver(this, wire::save, lane = EmptyCoroutineContext)

    private fun pad(id: String = "p1", content: String = "", rev: Int = 1) =
        Scratchpad(id = id, name = if (id == "p1") "Main" else id, content = content, rev = rev, main = id == "p1")

    @Test
    fun `typing writes once, after the pause`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()

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
        assertEquals("hello", wire.calls.last().content)
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `the rev advances, so the next save is not a conflict with our own write`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(rev = 1))
        advanceUntilIdle()

        saver.set("one")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(2, saver.pad.value?.rev)

        saver.set("two")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(2, wire.calls[1].rev, "the second write must carry the rev the first one produced")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `leaving the view lands what is still in the air`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()

        saver.set("half a thought")
        saver.flush()
        advanceUntilIdle()
        assertEquals("half a thought", wire.calls.last().content)
    }

    @Test
    fun `switching pages writes the old one under the OLD id`() = runTest {
        // The one case where reading current state at write time is wrong: the
        // timer fires after the editor has moved on, and the text belongs to the
        // page it was typed into, not to the page now on screen.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(id = "p1"))
        advanceUntilIdle()
        saver.set("belongs to p1")
        saver.open(pad(id = "p2"))
        advanceUntilIdle()

        assertEquals(1, wire.calls.size)
        assertEquals("p1", wire.calls.last().id)
        assertEquals("belongs to p1", wire.calls.last().content)
        assertEquals("p2", saver.pad.value?.id)
    }

    @Test
    fun `a deleted page is never recreated by a timer`() = runTest {
        // forget() is the one path that DROPS the pending write. Writing it would
        // put back a page somebody had just deleted, which is worse than losing
        // the last sentence typed into it.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()
        saver.set("about to be deleted")
        saver.forget()
        advanceUntilIdle()

        assertEquals(0, wire.calls.size)
        assertNull(saver.pad.value)
    }

    @Test
    fun `a conflict adopts the other device's text and says so`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(rev = 1))
        advanceUntilIdle()
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
        val saver = saver(wire)
        saver.open(pad(rev = 1))
        advanceUntilIdle()
        wire.revs["p1"] = 5
        saver.set("from the phone")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        saver.set("and now this")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(5, wire.calls.last().rev, "a second conflict in a row would be an editor nobody can use")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `a failed write HOLDS the text and retries on the next flush`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()

        wire.fail = "network is down"
        saver.set("worth keeping")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(ScratchpadSaver.State.FAILED, saver.state.value)
        assertEquals("network is down", saver.note.value)

        wire.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals("worth keeping", wire.calls.last().content, "the held text never reached the daemon")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `text typed while a write is in flight is not lost by its answer`() = runTest {
        // The answer to a save carries the SERVER's copy of the content, which by
        // then is already behind the screen. Adopting it here would delete live
        // typing a keystroke at a time — and only in the window nobody tests.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()

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
        val saver = saver(wire)
        saver.open(pad(rev = 1))
        advanceUntilIdle()

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
        assertEquals(2, wire.calls.last().rev, "the second write went out on the rev the first one produced")
        assertEquals("first and more", saver.pad.value?.content)
        assertNull(saver.note.value, "it conflicted with its own previous save")
    }

    @Test
    fun `an unchanged page is not written at all`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(content = "already this"))
        advanceUntilIdle()

        saver.set("already this")
        saver.flush()
        advanceUntilIdle()
        assertEquals(0, wire.calls.size, "opening a page must not rewrite it")
    }

    @Test
    fun `closing lands the last write and then holds no page`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()

        saver.set("last thought")
        saver.close()
        advanceUntilIdle()
        assertTrue(wire.calls.any { it.content == "last thought" }, "the last thought was dropped on close")
        assertNull(saver.pad.value)
    }

    // ------------------------------------------- invariant 1: an older fetch

    @Test
    fun `a fetch read before the last write never replaces the newer text`() = runTest {
        // THE SILENT ONE. A page's text is fetched on open; the answer is a
        // photograph of the page at the moment the daemon read it, and it can
        // arrive after a write that is newer than it. Adopted, the screen shows
        // the OLD paragraph — and then the write's answer stamps the NEW rev onto
        // it, so the next autosave overwrites the good copy with the stale one
        // against a revision the daemon has no reason to refuse. Nothing is
        // reported, nothing looks wrong, and the paragraph is gone.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(rev = 1))
        advanceUntilIdle()

        // The caller captures the generation BEFORE its fetch — the contract.
        val at = saver.generation()
        val asTheDaemonReadIt = pad(content = "", rev = 1)

        saver.set("a paragraph worth keeping")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(2, saver.pad.value?.rev, "the write landed and the rev moved")

        // ...and only NOW does that fetch come back.
        saver.open(asTheDaemonReadIt, at)
        advanceUntilIdle()
        assertEquals(
            "a paragraph worth keeping",
            saver.pad.value?.content,
            "an older copy of the page replaced the newer one",
        )
        assertEquals(2, saver.pad.value?.rev, "and took the rev with it")

        // The proof that nothing was quietly corrupted: the next write goes out on
        // the rev its own text was written on, and lands without a conflict.
        saver.set("a paragraph worth keeping, and more")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(2, wire.calls.last().rev)
        assertEquals("a paragraph worth keeping, and more", wire.calls.last().content)
        assertNull(saver.note.value)
    }

    @Test
    fun `a fetch that lands while a write is still in the air is refused too`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(rev = 1))
        advanceUntilIdle()

        val at = saver.generation()
        wire.hold()
        saver.set("typed while the page was being read")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(1, wire.calls.size, "the write is out and unanswered")

        saver.open(pad(content = "", rev = 1), at)
        advanceUntilIdle()
        assertEquals("typed while the page was being read", saver.pad.value?.content)

        wire.release()
        advanceUntilIdle()
        assertEquals(2, saver.pad.value?.rev, "and the write it was racing still counted")
        assertEquals("typed while the page was being read", saver.pad.value?.content)
    }

    @Test
    fun `a page opened with nothing owed says nothing at all`() = runTest {
        // The ordinary path is unaffected by all of the above: a current fetch is
        // adopted whole, and the line goes quiet.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(content = "one"))
        advanceUntilIdle()
        saver.set("two")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)

        saver.open(pad(id = "p2", content = "another page", rev = 4))
        advanceUntilIdle()
        assertEquals("another page", saver.pad.value?.content)
        assertEquals(ScratchpadSaver.State.IDLE, saver.state.value)
    }

    // --------------------------------- invariant 3: the hold belongs to a page

    @Test
    fun `a failed write never replaces text typed after it went out`() = runTest {
        // The failure path re-holds the snapshot it was carrying. Doing that
        // unconditionally puts an OLDER paragraph back over a newer one, and the
        // next flush then writes the old text to the server while the screen goes
        // on showing the new — the two disagree, and only the server's copy
        // survives the next open.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()

        wire.hold()
        wire.fail = "no route to host"
        saver.set("first")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        saver.set("first and second")
        wire.release()
        advanceUntilIdle()
        assertEquals(ScratchpadSaver.State.FAILED, saver.state.value)
        assertEquals("first and second", saver.pad.value?.content, "the screen keeps the newer text")

        wire.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals(
            "first and second",
            wire.calls.last().content,
            "the retry wrote the snapshot the failure was holding, not the text on screen",
        )
    }

    @Test
    fun `a write that failed for a page you have left is still owed`() = runTest {
        // The failure arrives after the editor has moved on, which is exactly when
        // nobody is looking at the page whose sentence is about to be dropped.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(id = "p1"))
        advanceUntilIdle()

        wire.hold()
        wire.fail = "no route to host"
        saver.set("worth keeping")
        saver.flush()
        advanceUntilIdle()
        assertEquals(1, wire.calls.count { it.id == "p1" })

        saver.open(pad(id = "p2"))
        advanceUntilIdle()
        wire.release()
        advanceUntilIdle()

        wire.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals(2, wire.calls.count { it.id == "p1" }, "the failure was dropped with the page")
        assertEquals("worth keeping", wire.calls.last { it.id == "p1" }.content)
    }

    @Test
    fun `re-opening a page that is still holding text shows the held copy`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(id = "p1"))
        advanceUntilIdle()

        wire.fail = "no route to host"
        saver.set("held for p1")
        saver.flush()
        advanceUntilIdle()
        saver.open(pad(id = "p2"))
        advanceUntilIdle()

        // The list still says what the daemon last knew, which is nothing.
        saver.open(pad(id = "p1", content = ""), saver.generation())
        advanceUntilIdle()
        assertEquals("held for p1", saver.pad.value?.content, "the unsent text is newer than the server's copy")
        assertEquals(ScratchpadSaver.State.FAILED, saver.state.value)
        assertEquals("no route to host", saver.note.value)
    }

    // ------------------------------- invariant 4: the line is about this page

    @Test
    fun `switching pages does not leave the new one saying Saving`() = runTest {
        // The write for the OLD page is issued BY the switch, so its "Saving…"
        // used to be published while the new page was already on screen — and the
        // answer, which is about a page nobody is looking at, then declined to
        // clear it. The new page sat there claiming to be saving forever.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(id = "p1"))
        advanceUntilIdle()

        wire.hold()
        saver.set("still typing")
        saver.open(pad(id = "p2"))
        advanceUntilIdle()
        assertEquals("p2", saver.pad.value?.id)
        assertEquals(ScratchpadSaver.State.IDLE, saver.state.value, "p1's write is not p2's business")

        wire.release()
        advanceUntilIdle()
        assertEquals(ScratchpadSaver.State.IDLE, saver.state.value)
    }

    // ------------------------------------------- invariant 5: a terminal refusal

    @Test
    fun `a page too large to save is refused once, not on every keystroke`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()

        wire.failCode = 413
        wire.fail = "payload too large"
        saver.set("x".repeat(200))
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(ScratchpadSaver.State.FAILED, saver.state.value)
        assertEquals(ScratchpadSaver.TOO_LARGE, saver.note.value, "and it says what to do about it")

        val attempts = wire.calls.size
        saver.flush()
        advanceUntilIdle()
        saver.flush()
        advanceUntilIdle()
        assertEquals(attempts, wire.calls.size, "the same text against the same cap will be refused again")
    }

    @Test
    fun `shortening a page too large to save tries again, with everything still there`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad())
        advanceUntilIdle()

        wire.failCode = 413
        wire.fail = "payload too large"
        saver.set("x".repeat(200))
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        wire.failCode = null
        wire.fail = null
        saver.set("shorter now")
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals("shorter now", wire.calls.last().content, "an edit is a new chance, not the same refusal")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
        assertNull(saver.note.value)
    }

    // ----------------------------------------------- the rename joins the queue

    @Test
    fun `a rename waits for the write in the air instead of racing it`() = runTest {
        // Both PATCH the same row with the same rev, so two in flight means one of
        // them is refused: the autosave losing puts the server's older text back
        // over what is being typed, the rename losing simply does not happen.
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(rev = 1))
        advanceUntilIdle()

        wire.hold()
        saver.set("typed just now")
        saver.flush()
        advanceUntilIdle()
        assertEquals(1, wire.calls.size)

        val renamed = async { saver.rename("p1", "Deploy notes", rev = 1) }
        advanceUntilIdle()
        assertEquals(1, wire.calls.size, "the rename went out beside a write on the same rev")

        wire.release()
        advanceUntilIdle()
        assertEquals(2, wire.calls.size)
        assertEquals(2, wire.calls.last().rev, "the rename went out on the rev the save produced")
        assertEquals("Deploy notes", wire.calls.last().name)
        assertNull(wire.calls.last().content, "a rename must not rewrite the text")

        val answer = renamed.await()
        assertTrue(answer.isSuccess, "the rename was refused: ${answer.exceptionOrNull()}")
        assertEquals("Deploy notes", saver.pad.value?.name)
        assertEquals("typed just now", saver.pad.value?.content, "renaming a page must not revert it")
    }

    @Test
    fun `text typed during a rename is written after it, on the rev it produced`() = runTest {
        val wire = Wire()
        val saver = saver(wire)
        saver.open(pad(rev = 1))
        advanceUntilIdle()

        wire.hold()
        val renamed = async { saver.rename("p1", "Deploy notes", rev = 1) }
        advanceUntilIdle()
        saver.set("typed while it was renaming")
        wire.release()
        advanceUntilIdle()
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertTrue(renamed.await().isSuccess)
        assertEquals(2, wire.calls.size)
        assertEquals("typed while it was renaming", wire.calls.last().content)
        assertEquals(2, wire.calls.last().rev, "the text went out on the rev the rename produced")
        assertNull(saver.note.value, "it conflicted with the rename")
        assertEquals("Deploy notes", saver.pad.value?.name)
    }
}
