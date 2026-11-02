package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.PendingAdded
import com.silencelen.huginn.data.PendingChanged
import com.silencelen.huginn.data.PendingRemoved
import com.silencelen.huginn.data.ProjectPendingChange
import com.silencelen.huginn.ui.PendingChangeCard
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlin.test.Test
import kotlin.test.assertEquals

/** The roster-change card (appd 3.17): what it lists, and what Approve and Decline do. */
@OptIn(ExperimentalTestApi::class)
class PendingChangeCardTest {
    private val change = ProjectPendingChange(
        rev = 3,
        summary = "Add a tester, drop the docs role",
        added = listOf(PendingAdded(role = "tester", firstPrompt = "Write the bench tests.\nStart with the radio.", model = "sonnet")),
        removed = listOf(PendingRemoved("docs")),
        changed = listOf(PendingChanged("firmware", "Switch to the power budget.")),
        problem = "the project is at its member limit",
    )

    @Test
    fun `the card lists the roles and Approve sends the rev, Decline fires`() = runComposeUiTest {
        val approved = mutableListOf<Int>()
        var declined = 0
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(600.dp)) {
                    PendingChangeCard(change = change, onApprove = { approved += it }, onDecline = { declined++ }, refusal = "rev 3 moved")
                }
            }
        }
        waitForIdle()
        onNodeWithTag("change.summary", useUnmergedTree = true).assertTextContains("Add a tester, drop the docs role")
        onNodeWithText("tester  —  Write the bench tests.  (sonnet)", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("docs", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("firmware", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("change.problem", useUnmergedTree = true).assertTextContains("the project is at its member limit")
        onNodeWithTag("change.refusal", useUnmergedTree = true).assertTextContains("rev 3 moved")
        onNodeWithTag("change.approve", useUnmergedTree = true).performClick()
        onNodeWithTag("change.decline", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf(3), approved, "Approve quotes the change's rev, not a guess")
        assertEquals(1, declined)
    }

    @Test
    fun `while busy neither verb can be pressed`() = runComposeUiTest {
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(600.dp)) {
                    PendingChangeCard(change = change, onApprove = {}, onDecline = {}, busy = true)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("change.approve", useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag("change.decline", useUnmergedTree = true).assertIsNotEnabled()
    }
}
