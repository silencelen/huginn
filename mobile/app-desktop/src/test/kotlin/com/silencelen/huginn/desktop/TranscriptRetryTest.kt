package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.Backoff
import com.silencelen.huginn.data.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule behind the transcript tail's wait: "not yet" is asked again at the
 * healthy tick, a plain shell keeps the ladder. The wiring — that the loop really
 * waits what this says and wakes when it should — is [NewSessionTranscriptTest].
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class TranscriptRetryTest {

    /** Claude Code up in the pane, no transcript on disk: a new session before its first prompt. */
    private val fresh = Session(name = "s1", state = "idle", claudeSessionId = "cs-1", hasTranscript = false)

    /** A plain shell: no hook has ever recorded anything. */
    private val shell = Session(name = "s1")

    /** A host whose hook could only write the bare state word (no jq): no session id, so never a transcript. */
    private val bareWord = Session(name = "s1", state = "idle")

    @Test
    fun `not yet from a session whose Claude is up is asked again at the healthy tick`() {
        for (failures in listOf(1, 2, 3, 5, 40)) {
            assertEquals(Backoff.TRANSCRIPT_MS, TranscriptRetry.delayMs(failures, 409, fresh), "after $failures failures")
        }
        // Working or asking is still up.
        assertEquals(Backoff.TRANSCRIPT_MS, TranscriptRetry.delayMs(3, 409, fresh.copy(state = "running")))
        assertEquals(Backoff.TRANSCRIPT_MS, TranscriptRetry.delayMs(3, 409, fresh.copy(state = "attention")))
    }

    @Test
    fun `a plain shell keeps the ladder`() {
        for (failures in 1..6) {
            assertEquals(Backoff.transcript(failures), TranscriptRetry.delayMs(failures, 409, shell), "after $failures failures")
        }
        assertEquals(5_000, TranscriptRetry.delayMs(1, 409, shell))
        assertEquals(60_000, TranscriptRetry.delayMs(6, 409, shell))
    }

    @Test
    fun `so does a session the list does not have, and one with no session id`() {
        assertEquals(Backoff.transcript(2), TranscriptRetry.delayMs(2, 409, null))
        assertEquals(Backoff.transcript(2), TranscriptRetry.delayMs(2, 409, bareWord))
    }

    @Test
    fun `only the daemon's not-yet is a reason to ask again soon`() {
        // A network failure, a gone session and a broken daemon are not "not yet",
        // whatever the list last said.
        assertEquals(Backoff.transcript(3), TranscriptRetry.delayMs(3, null, fresh))
        assertEquals(Backoff.transcript(3), TranscriptRetry.delayMs(3, 404, fresh))
        assertEquals(Backoff.transcript(3), TranscriptRetry.delayMs(3, 500, fresh))
    }

    @Test
    fun `a read that landed waits the healthy tick whatever the row says`() {
        assertEquals(Backoff.TRANSCRIPT_MS, TranscriptRetry.delayMs(0, null, shell))
        assertEquals(Backoff.TRANSCRIPT_MS, TranscriptRetry.delayMs(0, null, fresh))
    }

    @Test
    fun `a laddered wait ends only when Claude comes up`() {
        assertTrue(TranscriptRetry.wakes(shell, fresh), "Claude started in the pane")
        assertTrue(TranscriptRetry.wakes(null, fresh), "the session reached the list already up")
        // Already up when the wait began: waking on that would end every wait at once.
        assertFalse(TranscriptRetry.wakes(fresh, fresh))
        assertFalse(TranscriptRetry.wakes(fresh, fresh.copy(state = "running", hasTranscript = true)))
        assertFalse(TranscriptRetry.wakes(shell, shell))
        assertFalse(TranscriptRetry.wakes(shell, bareWord))
        assertFalse(TranscriptRetry.wakes(fresh, shell), "Claude exiting is not a reason to read")
    }
}
