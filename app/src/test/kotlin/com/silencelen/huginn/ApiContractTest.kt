package com.silencelen.huginn

import com.silencelen.huginn.data.ChatList
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.SessionList
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.data.TranscriptPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes real huginn-appd 2.0.0 responses.
 *
 * The fixtures in `src/test/resources` were captured from the live daemon on
 * 2026-07-27 and then scrubbed: every key and every value whose shape matters
 * (numbers, booleans, enum-like strings) is untouched, and free text is replaced
 * with a placeholder, so no session content lives in the repo.
 *
 * This is the only automated check that the app and the daemon still agree on
 * the wire format. A renamed server field is otherwise invisible until the app
 * silently shows an empty screen on a phone this host cannot run.
 */
class ApiContractTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test
    fun `sessions decode with the v2 fields the list depends on`() {
        val list = json.decodeFromString<SessionList>(fixture("sessions.json"))
        assertTrue("expected at least one live session", list.sessions.isNotEmpty())
        val s = list.sessions.first()
        assertTrue("name must survive", s.name.isNotBlank())
        // Geometry drives the resize request; 0 would mean the field was renamed.
        assertTrue("cols must decode", s.cols > 0)
        assertTrue("rows must decode", s.rows > 0)
        assertNotNull("windowSize must decode", s.windowSize)
        assertTrue("activityAt must decode", s.activityAt > 0)
        // At least one session on the capture host had a recorded Claude state.
        assertTrue(
            "no session carried a state; the hook mapping may have broken",
            list.sessions.any { it.state != null },
        )
        assertTrue(
            "no session carried a transcript; the session view would be empty",
            list.sessions.any { it.hasTranscript },
        )
    }

    @Test
    fun `a screen decodes with geometry, hash and cursor`() {
        val s = json.decodeFromString<Screen>(fixture("screen.json"))
        assertTrue(s.width > 0)
        assertTrue(s.height > 0)
        assertEquals("one captured line per pane row", s.height, s.lines.size)
        assertNotNull("hash drives long polling", s.hash)
        assertTrue(s.cursorX >= 0 && s.cursorY >= 0)
    }

    @Test
    fun `a transcript decodes into the event kinds the renderer handles`() {
        val p = json.decodeFromString<TranscriptPage>(fixture("transcript.json"))
        assertTrue("expected events", p.events.isNotEmpty())
        assertTrue("nextOffset drives tailing", p.nextOffset > 0)
        val known = setOf("user", "assistant", "thinking", "tool", "tool_result", "system")
        p.events.forEach {
            assertTrue("unhandled event kind '${it.kind}'", it.kind in known)
        }
        // A tool event must carry its name, else the card renders blank.
        p.events.filter { it.kind == "tool" }.forEach {
            assertTrue("tool event without a name", !it.name.isNullOrBlank())
        }
    }

    @Test
    fun `status decodes the fields the status screen shows`() {
        val s = json.decodeFromString<Status>(fixture("status.json"))
        assertNotNull(s.host)
        assertNotNull(s.claude)
        assertNotNull(s.mempalace)
        assertNotNull(s.disk)
        assertTrue(s.uptimeSec > 0)
        assertTrue(s.cores > 0)
        assertEquals(3, s.load.size)
    }

    @Test
    fun `the chat list decodes even when empty`() {
        val c = json.decodeFromString<ChatList>(fixture("chats.json"))
        assertNotNull(c.chats)
    }

    @Test
    fun `an unknown future field does not fail the decode`() {
        // Forward compatibility: a newer daemon must not break an older app.
        val s = json.decodeFromString<Screen>("""{"width":80,"height":24,"somethingNew":true}""")
        assertEquals(80, s.width)
    }
}
