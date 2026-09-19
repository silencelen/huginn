package com.silencelen.huginn

import com.silencelen.huginn.notify.PushTally
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "1274 of 916 pushes arrived — nothing dropped."
 *
 * Read off the owner's Fold on 2026-09-15. More arrived than were ever sent, and
 * the page drew a conclusion from the subtraction. Neither counter was wrong:
 * the phone's is cumulative since install, and the daemon recreates an install's
 * row — resetting its count — when the FCM token rotates. Two epochs, subtracted.
 *
 * appd 3.0.5 answers with `pushEpoch` beside `pushesSent` so the two can be told
 * apart; a daemon older than that answers with nothing, and the `received > sent`
 * guard is the whole fallback.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class PushTallyTest {

    // ------------------------------------------------------- the reported case

    @Test
    fun `the phone re-bases when the host says its counter is from a new epoch`() {
        val r = PushTally.reconcile(received = 1274, storedEpoch = "e1", sent = 916, epoch = "e2")
        assertEquals(916L, r.received, "the two counts have to come from one epoch")
        assertEquals("e2", r.epoch)
        assertTrue(r.rebaselined, "and the page says so once")
    }

    @Test
    fun `the page can never print more arrived than were sent`() {
        assertEquals(916L, PushTally.arrived(1274, 916))
        assertEquals(0L, PushTally.missing(1274, 916), "which is also not a deficit")
        // The guard holds before any reconciliation has run — the settings screen
        // can be opened before the first watch response lands.
        assertEquals(0L, PushTally.arrived(5, 0))
    }

    // ------------------------------------------------------------ the epoch rule

    @Test
    fun `a familiar epoch changes nothing`() {
        val r = PushTally.reconcile(received = 40, storedEpoch = "e1", sent = 42, epoch = "e1")
        assertEquals(40L, r.received, "two sent and not yet arrived is an ordinary deficit")
        assertEquals("e1", r.epoch)
        assertFalse(r.rebaselined)
    }

    @Test
    fun `the first epoch ever seen is adopted`() {
        val r = PushTally.reconcile(received = 3, storedEpoch = null, sent = 9, epoch = "e1")
        assertEquals("e1", r.epoch)
        assertEquals(3L, r.received, "behind is behind — see below")
        assertFalse(r.rebaselined)
    }

    @Test
    fun `re-basing only ever corrects the count DOWNWARD`() {
        // ⚠ The rule is min(received, sent), not `= sent`. In the reported case
        // they are the same number. They differ when the phone is BEHIND at an
        // epoch change, and there `= sent` would invent arrivals the phone never
        // saw — telling Heartbeat everything got through, which relaxes the alarm
        // to hourly on a path that has just proved it drops things.
        val r = PushTally.reconcile(received = 2, storedEpoch = "e1", sent = 9, epoch = "e2")
        assertEquals(2L, r.received, "seven pushes are missing and must stay missing")
        assertEquals(7L, PushTally.missing(r.received, 9))
        assertFalse(r.rebaselined, "nothing moved, so nothing to explain")
    }

    // ------------------------------------------------------- the older daemon

    @Test
    fun `without an epoch, received over sent is still proof of a restart`() {
        // A pre-3.0.5 daemon cannot announce its own reset. Over-counting is the
        // only evidence there is, and it is conclusive: a push cannot arrive
        // without having been sent.
        val r = PushTally.reconcile(received = 1274, storedEpoch = null, sent = 916, epoch = null)
        assertEquals(916L, r.received)
        assertTrue(r.rebaselined)
        assertNull(r.epoch, "and no epoch is invented for a host that has none")
    }

    @Test
    fun `an ordinary deficit against an older daemon is left alone`() {
        val r = PushTally.reconcile(received = 900, storedEpoch = null, sent = 916, epoch = null)
        assertEquals(900L, r.received)
        assertFalse(r.rebaselined)
    }

    // -------------------------------------------------------------- null tally

    @Test
    fun `a frame with no tally changes nothing, epoch included`() {
        // NULL IS NOT ZERO — the same rule Watch.pushesSent already documents for
        // itself, and the reason an SSE state frame stopped wiping the count.
        val r = PushTally.reconcile(received = 40, storedEpoch = "e1", sent = null, epoch = "e2")
        assertEquals(40L, r.received)
        assertFalse(r.rebaselined)
        assertEquals(
            "e1", r.epoch,
            "adopting an epoch with no tally beside it would spend the signal that a " +
                "re-baseline is due, and the next frame would compare across the gap anyway",
        )
    }

    @Test
    fun `a blank epoch is treated as no epoch rather than as a new one`() {
        // Belt and braces for a daemon that sends the field empty: it must not
        // read as "the epoch changed to nothing" on every single frame.
        val r = PushTally.reconcile(received = 40, storedEpoch = "e1", sent = 42, epoch = null)
        assertEquals(40L, r.received)
        assertFalse(r.rebaselined)
    }

    /**
     * The wording moved. `REBASELINED_NOTE` was a terse line printed BELOW the
     * two totals it explained; the re-baseline is now a clause inside
     * `DeliveryCopy.pushCounts`'s provenance sentence, beside the lifetime it
     * belongs with, and `DeliveryCopyTest` holds it there. What stays here is
     * the RECONCILIATION — this object's actual job.
     */
}
