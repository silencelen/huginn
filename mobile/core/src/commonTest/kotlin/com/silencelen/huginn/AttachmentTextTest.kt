package com.silencelen.huginn

import com.silencelen.huginn.ui.AttachBatch
import com.silencelen.huginn.ui.AttachmentText
import com.silencelen.huginn.ui.PANE_SEPARATOR
import com.silencelen.huginn.ui.composeMessage
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

/**
 * The join, which is now ONE rule in `:core` rather than three.
 *
 * It lived in the desktop module (`AttachmentController.composeMessage`) and the
 * phone hand-rolled `"\n\n"` at both of its send sites, so the pane variant — the
 * one where a newline is the submit key — existed in exactly one of the three
 * places. These assertions are what both shells now go through.
 */
class ComposeMessageRuleTest {

    private val a = AttachmentText.marker("/up/one.jpg")
    private val b = AttachmentText.marker("/up/two.png")
    private val c = AttachmentText.fileMarker("/up/notes.pdf", "notes.pdf")

    @Test
    fun `markers ride in attach order, all of them`() {
        val sent = composeMessage("look at these", listOf(a, b))
        assertEquals(
            listOf("/up/one.jpg", "/up/two.png"),
            AttachmentText.imagePaths(sent),
            "intake order is chip order is marker order",
        )
        // The size is the assertion that matters: the single-marker rule this
        // replaced returned one path here and read as correct.
        assertEquals(2, AttachmentText.imagePaths(sent).size)
        assertTrue(sent.startsWith("look at these"), "the typed text comes first")
    }

    @Test
    fun `a pane line never contains a newline, between markers either`() {
        val line = composeMessage("look at these", listOf(a, b, c), PANE_SEPARATOR)
        // A pane is TYPED into and a newline is the submit key. Joining the TEXT
        // to the first marker correctly while joining marker to marker with "\n"
        // is the version of this bug that only appears with two attachments.
        assertTrue('\n' !in line, "no newline may reach a tmux pane mid-message")
        assertEquals(3, line.split(PANE_SEPARATOR).count { it.startsWith("[Attached") })
    }

    @Test
    fun `both shells now join the same way — the phone's old hand-rolled two newlines`() {
        // What `HuginnViewModel` used to write inline, twice, and what the
        // desktop composer wrote in its own module: identical for one marker, so
        // adopting the shared rule changed no existing message.
        assertEquals("draft\n\n$a", composeMessage("draft", listOf(a)))
        assertEquals("draft\n\n$a", composeMessage("draft", a))
        assertEquals(a, composeMessage("   ", listOf(a)), "a blank draft sends the marker alone")
    }

    @Test
    fun `no markers means the message is just the trimmed text`() {
        assertEquals("hello", composeMessage("  hello  ", emptyList()))
        assertEquals("hello", composeMessage("hello", listOf("", "   ")))
        assertEquals("hello", composeMessage("hello", null as String?))
    }

    @Test
    fun `the cap is ten, and it is the same number on both shells`() {
        assertEquals(10, AttachBatch.MAX_ITEMS)
        assertEquals(3, AttachBatch.accept(7, (1..9).toList()).size)
        assertEquals(0, AttachBatch.accept(10, listOf(1, 2)).size)
        assertEquals(listOf(1, 2, 3), AttachBatch.accept(0, listOf(1, 2, 3)), "order is kept")
        assertNull(AttachBatch.refusedNote(0, 4), "nothing to say when it all fit")
        assertTrue(AttachBatch.refusedNote(8, 4)!!.contains("2 left off"))
    }

    @Test
    fun `a partial batch names what failed and says the rest went`() {
        assertEquals(
            "2 of 5 attachments did not upload: notes.pdf, big.zip — sent without them",
            AttachBatch.failureLine(listOf("notes.pdf", "big.zip"), 5),
        )
        assertEquals(
            "1 of 3 attachments did not upload: notes.pdf — sent without it",
            AttachBatch.failureLine(listOf("notes.pdf"), 3),
        )
        assertNull(AttachBatch.failureLine(emptyList(), 3), "a clean batch says nothing")
    }
}
