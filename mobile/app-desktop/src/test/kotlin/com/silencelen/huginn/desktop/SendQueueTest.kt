package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.IntoDraft
import com.silencelen.huginn.data.SendKeysResult
import com.silencelen.huginn.data.TypingState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE MESSAGE THAT "JUST DISAPPEARS" — the owner's words, and a P1.
 *
 * A send into a session that is mid-turn is accepted and HELD by the daemon until
 * the turn ends. The desktop threw that answer away: the composer emptied on
 * press, the transcript showed nothing, and the resulting screen is byte for byte
 * the screen of a message that was dropped. Silence is a lie here.
 *
 * Both halves are asserted, because both are ways of lying. A line that never
 * appears is the reported bug; a line that never CLEARS is worse, because it
 * claims a delivered message is still waiting and sends the reader looking for
 * something that already arrived.
 *
 * NOTE the kotlin.test argument order: (expected, actual, message).
 */
class SendQueueTest {

    // ------------------------------------------------------------- the sentence

    @Test
    fun `nothing held says nothing`() {
        assertNull(SendQueue.line(TypingState()))
        assertNull(SendQueue.line(TypingState(queued = 0, delivering = true)))
    }

    @Test
    fun `a held message says so, and says how many`() {
        assertEquals(
            "Queued · will send when Claude finishes its turn (1 waiting)",
            SendQueue.line(TypingState(queued = 1, blockedBy = "turn")),
        )
        assertEquals(
            "Queued · will send when Claude finishes its turn (3 waiting)",
            SendQueue.line(TypingState(queued = 3, blockedBy = "turn")),
        )
    }

    @Test
    fun `a question in the pane is a different wait and says which`() {
        // The one the reader can actually do something about: the queue is not
        // waiting on a model, it is waiting on them.
        assertEquals(
            "Queued · waiting on a question in the pane (2 waiting)",
            SendQueue.line(TypingState(queued = 2, blockedBy = "modal")),
        )
    }

    @Test
    fun `a session still starting says it is waiting for Claude to start`() {
        // appd 3.0.7 holds a send into a just-created session until the composer
        // draws (blockedBy "starting"). The turn sentence there would describe a
        // turn that has not begun.
        assertEquals(
            "Queued · waiting for Claude to start (1 waiting)",
            SendQueue.line(TypingState(queued = 1, blockedBy = "starting")),
        )
    }

    @Test
    fun `a question waiting on the screen holds a send, and says so`() {
        assertEquals(
            "Queued · waiting on a question in the pane (1 waiting)",
            SendQueue.line(TypingState(queued = 1, blockedBy = "attention")),
        )
    }

    @Test
    fun `unsent text in the live view holds a send, and names it`() {
        assertEquals(
            "Queued · unsent text in the live view (1 waiting)",
            SendQueue.line(TypingState(queued = 1, blockedBy = "draft")),
        )
    }

    @Test
    fun `an error is shown verbatim and wins over the count`() {
        // The daemon knows why it could not deliver; a paraphrase here would be
        // this client guessing about the other end of a queue it does not own.
        assertEquals(
            "Not sent — pane is gone",
            SendQueue.line(TypingState(queued = 1, lastError = "pane is gone")),
        )
        assertNull(
            SendQueue.line(TypingState(lastError = "  ")),
            "a blank error is not an error",
        )
    }

    // ------------------------------------------------ what the controller records

    private val BASE = "http://h"

    private fun controller(scope: CoroutineScope, typing: () -> String): SessionController {
        val client = HuginnClient(
            baseUrlProvider = { BASE },
            tokenProvider = { "t" },
            engine = MockEngine { request ->
                val path = request.url.toString().removePrefix(BASE)
                val body = if ("/typing" in path) typing() else """{"ok":true}"""
                respond(body, HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", listOf("application/json")))
            },
        )
        return SessionController(
            client = client,
            name = "jtyper",
            presence = Presence(),
            lease = PaneLeaseHolder(client, scope),
            meta = com.silencelen.huginn.data.SessionMetaSaver(
                scope = scope,
                save = { _, _, _ -> com.silencelen.huginn.data.SessionMeta() },
            ),
            appScope = scope,
        )
    }

    @Test
    fun `a queued send produces the status line, and a drained poll clears it`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var typing = """{"queued":2,"delivering":false,"blockedBy":"turn"}"""
        val c = controller(scope) { typing }

        // The daemon says it took the message but has not delivered it.
        c.noteSend(SendKeysResult(ok = true, queued = 2, position = 2, delivered = false))
        assertEquals(2, c.sendQueue.value.queued)
        assertNotNull(SendQueue.line(c.sendQueue.value), "the composer must say something")

        // The loop body, driven directly: the loop itself is a delay on a real
        // dispatcher, which is not where the mistakes live.
        assertTrue(c.pollQueueOnce(), "still held")
        assertEquals(2, c.sendQueue.value.queued)

        // The turn ends and the queue drains.
        typing = """{"queued":0,"delivering":false}"""
        assertTrue(!c.pollQueueOnce(), "the poll reports the queue empty and stops")
        assertNull(
            SendQueue.line(c.sendQueue.value),
            "a line that never clears claims a delivered message is still waiting",
        )

        scope.cancel()
    }

