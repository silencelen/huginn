package com.silencelen.huginn

import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.TypingState
import com.silencelen.huginn.ui.SendQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a message that has not landed yet says.
 *
 * THE BUG: a send into a busy session is queued by the daemon and delivered at
 * the next turn boundary. The composer empties, the transcript does not grow, and
 * until this existed nothing anywhere said why — the owner's report was that the
 * message "just disappears". Every case here is about a sentence being present
 * when it should be and, just as importantly, GONE when the queue drains.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SendQueueTest {

    @Test
    fun `a queued send produces a status line`() {
        val queued = SendKeysResult(ok = true, queued = 2, position = 2, delivered = false)
        val seeded = SendQueue.seed(queued)
        assertNotNull(seeded, "a send that did not land seeds the queue from its own answer")
        val note = SendQueue.note(seeded)
        assertNotNull(note, "and that seed has something to say")
        assertTrue(note!!.startsWith("Queued"), note)
        assertTrue(note.contains("2 waiting"), "it must carry the count: $note")
    }

    @Test
    fun `a delivered send says nothing at all`() {
        // The ordinary case, and the reason `landed` is not `delivered` alone: a
        // pre-queue daemon answers `{"ok":true}`, which decodes to queued 0 and
        // delivered false, and that is "nothing is waiting", not "queued".
        assertNull(SendQueue.seed(SendKeysResult(ok = true, delivered = true, queued = 0)))
        assertNull(SendQueue.seed(SendKeysResult(ok = true)), "an old daemon's bare ok")
    }

    @Test
    fun `a drained poll clears the line`() {
        // The other half, and the one that rots: a queue that emptied must stop
        // saying anything rather than settle on "(0 waiting)" under a composer
        // that is working perfectly.
        assertNull(SendQueue.note(TypingState(queued = 0)))
        assertNull(SendQueue.note(null), "nothing polled yet")
        assertNull(SendQueue.note(TypingState(queued = 0, delivering = false, lastError = "  ")))
    }

    @Test
    fun `the daemon's error wins, verbatim`() {
        // It is the only thing that tells a reader whether to retype the message or
        // wait, and "queued" printed over a failure is the worst of both.
        val note = SendQueue.note(TypingState(queued = 1, lastError = "pane is gone"))
        assertEquals("pane is gone", note)
    }

    @Test
    fun `a modal is a different wait and gets different words`() {
        // No amount of Claude finishing a turn clears an open dialog — somebody has
        // to answer it. The turn sentence there is an instruction to do nothing
        // about the one thing actually blocking the send.
        val note = SendQueue.note(TypingState(queued = 1, blockedBy = "modal"))
        assertNotNull(note)
        assertTrue(note!!.contains("dialog"), note)
        assertTrue(!note.contains("finishes its turn"), note)
    }

    @Test
    fun `the list row says only that there is a wait`() {
        assertEquals("3 queued", SendQueue.rowMark(3))
        assertNull(SendQueue.rowMark(0), "an empty queue is not a fact about a row")
    }
}
