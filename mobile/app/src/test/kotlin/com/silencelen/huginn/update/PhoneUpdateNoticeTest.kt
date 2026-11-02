package com.silencelen.huginn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the chats list's card says about an update, through the phone's three
 * steps, and when the shared reminder rule keeps it quiet.
 *
 * JUnit4 (this module's convention): `assertTrue(message, condition)` — message
 * FIRST — and no smart-cast contract, so an `is` assertion is followed by an
 * explicit cast.
 */
class PhoneUpdateNoticeTest {

    private val available = AppUpdateState.Available(
        versionName = "2.60.0", versionCode = 200, notes = "Carries core 2.7.0.\n\n### Added\n- Faster lists.\n",
        assetUrl = "https://dl/x.apk", sha256 = "a".repeat(64), size = 48_000_000, apkName = "x.apk",
    )
    private val ready = AppUpdateState.Ready("2.60.0", File("/tmp/x.apk"))

    @Test
    fun `a found release shows with a download named by its size, and the notes' first bullet`() {
        val n = phoneUpdateNotice(available, later = null, silenced = null)
        assertTrue("found shows: $n", n != null)
        assertEquals("2.60.0", n!!.version)
        assertEquals("Faster lists.", n.line)
        assertEquals("Download (48 MB)", n.buttonLabel)
        assertTrue(n.buttonEnabled)
        // Size unknown: no number invented.
        assertEquals("Download", phoneUpdateNotice(available.copy(size = 0), null, null)!!.buttonLabel)
    }

    @Test
    fun `a fetched release shows with Install and restart`() {
        val n = phoneUpdateNotice(ready, later = null, silenced = null)
        assertTrue("ready shows: $n", n != null)
        assertEquals(PhoneUpdateNotice.Action.Install, n!!.action)
        assertEquals("Install and restart", n.buttonLabel)
        assertTrue(n.buttonEnabled)
    }

    @Test
    fun `while it downloads the card stays, with an inert button that says how far`() {
        val n = phoneUpdateNotice(AppUpdateState.Downloading("2.60.0", 24, 48), null, null)
        assertTrue(n != null)
        assertEquals("Downloading 50%", n!!.buttonLabel)
        assertFalse("no second download from a second tap", n.buttonEnabled)
        assertEquals("Downloading…", phoneUpdateNotice(AppUpdateState.Downloading("2.60.0", 0, 0), null, null)!!.buttonLabel)
    }

    @Test
    fun `nothing found, nothing shown`() {
        assertNull(phoneUpdateNotice(AppUpdateState.Idle, null, null))
        assertNull(phoneUpdateNotice(AppUpdateState.Checking, null, null))
        assertNull(phoneUpdateNotice(AppUpdateState.UpToDate("2.59.0"), null, null))
        assertNull(phoneUpdateNotice(AppUpdateState.Error("offline"), null, null))
    }

    @Test
    fun `Later hides this version for the run, Don't remind me hides it for good, a newer one shows again`() {
        assertNull("Later", phoneUpdateNotice(available, later = "2.60.0", silenced = null))
        assertNull("Don't remind me", phoneUpdateNotice(available, later = null, silenced = "2.60.0"))
        assertNull("Don't remind me holds through the download too", phoneUpdateNotice(ready, later = null, silenced = "2.60.0"))
        val newer = available.copy(versionName = "2.61.0", versionCode = 201)
        assertEquals("2.61.0", phoneUpdateNotice(newer, later = "2.60.0", silenced = "2.60.0")!!.version)
        assertNull("the switch, were the phone to grow one", phoneUpdateNotice(available, null, null, notifyUpdates = false))
    }
}
