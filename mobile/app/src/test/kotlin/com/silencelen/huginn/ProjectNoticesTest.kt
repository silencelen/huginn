package com.silencelen.huginn

import com.silencelen.huginn.notify.ProjectNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proposal notification, and the one rule it exists to keep.
 *
 * A project's card has THREE verbs — Spawn · Edit · Discard — and only two may
 * ever reach a lock screen. Spawn and Discard are bounded choices about a plan
 * the daemon has already written down; Edit is an editor, and there is no such
 * thing as a bounded editor on a locked phone. The owner's rule
 * ("notification questions = bounded choices ONLY") is therefore not satisfied by
 * filtering Edit out somewhere — it is satisfied by the shade never being able to
 * carry it.
 */
class ProjectNoticesTest {

    private val payload =
        """{"projectId":"6f0d2c41-0000-4000-8000-0000000000a1","manifestRev":"2","name":"LoRa sensor stick"}"""

    @Test
    fun `a proposal carries Spawn and Discard, and nothing else`() {
        val n = ProjectNotices.fromPush(
            kind = ProjectNotices.KIND_PROPOSED,
            title = "LoRa sensor stick has a proposal",
            text = "two sessions: docs writes the README, fw brings up the radio",
            subject = null,
            payload = payload,
        )
        assertNotNull(n)
        val actions = n!!.actions
        assertEquals("exactly two buttons", 2, actions.size)
        assertEquals(listOf(ProjectNotices.LABEL_SPAWN, ProjectNotices.LABEL_DISCARD), actions.map { it.label })
        assertEquals(listOf(ProjectNotices.VERB_SPAWN, ProjectNotices.VERB_DISCARD), actions.map { it.verb })
        // The rev the card was DRAWN from travels with the button, so a
        // notification that sat on a lock screen while the lead revised its plan
        // cannot approve the revision.
        assertTrue(actions.all { it.manifestRev == 2 })
        assertTrue(actions.all { it.projectId == "6f0d2c41-0000-4000-8000-0000000000a1" })
    }

    /**
     * ⚠⚠ THE WIRE MAY NOT NAME THESE BUTTONS.
     *
     * `HeadroomNotices` lets the daemon supply labels positionally, which is safe
     * when both verbs are ways of saying "I have seen this". It is not safe here:
     * a payload carrying `["Edit","Discard"]` would draw a button reading Edit
     * that SPAWNS TWELVE SESSIONS. So the labels are constants and the daemon's
     * `options` are read for nothing — asserted by handing it the worst payload
     * anybody could send.
     */
    @Test
    fun `Edit cannot reach the shade, however the wire asks`() {
        val hostile = ProjectNotices.fromPush(
            kind = ProjectNotices.KIND_PROPOSED,
            title = "Edit this proposal",
            text = "tap Edit to change the roles",
            subject = null,
            payload = """{"projectId":"p1","manifestRev":"1","options":["Edit","Discard","Spawn"]}""",
        )
        assertNotNull(hostile)
        val labels = hostile!!.actions.map { it.label }
        assertEquals(listOf("Spawn", "Discard"), labels)
        assertFalse("Edit must never be a button", labels.any { it.equals("Edit", ignoreCase = true) })
        assertFalse("and never a verb", hostile.actions.any { it.verb.contains("edit", ignoreCase = true) })
        // The bounded set itself, so a third verb cannot be added without this
        // test being edited on purpose.
        assertEquals(setOf("spawn", "discard"), ProjectNotices.VERBS)
    }

    /**
     * The four load-bearing nulls.
     *
     * A reply box is what makes free text reachable from the shade; answer
     * options plus a fingerprint are what make a pane KEYSTROKE reachable. A
     * proposal needs neither, so carrying either would be a door opened for
     * nothing. `session` null matters too: the tap must open the PROJECT.
     */
    @Test
    fun `a proposal notification has no reply box and no pane answers`() {
        val n = ProjectNotices.fromPush(
            ProjectNotices.KIND_PROPOSED, "", "", null, payload,
        )!!
        val a = ProjectNotices.postArgs(n)
        assertNull("free text must not be reachable from the shade", a.replyChat)
        assertTrue("a proposal answers no pane dialog", a.answers.isEmpty())
        assertNull(a.fingerprint)
        assertNull("the tap opens the project, not a session", a.session)
        assertEquals("6f0d2c41-0000-4000-8000-0000000000a1", a.project)
        // Its own slot, so a proposal cannot replace a "needs you" whose session
        // happens to hash to the same id.
        assertEquals("project:6f0d2c41-0000-4000-8000-0000000000a1", a.key)
    }

    /** Anything that is not a proposal is not this object's business. */
    @Test
    fun `other kinds are left alone`() {
        assertNull(ProjectNotices.fromPush("session_attention", "t", "x", "jtyper", null))
        assertNull(ProjectNotices.fromPush("headroom_downgraded", "t", "x", "jtyper", null))
        // And a proposal with no project id is refused rather than drawn: its
        // buttons would address nothing.
        assertNull(ProjectNotices.fromPush(ProjectNotices.KIND_PROPOSED, "t", "x", null, null))
    }

    /**
     * The daemon's own sentence wins when it sent one — it knows the project's
     * name and what was proposed — and ours fills in when it did not.
     */
    @Test
    fun `the daemon's words are kept, and a silent push still says something`() {
        val spoken = ProjectNotices.fromPush(
            ProjectNotices.KIND_PROPOSED, "Status page flap", "four sessions", null,
            """{"projectId":"p1","manifestRev":"3"}""",
        )!!
        assertEquals("Status page flap", spoken.title)
        assertEquals("four sessions", spoken.text)

        val silent = ProjectNotices.fromPush(
            ProjectNotices.KIND_PROPOSED, "", "", null,
            """{"projectId":"p1","manifestRev":"3","name":"LoRa sensor stick"}""",
        )!!
        assertTrue(silent.title.contains("LoRa sensor stick"))
        assertTrue(silent.text.isNotBlank())
    }
}
