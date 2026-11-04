package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.theme.HuginnTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠ A `!pwd` DREW TWO BUBBLES OF RAW TAGS (2026-11-03, the Fold and the
 * desktop): `<bash-input>pwd</bash-input>` and
 * `<bash-stdout>/root/projects/x</bash-stdout><bash-stderr></bash-stderr>`.
 * Claude Code 2.1.316 writes a shell-mode command as two ordinary `user`
 * records and the daemon hands them on as `user` events, so the bubble printed
 * the markup as the owner's words. The record texts below are verbatim from a
 * 2.1.316 transcript (the paths and output changed to placeholders).
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class ShellRowsTest {

    private fun user(text: String) = TranscriptEvent(seq = 1, kind = "user", text = text)

    private fun ComposeUiTest.row(ev: TranscriptEvent) {
        setContent {
            HuginnTheme(darkTheme = true, rootSurface = true) {
                Box { Box(Modifier.width(420.dp)) { TranscriptEventItem(ev, onCopy = {}) } }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.shown(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `the command is drawn as a shell row, never as its tags`() = runComposeUiTest {
        var ev by mutableStateOf(user("<bash-input>pwd</bash-input>"))
        setContent {
            HuginnTheme(darkTheme = true, rootSurface = true) {
                Box { Box(Modifier.width(420.dp)) { TranscriptEventItem(ev, onCopy = {}) } }
            }
        }
        waitForIdle()
        assertEquals(1, shown("! pwd"), "the command, behind the composer's own `!`")
        assertEquals(0, shown("bash-input", substring = true), "no markup on screen")
        // Words around the tag are not a record: that is somebody's message.
        ev = user("why does <bash-input>ls</bash-input> show up raw?")
        waitForIdle()
        assertEquals(1, shown("why does <bash-input>ls</bash-input> show up raw?"))
        assertEquals(0, shown("! ls"))
    }

    @Test
    fun `a command typed as "! cmd" loses the space Claude Code kept`() = runComposeUiTest {
        // 88 of 96 records on this host start with a space: the person typed `! cmd`.
        row(user("<bash-input> git status --short</bash-input>"))
        assertEquals(1, shown("! git status --short"))
    }

    @Test
    fun `the output is a block of what the command printed`() = runComposeUiTest {
        row(user("<bash-stdout>/home/alex/projects/x</bash-stdout><bash-stderr></bash-stderr>"))
        assertEquals(1, shown("/home/alex/projects/x"))
        assertEquals(0, shown("bash-", substring = true), "no markup on screen")
        assertEquals(0, shown("no output"), "it printed something")
    }

    @Test
    fun `output Claude Code escaped is shown as the command printed it`() = runComposeUiTest {
        // The output is HTML-escaped in the record (785 `&gt;` across this host's
        // transcripts); the command line is not.
        row(user("<bash-stdout>dig box.example.net -&gt; 192.0.2.94 &amp;&amp; &lt;ok&gt;</bash-stdout><bash-stderr></bash-stderr>"))
        assertEquals(1, shown("dig box.example.net -> 192.0.2.94 && <ok>"))
    }

    @Test
    fun `stderr is shown only when there is some, apart from stdout`() = runComposeUiTest {
        row(
            user(
                "<bash-stdout></bash-stdout><bash-stderr>rm: cannot remove 'old.deb': Read-only file system\n" +
                    "</bash-stderr>",
            ),
        )
        assertEquals(1, shown("rm: cannot remove 'old.deb': Read-only file system"))
        assertEquals(0, shown("no output"), "stderr is output")
    }

    @Test
    fun `a command that printed nothing says so quietly`() = runComposeUiTest {
        row(user("<bash-stdout></bash-stdout><bash-stderr></bash-stderr>"))
        assertEquals(1, shown("no output"))
    }

    @Test
    fun `the Bash tool's own word for nothing is nothing`() = runComposeUiTest {
        // Verbatim 2.1.316, five of its twenty records on this host.
        row(user("<bash-stdout>(Bash completed with no output)</bash-stdout><bash-stderr></bash-stderr>"))
        assertEquals(1, shown("no output"))
        assertEquals(0, shown("Bash completed", substring = true))
    }

    @Test
    fun `a long output is folded to its first lines until it is opened`() = runComposeUiTest {
        val lines = (1..40).joinToString("\n") { "line $it" }
        row(user("<bash-stdout>$lines</bash-stdout><bash-stderr></bash-stderr>"))
        assertEquals(1, shown("line 1", substring = true))
        assertEquals(0, shown("line 40", substring = true), "the tail waits behind the fold")
        assertTrue(shown("more line", substring = true) == 1, "and the fold says how much is behind it")
    }

    @Test
    fun `a long press quotes the command and the output, not the markup`() {
        assertEquals("pwd", rowSelectionText(user("<bash-input>pwd</bash-input>")))
        assertEquals(
            "/home/alex/projects/x",
            rowSelectionText(user("<bash-stdout>/home/alex/projects/x</bash-stdout><bash-stderr></bash-stderr>")),
        )
        assertEquals(
            "a -> b\nboom",
            rowSelectionText(user("<bash-stdout>a -&gt; b</bash-stdout><bash-stderr>boom</bash-stderr>")),
        )
    }
}