    @Test
    fun `a delivered send says nothing at all`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = controller(scope) { """{"queued":0}""" }

        c.noteSend(SendKeysResult(ok = true, queued = 0, position = 0, delivered = true))
        assertNull(SendQueue.line(c.sendQueue.value))

        scope.cancel()
    }

    @Test
    fun `a pre-queue daemon's bare ok is not a permanent Queued line`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = controller(scope) { """{"queued":0}""" }

        // `{"ok":true}` decodes to delivered=false with nothing queued. Reading
        // `delivered` alone would park a "Queued" line under the composer of every
        // host older than 3.0 and never take it down.
        c.noteSend(SendKeysResult(ok = true))
        assertEquals(0, c.sendQueue.value.queued)
        assertNull(SendQueue.line(c.sendQueue.value))

        scope.cancel()
    }

    @Test
    fun `the seed carries the reason, so the first line is not the wrong sentence`() = runTest {
        // ⚠ THE TWO SECONDS BEFORE THE FIRST POLL. `noteSend` is what the composer
        // line is drawn from until `/typing` answers, and it recorded a COUNT and
        // nothing else — so a message held because Claude has not started yet was
        // announced as "will send when Claude finishes its turn", a turn that has
        // not begun, and then silently corrected. The daemon says `blockedBy` on the
        // send's own answer since appd 3.1.2.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = controller(scope) { """{"queued":1,"blockedBy":"starting"}""" }

        c.noteSend(SendKeysResult(ok = true, queued = 1, position = 1, delivered = false, blockedBy = "starting"))
        assertEquals("starting", c.sendQueue.value.blockedBy)
        assertEquals(
            "Queued · waiting for Claude to start (1 waiting)",
            SendQueue.line(c.sendQueue.value),
            "the FIRST sentence has to be right; a correction two seconds later is the bug",
        )

        scope.cancel()
    }

    @Test
    fun `an older daemon that says no reason keeps the sentence it always had`() = runTest {
        // Additive on the wire means additive on the screen: `blockedBy` absent
        // decodes to null, and null is the turn sentence, exactly as before.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = controller(scope) { """{"queued":1}""" }

        c.noteSend(SendKeysResult(ok = true, queued = 1, position = 1, delivered = false))
        assertNull(c.sendQueue.value.blockedBy)
        assertEquals(
            "Queued · will send when Claude finishes its turn (1 waiting)",
            SendQueue.line(c.sendQueue.value),
        )

        scope.cancel()
    }

    @Test
    fun `a failed poll keeps the last known state rather than inventing delivery`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var typing = """{"queued":1,"blockedBy":"turn"}"""
        val c = controller(scope) { typing }

        c.noteSend(SendKeysResult(ok = true, queued = 1, position = 1, delivered = false))
        typing = "not json at all"
        assertTrue(c.pollQueueOnce(), "a blip is not evidence of delivery, so it keeps watching")
        assertEquals(
            1,
            c.sendQueue.value.queued,
            "clearing on a failed poll would claim the message landed",
        )

        scope.cancel()
    }

    /**
     * ⚠ THE SEND THAT SAID NOTHING AT ALL. appd 3.5.1 drops an identical human
     * text it already holds (or delivered within 30 s) and answers
     * `duplicate: true` — nothing is queued for this press, so `landed` is true
     * and `noteSend` returned on its very first line. The composer emptied in
     * silence, which is the same screen as a message that vanished: exactly the
     * complaint the whole send queue exists to answer.
     */
    @Test
    fun `a duplicate says the message is already on its way`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = controller(scope) { """{"queued":0}""" }

        c.noteSend(SendKeysResult(ok = true, queued = 0, position = 0, delivered = false, duplicate = true))
        assertEquals(
            "That message is already on its way",
            SendQueue.line(c.sendQueue.value),
            "the composer must say the message is fine, not fall silent",
        )

        // ⚠ AND IT CLEARS. The seed is this client's guess; the daemon's own
        // account of what is pending replaces it, so the line cannot outlive the
        // message it describes.
        assertTrue(!c.pollQueueOnce(), "the poll finds nothing held and stops")
        assertNull(SendQueue.line(c.sendQueue.value))

        scope.cancel()
    }

    /** The sentence is :core's, once, so the two clients cannot drift on it. */
    @Test
    fun `both clients render the same duplicate sentence`() {
        assertEquals(
            com.silencelen.huginn.ui.SendQueue.DUPLICATE,
            SendQueue.line(TypingState(queued = 0, blockedBy = com.silencelen.huginn.ui.SendQueue.DUPLICATE_REASON)),
        )
    }

    // ------------------------- the message that went into somebody's draft (D-7)

    /**
     * ⚠⚠ THE SILENCE THIS ENDS. The walker typed `draft in progress` into the
     * live view without sending it, then sent `say OK2` from this composer. The
     * hold worked — `delivered:false, blockedBy:"draft"`, the right line under
     * the composer — and 28 seconds later the daemon sent it in anyway and the
     * model answered `draft in progresssay OK2`. The desktop said NOTHING: the
     * queued note simply disappeared and a merged user bubble arrived. Decision
     * 59 keeps the ceiling and ends the silence.
     */
    @Test
    fun `a delivery that went into a draft gets a line, once the queue is empty`() {
        val into = IntoDraft(at = 1_700_000_000, waitedMs = 28_000, composer = "draft in progress")
        val note = SendQueue.draftLine(TypingState(queued = 0, intoDraft = into))
        assertNotNull(note)
        assertTrue(note!!.contains("draft in progress"), note)
        assertTrue(note.contains("still typing"), note)
    }

    @Test
    fun `nothing went into a draft, so nothing is said`() {
        assertNull(SendQueue.draftLine(TypingState(queued = 0)))
        assertNull(SendQueue.draftLine(TypingState(queued = 2, blockedBy = "turn")))
    }

    /**
     * `intoDraft` outlives the queue by design — the daemon keeps it an hour so a
     * laptop that was shut still sees it — which means it is present while a
     * LATER message is waiting. A notice raised then describes a delivery whose
     * result the reader has not seen.
     */
    @Test
    fun `the line waits for the queue to drain`() {
        val into = IntoDraft(at = 1_700_000_000, composer = "half a sentence")
        assertNull(SendQueue.draftLine(TypingState(queued = 1, blockedBy = "turn", intoDraft = into)))
        assertNull(SendQueue.draftLine(TypingState(queued = 0, delivering = true, intoDraft = into)))
        assertNotNull(SendQueue.draftLine(TypingState(queued = 0, intoDraft = into)))
    }

    /**
     * The controller half: a SYNCHRONOUS delivery reports the merge on the send's
     * own answer, the notice stands until dismissed, and the next send clears it.
     */
    @Test
    fun `the controller raises the notice from the send's own answer, and a new send clears it`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val c = controller(scope) { """{"queued":0}""" }
            assertNull(c.draftNotice.value)
            c.noteSend(
                SendKeysResult(
                    ok = true, delivered = true, queued = 0,
                    intoDraft = IntoDraft(at = 1_700_000_000, composer = "mid sentence"),
                ),
            )
            val raised = c.draftNotice.value
            assertNotNull(raised, "a delivery that landed on somebody's draft must say so")
            assertTrue(raised!!.contains("mid sentence"), raised)

            // ⚠ THE SAME MERGE IS NOT RAISED TWICE. The daemon keeps reporting it
            // for an hour; a second poll must not re-raise a notice the reader
            // has dismissed.
            c.dismissDraftNotice()
            c.noteSend(SendKeysResult(ok = true, delivered = true, queued = 0,
                intoDraft = IntoDraft(at = 1_700_000_000, composer = "mid sentence")))
            assertNull(c.draftNotice.value, "the same instant, already reported")

            // A NEW merge is a new fact.
            c.noteSend(SendKeysResult(ok = true, delivered = true, queued = 0,
                intoDraft = IntoDraft(at = 1_700_000_099, composer = "later text")))
            assertTrue(c.draftNotice.value?.contains("later text") == true, "${c.draftNotice.value}")

            // And an ordinary send supersedes it.
            c.noteSend(SendKeysResult(ok = true, delivered = true, queued = 0))
            assertNull(c.draftNotice.value)
        } finally {
            scope.cancel()
        }
    }
}
