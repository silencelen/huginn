package com.silencelen.huginn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            Dest.Scratchpads,
            Dest.Scratchpad("6f1c0f5e-0000-4000-8000-000000000001"),
            // The four this list forgot. Devices, Rounds and RoundEdit have been
            // restorable the whole time and nothing said so; SettingsSection is
            // new, and it is the one whose id decides WHICH of nine drawers you
            // come back to.
            Dest.Devices,
            Dest.Rounds,
            Dest.RoundEdit(null),
            Dest.RoundEdit("r-7"),
            Dest.SettingsSection("usage"),
            Dest.ArchiveTranscript("9f1c8b52-5d2a-4a21-9f65-1a2b3c4d5e6f"),
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
        // A page restored as "a page" rather than THE page would open the wrong
        // one, and the editor autosaves — so the wrong page would then be typed
        // into with no sign anything was amiss.
        val p = keyToDest(destToKey(Dest.Scratchpad("pad-9"))) as Dest.Scratchpad
        assertEquals("pad-9", p.id)
        // "New round" and "edit round r-7" are different screens and the null is
        // the whole difference; restoring one as the other would open an editor
        // over the wrong record.
        assertNull(( keyToDest(destToKey(Dest.RoundEdit(null))) as Dest.RoundEdit).id)
        assertEquals("r-7", (keyToDest(destToKey(Dest.RoundEdit("r-7"))) as Dest.RoundEdit).id)
    }

    @Test
    fun `a settings drawer comes back as the drawer, not as the list`() {
        // Restoring Settings instead of THE category reads as working — the
        // screen is right, the nine rows are right — and silently throws away
        // where the reader was, every fold, on the one screen they unfolded the
        // phone to read.
        for (id in listOf("host", "usage", "chats", "notify", "devices", "privacy",
                          "appearance", "updates", "about")) {
            val d = keyToDest(destToKey(Dest.SettingsSection(id)))
            assertEquals("lost the drawer $id", Dest.SettingsSection(id), d)
        }
        // The home is still the home, and must not decode as a section with an
        // empty id — that would open an empty page instead of the list.
        assertEquals(Dest.Settings, keyToDest(destToKey(Dest.Settings)))
        assertEquals(Dest.Settings, keyToDest("settings:"))
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

    /**
     * ⚠ THE ARCHIVE'S KEY IS THE CLAUDE SESSION UUID, never a tmux name. A
     * restored destination that came back with the name would address a session
     * that is gone — or, on this host, a stranger that has since reused it.
     */
    @Test
    fun `an archived conversation comes back as THAT conversation`() {
        val id = "9f1c8b52-5d2a-4a21-9f65-1a2b3c4d5e6f"
        val d = keyToDest(destToKey(Dest.ArchiveTranscript(id))) as Dest.ArchiveTranscript
        assertEquals(id, d.id)
        assertEquals(Dest.ArchiveTranscript(id), keyToDest(destToKey(Dest.ArchiveTranscript(id))))
    }

    /** An empty id is the LIST, the same rule Projects and Settings already follow. */
    @Test
    fun `an archive key with no id lands on the sessions list`() {
        assertEquals(Dest.Sessions, keyToDest("archive:"))
    }

    /** Up from a read-only archive is the list its section lives at the bottom of. */
    @Test
    fun `back from an archived conversation is the sessions list`() {
        assertEquals(Dest.Sessions, backFrom(Dest.ArchiveTranscript("a"), tab = 1))
        // And it is a CHILD, so the system back gesture is handled rather than
        // closing the app — the same class as a session, a page or a drawer.
        assertEquals(Dest.Sessions, backFrom(Dest.ArchiveTranscript("a"), tab = 0))
    }
}
