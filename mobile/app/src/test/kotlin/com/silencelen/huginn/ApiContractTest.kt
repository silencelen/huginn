package com.silencelen.huginn

import com.silencelen.huginn.data.ChatList
import com.silencelen.huginn.data.DeviceList
import com.silencelen.huginn.data.RoundList
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
    // ---------------------------------------------------------- Rounds + Devices
    //
    // ⚠ CAPTURED 2026-08-25, because until then this file's fixtures predated both
    // features. Its own KDoc says this is "the only automated check that the app
    // and the daemon still agree on the wire format" — and the two newest features,
    // the ones most likely to have a field renamed, were the two it did not cover.

    @Test
    fun `rounds decode with the fields the list and the report depend on`() {
        val list = json.decodeFromString<RoundList>(fixture("rounds.json"))
        assertTrue("expected at least one round", list.rounds.isNotEmpty())
        val r = list.rounds.first()
        assertTrue("title must survive", r.title.isNotBlank())
        // The daemon renders the cadence; the clients never re-derive it, so an
        // empty one here is a blank line under every row.
        assertTrue("cadence must decode", r.cadence.isNotBlank())
        assertNotNull("schedule must decode", r.schedule)
        assertTrue("nextRunAt drives the countdown", r.nextRunAt != null && r.nextRunAt!! > 0)

        val run = r.lastRun
        assertNotNull("a round that has run must decode its lastRun", run)
        assertTrue("status must decode", run!!.status.isNotBlank())
        assertTrue("the headline IS the notification", run.headline.isNotBlank())
        assertTrue("at is epoch SECONDS", run.at > 0)
        // Added 2026-08-25; a rename would silently take the row back to showing
        // the capped count as if it were the whole story.
        assertTrue("itemsTotal must decode", run.itemsTotal >= run.items.size)
        // Added 2026-08-25; a rename means Mark done stops sticking, with no error.
        assertNotNull("acknowledgedAt must decode", run.acknowledgedAt)
        run.items.firstOrNull()?.let {
            assertTrue("an item's next step is what Carry on is built from", it.suggest.isNotBlank())
        }
    }

    @Test
    fun `devices decode with the scope fields the controls depend on`() {
        val list = json.decodeFromString<DeviceList>(fixture("devices.json"))
        assertTrue("expected at least one device", list.devices.isNotEmpty())
        val d = list.devices.first()
        assertTrue("name must survive", d.name.isNotBlank())
        assertTrue("platform must decode", d.platform.isNotBlank())
        assertTrue("scope must decode", d.scope.isNotBlank())
        // ⚠ effectiveScope is what enables "Act here", not the enrolled scope. A
        // rename would leave the button reading the wrong field, which is the one
        // place on this screen where being wrong widens what a machine will do.
        assertTrue("effectiveScope must decode", d.effectiveScope.isNotBlank())
        assertEquals(
            "every device must report an effectiveScope",
            list.devices.size,
            list.devices.count { it.effectiveScope.isNotBlank() },
        )
    }

}