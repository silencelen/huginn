package com.silencelen.huginn

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.data.ChatFast
import com.silencelen.huginn.data.ClaudeSettings
import com.silencelen.huginn.data.LimitWindow
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.data.SessionContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The status-line readings on a session and a screen (appd 3.14.0), and the
 * two things about them that have to hold on a fleet of daemons of mixed age:
 * an older daemon's row still decodes with every new block null, and the one
 * field whose spelling the contract left open cannot fail the decode.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ContextModelsTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val row = """{
        "name": "alex", "contextPercent": 3,
        "context": {"used": 28700, "window": 1000000, "percent": 3, "exceeds200k": false},
        "model": "Haiku 5.5", "effort": "medium",
        "fast": {"on": false, "available": true, "reason": null},
        "limits": {"fiveHour": {"usedPercent": 5, "resetsAt": 1792830600}, "sevenDay": {"usedPercent": 1, "resetsAt": 1793001600}, "spend": null},
        "cost": {"usd": 0.00345736, "durationMs": 37177, "apiMs": 10015, "linesAdded": 0, "linesRemoved": 0}
    }"""

    @Test
    fun `a session row carries the status line's readings`() {
        val s = json.decodeFromString<Session>(row)
        assertEquals(28_700L, s.context?.used)
        assertEquals(1_000_000L, s.context?.window)
        assertEquals(3, s.context?.percent)
        assertEquals(false, s.context?.exceeds200k)
        assertEquals("Haiku 5.5", s.model)
        assertEquals("medium", s.effort)
        assertEquals(false, s.fast?.on)
        assertEquals(true, s.fast?.available)
        assertNull(s.fast?.reason)
        assertEquals(5.0, s.limits?.fiveHour?.usedPercent)
        assertEquals(1_792_830_600_000L, s.limits?.fiveHour?.resetsAtEpochMs(), "the status line stamps epoch seconds")
        assertNull(s.limits?.spend)
        assertEquals(37_177L, s.cost?.durationMs)
        assertEquals(3, s.contextPercent, "and the old reading is still served beside the new block")
    }

    @Test
    fun `a screen carries the same blocks`() {
        val s = json.decodeFromString<Screen>(row.replace("\"name\": \"alex\",", ""))
        assertEquals(28_700L, s.context?.used)
        assertEquals("Haiku 5.5", s.model)
        assertEquals("medium", s.effort)
        assertEquals(false, s.fast?.on)
        assertEquals(0.00345736, s.cost?.usd)
    }

    /** Every new block is nullable with a default, so a 3.13 daemon's row still reads. */
    @Test
    fun `an older daemon's row decodes with every new block absent`() {
        val s = json.decodeFromString<Session>("""{"name":"alex","contextPercent":47}""")
        assertNull(s.context)
        assertNull(s.model)
        assertNull(s.effort)
        assertNull(s.fast)
        assertNull(s.limits)
        assertNull(s.cost)
        assertEquals(47, s.contextPercent, "the fallback the meter keeps reading")
        val screen = json.decodeFromString<Screen>("""{"width":80,"height":24,"contextPercent":47}""")
        assertNull(screen.context)
        assertEquals(47, screen.contextPercent)
    }

    /**
     * ⚠ THE ONE FIELD THE CONTRACT LEFT OPEN. The status line writes `resets_at`
     * as epoch seconds; everything else a reader sees on this wire is an ISO
     * string. Whichever the daemon settles on, a session list must not fail to
     * decode over a field no row draws.
     */
    @Test
    fun `a reset instant reads as seconds, millis or an ISO string`() {
        assertEquals(1_792_830_600_000L, json.decodeFromString<LimitWindow>("""{"resetsAt":1792830600}""").resetsAtEpochMs())
        assertEquals(1_792_830_600_000L, json.decodeFromString<LimitWindow>("""{"resetsAt":1792830600000}""").resetsAtEpochMs())
        assertEquals(
            1_788_195_600_296L,
            json.decodeFromString<LimitWindow>("""{"resetsAt":"2026-08-31T17:00:00.296172+00:00"}""").resetsAtEpochMs(),
        )
        assertNull(json.decodeFromString<LimitWindow>("""{"usedPercent":5}""").resetsAtEpochMs())
        assertNull(json.decodeFromString<LimitWindow>("""{"resetsAt":{"odd":1}}""").resetsAtEpochMs(), "an unforeseen shape is held, not thrown")
    }

    @Test
    fun `the context route decodes the breakdown with its kinds`() {
        val c = json.decodeFromString<SessionContext>(
            """{"context":{"used":28700,"window":1000000,"percent":3,"exceeds200k":false},"model":"Haiku 5.5",
                "breakdown":{"asOf":1792819000000,"totalTokens":28700,"maxTokens":1000000,"autocompactSource":"transcript",
                "categories":[{"id":"system_prompt","name":"System prompt","tokens":3700,"kind":"used"},
                              {"id":"free_space","name":"Free space","tokens":938300,"kind":"free"},
                              {"id":"autocompact_buffer","name":"Autocompact buffer","tokens":33000,"kind":"buffer"}]}}""",
        )
        assertEquals(3, c.breakdown?.categories?.size)
        assertEquals("buffer", c.breakdown?.categories?.last()?.kind)
        assertEquals(1_792_819_000_000L, c.breakdown?.asOf)
        assertEquals("transcript", c.breakdown?.autocompactSource)
        val none = json.decodeFromString<SessionContext>("""{"context":null,"model":null,"breakdown":null}""")
        assertNull(none.breakdown, "a session that never ran /context has no table")
    }

    @Test
    fun `claude settings decode, and a chat's fast choice is tri-state`() {
        val s = json.decodeFromString<ClaudeSettings>("""{"fastModeDefault":true,"fastModePerSession":true,"feedbackOff":false}""")
        assertTrue(s.fastModeDefault)
        assertTrue(s.fastModePerSession)
        assertFalse(s.feedbackOff)
        assertNull(json.decodeFromString<Chat>("""{"id":"c1"}""").fast, "absent is the host default")
        assertEquals(true, json.decodeFromString<ChatDetail>("""{"id":"c1","fast":true}""").fast)
        assertEquals(false, json.decodeFromString<ChatDetail>("""{"id":"c1","fast":false}""").fast)
        assertEquals(ChatFast.DEFAULT, ChatFast.of(null))
        assertEquals(ChatFast.ON, ChatFast.of(true))
        assertEquals(ChatFast.OFF, ChatFast.of(false))
        assertNull(ChatFast.DEFAULT.wire)
    }
}
