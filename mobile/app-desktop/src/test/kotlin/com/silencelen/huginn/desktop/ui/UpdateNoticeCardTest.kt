package com.silencelen.huginn.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.silencelen.huginn.ui.UpdateNoticeCard
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import com.silencelen.huginn.update.UpdateReminder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The notice card as a reader sees it: the version and the first bullet, and
 * three answers that each reach their own handler. The rule behind which answer
 * hides what is `UpdateReminderTest` in `:core`; this is the surface.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class UpdateNoticeCardTest {

    @Test
    fun `the card names the version and the first bullet, and each answer reaches its handler`() = runComposeUiTest {
        var installed = 0
        var later = 0
        var silenced = 0
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                UpdateNoticeCard(
                    version = "2.7.0",
                    line = "Open in terminal on the session menu.",
                    installable = true,
                    hint = "",
                    command = null,
                    onInstall = { installed++ },
                    onCopyCommand = {},
                    onLater = { later++ },
                    onSilence = { silenced++ },
                )
            }
        }
        onNodeWithText("Huginn 2.7.0 is ready").assertExists()
        onNodeWithText("Open in terminal on the session menu.").assertExists()
        onNodeWithTag("update.notice.copy").assertDoesNotExist()
        onNodeWithTag("update.notice.install").performClick()
        onNodeWithTag("update.notice.later").performClick()
        onNodeWithTag("update.notice.silence").performClick()
        assertEquals(1, installed)
        assertEquals(1, later)
        assertEquals(1, silenced)
    }

    @Test
    fun `a kind this client cannot install shows the hint and copies the command instead`() = runComposeUiTest {
        var copied = 0
        val hint = "downloaded to /tmp/h.deb — install it with: sudo apt install /tmp/h.deb " +
            "(or switch to the self-updating install: huginn desktop install)"
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                UpdateNoticeCard(
                    version = "2.7.0",
                    line = null,
                    installable = false,
                    hint = hint,
                    command = UpdateReminder.commandOf(hint),
                    onInstall = {},
                    onCopyCommand = { copied++ },
                    onLater = {},
                    onSilence = {},
                )
            }
        }
        onNodeWithTag("update.notice.install").assertDoesNotExist()
        onNodeWithText(hint).assertExists()
        onNodeWithTag("update.notice.copy").performClick()
        assertEquals(1, copied)
    }

    @Test
    fun `a hint with no command in it gets no Copy button, only the sentence`() = runComposeUiTest {
        val hint = "downloaded to /tmp/x — install it by hand on this platform"
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                UpdateNoticeCard(
                    version = "2.7.0",
                    line = null,
                    installable = false,
                    hint = hint,
                    command = UpdateReminder.commandOf(hint),
                    onInstall = {},
                    onCopyCommand = {},
                    onLater = {},
                    onSilence = {},
                )
            }
        }
        onNodeWithTag("update.notice.install").assertDoesNotExist()
        onNodeWithTag("update.notice.copy").assertDoesNotExist()
        onNodeWithText(hint).assertExists()
        onNodeWithTag("update.notice.later").assertExists()
    }
}
