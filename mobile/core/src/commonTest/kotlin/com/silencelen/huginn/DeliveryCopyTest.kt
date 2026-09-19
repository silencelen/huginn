package com.silencelen.huginn

import com.silencelen.huginn.notify.DeliveryCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Notifications page may say about delivery.
 *
 * Both halves are corrections to the same screen, off the owner's Fold.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class DeliveryCopyTest {

    private val MIN = 60_000L
    private val NOW = 1_789_460_000_000L

    /** Verbatim from the phone. */
    private val ktorRaw =
        "Connect timeout has expired [url=http://192.168.2.117:8787/v1/watch, connect_timeout=8000 ms]"

    // -------------------------------------------------------------- the address

    @Test
    fun `the daemon's address never reaches the screen`() {
        // THE ONE THAT IS NOT COSMETIC. This page gets screenshotted into chats
        // and bug reports; it printed the host's LAN address and port.
        val out = DeliveryCopy.trouble(ktorRaw)
        assertFalse("192.168.2.117" in out, out)
        assertFalse("8787" in out, out)
        assertFalse("http" in out, out)
        assertFalse("url=" in out, out)
    }

    @Test
    fun `scrub takes out anything address-shaped, whatever wraps it`() {
        for (raw in listOf(
            "failed: http://192.168.2.117:8787/v1/watch",
            "failed: https://huginn.tail1234.ts.net/v1/watch timed out",
            "connect to 192.168.2.117:8787 refused",
            "unable to resolve host \"huginn.local\"",
            "no route to 10.42.0.20",
        )) {
            val out = DeliveryCopy.scrub(raw)
            assertFalse(Regex("""\d{1,3}(\.\d{1,3}){3}""").containsMatchIn(out), "$raw -> $out")
            assertFalse("://" in out, "$raw -> $out")
            assertFalse(".net" in out || ".local" in out, "$raw -> $out")
        }
    }

    /**
     * ⚠ IPv6 IS A SUPPORTED CONFIGURATION AND THE SCRUBBER HAD NO RULE FOR IT.
     * RouteGuard allows plain http to `fc00::/7` and `RouteKind.MESH` is
     * first-class, so a phone reaching huginn over the mesh or the tailnet's IPv6
     * address hit a transport error `trouble()` does not map and printed the
     * address material this file exists to keep off the screen — Android's
     * `failed to connect to /fd7a:115c:a1e0:0:0::c65a (port 8787) after 8000ms:
     * ETIMEDOUT`, and a Yggdrasil-shaped `200:1b4f:…` the same way. Java prints
     * InetAddress uncompressed, so both forms have to go.
     */
    @Test
    fun `an IPv6 address never reaches the screen either`() {
        for (raw in listOf(
            "failed to connect to /fd7a:115c:a1e0:0:0::c65a (port 8787) after 8000ms: ETIMEDOUT",
            "failed to connect to /200:1b4f:9c2e:aa01:0:0:0:1 (port 8787)",
            "Connect timeout has expired [url=http://[fd7a:115c:a1e0::c65a]:8787/v1/watch]",
            "no route to [200:1b4f:9c2e:aa01::1]:8787",
            "connect to fd7a:115c:a1e0::c65a failed",
        )) {
            val out = DeliveryCopy.trouble(raw)
            assertFalse("fd7a" in out, "$raw -> $out")
            assertFalse("1b4f" in out, "$raw -> $out")
            assertFalse("c65a" in out, "$raw -> $out")
            assertFalse("aa01" in out, "$raw -> $out")
            val scrubbed = DeliveryCopy.scrub(raw)
            assertFalse("fd7a" in scrubbed, "scrub: $raw -> $scrubbed")
            assertFalse("1b4f" in scrubbed, "scrub: $raw -> $scrubbed")
        }
    }

    /**
     * OkHttp replaces libcore's message with `Failed to connect to
     * <InetSocketAddress>`, so the useful half ("refused", "timed out") is often
     * gone by the time this runs — every one of these used to fall through to the
     * bare fallback with the address as the only thing it had left to say.
     */
    @Test
    fun `the phrases Android actually produces are mapped`() {
        assertTrue("did not answer in time" in DeliveryCopy.trouble("ETIMEDOUT (Connection timed out)"))
        assertTrue("did not answer in time" in DeliveryCopy.trouble("java.net.SocketTimeoutException: Connect timed out"))
        val bare = DeliveryCopy.trouble("Failed to connect to /10.0.0.9:8787")
        assertFalse("10.0.0.9" in bare, bare)
        assertTrue("could not reach huginn" in bare, bare)
        assertFalse(bare.endsWith("()."), "an empty parenthesis is not an explanation: $bare")
    }

    @Test
    fun `an unrecognised failure still says nothing about where huginn lives`() {
        val out = DeliveryCopy.trouble("Something odd at https://10.0.0.9:8787/v1/watch (retry 3)")
        assertFalse("10.0.0.9" in out, out)
        assertTrue(out.startsWith("this phone could not reach huginn"), out)
    }

    // -------------------------------------------------------------- the words

    @Test
    fun `a connect timeout reads as what it means`() {
        assertEquals(
            "huginn did not answer in time — usually this phone being off the tailnet.",
            DeliveryCopy.trouble(ktorRaw),
        )
    }

    @Test
    fun `the common transport failures each get their own sentence`() {
        // Mapped on the TEXT, not on an exception type, because this runs over a
        // string that may have been written by an older build of the app.
        assertTrue("look huginn up" in DeliveryCopy.trouble("java.net.UnknownHostException: huginn"))
        assertTrue("nothing was listening" in DeliveryCopy.trouble("Connection refused"))
        assertTrue("no route" in DeliveryCopy.trouble("Network is unreachable"))
        assertTrue("dropped mid-answer" in DeliveryCopy.trouble("Connection reset by peer"))
        assertTrue("token" in DeliveryCopy.trouble("401 Unauthorized"))
        assertEquals("", DeliveryCopy.trouble("   "), "no failure, no sentence")
    }

    // ------------------------------------------------------------- the cadence

    @Test
    fun `the schedule and the last run are one sentence`() {
        // THE DEFECT: "Checks huginn about every 10 minutes" sat several lines
        // above "Background check last ran 7h ago". Both true, and printed apart
        // they read as one of them lying with no way to tell which.
        val line = DeliveryCopy.cadence(10 * MIN, NOW - 7 * 60 * MIN, NOW)
        assertTrue("every 10 minutes" in line, line)
        assertTrue("7h ago" in line, line)
        assertTrue("when Android lets it" in line, line)
        assertTrue("Android has been deferring it" in line, line)
    }

    @Test
    fun `a gap inside twice the cadence is not called a deferral`() {
        // setAndAllowWhileIdle is throttled to roughly one firing per nine
        // minutes, so a ten-minute alarm lands late routinely. A gate that fired
        // on one missed beat would say "Android is deferring it" all day.
        val fine = DeliveryCopy.cadence(10 * MIN, NOW - 19 * MIN, NOW)
        assertFalse("deferring" in fine, fine)
        assertTrue("19m ago" in fine, fine)
        val late = DeliveryCopy.cadence(10 * MIN, NOW - 21 * MIN, NOW)
        assertTrue("deferring" in late, late)
    }

    @Test
    fun `the deferral is named in the ordinary line too, not only the bad one`() {
        // It is how the platform works, not an incident. A sentence that only
        // mentions it when things look bad teaches the reader it means trouble.
        assertTrue("when Android lets it" in DeliveryCopy.cadence(10 * MIN, NOW - MIN, NOW))
        assertTrue("when Android lets it" in DeliveryCopy.cadence(60 * MIN, 0, NOW))
    }

    @Test
    fun `the cadence words follow the interval the alarm is actually armed at`() {
        assertEquals("every 10 minutes", DeliveryCopy.cadenceWords(10 * MIN))
        assertEquals("hourly", DeliveryCopy.cadenceWords(60 * MIN))
    }

    @Test
    fun `a check that has never run says so rather than claiming just now`() {
        val line = DeliveryCopy.cadence(10 * MIN, 0L, NOW)
        assertTrue("has not run yet" in line, line)
        assertFalse("ago" in line, line)
    }

    // ------------------------------------------------------------ push counts

    /**
     * ⚠ TWO BARE TOTALS, STACKED, FROM TWO DIFFERENT ERAS. The page printed
     *
     *     1476 delivered so far
     *     1072 of 1072 pushes arrived — nothing dropped…
     *     host counter restarted — re-baselined
     *
     * and nothing said that the first is huginn's lifetime across every device
     * while the second is this phone's tally since the counter it compares
     * against last restarted. A reader subtracts them, gets 404, and concludes
     * that 404 pushes were dropped by a delivery path that is in fact perfect.
     */
    @Test
    fun `the lifetime total is named as one, not stacked above the tally`() {
        val out = DeliveryCopy.pushCounts(arrived = 1072, sent = 1072, missing = 0, lifetime = 1476, rebaselined = true)
        assertEquals(2, out.size, "one sentence pair, not three notes: $out")
        assertTrue("1072 of 1072" in out[0], out[0])
        assertTrue("nothing dropped" in out[0], out[0])
        assertTrue("1476" in out[1], out[1])
        assertFalse("1476" in out[0], "the lifetime never rides the tally sentence: ${out[0]}")
        assertFalse("1072" in out[1], "and the tally never rides the lifetime one: ${out[1]}")
        assertTrue("restart" in out[1], "the re-baseline is folded in, not left trailing: ${out[1]}")
    }

    @Test
    fun `an untouched counter says the lifetime without inventing a restart`() {
        val out = DeliveryCopy.pushCounts(arrived = 12, sent = 12, missing = 0, lifetime = 40, rebaselined = false)
        assertEquals(2, out.size, "$out")
        assertFalse("restart" in out[1], out[1])
        assertTrue("40" in out[1], out[1])
    }

    @Test
    fun `nothing arrived yet, and no lifetime to name, is one line`() {
        val out = DeliveryCopy.pushCounts(arrived = 0, sent = 0, missing = 0, lifetime = 0, rebaselined = false)
        assertEquals(1, out.size, "$out")
        assertTrue("No push has arrived here yet" in out[0], out[0])
    }

    @Test
    fun `a drop is still reported as a drop`() {
        val out = DeliveryCopy.pushCounts(arrived = 8, sent = 11, missing = 3, lifetime = 900, rebaselined = false)
        assertTrue("3 push" in out[0], out[0])
        assertTrue("never arrived" in out[0], out[0])
        assertTrue("every 10 minutes" in out[0], out[0])
    }

    @Test
    fun `a re-baseline with no lifetime still says the count restarted`() {
        val out = DeliveryCopy.pushCounts(arrived = 4, sent = 4, missing = 0, lifetime = 0, rebaselined = true)
        assertEquals(2, out.size, "$out")
        assertTrue("restart" in out[1], out[1])
    }
}
