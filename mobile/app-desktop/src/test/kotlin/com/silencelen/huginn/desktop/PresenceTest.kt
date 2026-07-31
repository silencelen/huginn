package com.silencelen.huginn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Presence, the reconnect signal it carries, and the resume detector.
 *
 * The reconnect rule is the one with teeth: the notify claim is stamped on the
 * request when the socket OPENS, so a parked SSE goes on asserting a claim made
 * half an hour ago. Everything here is about the stream being dropped at the exact
 * moments that claim stops being true.
 *
 * NOTE the assertion order: kotlin.test is `assertEquals(expected, actual, message)`,
 * the REVERSE of JUnit's.
 */
class PresenceTest {

    private class Clock(var now: Long = 1_000L)

    @Test
    fun `presence needs both a visible window and a recently attended one`() {
        val p = Presence()
        assertFalse(p.present.value)
        p.setFocused(true)
        assertFalse(p.present.value, "focused but not visible is not presence")
        p.setVisible(true)
        assertTrue(p.present.value)
    }

    @Test
    fun `THE STREAM KEY MOVES WHEN THE CLAIM DOES`() {
        val p = Presence()
        val before = p.streamKey.value
        p.setVisible(true)
        p.setFocused(true)
        assertTrue(p.streamKey.value > before, "becoming present must force a reconnect")

        // And NOT when nothing changed: a key that moved on every event would drop
        // the watch stream on every mouse click.
        val settled = p.streamKey.value
        p.setFocused(true)
        p.tick()
        assertEquals(settled, p.streamKey.value)
    }

    @Test
    fun `losing presence also forces a reconnect`() {
        val clock = Clock()
        val p = Presence { clock.now }
        p.setVisible(true)
        p.setFocused(true)
        val whilePresent = p.streamKey.value

        // Walked away: focus lost, and the grace window then expires on a tick
        // nobody triggered — which is the whole reason `tick` exists.
        p.setFocused(false)
        clock.now += p.graceMs + 1
        p.tick()
        assertFalse(p.present.value)
        assertTrue(p.streamKey.value > whilePresent, "dropping the claim must re-open the stream")
    }

    @Test
    fun `a glance away does not drop the claim`() {
        val clock = Clock()
        val p = Presence { clock.now }
        p.setVisible(true)
        p.setFocused(true)
        p.setFocused(false)
        clock.now += 30_000
        p.tick()
        assertTrue(p.present.value)
    }

    @Test
    fun `resume forces a reconnect on its own`() {
        val p = Presence()
        val before = p.streamKey.value
        p.noteResume()
        assertEquals(before + 1, p.streamKey.value)
    }

    // ------------------------------------------------------- sleep detection

    @Test
    fun `the first tick only establishes a baseline`() {
        val d = SleepDetector(intervalMs = 15_000, slackMs = 45_000)
        assertFalse(d.tick(1_000_000L, 0L))
    }

    @Test
    fun `an ordinary tick is not a resume`() {
        val d = SleepDetector(intervalMs = 15_000, slackMs = 45_000)
        d.tick(0L, 0L)
        assertFalse(d.tick(15_000L, 15_000L * 1_000_000))
    }

    @Test
    fun `a frozen process is a resume`() {
        // The monotonic clock is what catches a suspend: the JVM's threads simply
        // stop, and the ticker finds far more than its interval has passed.
        val d = SleepDetector(intervalMs = 15_000, slackMs = 45_000)
        d.tick(0L, 0L)
        assertTrue(d.tick(15_000L, 600_000L * 1_000_000))
    }

    @Test
    fun `a wall-clock jump in either direction is a resume`() {
        // Forwards: the machine slept, or NTP stepped. Backwards: a hypervisor
        // restored a snapshot. Both black-hole every open socket identically, and
        // only the forward case is obvious.
        val forwards = SleepDetector(intervalMs = 15_000, slackMs = 45_000)
        forwards.tick(0L, 0L)
        assertTrue(forwards.tick(3_600_000L, 15_000L * 1_000_000))

        val backwards = SleepDetector(intervalMs = 15_000, slackMs = 45_000)
        backwards.tick(3_600_000L, 0L)
        assertTrue(backwards.tick(0L, 15_000L * 1_000_000))
    }
}
