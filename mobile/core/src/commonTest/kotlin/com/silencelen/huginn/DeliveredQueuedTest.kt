package com.silencelen.huginn

import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.mergeTranscriptPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A message typed while Claude is busy is queued, and its enqueue and delivery
 * land in different tail windows on nearly every send-while-busy. The daemon
 * used to re-send the whole message on delivery, which put a second identical
 * bubble on screen and left the first badged as still-waiting; it now reports
 * the delivery instead, and this is the half that acts on it.
 */
class DeliveredQueuedTest {

    private fun user(seq: Int, text: String, queued: Boolean = false) =
        TranscriptEvent(seq = seq, kind = "user", text = text, queued = queued)

    @Test
    fun `a delivered message loses its badge without gaining a second bubble`() {
        val current = TranscriptPage(events = listOf(user(0, "first"), user(1, "also fix the header", queued = true)))
        val page = TranscriptPage(events = emptyList(), deliveredQueued = listOf("also fix the header"))

        val merged = mergeTranscriptPage(current, page)

        assertEquals(2, merged.events.size, "no bubble may be added or removed")
        assertEquals(1, merged.events.count { it.text == "also fix the header" })
        assertTrue(merged.events.none { it.queued }, "the badge is no longer true")
    }

    @Test
    fun `only as many copies are cleared as were delivered`() {
        // Sending the same text twice while busy queues two, and the daemon
        // dequeues them one at a time. Clearing both on the first delivery would
        // claim a message had landed when it had not.
        val current = TranscriptPage(
            events = listOf(user(0, "ok", queued = true), user(1, "ok", queued = true)),
        )
        val page = TranscriptPage(deliveredQueued = listOf("ok"))

        val merged = mergeTranscriptPage(current, page)

        assertEquals(1, merged.events.count { it.queued }, "the second copy is still waiting")
        assertEquals(false, merged.events.first().queued, "and the OLDEST is the one delivered")
    }

    @Test
    fun `a page from an older daemon leaves badges alone`() {
        // deliveredQueued defaults to empty, so a daemon that does not send it
        // must not be read as "everything was delivered".
        val current = TranscriptPage(events = listOf(user(0, "waiting", queued = true)))
        val merged = mergeTranscriptPage(current, TranscriptPage())
        assertTrue(merged.events.single().queued)
    }
}
