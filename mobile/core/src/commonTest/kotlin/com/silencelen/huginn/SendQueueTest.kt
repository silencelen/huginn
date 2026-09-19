package com.silencelen.huginn

import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.TypingState
import com.silencelen.huginn.ui.SendQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `a session still starting is a third wait, and names Claude starting`() {
        // appd 3.0.7: a send into a session whose Claude has not drawn its composer
        // yet is held with blockedBy "starting". "Finishes its turn" there describes
        // a turn that has not begun; the reader should know they are waiting on a
        // start, which clears itself in about two seconds.
        val note = SendQueue.note(TypingState(queued = 1, blockedBy = "starting"))
        assertNotNull(note)
        assertTrue(note!!.contains("start"), note)
        assertTrue(!note.contains("finishes its turn"), note)
    }

    @Test
    fun `the seed carries the REASON the send is waiting, not just the count`() {
        // ⚠ THE TWO SECONDS NOBODY WAS POLLING FOR. The seed is what the composer
        // line is drawn from until the first `/typing` poll lands, and it knew a
        // number and nothing else — so `note` fell through to its default sentence,
        // "will send when Claude finishes its turn", for a session whose Claude has
        // not started a turn at all. Then it silently corrected itself. The daemon
        // has said `blockedBy` on the send's own answer since appd 3.1.2.
        val held = SendKeysResult(ok = true, queued = 1, position = 1, delivered = false, blockedBy = "starting")
        val seeded = SendQueue.seed(held)
        assertNotNull(seeded)
        assertEquals("starting", seeded!!.blockedBy, "the reason must survive the seed")
        assertEquals(
            "Queued · waiting for Claude to start (1 waiting)",
            SendQueue.note(seeded),
            "and the first sentence must be the right one, not a correction two seconds later",
        )
    }

    @Test
    fun `a seed from an older daemon still says something, just not why`() {
        // A daemon before 3.1.2 answers with no `blockedBy` at all, which decodes to
        // null — and the line must stay exactly what it was rather than becoming
        // nothing. Additive on the wire means additive on the screen too.
        val seeded = SendQueue.seed(SendKeysResult(ok = true, queued = 1, position = 1))
        assertNotNull(seeded)
        assertNull(seeded!!.blockedBy)
        assertEquals("Queued · will send when Claude finishes its turn (1 waiting)", SendQueue.note(seeded))
    }

    @Test
    fun `a modal seeded from the send says dialog, not turn`() {
        // The same fix for the other reason a send can be held the instant it is
        // made: a send into a pane with a dialog on it is queued, and "finishes its
        // turn" tells the reader to wait for the one thing that will not happen.
        val note = SendQueue.note(SendQueue.seed(
            SendKeysResult(ok = true, queued = 1, position = 1, blockedBy = "modal"),
        ))
        assertNotNull(note)
        assertTrue(note!!.contains("dialog"), note)
        assertTrue(!note.contains("finishes its turn"), note)
    }

    @Test
    fun `a question waiting on the screen holds a send, and says so`() {
        // appd 3.4.0 reports blockedBy "attention" for a human send held behind a
        // pending question. The turn sentence there would be an instruction to wait
        // for something that is not happening.
        val note = SendQueue.note(TypingState(queued = 1, blockedBy = "attention"))
        assertNotNull(note)
        assertTrue(note!!.contains("question"), note)
        assertTrue(!note.contains("finishes its turn"), note)
    }

    @Test
    fun `unsent text in the live view holds a send, and names it`() {
        // appd 3.5.0 reports blockedBy "draft" when the composer holds the reader's own
        // unsent text; a paste in front of it would be submitted as one sentence.
        val note = SendQueue.note(TypingState(queued = 1, blockedBy = "draft"))
        assertNotNull(note)
        assertTrue(note!!.contains("unsent"), note)
        assertTrue(!note.contains("finishes its turn"), note)
    }

    @Test
    fun `the list row says only that there is a wait`() {
        assertEquals("3 queued", SendQueue.rowMark(3))
        assertNull(SendQueue.rowMark(0), "an empty queue is not a fact about a row")
    }

    // -------------------------------------------- the send the daemon already had

    /**
     * ⚠ THE SEND WHOSE FATE A READER MOST WANTS EXPLAINED. appd 3.5.1 drops an
     * identical human text that is already pending, or that it delivered within
     * the last 30 seconds, and answers `duplicate: true` — the fix for the P1
     * where three `/keys` POSTs from one tap put the owner's message on the pane
     * three times. Nothing is queued for THIS press, so `landed` is true and the
     * old rule seeded nothing: the composer emptied and said absolutely nothing,
     * which is the same screen as a message that vanished.
     */
    @Test
    fun `a duplicate says the message is already on its way`() {
        val dup = SendKeysResult(ok = true, queued = 0, position = 0, delivered = false, duplicate = true)
        assertTrue(dup.landed, "a duplicate is 'landed' — nothing of it is waiting")
        val seeded = SendQueue.seed(dup)
        assertNotNull(seeded, "and it must still seed a line, which landed alone would not")
        assertEquals("That message is already on its way", SendQueue.note(seeded))
    }

    /** It says the message is FINE. "Rejected" or "not sent" would mean retype it. */
    @Test
    fun `the duplicate sentence never invites a retype`() {
        val words = SendQueue.DUPLICATE.lowercase()
        assertTrue("already" in words, SendQueue.DUPLICATE)
        assertFalse("duplicate" in words, "the word is for the wire, not for the reader")
        assertFalse("not sent" in words, SendQueue.DUPLICATE)
        assertFalse("rejected" in words, SendQueue.DUPLICATE)
    }

    /**
     * A duplicate BEHIND a queue says the same thing — the count belongs to other
     * people's sends, not to this press, so leading with it would be a lie about
     * where this message is.
     */
    @Test
    fun `a duplicate with a queue behind it still leads with the duplicate`() {
        val seeded = SendQueue.seed(SendKeysResult(ok = true, queued = 3, duplicate = true))
        assertEquals("That message is already on its way", SendQueue.note(seeded))
    }

    /** An older daemon never sets it, and nothing about the old path moves. */
    @Test
    fun `without the field the old answers behave exactly as before`() {
        assertNull(SendQueue.seed(SendKeysResult(ok = true)), "a pre-queue daemon's bare ok")
        val queued = SendQueue.seed(SendKeysResult(ok = true, queued = 2, position = 2))
        assertEquals("Queued · will send when Claude finishes its turn (2 waiting)", SendQueue.note(queued))
    }

    /**
     * ⚠ AND IT CLEARS ITSELF. The seed is this client's guess; the next /typing
     * answer is the daemon's own account of what is pending and replaces it. A
     * line that claimed forever that a message was on its way would be worse than
     * the silence it replaced.
     */
    @Test
    fun `the next poll clears the duplicate line`() {
        assertNull(SendQueue.note(TypingState(queued = 0)))
    }
}
