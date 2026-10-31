package com.silencelen.huginn

import com.silencelen.huginn.data.SentFile
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.SentFiles
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file Claude sent (appd send_file): the wire, and the card's rules. */
class SentFilesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theToolRowCarriesTheFileAndAnOlderDaemonCarriesNone() {
        val ev = json.decodeFromString<TranscriptEvent>(
            """{"seq":3,"kind":"tool","name":"mcp__huginn__send_file","ok":true,
               "file":{"id":"out-1793400000000-abcdef.png","name":"chart.png","mime":"image/png","size":2048,"image":true,"caption":"the chart"}}""",
        )
        assertEquals(SentFile("out-1793400000000-abcdef.png", "chart.png", "image/png", 2048, true, "the chart"), ev.file)
        assertNull(json.decodeFromString<TranscriptEvent>("""{"seq":1,"kind":"tool","name":"Bash"}""").file)
    }

    @Test
    fun anythingThatRunsWhenOpenedIsDownloadOnly() {
        fun f(name: String) = SentFile(id = "out-1-abcdef.${name.substringAfterLast('.')}", name = name)
        assertTrue(SentFiles.openable(f("report.pdf")))
        assertTrue(SentFiles.openable(f("notes.txt")))
        assertTrue(SentFiles.openable(f("chart.png")))
        for (n in listOf("huginn.apk", "setup.exe", "run.sh", "page.html", "logo.svg", "tool.jar", "x.ps1", "a.desktop")) {
            assertFalse(SentFiles.openable(f(n)), n)
        }
        // A harmless name on an executable id is still judged by the id.
        assertFalse(SentFiles.openable(SentFile(id = "out-1-abcdef.exe", name = "readme.txt")))
    }

    @Test
    fun theCardSaysTypeAndSizeAndSavesUnderASafeName() {
        assertEquals("PDF · 1.5 KB", SentFiles.meta(SentFile("out-1-abcdef.pdf", "r.pdf", size = 1536)))
        assertEquals("12 MB", SentFiles.sizeWords(12L * 1024 * 1024 + 5))
        assertEquals("900 B", SentFiles.sizeWords(900))
        assertEquals("report _q3_.pdf", SentFiles.saveName(SentFile("out-1-abcdef.pdf", "../report \"q3\".pdf")))
        assertEquals("out-1-abcdef.bin", SentFiles.saveName(SentFile("out-1-abcdef.bin", "...")))
    }
}
