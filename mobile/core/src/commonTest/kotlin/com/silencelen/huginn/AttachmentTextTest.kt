package com.silencelen.huginn

import com.silencelen.huginn.ui.AttachmentText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The :core companion to app-desktop's AttachTest — this file's contract has
 * copies in the Electron client (attachmentMarker.ts) and in appd
 * (humanizeUserText), so the exact literal is PINNED here: changing the wording
 * silently breaks both of those and orphans every thumbnail.
 */
class AttachmentTextTest {

    @Test
    fun `the marker literal is pinned — copies exist in Electron and appd`() {
        assertEquals(
            "[Attached image at /var/lib/huginn-appd/uploads/up-1-ab.jpg — view it with the Read tool.]",
            AttachmentText.marker("/var/lib/huginn-appd/uploads/up-1-ab.jpg"),
        )
    }

    @Test
    fun `marker round-trips through imagePaths`() {
        val path = "/var/lib/huginn-appd/uploads/up-1786400000000-a1b2c3.jpg"
        val text = "look at this\n\n" + AttachmentText.marker(path)
        assertEquals(listOf(path), AttachmentText.imagePaths(text))
    }

    @Test
    fun `legacy img-star names parse too`() {
        val path = "/var/lib/huginn-appd/uploads/img-1785279197583-fa8d3c.jpg"
        assertEquals(listOf(path), AttachmentText.imagePaths(AttachmentText.marker(path)))
    }

    @Test
    fun `two markers yield two paths in order`() {
        val a = "/up/one.jpg"; val b = "/up/two.png"
        val text = AttachmentText.marker(a) + "\n" + AttachmentText.marker(b)
        assertEquals(listOf(a, b), AttachmentText.imagePaths(text))
    }

    @Test
    fun `plain text has no image paths`() {
        assertTrue(AttachmentText.imagePaths("no markers here [not one]").isEmpty())
    }

    @Test
    fun `uploadName is the basename, and a bare directory is null`() {
        assertEquals("up-1-ab.jpg", AttachmentText.uploadName("/var/lib/huginn-appd/uploads/up-1-ab.jpg"))
        assertEquals("plain.jpg", AttachmentText.uploadName("plain.jpg"))
        assertNull(AttachmentText.uploadName("/var/lib/huginn-appd/uploads/"))
    }

    @Test
    fun `stripImageMarkers leaves the words and drops the plumbing`() {
        val text = "check the roof\n\n" + AttachmentText.marker("/up/x.jpg")
        assertEquals("check the roof", AttachmentText.stripImageMarkers(text))
        // A message that was ONLY the marker strips to empty — the caller shows
        // the thumbnail alone.
        assertEquals("", AttachmentText.stripImageMarkers(AttachmentText.marker("/up/x.jpg")))
    }

    @Test
    fun `displayText still renders the pill (the fallback path)`() {
        assertEquals("📷 Photo attached", AttachmentText.displayText(AttachmentText.marker("/up/x.jpg")))
    }

    @Test
    fun `file markers are not image paths`() {
        val text = AttachmentText.fileMarker("/up/backup.tar.gz", "backup.tar.gz", readable = false)
        assertTrue(AttachmentText.imagePaths(text).isEmpty())
        assertEquals(text, AttachmentText.stripImageMarkers(text))
    }
}
