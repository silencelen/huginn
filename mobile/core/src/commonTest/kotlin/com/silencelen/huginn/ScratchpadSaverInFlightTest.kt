package com.silencelen.huginn

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Re-opening a page while a write for it is still in the air.
 *
 * Switching pages lands the old page's text, and sending it empties the hold —
 * so between that write leaving and its answer, the words exist only inside the
 * request. A page re-opened in that window (the switcher, a slow link, a write
 * queued behind another page's) used to be shown as the daemon's copy from
 * BEFORE the write, and then either the write's new rev was stamped onto that
 * stale copy, so the next keystroke overwrote the saved text without a conflict,
 * or the write failed and nothing held the text for a retry. Both silent.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScratchpadSaverInFlightTest {

    private data class Call(val id: String, val rev: Int, val name: String?, val content: String?)

    /**
     * The daemon's PATCH as huginn-appd serves it: a stale rev is answered 409
     * with the current page, anything else is applied and bumps the rev. A GET
     * is served the moment it is asked for, so a GET asked while a PATCH is held
     * open is the page as it was before that PATCH.
     */
    private class Daemon(vararg pads: Scratchpad) {
        val pads = pads.associateBy { it.id }.toMutableMap()
        val calls = mutableListOf<Call>()
        var fail: String? = null

        private var gate: CompletableDeferred<Unit>? = null
        fun hold() { gate = CompletableDeferred() }
        fun release() { gate?.complete(Unit); gate = null }

        fun get(id: String): Scratchpad = pads.getValue(id)

        suspend fun save(id: String, rev: Int, name: String?, content: String?): ScratchpadSave {
            calls += Call(id, rev, name, content)
            gate?.await()
            fail?.let { throw IllegalStateException(it) }
            val current = pads.getValue(id)
            if (rev != current.rev) return ScratchpadSave(current, conflict = true)
            val next = current.copy(
                name = name ?: current.name,
                content = content ?: current.content,
                rev = current.rev + 1,
            )
            pads[id] = next
            return ScratchpadSave(next, conflict = false)
        }
    }

    private fun TestScope.saver(daemon: Daemon) =
        ScratchpadSaver(this, daemon::save, lane = EmptyCoroutineContext)

    /**
     * What both shells do to open a page: capture the generation, fetch, open.
     * The fetch is served HERE, so whatever the daemon holds right now is what
     * arrives.
     */
    private fun ScratchpadSaver.openFrom(daemon: Daemon, id: String) {
        val at = generation()
        open(daemon.get(id), at)
    }

    private fun daemon() = Daemon(
        Scratchpad(id = "A", name = "Ideas", content = "old", rev = 5),
        Scratchpad(id = "B", name = "Other", content = "page b", rev = 3),
    )

    /** " hello" typed onto A, then the switcher to B: A's write is out and held open. */
    private fun TestScope.leaveWithWriteInTheAir(daemon: Daemon, saver: ScratchpadSaver) {
        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        saver.set("old hello")
        daemon.hold()
        saver.openFrom(daemon, "B")
        advanceUntilIdle()
        assertEquals(Call("A", 5, null, "old hello"), daemon.calls.last(), "switching pages sends what was typed")
    }

    private fun TestScope.typeAndLand(saver: ScratchpadSaver, text: String) {
        saver.set(text)
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
    }

    @Test
    fun `re-opening a page whose write is in the air shows the words being written`() = runTest {
        val daemon = daemon()
        val saver = saver(daemon)
        leaveWithWriteInTheAir(daemon, saver)

        // Back in the switcher: the GET is served before the PATCH has applied.
        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        assertEquals("old hello", saver.pad.value?.content, "the page came back without the words being saved")
        assertEquals("Ideas", saver.pad.value?.name)
        assertEquals(ScratchpadSaver.State.SAVING, saver.state.value)

        daemon.release()
        advanceUntilIdle()
        assertEquals(6, saver.pad.value?.rev, "the write's rev belongs to the text it wrote")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)

        typeAndLand(saver, "old hello!")
        assertEquals("old hello!", daemon.pads.getValue("A").content)
        assertNull(saver.note.value)
    }

    @Test
    fun `a write that fails after the page was re-opened is held and retried`() = runTest {
        val daemon = daemon()
        val saver = saver(daemon)
        leaveWithWriteInTheAir(daemon, saver)
        daemon.fail = "no route to host"

        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        daemon.release()
        advanceUntilIdle()
        assertEquals(ScratchpadSaver.State.FAILED, saver.state.value)
        assertEquals("old hello", saver.pad.value?.content)

        daemon.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals("old hello", daemon.pads.getValue("A").content, "the text was neither saved nor held for a retry")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `a write queued behind another page's is still this page's text`() = runTest {
        // The chain is single-file across pages, so A's write can be waiting behind
        // C's when A is opened again.
        val daemon = Daemon(
            Scratchpad(id = "A", name = "Ideas", content = "old", rev = 5),
            Scratchpad(id = "B", name = "Other", content = "page b", rev = 3),
            Scratchpad(id = "C", name = "Third", content = "page c", rev = 2),
        )
        val saver = saver(daemon)
        saver.openFrom(daemon, "C")
        advanceUntilIdle()
        saver.set("page c, edited")
        daemon.hold()
        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        saver.set("old hello")
        saver.openFrom(daemon, "B")
        advanceUntilIdle()
        assertEquals(listOf("C"), daemon.calls.map { it.id }, "A's write is queued, not sent")

        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        assertEquals("old hello", saver.pad.value?.content)

        daemon.release()
        advanceUntilIdle()
        typeAndLand(saver, "old hello!")
        assertEquals("old hello!", daemon.pads.getValue("A").content, "the next edit was written over a stale copy")
        assertNull(saver.note.value)
    }

    @Test
    fun `the phone's second open of the same page takes the copy the write produced`() = runTest {
        // The switcher and the page's own effect each open it, and both callers
        // capture the generation before fetching.
        val daemon = daemon()
        val saver = saver(daemon)
        leaveWithWriteInTheAir(daemon, saver)

        val first = saver.generation()
        val second = saver.generation()
        saver.open(daemon.get("A"), first) // served before the PATCH applied
        advanceUntilIdle()
        daemon.release()
        advanceUntilIdle()
        saver.open(daemon.get("A"), second) // served after it
        advanceUntilIdle()
        assertEquals("old hello", saver.pad.value?.content)
        assertEquals(6, saver.pad.value?.rev)

        typeAndLand(saver, "old hello!")
        assertEquals("old hello!", daemon.pads.getValue("A").content)
    }

    @Test
    fun `a retry sent by the re-open itself that fails again is still held`() = runTest {
        val daemon = daemon()
        val saver = saver(daemon)
        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        daemon.fail = "no route to host"
        typeAndLand(saver, "old hello")
        assertEquals(ScratchpadSaver.State.FAILED, saver.state.value)
        saver.openFrom(daemon, "B")
        advanceUntilIdle()

        // open(A) lands what A is holding, and that retry fails too.
        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        assertEquals("old hello", saver.pad.value?.content)

        daemon.fail = null
        saver.flush()
        advanceUntilIdle()
        assertEquals("old hello", daemon.pads.getValue("A").content, "the text was dropped once the retry failed")
    }

    /**
     * Invariant 2, judged when the answer ARRIVES. A stale copy adopted while a
     * rename is in the air keeps its own rev; stamping the rename's rev onto it —
     * decided before the rename went out — let the next keystroke overwrite the
     * saved text with no conflict.
     */
    @Test
    fun `a stale copy adopted during a rename keeps its own rev`() = runTest {
        val daemon = daemon()
        val saver = saver(daemon)
        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        saver.set("old hello")
        // A GET served now, before the save below applies, and delivered late.
        val at = saver.generation()
        val stale = daemon.get("A")
        saver.flush()
        advanceUntilIdle()
        assertEquals("old hello", daemon.pads.getValue("A").content)
        saver.openFrom(daemon, "B")
        advanceUntilIdle()

        daemon.hold()
        val renamed = async { saver.rename("A", "Deploy notes", rev = 6) }
        advanceUntilIdle()
        saver.open(stale, at)
        advanceUntilIdle()
        daemon.release()
        advanceUntilIdle()
        assertTrue(renamed.await().isSuccess)

        typeAndLand(saver, saver.pad.value!!.content + "!")
        assertEquals(
            "old hello",
            daemon.pads.getValue("A").content,
            "a write on the rename's rev overwrote the saved text from a stale copy",
        )
        assertEquals("old hello", saver.pad.value?.content, "the conflict hands back what was saved")
        assertNotNull(saver.note.value, "and says so")
    }

    /**
     * The other side of the case above. A rename writes no text, so a copy
     * fetched just before it applied — at exactly the rev it went out on — is
     * the text its answer describes, and the next keystroke must not conflict
     * with the rename.
     */
    @Test
    fun `a copy fetched just before a rename applied takes the rename's rev`() = runTest {
        val daemon = daemon()
        val saver = saver(daemon)
        saver.openFrom(daemon, "A")
        advanceUntilIdle()
        saver.openFrom(daemon, "B")
        advanceUntilIdle()

        daemon.hold()
        val renamed = async { saver.rename("A", "Deploy notes", rev = 5) }
        advanceUntilIdle()
        saver.openFrom(daemon, "A") // served before the rename applied: rev 5
        advanceUntilIdle()
        daemon.release()
        advanceUntilIdle()
        assertTrue(renamed.await().isSuccess)
        assertEquals("Deploy notes", saver.pad.value?.name)
        assertEquals(6, saver.pad.value?.rev)

        typeAndLand(saver, "old, and more")
        assertEquals("old, and more", daemon.pads.getValue("A").content)
        assertNull(saver.note.value, "the next save conflicted with the rename")
    }
}
