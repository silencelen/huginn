package com.silencelen.huginn.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.TranscriptEvent
import com.silencelen.huginn.ui.AttachmentImageLoader
import com.silencelen.huginn.ui.LocalAttachmentImages
import com.silencelen.huginn.ui.SkiaImageBytesDecoder
import com.silencelen.huginn.ui.TranscriptEventItem
import com.silencelen.huginn.ui.theme.HuginnTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A user message's photos, every one of them (2026-10-30). The bubble took the
 * FIRST marker and stripped them all, so a three-photo message showed one photo
 * and the other two vanished without a trace.
 */
@OptIn(ExperimentalTestApi::class)
class UserBubblePhotosTest {
    private fun png(): ByteArray {
        val bmp = Bitmap().apply { allocN32Pixels(6, 6) }
        return Image.makeFromBitmap(bmp).encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun marker(name: String) = "[Attached image at /var/lib/huginn-appd/uploads/$name — view it with the Read tool.]"

    @Test
    fun `three photos draw three thumbnails, and one that is gone keeps a placeholder`() = runComposeUiTest {
        val bytes = png()
        val loader = AttachmentImageLoader(
            fetch = { name -> if (name.contains("gone")) throw HuginnClient.HuginnException(404, "gone") else bytes },
            decoder = SkiaImageBytesDecoder(),
        )
        val text = "look at these ${marker("up-1-a.png")} ${marker("up-2-b.png")} ${marker("up-3-c.png")} ${marker("up-4-gone.png")}"
        setContent {
            HuginnTheme(darkTheme = true) {
                CompositionLocalProvider(LocalAttachmentImages provides loader) {
                    TranscriptEventItem(TranscriptEvent(seq = 1, kind = "user", text = text), onCopy = {})
                }
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Photo attachment").fetchSemanticsNodes().size == 3 }
        assertEquals(3, onAllNodesWithContentDescription("Photo attachment").fetchSemanticsNodes().size)
        assertEquals(1, onAllNodesWithText("Photo").fetchSemanticsNodes().size, "the missing one keeps its slot")
        assertEquals(1, onAllNodesWithText("look at these", substring = true).fetchSemanticsNodes().size, "the words still show")
    }
}
