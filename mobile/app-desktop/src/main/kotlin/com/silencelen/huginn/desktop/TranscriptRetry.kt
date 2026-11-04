package com.silencelen.huginn.desktop

import com.silencelen.huginn.data.Backoff
import com.silencelen.huginn.data.Session

/**
 * How soon a session's transcript is asked for again after a read that found none.
 *
 * ⚠⚠ A NEW SESSION SAT ON "No conversation yet" FOR ABOUT 70 s (desktop 2.10.0,
 * 2026-11-03): made from New session, first prompt sent, the daemon serving its
 * transcript within seconds — and this client still waiting out [Backoff.transcript]'s
 * 5, 10, 20, 40 s between reads. That ladder exists for a plain shell, which answers
 * 409 for as long as the view is open, and it is right there. It is wrong for a
 * session whose Claude Code is up and about to write its first record, and the
 * daemon's 409 is the same sentence for both ("no transcript recorded for this
 * session yet"), so the read alone cannot tell them apart.
 *
 * The sessions list can. Claude Code's hooks write the session's state record from
 * its first event (SessionStart), with the Claude session id the transcript will be
 * named by; a plain shell has neither. So a 409 from a session whose row carries
 * both is "not yet", asked again at the healthy tick; anything else keeps the
 * ladder — and a laddered wait ends the moment the list says Claude has come up,
 * which is how a session opened before its Claude started still catches its first
 * prompt within a tick.
 *
 * The phone tails at a flat interval and never had the ladder, which is why this
 * lives with the one client that did.
 */
internal object TranscriptRetry {

    /** The daemon's answer for a session with no transcript on disk (yet). */
    const val NO_TRANSCRIPT: Int = 409

    /**
     * Claude Code is up in this session's pane: a hook has recorded a state for it,
     * and the Claude session id its transcript will be named by. A plain shell has
     * no record, and a Claude that has exited removed it (SessionEnd).
     */
    fun claudeUp(row: Session?): Boolean = row?.state != null && row.claudeSessionId != null

    /**
     * The wait before the next read.
     *
     * @param failures consecutive failed reads, this one included; 0 when it landed.
     * @param code the HTTP status the failed read was answered with, or null (it
     *   landed, or it failed without an answer — a network error is not "not yet").
     * @param row this session's row in the app's sessions list, or null.
     */
    fun delayMs(failures: Int, code: Int?, row: Session?): Long =
        if (failures > 0 && code == NO_TRANSCRIPT && claudeUp(row)) Backoff.TRANSCRIPT_MS
        else Backoff.transcript(failures)

    /**
     * Whether a row read during a laddered wait ends it early: the wait began on
     * [before], and [now] says Claude has come up since. Only that change — a row
     * that already said so when the wait began would end every wait at once.
     */
    fun wakes(before: Session?, now: Session?): Boolean = !claudeUp(before) && claudeUp(now)
}
