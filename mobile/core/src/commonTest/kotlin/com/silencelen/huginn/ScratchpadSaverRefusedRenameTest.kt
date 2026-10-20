package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.ScratchpadSave
import com.silencelen.huginn.data.ScratchpadSaver
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * A rename the daemon refuses.
 *
 * The rename dialog checks the name against the page list it last polled, so a
 * name another device has just taken passes the check and is refused by the
 * daemon with a 400. The daemon checks the name BEFORE the text and refuses the
 * whole body, so a refused name the saver kept for a retry rode along with every
 * later autosave of that page: each one was refused, nothing typed after the
 * rename reached the server, and if the name came free later the page was
 * silently renamed back to it.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScratchpadSaverRefusedRenameTest {

    private data class Call(val id: String, val rev: Int, val name: String?, val content: String?)

    /**
     * The daemon's PATCH in the order huginn-appd checks it: the rev, then the
     * name against every OTHER page's, then the content. A refused name refuses
     * the whole body.
     */
    private class Daemon(vararg pads: Scratchpad) {
        val pads = pads.associateBy { it.id }.toMutableMap()
        val calls = mutableListOf<Call>()

        /** A failure that never reaches the daemon — no HTTP code at all. */
        var unreachable: Boolean = false

        suspend fun save(id: String, rev: Int, name: String?, content: String?): ScratchpadSave {
            calls += Call(id, rev, name, content)
            if (unreachable) throw IllegalStateException("no route to host")
            val current = pads.getValue(id)
            if (rev != current.rev) return ScratchpadSave(current, conflict = true)
            if (name != null && pads.values.any { it.id != id && it.name.equals(name, ignoreCase = true) }) {
                throw HuginnClient.HuginnException(400, "there is already a page with that name")
            }
            val next = current.copy(
                name = name ?: current.name,
                content = content ?: current.content,
                rev = current.rev + 1,
            )
            pads[id] = next
            return ScratchpadSave(next, conflict = false)
        }

        /** Another device renaming a page. */
        fun renameElsewhere(id: String, name: String) {
            val p = pads.getValue(id)
            pads[id] = p.copy(name = name, rev = p.rev + 1)
        }
    }

    private fun TestScope.saver(daemon: Daemon) =
        ScratchpadSaver(this, daemon::save, lane = EmptyCoroutineContext)

    private fun daemon() = Daemon(
        Scratchpad(id = "A", name = "Scratch", content = "original", rev = 1),
        // Taken on another device since this one last polled the list.
        Scratchpad(id = "B", name = "Notes", content = "", rev = 1),
    )

    private fun TestScope.typeAndLand(saver: ScratchpadSaver, text: String) {
        saver.set(text)
        advanceTimeBy(ScratchpadSaver.DEBOUNCE_MS + 1)
        advanceUntilIdle()
    }

    @Test
    fun `a refused name does not ride along with the page's next save`() = runTest {
        val daemon = daemon()
        val saver = saver(daemon)
        saver.open(daemon.pads.getValue("A"))
        advanceUntilIdle()

        val refused = saver.rename("A", "Notes", rev = 1)
        assertTrue(refused.isFailure, "the caller is handed the refusal to show")

        typeAndLand(saver, "draft")
        assertNull(daemon.calls.last().name, "the refused name was sent again with the text")
        assertEquals("draft", daemon.pads.getValue("A").content, "nothing typed after the rename reached the server")
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
        assertNull(saver.note.value)
    }

    @Test
    fun `after a refused rename a free name sticks, even once the refused one is free`() = runTest {
        val daemon = daemon()
        val saver = saver(daemon)
        saver.open(daemon.pads.getValue("A"))
        advanceUntilIdle()

        assertTrue(saver.rename("A", "Notes", rev = 1).isFailure)
        assertTrue(saver.rename("A", "Ideas v2", rev = 1).isSuccess)
        assertEquals("Ideas v2", daemon.pads.getValue("A").name)

        daemon.renameElsewhere("B", "Archive")
        typeAndLand(saver, "draft")
        assertEquals("Ideas v2", daemon.pads.getValue("A").name, "the page was renamed back to the name it was refused")
        assertEquals("draft", daemon.pads.getValue("A").content)
    }

    @Test
    fun `a held name the daemon then refuses stops blocking the text`() = runTest {
        // A rename that never reached the daemon is held and rides with the next
        // save — and by then the name can have been taken.
        val daemon = daemon()
        val saver = saver(daemon)
        saver.open(daemon.pads.getValue("A"))
        advanceUntilIdle()

        daemon.unreachable = true
        assertTrue(saver.rename("A", "Notes v2", rev = 1).isFailure)
        daemon.unreachable = false
        daemon.renameElsewhere("B", "Notes v2")

        typeAndLand(saver, "draft")
        assertEquals("Notes v2", daemon.calls.last().name, "the held rename rode along, as it should have")
        assertEquals(ScratchpadSaver.State.FAILED, saver.state.value)

        saver.flush()
        advanceUntilIdle()
        assertEquals("draft", daemon.pads.getValue("A").content, "the text stayed tied to a name the daemon refuses")
        assertEquals("Scratch", daemon.pads.getValue("A").name)
        assertEquals(ScratchpadSaver.State.SAVED, saver.state.value)
    }

    @Test
    fun `a rename that never reached the daemon is still retried`() = runTest {
        // The hold the fix narrows: only the daemon's own refusal drops a name.
        val daemon = daemon()
        val saver = saver(daemon)
        saver.open(daemon.pads.getValue("A"))
        advanceUntilIdle()

        daemon.unreachable = true
        assertTrue(saver.rename("A", "Deploy notes", rev = 1).isFailure)
        daemon.unreachable = false

        saver.flush()
        advanceUntilIdle()
        assertEquals("Deploy notes", daemon.pads.getValue("A").name)
    }
}
