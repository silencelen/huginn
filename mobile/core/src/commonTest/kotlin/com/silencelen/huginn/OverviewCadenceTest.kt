package com.silencelen.huginn

import com.silencelen.huginn.ui.OverviewCadence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverviewCadenceTest {
    @Test
    fun `the tab polls fast and the background slowly, never not at all`() {
        assertEquals(5_000, OverviewCadence.intervalMs(onTab = true))
        assertEquals(30_000, OverviewCadence.intervalMs(onTab = false))
        assertTrue(OverviewCadence.BACKGROUND_MS > OverviewCadence.FOREGROUND_MS)
    }

    @Test
    fun `the header comes with the session's first pass and every arrival at the tab`() {
        assertTrue(OverviewCadence.fetchHeader(onTab = false, haveHeader = false), "session start")
        assertFalse(OverviewCadence.fetchHeader(onTab = false, haveHeader = true), "behind the tab the cursor is enough")
        assertTrue(OverviewCadence.fetchHeader(onTab = true, haveHeader = true), "navigation refreshes")
    }

    @Test
    fun `a cached map is kept for its own session and dropped for another`() {
        assertTrue(OverviewCadence.keepCache("jtyper", "jtyper"))
        assertFalse(OverviewCadence.keepCache("jtyper", "main"))
        assertFalse(OverviewCadence.keepCache(null, "main"))
    }
}
