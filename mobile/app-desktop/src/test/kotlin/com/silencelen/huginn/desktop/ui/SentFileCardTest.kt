package com.silencelen.huginn.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.silencelen.huginn.data.SentFile
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.desktop.DesktopSentFiles
import com.silencelen.huginn.ui.LocalSentFiles
import com.silencelen.huginn.ui.SENT_FILE_TAG
import com.silencelen.huginn.ui.SentFileHost
import com.silencelen.huginn.ui.TranscriptEventItem
import com.silencelen.huginn.ui.theme.HuginnTheme
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A file Claude sent (send_file), drawn in place of its tool row, with Download and Open. */
@OptIn(ExperimentalTestApi::class)
class SentFileCardTest {
    private class FakeHost : SentFileHost {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(id: String): ByteArray { fetched += id; return "BYTES".encodeToByteArray() }
        override suspend fun download(file: SentFile, bytes: ByteArray) = "Saved to /home/x/Downloads/${file.name}"
        override suspend fun open(file: SentFile, bytes: ByteArray): String? = null
    }

    private fun tool(file: SentFile) = TranscriptEvent(seq = 1, kind = "tool", name = "mcp__huginn__send_file", ok = true, file = file)

    @Test
    fun `a sent file replaces its tool row and downloads through the host`() = runComposeUiTest {
        val host = FakeHost()
        setContent {
            HuginnTheme(darkTheme = true) {
                CompositionLocalProvider(LocalSentFiles provides host) {
                    TranscriptEventItem(tool(SentFile("out-1793400000000-abcdef.pdf", "q3 report.pdf", size = 2048, caption = "the numbers")), onCopy = {})
                }
            }
        }
        waitForIdle()
        assertEquals(1, onAllNodesWithText("q3 report.pdf").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText("PDF · 2 KB").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText("the numbers").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("mcp__huginn__send_file", substring = true).fetchSemanticsNodes().size, "the tool row is gone")
        assertEquals(1, onAllNodesWithTag("$SENT_FILE_TAG.open").fetchSemanticsNodes().size)
        onNodeWithTag("$SENT_FILE_TAG.download").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Saved to /home/x/Downloads/q3 report.pdf").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("out-1793400000000-abcdef.pdf"), host.fetched)
    }

    @Test
    fun `something that runs when opened has no Open button`() = runComposeUiTest {
        setContent {
            HuginnTheme(darkTheme = true) {
                CompositionLocalProvider(LocalSentFiles provides FakeHost()) {
                    TranscriptEventItem(tool(SentFile("out-1793400000000-abcdef.exe", "setup.exe", size = 10)), onCopy = {})
                }
            }
        }
        waitForIdle()
        assertEquals(1, onAllNodesWithTag("$SENT_FILE_TAG.download").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag("$SENT_FILE_TAG.open").fetchSemanticsNodes().size)
    }

    @Test
    fun `the desktop saves without overwriting and refuses to open an executable`() = runBlocking {
        val dir = Files.createTempDirectory("dl").toFile()
        var opened = 0
        val host = DesktopSentFiles({ error("no client in this test") }, downloads = { dir }, opener = { opened++; true })
        val f = SentFile("out-1-abcdef.pdf", "r.pdf")
        assertEquals("Saved to ${dir.path}/r.pdf", host.download(f, byteArrayOf(1)))
        assertEquals("Saved to ${dir.path}/r (2).pdf", host.download(f, byteArrayOf(2)))
        assertEquals("This type is download-only", host.open(SentFile("out-1-abcdef.sh", "run.sh"), byteArrayOf(1)))
        assertEquals(0, opened)
        assertEquals(null, host.open(f, byteArrayOf(1)))
        assertEquals(1, opened)
        assertTrue(dir.deleteRecursively())
    }
}
