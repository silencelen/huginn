package com.silencelen.huginn

import com.silencelen.huginn.data.SettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two map-valued settings are stored as JSON in a single string preference,
 * and that encoding is now written once in :core rather than inline in each
 * client's store. These tests are the reason it can be: a second client reading
 * the same shape is only safe if the shape is pinned.
 */
class SettingsCodecTest {

    @Test
    fun `drafts survive a round trip`() {
        val drafts = mapOf(
            "sess:jtyper" to "half a thought",
            "chat:86ed1440-e7ad-4dc4-aa2d-1d2142c570a1" to "line one\nline two",
        )
        assertEquals(drafts, SettingsCodec.decodeDrafts(SettingsCodec.encodeDrafts(drafts)))
    }

    @Test
    fun `an emptied composer is not a draft`() {
        // The composer writes back on every keystroke, including the one that
        // clears it; without this the drafts map only ever grows.
        val encoded = SettingsCodec.encodeDrafts(mapOf("sess:a" to "", "sess:b" to "kept"))
        assertEquals(mapOf("sess:b" to "kept"), SettingsCodec.decodeDrafts(encoded))
    }

    @Test
    fun `nothing stored decodes to nothing, not to a crash`() {
        assertTrue(SettingsCodec.decodeDrafts(null).isEmpty())
        assertTrue(SettingsCodec.decodeDrafts("").isEmpty())
        assertTrue(SettingsCodec.decodeChatRuns(null).isEmpty())
    }

    @Test
    fun `a half-written value decodes to empty rather than throwing`() {
        // The process can be killed mid-write while the phone is in a pocket.
        // Losing a draft is recoverable; refusing to launch is not.
        assertTrue(SettingsCodec.decodeDrafts("""{"sess:a":"unterminat""").isEmpty())
        assertTrue(SettingsCodec.decodeChatRuns("""{"chat:a":not-a-number}""").isEmpty())
    }

    @Test
    fun `chat run counts survive a round trip as numbers`() {
        val runs = mapOf("chat:a" to 0L, "chat:b" to 41L, "chat:c" to Long.MAX_VALUE)
        assertEquals(runs, SettingsCodec.decodeChatRuns(SettingsCodec.encodeChatRuns(runs)))
    }

    @Test
    fun `keys with the characters real targets use are not mangled`() {
        // Targets are "sess:<name>" and "chat:<uuid>" — the colon must survive,
        // and so must a session name with a dot in it.
        val drafts = mapOf("sess:cc-2.0" to "x", "chat:86ed1440-e7ad-4dc4" to "y")
        assertEquals(drafts, SettingsCodec.decodeDrafts(SettingsCodec.encodeDrafts(drafts)))
    }
}
