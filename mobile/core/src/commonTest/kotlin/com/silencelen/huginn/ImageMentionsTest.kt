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

    // ------------------------------------------------- the scan is the regex

    /**
     * The patterns the scan replaced, kept here as the reference it is held to.
     * They are correct and only slow — quadratic on a long run with many places
     * a path could start — so they judge small messages and nothing else.
     */
    private val oldPath = Regex(
        "(?<![A-Za-z0-9_.~/\\\\-])" +
            "((?:[A-Za-z]:[\\\\/]|~/|\\.\\.?/|/)[^\\s\"'`<>|*?()\\[\\]{},;!]*\\.(?:png|jpe?g|gif|webp|bmp))" +
            "(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val oldDestination = Regex("\\]\\([^)]*\\)")

    /**
     * What the old scan returned for text with no code in it (the masking of
     * code did not change, and the messages below never form a fence or a span).
     */
    private fun oldPaths(text: String, max: Int): List<String> {
        val blanked = oldDestination.replace(text) { " ".repeat(it.value.length) }
        val out = LinkedHashSet<String>()
        for (m in oldPath.findAll(blanked)) {
            out.add(m.groupValues[1])
            if (out.size >= max) break
        }
        return out.toList()
    }

    private fun assertSameAsRegex(text: String) {
        for (max in listOf(1_000, ImageMentions.MAX_PER_MESSAGE, 1)) {
            assertEquals(oldPaths(text, max), ImageMentions.paths(text, max), "max $max: ${text.replace("\n", "\\n")}")
        }
    }

    @Test
    fun `the scan finds exactly what the patterns found, case by case`() {
        for (text in listOf(
            "/a.png/b.png",
            "/tmp/a.png.bak and /tmp/a.png_x and /tmp/a.pngx.png and /tmp/a.pngx",
            "file:/tmp/a.png http://host/x/y.jpg C:/a.png:/b.gif",
            "::C:/x.png a:\\b\\c.bmp aC:/x.png ~/~/a.png ./../a.png ../.png /.png",
            "x.png/y ./png ../x.webp. ~/a.JPEG! (./b.jPg) [/c.gif] {/d.bmp} </e.png> |/f.png| *g/h.png*",
            "é/a.png ü/b.png a/c.png _/d.png -/e.png ~/f.png ./g.png //h.png \\/i.png",
            "[a](/tmp/a.png) /tmp/b.png [c](/tmp/c.png /tmp/d.png ](/tmp/e.png) /tmp/f.png ](",
            "/tmp/a.png /tmp/b.png /tmp/a.png /tmp/c.png /tmp/d.png /tmp/e.png",
            "/x/a.png\t/x/b.jpg\n/x/c.gif /x/d.webp /x/e.bmp /x/f.svg",
            ":/:/:/a.png :/:/:/ /:/b.png",
        )) {
            assertSameAsRegex(text)
        }
    }

    /**
     * Generated messages over the characters the patterns turn on, seeded so a
     * failure repeats. ASCII whitespace only: past it the old `\s` meant one
     * thing on the JVM and another on Android, and the scan now treats every
     * whitespace character as the end of a path on both.
     */
    @Test
    fun `the scan finds exactly what the patterns found, on generated messages`() {
        val pieces = listOf(
            "/", "./", "../", "~/", "C:\\", "c:/", ":", ".png", ".PNG", ".jpg", ".jpeg", ".Gif", ".webp", ".bmp",
            ".svg", ".", "png", "a", "x", "1", "_", "-", "\\", "é", "(", ")", "[", "]", "](", "{", "}", "<", ">",
            "|", "*", "?", "!", ",", ";", "\"", "'", " ", "\t", "\n", "@", "=",
        )
        val random = kotlin.random.Random(20261023)
        var withOne = 0
        var withMore = 0
        repeat(4_000) {
            val text = buildString { repeat(random.nextInt(1, 28)) { append(pieces[random.nextInt(pieces.size)]) } }
            assertSameAsRegex(text)
            val found = oldPaths(text, 1_000).size
            if (found >= 1) withOne++
            if (found >= 2) withMore++
        }
        // Agreement on messages that hold nothing proves nothing: about one in
        // five of these holds a mention, and one in fifty holds several.
        assertTrue(withOne >= 400 && withMore >= 20, "only $withOne messages held a mention, $withMore several")
    }

    // ------------------------------------------------------------ the timing

    /**
     * ⚠ THE REASON THE PATTERNS WENT. Text the model wrote, scanned inside
     * `remember` on the UI thread: a run of `:/` is a run of places a path could
     * start, none of them ending in an image, and the path pattern scanned to the
     * end of the run and back from every one of them. A run of `](` with no `)`
     * did the same to the destination pattern. Measured on the JVM, 50,000
     * characters of either took the old patterns four to six seconds; one pass
     * takes milliseconds, and the bound below is generous.
     */
    @Test
    fun `a long degenerate run costs one pass, not one pass per place a path could start`() {
        val n = 50_000
        for (text in listOf(":/".repeat(n / 2), "](".repeat(n / 2), "/a.".repeat(n / 3), "](/".repeat(n / 3))) {
            val mark = kotlin.time.TimeSource.Monotonic.markNow()
            val found = ImageMentions.paths(text)
            val took = mark.elapsedNow()
            assertTrue(found.isEmpty(), "nothing in ${text.take(6)}… is an image")
            assertTrue(took.inWholeMilliseconds < 2_000, "took $took on ${text.length} chars of ${text.take(6)}…")
        }
    }

    @Test
    fun `only the first part of a very long message is read, cut between lines`() {
        val filler = "Disk is fine.\n".repeat(ImageMentions.MAX_SCAN_CHARS / 14 + 10)
        assertEquals(listOf("/tmp/early.png"), ImageMentions.paths("wrote /tmp/early.png\n$filler/tmp/late.png"))
        assertTrue(ImageMentions.paths("$filler/tmp/late.png").isEmpty(), "a path past the limit draws nothing")

        // A span the limit would have cut in half is not left with a dangling
        // tick: the line it sits on is not read at all.
        val pad = "x".repeat(ImageMentions.MAX_SCAN_CHARS - 20)
        assertTrue(ImageMentions.paths("$pad\nrun `open /tmp/a.png` yourself, it is fine").isEmpty())

        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        assertTrue(ImageMentions.paths(":/".repeat(1_000_000)).isEmpty())
        assertTrue(mark.elapsedNow().inWholeMilliseconds < 2_000, "a two-megabyte run is read only to the limit")
    }
}
