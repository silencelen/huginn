package com.silencelen.huginn

import com.silencelen.huginn.ui.ImageMentions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "I wrote the chart to /tmp/claude-0/x/plot.png" is the single most common way
 * an answer refers to a picture, and until now the reader had to go and open it
 * somewhere else. This is the scanner that turns that sentence into a thumbnail.
 *
 * It is NOT [com.silencelen.huginn.ui.AttachmentText.imagePaths], which reads
 * attachment MARKERS out of a message the person sent. This one reads assistant
 * PROSE, where a path is just a word in a sentence and everything around it —
 * punctuation, parentheses, backticks — is someone else's syntax. Two scanners,
 * two jobs, deliberately not merged.
 */
class ImageMentionsTest {

    @Test
    fun `an absolute posix path is a mention`() {
        assertEquals(listOf("/tmp/claude-0/shot.png"), ImageMentions.paths("wrote /tmp/claude-0/shot.png"))
    }

    @Test
    fun `every path shape the assistant actually writes is found`() {
        assertEquals(listOf("~/shots/a.jpg"), ImageMentions.paths("saved to ~/shots/a.jpg"))
        assertEquals(listOf("./out/b.jpeg"), ImageMentions.paths("see ./out/b.jpeg"))
        assertEquals(listOf("../sibling/c.webp"), ImageMentions.paths("or ../sibling/c.webp"))
        assertEquals(listOf("""C:\Users\me\d.gif"""), ImageMentions.paths("""on the box: C:\Users\me\d.gif"""))
        assertEquals(listOf("C:/Users/me/e.bmp"), ImageMentions.paths("or C:/Users/me/e.bmp"))
    }

    @Test
    fun `the extension decides, case-insensitively`() {
        assertEquals(listOf("/tmp/A.PNG"), ImageMentions.paths("at /tmp/A.PNG"))
        assertEquals(listOf("/tmp/b.JpEg"), ImageMentions.paths("at /tmp/b.JpEg"))
    }

    @Test
    fun `a non-image extension is not a mention`() {
        assertTrue(ImageMentions.paths("wrote /tmp/report.pdf and /tmp/x.log and /etc/passwd").isEmpty())
        assertTrue(ImageMentions.paths("edited /root/huginn/Markdown.kt").isEmpty())
        // .svg is an image to a browser and a script to a renderer we do not have.
        assertTrue(ImageMentions.paths("drew /tmp/chart.svg").isEmpty())
    }

    /**
     * ⚠ THE ONE THAT MATTERS. Half of what an answer contains is a command, and a
     * command names files constantly — `rm /tmp/old.png`, a `ls` listing, a diff.
     * Drawing a picture of every file a code block mentions turns an answer into
     * a contact sheet.
     */
    @Test
    fun `paths inside code are not mentions`() {
        assertTrue(ImageMentions.paths("run `open /tmp/a.png` yourself").isEmpty(), "inline code span")
        assertTrue(
            ImageMentions.paths("do this:\n\n```bash\nrm /tmp/a.png /tmp/b.png\n```\n").isEmpty(),
            "fenced block",
        )
        // Prose either side of a fence still counts.
        assertEquals(
            listOf("/tmp/keep.png"),
            ImageMentions.paths("wrote /tmp/keep.png\n\n```\nrm /tmp/gone.png\n```\n"),
        )
    }

    @Test
    fun `a markdown image or link destination is left to the markdown renderer`() {
        // It already draws as an MdBlock.Image; a mention thumbnail underneath
        // would be the same picture twice.
        assertTrue(ImageMentions.paths("![a shot](/tmp/shot.png)").isEmpty())
        assertTrue(ImageMentions.paths("see [the shot](/tmp/shot.png)").isEmpty())
    }

    @Test
    fun `surrounding punctuation is not part of the path`() {
        assertEquals(listOf("/tmp/a.png"), ImageMentions.paths("wrote /tmp/a.png."))
        assertEquals(listOf("/tmp/a.png"), ImageMentions.paths("(/tmp/a.png)"))
        assertEquals(listOf("/tmp/a.png"), ImageMentions.paths("\"/tmp/a.png\","))
        assertEquals(listOf("/tmp/a.png"), ImageMentions.paths("at /tmp/a.png!"))
    }

    @Test
    fun `a bare relative path is not a mention, because a client cannot resolve one`() {
        assertTrue(ImageMentions.paths("see shot.png").isEmpty())
        assertTrue(ImageMentions.paths("see out/shot.png").isEmpty())
    }

    @Test
    fun `the same path said twice draws once`() {
        assertEquals(
            listOf("/tmp/a.png"),
            ImageMentions.paths("/tmp/a.png is the before, and /tmp/a.png again"),
        )
    }

    @Test
    fun `a directory listing does not become a gallery`() {
        val many = (1..9).joinToString(" ") { "/tmp/shot$it.png" }
        assertEquals(ImageMentions.MAX_PER_MESSAGE, ImageMentions.paths(many).size)
        assertEquals(2, ImageMentions.paths(many, max = 2).size)
        assertEquals("/tmp/shot1.png", ImageMentions.paths(many).first(), "in the order they were written")
    }

    @Test
    fun `an empty or pathless message costs nothing`() {
        assertTrue(ImageMentions.paths("").isEmpty())
        assertTrue(ImageMentions.paths("Disk is at 62% and the daemon is healthy.").isEmpty())
    }
}
