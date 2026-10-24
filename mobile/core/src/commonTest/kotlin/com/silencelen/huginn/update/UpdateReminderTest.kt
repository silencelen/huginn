package com.silencelen.huginn.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a verified update is announced outside Settings, and when it is not.
 *
 * Three answers can put the notice away and they mean three different things:
 * Later is "not now" and lasts until the next launch; Don't remind me is "not
 * this version" and lasts until a newer one; the switch is "never" and lasts
 * until it is turned back on. A rule that confused any two would either nag
 * (Later surviving a relaunch is a card nobody asked for twice) or go silent
 * (Don't remind me swallowing the next release is a client that stops
 * updating). Pure, in `:core`, so the phone can read it tomorrow.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class UpdateReminderTest {

    @Test
    fun `nothing ready means nothing shown, whatever else is set`() {
        assertFalse(UpdateReminder.show(null, null, null, notifyUpdates = true))
        assertFalse(UpdateReminder.show(null, "2.7.0", "2.6.0", notifyUpdates = true))
        assertFalse(UpdateReminder.mark(null, null, notifyUpdates = true))
    }

    @Test
    fun `a verified update shows until somebody answers`() {
        assertTrue(UpdateReminder.show("2.7.0", null, null, notifyUpdates = true))
        assertTrue(UpdateReminder.mark("2.7.0", null, notifyUpdates = true))
    }

    @Test
    fun `Later hides the card until the next launch and leaves the quiet marks`() {
        // The card goes; the rail dot and the tray row stay, because "not now"
        // is not "I never want to know".
        assertFalse(UpdateReminder.show("2.7.0", dismissedUntilLaunch = "2.7.0", silencedVersion = null, notifyUpdates = true))
        assertTrue(UpdateReminder.mark("2.7.0", null, notifyUpdates = true))
        // At the next launch the in-memory dismissal is gone, and the card is back.
        assertTrue(UpdateReminder.show("2.7.0", dismissedUntilLaunch = null, silencedVersion = null, notifyUpdates = true))
    }

    @Test
    fun `a newer Ready after Later shows again`() {
        assertTrue(UpdateReminder.show("2.7.1", dismissedUntilLaunch = "2.7.0", silencedVersion = null, notifyUpdates = true))
    }

    @Test
    fun `Don't remind me hides that version everywhere, and not a newer one`() {
        assertFalse(UpdateReminder.show("2.7.0", null, silencedVersion = "2.7.0", notifyUpdates = true))
        assertFalse(UpdateReminder.mark("2.7.0", silencedVersion = "2.7.0", notifyUpdates = true))
        assertTrue(UpdateReminder.show("2.7.1", null, silencedVersion = "2.7.0", notifyUpdates = true))
        assertTrue(UpdateReminder.mark("2.7.1", silencedVersion = "2.7.0", notifyUpdates = true))
    }

    @Test
    fun `the switch hides all`() {
        assertFalse(UpdateReminder.show("2.7.0", null, null, notifyUpdates = false))
        assertFalse(UpdateReminder.mark("2.7.0", null, notifyUpdates = false))
        // Turning it back on brings a silenced version back too? No: the two are
        // independent answers, and Don't remind me still stands for its version.
        assertFalse(UpdateReminder.show("2.7.0", null, silencedVersion = "2.7.0", notifyUpdates = true))
    }

    @Test
    fun `the card's line is the notes' first bullet, never a heading`() {
        val notes = "Carries core 2.7.0.\n\n### Added\n- Open in terminal on the session menu.\n- A notice when an update is ready.\n"
        assertEquals("Open in terminal on the session menu.", UpdateReminder.firstLine(notes))
        // No bullet: the first line that is not a heading.
        assertEquals("Carries core 2.7.0.", UpdateReminder.firstLine("Carries core 2.7.0.\n\n### Changed\n"))
        assertEquals("Faster lists.", UpdateReminder.firstLine("## 2.7.0\n### Changed\n* Faster lists."))
        assertNull(UpdateReminder.firstLine(""))
        assertNull(UpdateReminder.firstLine("### Added\n## Fixed\n"))
        // Long bullets are cut, with a mark that says so.
        val long = "- " + "x".repeat(300)
        val cut = UpdateReminder.firstLine(long)!!
        assertTrue(cut.length <= 140 && cut.endsWith("…"), cut)
    }

    @Test
    fun `the command inside a manual-install hint, and none when there is none`() {
        assertEquals(
            "sudo pacman -U /home/alex/.cache/huginn-desktop-kt/updates/huginn-desktop-2.7.0.pkg.tar.zst",
            UpdateReminder.commandOf(
                "downloaded to /home/alex/.cache/huginn-desktop-kt/updates/huginn-desktop-2.7.0.pkg.tar.zst — " +
                    "install it with: sudo pacman -U /home/alex/.cache/huginn-desktop-kt/updates/huginn-desktop-2.7.0.pkg.tar.zst " +
                    "(or switch to the self-updating install: huginn desktop install)",
            ),
        )
        assertEquals("sudo apt install /tmp/h.deb", UpdateReminder.commandOf("downloaded to /tmp/h.deb — install it with: sudo apt install /tmp/h.deb (or switch)"))
        assertNull(UpdateReminder.commandOf("downloaded to /tmp/x — install it by hand on this platform"))
        assertNull(UpdateReminder.commandOf(""))
    }

    @Test
    fun `the title names the version`() {
        assertEquals("Huginn 2.7.0 is ready", UpdateReminder.title("2.7.0"))
    }
}
