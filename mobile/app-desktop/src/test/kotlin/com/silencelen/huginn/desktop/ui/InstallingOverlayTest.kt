package com.silencelen.huginn.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.silencelen.huginn.ui.theme.HuginnTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The "Installing…" overlay "Install and restart" raises until the app quits (2026-10-31). */
@OptIn(ExperimentalTestApi::class)
class InstallingOverlayTest {
    @Test
    fun `it says which version and that the app comes back, and is absent otherwise`() = runComposeUiTest {
        val state = androidx.compose.runtime.mutableStateOf<String?>("2.9.1")
        setContent { HuginnTheme(darkTheme = true) { InstallingOverlay(state.value) } }
        waitForIdle()
        assertEquals(1, onAllNodesWithText("Installing Huginn 2.9.1").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText("opens again by itself", substring = true).fetchSemanticsNodes().size)
        state.value = null
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(INSTALLING_OVERLAY_TAG).fetchSemanticsNodes().size)
    }
}
