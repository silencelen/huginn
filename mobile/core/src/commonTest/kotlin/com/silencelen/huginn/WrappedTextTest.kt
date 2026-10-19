package com.silencelen.huginn

import com.silencelen.huginn.ui.WrappedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A long paste is stored wrapped in `<pasted_content>` tags, and the bubble drew
 * the tags as the owner's own words. The shapes below are Claude Code's, as
 * stored in this host's transcripts (the content shortened).
 */
class WrappedTextTest {
    private val paste = "\n\n<pasted_content id=\"d842\">\nbug: empty chats should auto delete\n" +
        "bug: projects should show # of projects\n</pasted_content id=\"d842\">\n"

    @Test
    fun `a message that is one paste is one folded block with no tags`() {
        val segs = WrappedText.segments(paste)
        assertEquals(1, segs.size)
        assertEquals("Pasted text · 2 lines", segs[0].label)
        assertEquals("bug: empty chats should auto delete\nbug: projects should show # of projects", segs[0].text)
        assertFalse("pasted_content" in segs[0].text)
    }

    @Test
    fun `typed words around a paste stay typed, in order`() {
        val segs = WrappedText.segments("look at this$paste and fix it")
        assertEquals(listOf(null, "Pasted text · 2 lines", null), segs.map { it.label })
        assertEquals("look at this", segs[0].text)
        assertEquals("and fix it", segs[2].text)
    }

    @Test
    fun `a closer naming another id does not end the paste`() {
        val quoted = "<pasted_content id=\"aa\">\nX </pasted_content id=\"bb\"> Y\n</pasted_content id=\"aa\">\n"
        val segs = WrappedText.segments(quoted)
        assertEquals(1, segs.size)
        assertEquals("X </pasted_content id=\"bb\"> Y", segs[0].text)
    }

    @Test
    fun `an opener with no closer is just text`() {
        val text = "the tag looks like <pasted_content id=\"ab\"> in a transcript"
        assertFalse(WrappedText.hasWrapped(text))
        assertEquals(listOf(WrappedText.Segment(text)), WrappedText.segments(text))
    }

    @Test
    fun `a raw subagent report folds under its sender, frame and indent removed`() {
        val report = "<agent-message from=\"aaeabc37b078a2927\">\n[Subagent hand-back] The text below is the " +
            "final report. The report follows:\n  I could only do part of this.\n  \n  - **S0**: not run.\n</agent-message>"
        val segs = WrappedText.segments(report)
        assertEquals(1, segs.size)
        assertEquals("Message from a subagent", segs[0].label)
        assertEquals("I could only do part of this.\n\n- **S0**: not run.", segs[0].text)
    }

    @Test
    fun `a cross-session message folds under the sending session's name, one card`() {
        val msg = "<cross-session-message from=\"uds:/tmp/cc-socks/1.sock\" from-name=\"app-fd\" " +
            "from-mode=\"prompting\">\n<agent-message from=\"ae6c\">\nSPIKE-PING: reply PONG.\n</agent-message>\n" +
            "</cross-session-message>"
        val segs = WrappedText.segments(msg)
        assertEquals(1, segs.size)
        assertEquals("Message from app-fd", segs[0].label)
        assertEquals("SPIKE-PING: reply PONG.", segs[0].text)
    }

    @Test
    fun `plain keeps the words and drops the markup`() {
        assertEquals("look at this\n\nbug: empty chats should auto delete\nbug: projects should show # of projects",
            WrappedText.plain("look at this$paste"))
        assertEquals("nothing wrapped", WrappedText.plain("nothing wrapped"))
    }

    @Test
    fun `the preview is the first line worth reading`() {
        assertEquals("first", WrappedText.preview("\n  \n first \nsecond"))
        assertEquals("x".repeat(110), WrappedText.preview("x".repeat(300)))
        assertTrue(WrappedText.preview("").isEmpty())
    }

    @Test
    fun `one line is one line`() {
        assertEquals("Pasted text · 1 line", WrappedText.pastedLabel("only\n"))
    }

    // ------------------------------------------------- the scan is the regex

    /**
     * The patterns the scan replaced, kept here as the reference it is held to.
     * They are correct and only slow — quadratic on an opener whose closer
     * never comes — so they judge small messages and nothing else.
     */
    private val oldPasted = Regex(
        """<pasted_content id="([^"\n]{1,32})">\n?([\s\S]*?)\n?</pasted_content id="\1">\n?""",
    )
    private val oldAgent = Regex("""<agent-message\b[^>]*>\n?([\s\S]*?)\n?</agent-message>""")
    private val oldCross = Regex("""<cross-session-message\b([^>]*)>\n?([\s\S]*?)\n?</cross-session-message>""")

    private fun scanned(blocks: List<WrappedText.Block>) = blocks.map { Triple(it.range, it.body, it.attrs) }

