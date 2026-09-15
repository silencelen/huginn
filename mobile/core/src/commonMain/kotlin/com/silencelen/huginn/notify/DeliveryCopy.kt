package com.silencelen.huginn.notify

import com.silencelen.huginn.ui.agoWordsMs

/**
 * What the Notifications page is allowed to say about delivery.
 *
 * Both halves of this file are corrections to the same page, caught on the
 * owner's Fold on 2026-09-15.
 *
 * ⚠ NO HOSTS, NO URLS, NO LIBRARY NAMES. The page printed the transport
 * exception's own `message` straight onto the screen:
 *
 *   Last failure yesterday: Connect timeout has expired
 *   [url=http://192.168.2.117:8787/v1/watch, connect_timeout=8000 ms]
 *
 * Three things wrong with that and only one of them is cosmetic. It is Ktor's
 * sentence, not this product's. It is unreadable as an answer to "is this
 * working" — the reader wants "the phone was off the tailnet overnight", which
 * is what it means. And it publishes the daemon's LAN address and port onto a
 * settings screen that gets screenshotted into chats and bug reports. The last
 * one is why [scrub] exists as its own function with its own test rather than
 * being a detail of the mapping: the household wording is a nicety, and the
 * address never reaching the screen is not.
 *
 * ⚠ AND THE CADENCE LINE HAS TO AGREE WITH THE CLOCK BESIDE IT. The page read
 * "Checks huginn about every 10 minutes" directly above "Background check last
 * ran 7h ago". Both sentences were true — Android defers the alarm, which is
 * the entire reason the alarm is documented the way it is in [Heartbeat] — but
 * printed together with nothing between them they read as one of the two being
 * a lie, and the reader has no way to tell which. [cadence] says the schedule
 * and the last run in ONE sentence, and names the deferral when the gap is big
 * enough to be one.
 */
object DeliveryCopy {

    /**
     * How far past the cadence a gap has to be before it is Android holding the
     * alarm rather than ordinary jitter.
     *
     * `setAndAllowWhileIdle` is throttled to roughly one firing per nine minutes
     * per app, so a ten-minute alarm lands late routinely and a single missed
     * beat means nothing. Twice the period is the smallest gap that cannot be
     * explained that way.
     */
    const val DEFERRAL_FACTOR: Long = 2

    // ------------------------------------------------------------------ errors

    /**
     * Anything that could name the host: a URL, a bare `host:port`, a dotted
     * quad, or a tailnet/`.local` name.
     *
     * Deliberately greedy. A settings screen that occasionally says less than it
     * could is a fair trade against one that occasionally says where the daemon
     * lives.
     */
    private val ADDRESSY = listOf(
        Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+"""),
        Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b"""),
        Regex("""\b[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+(?::\d+)?\b"""),
        Regex("""\b[A-Za-z0-9-]+:\d{2,5}\b"""),
    )

    /** Everything address-shaped taken out. Used by [trouble]; tested on its own. */
    fun scrub(raw: String): String {
        var out = raw
        for (r in ADDRESSY) out = r.replace(out, "")
        return out
            .replace(Regex("""[\[\](){}]"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim(',', ';', '.', '-', '=')
            .trim()
    }

    /**
     * A transport failure in household words.
     *
     * Mapped on what the message CONTAINS rather than on an exception type,
     * because this runs over a stored string: the page shows the last failure,
     * which may have been written by an older build of this app. Matching text
     * means the sentences improve for failures already on disk.
     *
     * The fallback never echoes the raw message — only its scrubbed leading
     * clause, and only when something survives scrubbing.
     */
    fun trouble(raw: String): String {
        val t = raw.lowercase()
        fun has(vararg needles: String) = needles.any { it in t }
        return when {
            raw.isBlank() -> ""
            has("connect timeout", "connecttimeout") ->
                "huginn did not answer in time — usually this phone being off the tailnet."
            has("socket timeout", "sockettimeout", "request timeout", "read timed out") ->
                "huginn started answering and then stopped."
            has("unknownhost", "unable to resolve host", "nodename nor servname", "name or service not known") ->
                "this phone could not look huginn up on the network it was on."
            has("connection refused", "econnrefused") ->
                "huginn's machine answered but nothing was listening — the daemon was down or restarting."
            has("network is unreachable", "no route to host", "enetunreach", "ehostunreach") ->
                "there was no route to huginn from the network this phone was on."
            has("connection reset", "econnreset", "unexpected end of stream", "stream closed", "broken pipe") ->
                "the connection to huginn dropped mid-answer."
            has("ssl", "certificate", "handshake", "trust anchor") ->
                "the secure connection to huginn could not be set up."
            has("401", "unauthorized") ->
                "huginn rejected this phone's token — check it in Host & sign-in."
            has("403", "forbidden") -> "huginn refused this phone."
            has("no address associated", "airplane", "no network") ->
                "this phone had no network at all."
            else -> {
                val rest = scrub(raw.substringBefore('[').substringBefore('(')).take(60).trim()
                if (rest.isEmpty()) "this phone could not reach huginn."
                else "this phone could not reach huginn ($rest)."
            }
        }
    }

    // ----------------------------------------------------------------- cadence

    /** "every 10 minutes" / "hourly", from the interval the heartbeat is armed at. */
    fun cadenceWords(intervalMs: Long): String = when {
        intervalMs <= 0L -> "on demand"
        intervalMs >= 60 * 60 * 1000L -> "hourly"
        intervalMs % (60 * 1000L) == 0L -> "every ${intervalMs / (60 * 1000L)} minutes"
        else -> "every ${intervalMs / 1000L} seconds"
    }

    /**
     * The schedule and the last run, in one sentence that cannot contradict
     * itself — and which says so when Android has plainly been sitting on the
     * alarm.
     *
     * "when Android lets it" is in EVERY form of the line, not only the late one.
     * The deferral is not an incident, it is how the platform works, and a
     * sentence that only mentions it when things look bad teaches the reader that
     * seeing it means something is broken.
     */
    fun cadence(intervalMs: Long, lastAlarmAt: Long, nowMs: Long): String {
        val words = cadenceWords(intervalMs)
        if (lastAlarmAt <= 0L) return "Background check runs $words when Android lets it; it has not run yet."
        val ago = agoWordsMs(lastAlarmAt, nowMs).ifBlank { "just now" }
        val gap = nowMs - lastAlarmAt
        val deferred = intervalMs > 0L && gap > DEFERRAL_FACTOR * intervalMs
        return if (deferred) {
            "Background check runs $words when Android lets it; last ran $ago — Android has been deferring it."
        } else {
            "Background check runs $words when Android lets it; last ran $ago."
        }
    }
}
