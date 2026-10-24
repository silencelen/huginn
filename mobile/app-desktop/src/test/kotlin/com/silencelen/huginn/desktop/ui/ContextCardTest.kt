package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ContextBreakdown
import com.silencelen.huginn.data.ContextCategory
import com.silencelen.huginn.data.ContextUsage
import com.silencelen.huginn.data.FastMode
import com.silencelen.huginn.data.Queued
import com.silencelen.huginn.data.SessionContext
import com.silencelen.huginn.ui.ContextCard
import com.silencelen.huginn.ui.ContextCardBody
import com.silencelen.huginn.ui.ContextCardFacts
import com.silencelen.huginn.ui.ContextFormat
import com.silencelen.huginn.ui.ContextSource
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The context card (appd 3.14.0): the table behind the meter, what a reader
 * sees of it, and what the Refresh says back. Rendered here because `:ui` has
 * no Compose test runtime; the words are `ContextFormat`'s, asserted in `:core`.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class ContextCardTest {

    private val now = 1_792_820_000_000L

    /** The real 2.1.306 table, as the daemon shapes it. */
    private val table = ContextBreakdown(
        asOf = now - 14 * 60_000L,
        totalTokens = 28_700,
        maxTokens = 1_000_000,
        autocompactSource = "transcript",
        categories = listOf(
            ContextCategory("system_prompt", "System prompt", 3_700, "used"),
            ContextCategory("system_tools", "System tools", 20_800, "used"),
            ContextCategory("mcp_tools", "MCP tools", 671, "used"),
            ContextCategory("free_space", "Free space", 938_300, "free"),
            ContextCategory("autocompact_buffer", "Autocompact buffer", 33_000, "buffer"),
        ),
    )

    private val facts = ContextCardFacts(
        usage = ContextUsage(used = 28_700, window = 1_000_000, percent = 3),
        model = "Haiku 5.5",
        effort = "medium",
        fast = FastMode(on = false, available = true),
    )

    @Test
    fun `the card shows the total, the settings line, every row and the table's age`() = runComposeUiTest {
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(380.dp)) {
                    ContextCard(facts, table, now, refreshNote = null, onRefresh = {})
                }
            }
        }
        waitForIdle()
        onNodeWithTag("context.total", useUnmergedTree = true).assertTextEquals("28.7k / 1m tokens · 3%")
        onNodeWithText("Haiku 5.5 · effort medium · fast off").assertIsDisplayed()
        for (c in table.categories) onNodeWithTag("context.row.${c.id}", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("System tools").assertIsDisplayed()
        onNodeWithText("20.8k").assertIsDisplayed()
        onNodeWithText("2.1%").assertIsDisplayed()
        onNodeWithText("as of 14m ago").assertIsDisplayed()
        onNodeWithTag("context.refresh", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("context.none").assertDoesNotExist()
    }

    /** An older daemon, or a session that never ran /context: totals only, and the way to ask. */
    @Test
    fun `without a table the card shows the reading it has and offers Refresh`() = runComposeUiTest {
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(380.dp)) {
                    ContextCard(ContextCardFacts(contextPercent = 47), null, now, refreshNote = null, onRefresh = {})
                }
            }
        }
        waitForIdle()
        onNodeWithTag("context.total", useUnmergedTree = true).assertTextEquals("47% of the window")
        onNodeWithTag("context.none", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("context.refresh", useUnmergedTree = true).assertIsDisplayed()
    }

    /** Refresh is a request the daemon queues or refuses, and the card says which. */
    @Test
    fun `refresh asks the source and shows the daemon's answer`() = runComposeUiTest {
        var loads = 0
        var answer = Queued(queued = true)
        val source = ContextSource(
            load = { loads++; SessionContext(context = facts.usage, model = "Haiku 5.5", breakdown = table) },
            refresh = { answer },
        )
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                // No polling: an endless delay loop never lets the test clock go idle.
                Box(Modifier.width(380.dp)) { ContextCardBody("alex", source, facts, now, pollMs = 0L) }
            }
        }
        waitForIdle()
        assertEquals(1, loads, "the table is read when the card opens")
        onNodeWithTag("context.row.system_prompt", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag("context.refresh", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("context.note", useUnmergedTree = true).assertTextEquals(ContextFormat.refreshNote(true, null))
        assertEquals(2, loads, "a refresh re-reads the table from the moment it was asked")

        answer = Queued(queued = false, reason = "dialog")
        onNodeWithTag("context.refresh", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("context.note", useUnmergedTree = true).assertTextEquals("not now · a question is waiting in the pane")

        // A 409 with the daemon's own sentence shows that sentence (an observed appd 3.14.0 body).
        answer = Queued(queued = false, reason = "busy", error = "/context is already waiting for this session's next turn boundary")
        onNodeWithTag("context.refresh", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("context.note", useUnmergedTree = true)
            .assertTextEquals("/context is already waiting for this session's next turn boundary")

        // A 202 held behind unsent composer text says so, rather than "queued".
        answer = Queued(queued = true, delivered = false, blockedBy = "draft")
        onNodeWithTag("context.refresh", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("context.note", useUnmergedTree = true).assertTextEquals("held · the composer has unsent text")
    }

    /** A table without a stamp (asOf 0, as a daemon null decodes) prints no age — never "as of 56 years ago". */
    @Test
    fun `a table without a stamp shows no age`() = runComposeUiTest {
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(380.dp)) {
                    ContextCard(facts, table.copy(asOf = 0L), now, refreshNote = null, onRefresh = {})
                }
            }
        }
        waitForIdle()
        onNodeWithTag("context.row.system_prompt", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("as of", substring = true).assertDoesNotExist()
    }
}