    private fun assertSameAsRegex(text: String) {
        assertEquals(
            oldPasted.findAll(text).map { Triple(it.range, it.groupValues[2], "") }.toList(),
            scanned(WrappedText.pastedBlocks(text)),
            "pasted: ${text.replace("\n", "\\n")}",
        )
        assertEquals(
            oldAgent.findAll(text).map { Triple(it.range, it.groupValues[1], text.substring(it.range.first + 14).substringBefore('>')) }.toList(),
            scanned(WrappedText.taggedBlocks(text, "<agent-message", "</agent-message>")),
            "agent: ${text.replace("\n", "\\n")}",
        )
        assertEquals(
            oldCross.findAll(text).map { Triple(it.range, it.groupValues[2], it.groupValues[1]) }.toList(),
            scanned(WrappedText.taggedBlocks(text, "<cross-session-message", "</cross-session-message>")),
            "cross: ${text.replace("\n", "\\n")}",
        )
    }

    @Test
    fun `the scan finds exactly what the patterns found, case by case`() {
        for (text in listOf(
            paste,
            "a<pasted_content id=\"x\"></pasted_content id=\"x\">b",
            "<pasted_content id=\"x\">\n</pasted_content id=\"x\">",
            "<pasted_content id=\"x\">\n\n</pasted_content id=\"x\">\n\n",
            "<pasted_content id=\"x\">a\n</pasted_content id=\"y\">\n</pasted_content id=\"x\">",
            "<pasted_content id=\"x\"><pasted_content id=\"x\">in</pasted_content id=\"x\"></pasted_content id=\"x\">",
            "<pasted_content id=\"a\">1</pasted_content id=\"b\"><pasted_content id=\"b\">2</pasted_content id=\"a\"></pasted_content id=\"b\">",
            "<pasted_content id=\"" + "i".repeat(32) + "\">ok</pasted_content id=\"" + "i".repeat(32) + "\">",
            "<pasted_content id=\"" + "i".repeat(33) + "\">no</pasted_content id=\"" + "i".repeat(33) + "\">",
            "<pasted_content id=\"\">empty id</pasted_content id=\"\">",
            "<pasted_content id=\"a\nb\">newline in id</pasted_content id=\"a\nb\">",
            "<pasted_content id=\"<pasted_content id=\">x</pasted_content id=\"<pasted_content id=\">",
            "<agent-message>a</agent-message><agent-message from=\"x\">\nb\n</agent-message>",
            "<agent-messages>no</agent-message><agent-message-x>yes</agent-message>",
            "<agent-message\nfrom=\"x\"\n>spans\n</agent-message>",
            "</agent-message><agent-message>after the last closer",
            "<agent-message no closing bracket</agent-message>",
            "<cross-session-message from-name=\"a\">\n<agent-message>x</agent-message>\n</cross-session-message>",
            "<cross-session-message>one</cross-session-message><cross-session-message>two</cross-session-message>",
        )) {
            assertSameAsRegex(text)
        }
    }

    /** Generated messages over the characters the patterns turn on, seeded so a failure repeats. */
    @Test
    fun `the scan finds exactly what the patterns found, on generated messages`() {
        val pieces = listOf(
            "<pasted_content id=\"", "</pasted_content id=\"", "\">", "<agent-message", "</agent-message>",
            "<cross-session-message", "</cross-session-message>", " from-name=\"n\"", ">", "\n", "\"", "a", "b", "x",
            "<", "-", "_", " ",
        )
        val random = kotlin.random.Random(20261019)
        repeat(4000) {
            val text = buildString { repeat(random.nextInt(1, 24)) { append(pieces[random.nextInt(pieces.size)]) } }
            assertSameAsRegex(text)
        }
    }

    // ------------------------------------------------------------ the timing

    /**
     * ⚠ THE REASON THE PATTERNS WENT. Many openers whose closer never comes —
     * one closer of another id is enough to get past the cheap pre-check — and
     * the backreferenced pattern scanned to the end of the message once per
     * opener: time in the square of the length, on the render path. Measured
     * on the JVM, 2,500 such openers (60 KB) took the regex 6.6 s and 5,000
     * took 28 s; one pass takes milliseconds, and the bound below is generous.
     */
    @Test
    fun `many openers without closers cost one pass, not one pass each`() {
        val openers = 4_000
        val pasted = "<pasted_content id=\"ab\">".repeat(openers) + "</pasted_content id=\"zz\">"
        val agents = "</agent-message>" + "<agent-message>".repeat(openers)
        val crosses = "</cross-session-message>" + "<cross-session-message>".repeat(openers)
        for (text in listOf(pasted, agents, crosses)) {
            assertTrue(WrappedText.hasWrapped(text), "the pre-check passes, so the scan really runs")
            val mark = kotlin.time.TimeSource.Monotonic.markNow()
            val segs = WrappedText.segments(text)
            val took = mark.elapsedNow()
            assertEquals(1, segs.size, "nothing folds: no opener has its closer")
            assertTrue(took.inWholeMilliseconds < 2_000, "took $took on ${text.length} characters")
        }
    }
}
