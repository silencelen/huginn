package com.silencelen.huginn

import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.mergeTranscriptPage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tail read only reports session-level fields whose records happen to fall
 * inside its window, so every one of them has to be carried forward.
 *
 * Each field here is something a screen reads and something that used to revert
 * to null seconds after a view opened, because the phone hand-rolled this merge
 * and its copy carried fewer fields than the shared one:
 *
 *  * `state` drives the composer's Send/Stop control, and is the LIVE source on a
 *    folded phone where the sessions list is not composed and its own copy freezes;
 *  * `modelDisplay` is the model control's label — dropped, it falls back to a
 *    placeholder;
 *  * `mode` and `claudeSessionId` identify what the session is and which Claude
 *    conversation it belongs to.
 */
class TailCarryForwardTest {

    @Test
    fun `a tail read that mentions none of them keeps what the view already had`() {
        val established = TranscriptPage(
            state = "running",
            modelDisplay = "Opus 5",
            mode = "auto",
            claudeSessionId = "abc-123",
            effort = "high",
            title = "the session",
            gitBranch = "main",
            cwd = "/root/netplan",
        )
        // What a tail read looks like when its window held only assistant text.
        val tail = TranscriptPage()

        val merged = mergeTranscriptPage(established, tail)

        assertEquals("running", merged.state, "the Send/Stop control reads this")
        assertEquals("Opus 5", merged.modelDisplay, "the model control reads this")
        assertEquals("auto", merged.mode)
        assertEquals("abc-123", merged.claudeSessionId)
        assertEquals("high", merged.effort)
        assertEquals("the session", merged.title)
        assertEquals("main", merged.gitBranch)
        assertEquals("/root/netplan", merged.cwd)
    }

    @Test
    fun `a fresher value always wins over the carried one`() {
        val merged = mergeTranscriptPage(
            TranscriptPage(state = "running", modelDisplay = "Opus 5"),
            TranscriptPage(state = "idle", modelDisplay = "Haiku 4.5"),
        )
        assertEquals("idle", merged.state)
        assertEquals("Haiku 4.5", merged.modelDisplay)
    }
}
