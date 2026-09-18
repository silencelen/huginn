package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.ProjectMember
import com.silencelen.huginn.data.SpawnFailure
import com.silencelen.huginn.data.SpawnOutcome
import com.silencelen.huginn.data.SpawnResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the manifest card says after Spawn — and it is never "it worked" or "it
 * failed", because the daemon rarely answers either.
 *
 * ⚠⚠ `ok=false` ARRIVES ON A 200 AND IS THE NORMAL CASE. Spawning is a loop over
 * tmux: the second of three roles failing does not un-spawn the first, so the
 * daemon carries on, makes the rest, and answers with BOTH lists. Read as a
 * verdict, the status code reports a working cluster as a failure; collapsed to
 * one boolean, the card loses which role to retry — and "which one" is the entire
 * content of the message.
 *
 * ⚠⚠ AND THE 409s ARE STATES OF THE HOUSE, NOT ERRORS. The headroom arbiter's
 * STOP sentinel and a manifest that moved under the card both come back as
 * sentences with the fix inside them. Summarised, "there is no room on this
 * account right now (weekly_all at 94%)" becomes "could not spawn", which is a
 * dead end — so the whole path from the wire to the card is asserted VERBATIM.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ProjectSpawnNoteTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(status: HttpStatusCode, body: String): HuginnClient = HuginnClient(
        baseUrlProvider = { "http://stub" },
        tokenProvider = { "t" },
        engine = MockEngine { respond(body, status, json) },
    )

    private fun member(role: String) = ProjectMember(
        role = role,
        name = "statusflap-$role",
        claudeName = "statusflap/$role",
    )

    @Test
    fun `a partial spawn names every role that did not start, in the daemon's own words`() {
        val outcome = SpawnOutcome(
            SpawnResult(
                ok = false,
                spawned = listOf(member("db"), member("web")),
                failed = listOf(
                    SpawnFailure("probe", "duplicate session: statusflap-probe"),
                    SpawnFailure("docs", "persona could not be written"),
                ),
            ),
            refusal = null,
        )
        val note = spawnOutcomeNote(outcome)!!
        // The headline reads as PARTIAL — not as a failure, which would send
        // somebody looking for two sessions that are sitting there working.
        assertEquals("2 of 4 started · 2 failed", note.lineSequence().first())
        assertTrue(
            note.contains("probe — duplicate session: statusflap-probe"),
            "a duplicate name and an unwritable persona are different problems: $note",
        )
        assertTrue(note.contains("docs — persona could not be written"), note)
        assertEquals(3, note.lines().size, "one headline, one line per failed role: $note")
    }

    @Test
    fun `everything coming up says nothing at all`() {
        val outcome = SpawnOutcome(
            SpawnResult(ok = true, spawned = listOf(member("db"), member("web")), failed = emptyList()),
            refusal = null,
        )
        // The members appearing in the tree IS the message. A banner that said
        // "2 members started" would be a notice to dismiss on the happy path.
        assertNull(spawnOutcomeNote(outcome), "success needs no sentence")
        assertNull(spawnOutcomeNote(null))
    }

    @Test
    fun `the STOP sentinel comes off the wire and onto the card unchanged`() = runTest {
        val refusal = "there is no room on this account right now (weekly_all at 94%); " +
            "spawning twelve sessions into a red window is how a cluster dies half-born"
        val body = """{"error":${quoted(refusal)}}"""
        val outcome = client(HttpStatusCode.Conflict, body).spawnProject("p1", 2)

        // A 409 is an ANSWER here, not a throw — reaching the card as a thrown
        // error would put it on the status bar with no manifest attached.
        assertEquals(refusal, outcome.refusal, "the daemon's sentence is the whole fix")
        assertEquals(refusal, spawnOutcomeNote(outcome), "and the card says it verbatim")
    }

    @Test
    fun `a stale manifest rev is refused with the project that is actually on offer`() = runTest {
        val refusal = "that proposal has been revised; read it again before approving"
        val project = """{"id":"p1","name":"Status page flap","slug":"statusflap","status":"proposed","rev":9}"""
        val body = """{"error":${quoted(refusal)},"project":$project}"""
        val outcome = client(HttpStatusCode.Conflict, body).spawnProject("p1", 1)
        assertEquals(refusal, outcome.refusal)
        assertEquals(refusal, spawnOutcomeNote(outcome))
        // ⚠ THE REV IS WHY A CARD ON A LOCK SCREEN CANNOT APPROVE A REVISION, and
        // the current project rides along so the card can redraw around the plan
        // that is actually on offer.
        assertEquals(9, outcome.project?.rev, "the refusal carries the project as it now stands")
    }

    @Test
    fun `an untrusted working directory is refused with the sentence that fixes it`() = runTest {
        val refusal = "open /root/dev-ledger once with `claude` there and accept the folder-trust " +
            "question, then create the project"
        val made = client(HttpStatusCode.Conflict, """{"error":${quoted(refusal)}}""")
            .createProject("LoRa stick", "hardware", "bring up the radio", "/root/dev-ledger")
        assertEquals(refusal, made.refusal, "shown under the field, with everything typed still in it")
        assertEquals(null, made.project)
    }

    private fun quoted(s: String): String = "\"" + s.replace("\"", "\\\"") + "\""
}
