package com.silencelen.huginn.ui

import com.silencelen.huginn.data.RelayProject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ M1. A RELAY IS NEVER THE READER'S OWN WORDS.
 *
 * The Projects screen's "send to member" button does not travel Claude Code's
 * native peer channel — the daemon pastes a frame into the pane — so the record
 * it leaves behind is a plain `user` one with no `origin`, and the member's
 * transcript drew the OWNER'S relay as the member's own bubble, safety paragraph
 * and all. appd 3.6.0 recognises the frame by its header, re-kinds it `system`
 * and hangs the project on it.
 *
 * Two things are asserted: the attribution sentence (pure), and that the row is
 * drawn as a note whatever `kind` says — because a row that names a project came
 * from somewhere else by definition, and a cache, an older daemon or a transcript
 * on disk can still hand over the old `kind`.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ProjectRelayRowTest {

    @Test
    fun `the attribution names the project and the sender`() {
        assertEquals(
            "lora-stick · from lora-stick/lead",
            relayAttribution(RelayProject(id = "p1", name = "lora-stick", from = "lora-stick/lead")),
        )
    }

    /**
     * ⚠ A TRANSCRIPT WRITTEN BEFORE THE HEADER NAMED A PROJECT still produces
     * this row, with the ids null. The line shortens to the sender rather than
     * inventing a project name — a made-up attribution is worse than a short one.
     */
    @Test
    fun `an older frame names the sender and claims no project`() {
        assertEquals(
            "from rv-proj/lead",
            relayAttribution(RelayProject(id = null, name = null, from = "rv-proj/lead")),
        )
        assertEquals(
            "from rv-proj/lead",
            relayAttribution(RelayProject(name = "   ", from = "rv-proj/lead")),
        )
    }

    @Test
    fun `no project row means no attribution line`() {
        assertNull(relayAttribution(null))
    }

    @Test
    fun `a project with no sender still names itself`() {
        // The header cannot match without a sender, so this is defensive rather
        // than expected — and an empty "· from " is the one thing it must not be.
        assertEquals("lora-stick", relayAttribution(RelayProject(name = "lora-stick", from = "")))
    }

    /**
     * The row kind. A source grep for the same reason `ConfirmTitleTest` and
     * `ScrollOwnerTest` are: there is no compose-ui-test in this module, and the
     * regression is a `when` branch going back to a bubble.
     */
    @Test
    fun `a row carrying a project is never drawn as a user bubble`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root")
        val text = File(root, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/TranscriptView.kt").readText()
        assertTrue(text.length > 2_000, "wrong file: ${text.length} chars")
        assertTrue(
            """"user" -> if (ev.project != null) ProjectRelayNote(ev)""" in text,
            "a user record carrying a project must be drawn as a note, not as the reader's own words",
        )
        assertTrue(
            """"system" -> if (ev.project != null) ProjectRelayNote(ev)""" in text,
            "and the system row must carry its attribution",
        )
    }
}
