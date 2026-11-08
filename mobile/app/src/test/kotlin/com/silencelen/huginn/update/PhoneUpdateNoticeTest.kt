package com.silencelen.huginn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the chats list's card says about a found update, where its button goes,
 * and when the shared reminder rule keeps it quiet.
 *
 * JUnit4 (this module's convention): `assertTrue(message, condition)` — message
 * FIRST — and no smart-cast contract, so an `is` assertion is followed by an
 * explicit cast.
 */
class PhoneUpdateNoticeTest {

    private val available = AppUpdateState.Available(
        versionName = "2.60.0", versionCode = 200,
        notes = "Carries core 2.7.0.\n\n### Added\n- Faster lists.\n",
        releasePageUrl = "https://github.com/alex/huginn/releases/tag/app-v2.60.0",
    )

    /** A phone whose store presence must not even be asked: there is no card to build. */
    private val notAsked: () -> Boolean = {
        throw AssertionError("asked whether the store is installed with no card to build")
    }

    @Test
    fun `with the store app on the phone the card opens it, with the notes' first bullet`() {
        val n = phoneUpdateNotice(available, later = null, silenced = null, storeInstalled = { true })
        assertTrue("found shows: $n", n != null)
        assertEquals("2.60.0", n!!.version)
        assertEquals("Faster lists.", n.line)
        assertEquals(UpdateHandoff.Store, n.handoff)
        assertEquals("Open devstore", n.buttonLabel)
    }

    @Test
    fun `without the store app the card opens the release page`() {
        val n = phoneUpdateNotice(available, later = null, silenced = null, storeInstalled = { false })
        assertTrue("found shows: $n", n != null)
        assertEquals(UpdateHandoff.ReleasePage("https://github.com/alex/huginn/releases/tag/app-v2.60.0"), n!!.handoff)
        assertEquals("Open release page", n.buttonLabel)
    }

    @Test
    fun `nothing found, nothing shown, and the store is not asked`() {
        assertNull(phoneUpdateNotice(AppUpdateState.Idle, null, null, notAsked))
        assertNull(phoneUpdateNotice(AppUpdateState.Checking, null, null, notAsked))
        assertNull(phoneUpdateNotice(AppUpdateState.UpToDate("2.59.0"), null, null, notAsked))
        assertNull(phoneUpdateNotice(AppUpdateState.Error("offline"), null, null, notAsked))
    }

    @Test
    fun `Later hides this version for the run, Don't remind me hides it for good, a newer one shows again`() {
        assertNull("Later", phoneUpdateNotice(available, later = "2.60.0", silenced = null, storeInstalled = notAsked))
        assertNull("Don't remind me", phoneUpdateNotice(available, later = null, silenced = "2.60.0", storeInstalled = notAsked))
        val newer = available.copy(versionName = "2.61.0", versionCode = 201)
        assertEquals("2.61.0", phoneUpdateNotice(newer, later = "2.60.0", silenced = "2.60.0", storeInstalled = { true })!!.version)
        assertNull("the switch, were the phone to grow one",
            phoneUpdateNotice(available, null, null, notAsked, notifyUpdates = false))
    }
}
