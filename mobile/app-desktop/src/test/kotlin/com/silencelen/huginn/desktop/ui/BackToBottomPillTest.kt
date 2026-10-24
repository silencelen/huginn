package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.ui.BACK_TO_BOTTOM
import com.silencelen.huginn.ui.BACK_TO_BOTTOM_TAG
import com.silencelen.huginn.ui.BackToBottomPill
import com.silencelen.huginn.ui.NEW_MESSAGES
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one pill both apps float over their conversations (2026-10-23): what it
 * says in each of the latch's states, and that it says nothing while the reader
 * is following. Rendered here because `:ui` has no Compose test runtime.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class BackToBottomPillTest {

    @Test
    fun `following shows nothing, away reads Back to bottom, unseen reads New messages`() = runComposeUiTest {
        var away by mutableStateOf(false)
        var unseen by mutableStateOf(false)
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.size(420.dp, 600.dp)) { BackToBottomPill(away, unseen) {} }
            }
        }
        waitForIdle()
        onNodeWithTag(BACK_TO_BOTTOM_TAG).assertDoesNotExist()

        away = true
        waitForIdle()
        onNodeWithText(BACK_TO_BOTTOM).assertIsDisplayed()

        unseen = true
        waitForIdle()
        onNodeWithText(NEW_MESSAGES).assertIsDisplayed()
        onNodeWithText(BACK_TO_BOTTOM).assertDoesNotExist()

        // Unseen without away: still the pill, still "New messages" — the reader
        // took the list a moment ago and something landed before they settled.
        away = false
        waitForIdle()
        onNodeWithText(NEW_MESSAGES).assertIsDisplayed()

        unseen = false
        waitForIdle()
        onNodeWithTag(BACK_TO_BOTTOM_TAG).assertDoesNotExist()
    }

    @Test
    fun `a click is the landing`() = runComposeUiTest {
        var clicks = 0
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.size(420.dp, 600.dp)) { BackToBottomPill(away = true, unseen = false) { clicks++ } }
            }
        }
        waitForIdle()
        onNodeWithTag(BACK_TO_BOTTOM_TAG).performClick()
        waitForIdle()
        assertEquals(1, clicks)
    }
}
