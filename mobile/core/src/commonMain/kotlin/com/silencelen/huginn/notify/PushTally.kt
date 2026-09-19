package com.silencelen.huginn.notify

/**
 * Two counters that have to come from the same era before they can be compared.
 *
 * The Notifications page said **"1274 of 916 pushes arrived — nothing dropped"**
 * on the owner's Fold. More arrived than were ever sent, and the sentence drew a
 * conclusion from it.
 *
 * Neither number was wrong. `pushesReceived` is the phone's own tally, kept in
 * this install's DataStore and incremented once per arrival since the app was
 * installed. `pushesSent` is the host's tally for this install's row — and the
 * daemon recreates that row when the FCM token rotates, which resets the count to
 * zero while the phone's keeps climbing. Six daemon restarts in one day is what
 * made it visible; a token rotation is what actually caused it.
 *
 * Two counters from two different epochs, subtracted. And the subtraction is not
 * cosmetic: [Heartbeat.intervalFor] reads it to decide between a ten-minute alarm
 * and an hourly one, so an epoch skew silently decides the phone's battery
 * behaviour for the day.
 *
 * ⚠ THE EPOCH IS THE HOST'S, NOT A TIMESTAMP. appd 3.0.5 mints `pushEpoch` when
 * an install's counter is created or reset and sends it on both `GET /v1/push`
 * (per device) and `/v1/watch` beside `pushesSent`. It survives restarts AND
 * token rotations, so a change in it means exactly one thing: the count the phone
 * has been comparing against is gone and a new one started.
 *
 * ⚠ AND THE FALLBACK STAYS. A daemon older than 3.0.5 sends no epoch at all, and
 * the phone must still never print a number larger than the one it is "of" — so
 * `received > sent` alone is treated as proof of a restart the host could not
 * announce.
 */
object PushTally {

    /**
     * The phone's counter after reconciliation, and the epoch it now belongs to.
     *
     * @param received what to store. Never larger than [Reading] was handed.
     * @param epoch what to store beside it; null means "still no epoch known",
     *   which is the state against a pre-3.0.5 daemon.
     * @param rebaselined the count actually moved, so the page says so once.
     */
    data class Reading(
        val received: Long,
        val epoch: String?,
        val rebaselined: Boolean,
    )

    /**
     * Reconcile the phone's tally against the host's.
     *
     * ⚠ THE RE-BASELINE IS `min(received, sent)`, NOT `= sent`. In the case that
     * motivated this they are the same number — the host's counter had restarted
     * near zero while the phone's was at 1274, so both give 916. They differ only
     * when the phone is BEHIND at an epoch change, and there `= sent` would
     * invent arrivals the phone never saw: it would tell [Heartbeat] that
     * everything the host sent got through, which relaxes the alarm to hourly on
     * a delivery path that has just proved it drops things. A counter may be
     * corrected downward on evidence; it may never be corrected upward on none.
     *
     * @param received the phone's stored tally.
     * @param storedEpoch the epoch that tally belongs to; null before 3.0.5.
     * @param sent the host's tally, or null when the response did not carry one —
     *   an older daemon, or any frame shape that omits it. Null is NOT zero and
     *   must change nothing, which is the same rule `Watch.pushesSent` already
     *   documents for itself.
     * @param epoch the host's epoch, or null before 3.0.5.
     */
    fun reconcile(received: Long, storedEpoch: String?, sent: Long?, epoch: String?): Reading {
        // Nothing to compare against. Note that the epoch is NOT adopted here
        // either: adopting it now would spend the one signal that says a
        // re-baseline is due, and the next frame that does carry a tally would
        // see a familiar epoch and compare across the gap anyway.
        if (sent == null) return Reading(received, storedEpoch, false)

        val epochChanged = epoch != null && epoch != storedEpoch
        val overCount = received > sent
        val next = if (epochChanged || overCount) minOf(received, sent) else received
        return Reading(
            received = next,
            // Only adopted alongside a tally, per the note above.
            epoch = epoch ?: storedEpoch,
            rebaselined = next != received,
        )
    }

    /**
     * What the page may print as "arrived", whatever is on disk.
     *
     * A second, smaller guard in front of the copy itself: reconciliation happens
     * when a watch response lands, and the settings page can be opened before the
     * first one does. "1274 of 916" must not be reachable by being quick.
     */
    fun arrived(received: Long, sent: Long): Long = minOf(received, sent).coerceAtLeast(0)

    /** How many the host sent that this phone never saw. Never negative. */
    fun missing(received: Long, sent: Long): Long = (sent - arrived(received, sent)).coerceAtLeast(0)
}
