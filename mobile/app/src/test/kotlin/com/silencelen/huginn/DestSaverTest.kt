package com.silencelen.huginn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screen must survive an activity rebuild.
 *
 * A fold, a rotate, or a theme change destroys and recreates the activity, and
 * the current destination used to be held in a plain `remember` — so reading a
 * session and unfolding threw the reader back to the sessions list, every time.
 * It is saved as a string now, and this pins the round trip, including the ids
 * that make a restored screen the RIGHT one rather than merely the right kind.
 */
class DestSaverTest {

    @Test
    fun `every destination survives the round trip`() {
        val cases = listOf(
            Dest.Chats,
            Dest.Sessions,
            Dest.Status,
            Dest.Settings,
            Dest.Chat("fc322e02-cf53-4b09-a28d-6fc291cd66fa"),
            Dest.SessionView("andrev"),
        )
        for (d in cases) {
            assertEquals("lost $d across a rebuild", d, keyToDest(destToKey(d)))
        }
    }

    @Test
    fun `the identifier survives, not just the kind`() {
        // Restoring "a chat" instead of THE chat would look like it worked and
        // silently open the wrong conversation.
        val d = keyToDest(destToKey(Dest.Chat("abc-123"))) as Dest.Chat
        assertEquals("abc-123", d.id)
        val s = keyToDest(destToKey(Dest.SessionView("huginnapp"))) as Dest.SessionView
        assertEquals("huginnapp", s.name)
    }

    @Test
    fun `session names with separators are not truncated`() {
        // The encoding uses ':' as its own separator; a name containing one must
        // still come back whole.
        val s = keyToDest(destToKey(Dest.SessionView("a:b:c"))) as Dest.SessionView
        assertEquals("a:b:c", s.name)
    }

    @Test
    fun `an unrecognised key lands home rather than crashing`() {
        // Saved state can outlive an app version that knew that destination.
        assertEquals(Dest.Sessions, keyToDest("nonsense-from-an-older-build"))
        assertEquals(Dest.Sessions, keyToDest(""))
    }
}
