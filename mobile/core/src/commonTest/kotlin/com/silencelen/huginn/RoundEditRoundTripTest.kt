package com.silencelen.huginn

import com.silencelen.huginn.data.Round
import com.silencelen.huginn.ui.toDraft
import com.silencelen.huginn.ui.toSchedule
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Opening a Round in the editor and saving it unchanged must change nothing.
 *
 * Payloads below are REAL — captured from a running daemon, not hand-written —
 * because the last class of bug that got through was one where every unit test
 * passed against invented data.
 */
class RoundEditRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val LONDON_DAILY = """
    {"v":1,"id":"r1","title":"cap","prompt":"p","goal":"","enabled":true,"mode":"ask",
     "host":"local","model":null,"effort":null,
     "schedule":{"kind":"daily","at":"07:30","tz":"Europe/London"},
     "notifyWhen":"attention","catchUp":false,"timeoutSec":900,
     "createdAt":1787600000,"updatedAt":1787600000,"nextRunAt":1787620000000,
     "currentChatId":null,"lastRun":null,"runs":[],
     "cadence":"Every day at 7:30 AM","running":false,"hostName":null}
    """.trimIndent()

    private val ON_DEVICE = """
    {"v":1,"id":"r2","title":"cap","prompt":"p","goal":"g","enabled":true,"mode":"act",
     "host":"938f793d-450b-4bb2-8399-3740baf1b7d3","model":null,"effort":null,
     "schedule":{"kind":"weekly","at":"19:00","tz":"America/Los_Angeles","days":[0,3]},
     "notifyWhen":"always","catchUp":false,"timeoutSec":900,
     "createdAt":1787600000,"updatedAt":1787600000,"nextRunAt":1787620000000,
     "currentChatId":null,"lastRun":null,"runs":[],
     "cadence":"Sundays and Wednesdays at 7:00 PM","running":false,"hostName":"capdev"}
    """.trimIndent()

    @Test
    fun aRealPayloadParsesWithNothingLost() {
        val r = json.decodeFromString<Round>(ON_DEVICE)
        assertEquals("938f793d-450b-4bb2-8399-3740baf1b7d3", r.host)
        assertEquals("capdev", r.hostName)
        assertEquals("g", r.goal)
        assertEquals("act", r.mode)
        assertEquals(listOf(0, 3), r.schedule.days)
    }

    @Test
    fun editingARoundFromANOTHERTIMEZONEDoesNotMoveIt() {
        // THE ONE THAT MATTERS. A Round set for 07:30 London, opened on a phone in
        // Los Angeles and saved without touching the time, must still be 07:30
        // London. The draft is the only thing between the two, so if it does not
        // carry the zone the save quietly reschedules the job by eight hours —
        // and every surface would keep showing a correct-looking "7:30".
        val r = json.decodeFromString<Round>(LONDON_DAILY)
        val phoneZone = "America/Los_Angeles"
        val saved = r.toDraft().toSchedule(phoneZone)
        assertEquals("Europe/London", saved.tz, "the editor moved the Round to the phone's zone")
        assertEquals("07:30", saved.at)
    }

    @Test
    fun aSaveWithNoEditsIsAnIdentity() {
        for (raw in listOf(LONDON_DAILY, ON_DEVICE)) {
            val r = json.decodeFromString<Round>(raw)
            val saved = r.toDraft().toSchedule("America/Los_Angeles")
            assertEquals(r.schedule.kind, saved.kind)
            assertEquals(r.schedule.at, saved.at)
            assertEquals(r.schedule.tz, saved.tz, "zone changed for ${r.schedule.kind}")
            assertEquals(r.schedule.days, saved.days)
            assertEquals(r.schedule.dates, saved.dates)
            assertEquals(r.schedule.everyMinutes, saved.everyMinutes)
        }
    }
}
