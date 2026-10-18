package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ProjectPeer
import com.silencelen.huginn.data.TranscriptEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A note with a body opens like a thinking block: another session's report, the
 * summary after a compaction. Its label is who sent it or what it is, and a
 * quote of the row takes the whole body rather than the clipped headline.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class FoldRowTest {

    private val report = TranscriptEvent(
        seq = 3, kind = "system", text = "Message from Explore: I could only do part of this.",
        peer = ProjectPeer(name = "Explore"), body = "I could only do part of this.\n\n- S0: not run.", fold = "peer",
    )

    @Test
    fun `a peer fold is labelled by its sender, not by the headline that repeats it`() {
        assertEquals("Message from Explore", foldLabel(report))
    }

    @Test
    fun `the compaction summary keeps the daemon's words`() {
        val summary = TranscriptEvent(
            seq = 4, kind = "system", text = "Summary of the earlier conversation",
            body = "This session is being continued…", fold = "compact",
        )
        assertEquals("Summary of the earlier conversation", foldLabel(summary))
    }

    @Test
    fun `quoting a folded note takes the whole body`() {
        assertEquals("I could only do part of this.\n\n- S0: not run.", rowSelectionText(report))
    }

    @Test
    fun `quoting a pasted message takes the words without the tags`() {
        val pasted = TranscriptEvent(
            seq = 5, kind = "user",
            text = "\n\n<pasted_content id=\"d842\">\nbug: empty chats\nbug: projects\n</pasted_content id=\"d842\">\n",
        )
        assertEquals("bug: empty chats\nbug: projects", rowSelectionText(pasted))
    }

    @Test
    fun `a note without a body quotes its line, as before`() {
        assertEquals("stick/lead is idle", rowSelectionText(TranscriptEvent(seq = 6, kind = "system", text = "stick/lead is idle")))
    }
}
