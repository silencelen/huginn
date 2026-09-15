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
}
