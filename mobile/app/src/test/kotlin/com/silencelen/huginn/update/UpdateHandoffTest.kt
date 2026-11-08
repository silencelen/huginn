package com.silencelen.huginn.update

import com.silencelen.huginn.ui.WebLinks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure half of the hand-off: which way a found release goes, what the page
 * address is, and what the button says. The Android half (the launch intent,
 * the browser) needs a Context and is held by `NoInstallerTest` instead.
 */
class UpdateHandoffTest {

    private val url = "https://github.com/alex/huginn/releases/tag/app-v2.60.0"

    @Test
    fun `the store when it is installed, else the release page`() {
        assertEquals(UpdateHandoff.Store, UpdateHandoff.choose({ true }, url))
        assertEquals(UpdateHandoff.ReleasePage(url), UpdateHandoff.choose({ false }, url))
    }

    @Test
    fun `the release page is the found tag's page in the pinned repo, and passes the link gate`() {
        assertEquals(url, UpdateHandoff.releasePageUrl("alex/huginn", "app-v2.60.0"))
        // What the browser gets goes through the app's own gate, http(s) only.
        assertEquals(url, WebLinks.browsable(url))
    }

    @Test
    fun `the button says where the tap goes, and the store package is the one the manifest can see`() {
        assertEquals("Open devstore", UpdateHandoff.Store.label)
        assertEquals("Open release page", UpdateHandoff.ReleasePage(url).label)
        // NoInstallerTest holds the manifest's <queries> to this name.
        assertEquals("com.silence.devstore", UpdateHandoff.STORE_PACKAGE)
    }
}
