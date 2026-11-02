package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.ui.ShellMode
import com.silencelen.huginn.ui.system.Composer
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlin.test.Test

/**
 * Shell mode in the composer (appd 3.17): a `!command` tints the box and says
 * what Send will do. The rule is `:core`'s (ShellMode); the shared composer
 * draws it for the phone, and the desktop's session composer draws the same
 * hint beside its own field (`ShellModeHint`, `shellFieldColors`).
 */
@OptIn(ExperimentalTestApi::class)
class ShellComposerTest {

    private fun androidx.compose.ui.test.ComposeUiTest.composer(text: String) {
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(600.dp)) {
                    Composer(
                        value = text,
                        onValueChange = {},
                        onSend = {},
                        shellMode = ShellMode.isShellCommand(text),
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `a bang command draws the shell hint`() = runComposeUiTest {
        composer("!ls -la")
        onNodeWithTag("composer.shell.hint", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an ordinary message draws no hint`() = runComposeUiTest {
        composer("hello there")
        onNodeWithTag("composer.shell.hint", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a multi-line bang message is a message, so no hint`() = runComposeUiTest {
        composer("!ls\npwd")
        onNodeWithTag("composer.shell.hint", useUnmergedTree = true).assertDoesNotExist()
    }
}
