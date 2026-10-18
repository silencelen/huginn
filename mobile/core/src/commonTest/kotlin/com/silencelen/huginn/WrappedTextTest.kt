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
        val msg = "<cross-session-message from=\"uds:/tmp/cc-socks/1.sock\" from-name=\"netplan-fd\" " +
            "from-mode=\"prompting\">\n<agent-message from=\"ae6c\">\nSPIKE-PING: reply PONG.\n</agent-message>\n" +
            "</cross-session-message>"
        val segs = WrappedText.segments(msg)
        assertEquals(1, segs.size)
        assertEquals("Message from netplan-fd", segs[0].label)
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
}
