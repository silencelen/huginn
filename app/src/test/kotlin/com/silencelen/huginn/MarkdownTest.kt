package com.silencelen.huginn

import com.silencelen.huginn.ui.MdBlock
import com.silencelen.huginn.ui.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer only has to handle what Claude actually writes in an answer. The
 * property that matters most is that a code fence survives as a code block:
 * flattening a shell command into prose is what made v1's chat unusable on a
 * phone, and a mangled command is worse than no command.
 */
class MarkdownTest {

    @Test
    fun `a fenced block becomes a code block with its language`() {
        val b = Markdown.parse("Run this:\n\n```bash\ncd /root/netplan\ngit status\n```\n")
        assertEquals(2, b.size)
        assertTrue(b[0] is MdBlock.Paragraph)
        val code = b[1] as MdBlock.Code
        assertEquals("bash", code.lang)
        assertEquals("cd /root/netplan\ngit status", code.code)
    }

    @Test
    fun `markdown inside a fence is left completely alone`() {
        val code = Markdown.parse("```\n**not bold** and *not italic* and `not code`\n```").first() as MdBlock.Code
        assertEquals("**not bold** and *not italic* and `not code`", code.code)
    }

    @Test
    fun `an unclosed fence still yields a code block rather than eating the answer`() {
        val b = Markdown.parse("```python\nprint(1)\n")
        val code = b.first() as MdBlock.Code
        assertEquals("print(1)", code.code)
    }

    @Test
    fun `a tilde fence works like a backtick fence`() {
        val code = Markdown.parse("~~~\nplain\n~~~").first() as MdBlock.Code
        assertEquals("plain", code.code)
    }

    @Test
    fun `headings carry their level`() {
        val b = Markdown.parse("# One\n## Two\n### Three")
        assertEquals(1, (b[0] as MdBlock.Heading).level)
        assertEquals(2, (b[1] as MdBlock.Heading).level)
        assertEquals(3, (b[2] as MdBlock.Heading).level)
        assertEquals("One", (b[0] as MdBlock.Heading).text.text)
    }

    @Test
    fun `bullets and numbered items are separate blocks`() {
        val b = Markdown.parse("- first\n- second\n\n1. one\n2. two")
        val bullets = b.filterIsInstance<MdBlock.Bullet>()
        assertEquals(4, bullets.size)
        assertEquals(null, bullets[0].ordinal)
        assertEquals("1.", bullets[2].ordinal)
        assertEquals("one", bullets[2].text.text)
    }

    @Test
    fun `a wrapped bullet stays one bullet`() {
        val b = Markdown.parse("- a long item that\n  continues on the next line\n- second")
        val bullets = b.filterIsInstance<MdBlock.Bullet>()
        assertEquals(2, bullets.size)
        assertEquals("a long item that continues on the next line", bullets[0].text.text)
    }

    @Test
    fun `inline styles are applied and their markers removed`() {
        val s = Markdown.inline("**bold** and *italic* and `code` and ~~gone~~")
        assertEquals("bold and italic and code and gone", s.text)
        assertTrue("expected several styled spans", s.spanStyles.size >= 4)
    }

    @Test
    fun `an unmatched marker is shown literally instead of vanishing`() {
        assertEquals("2 * 3 = 6", Markdown.inline("2 * 3 = 6").text)
        assertEquals("a `dangling", Markdown.inline("a `dangling").text)
        assertEquals("**not closed", Markdown.inline("**not closed").text)
    }

    @Test
    fun `snake_case identifiers are not treated as emphasis`() {
        // This one bites constantly in a codebase full of file_path and tool_use.
        assertEquals("some_long_name here", Markdown.inline("some_long_name here").text)
    }

    @Test
    fun `a link keeps its label and appends the url only when it adds something`() {
        assertEquals("docs (https://x.test/a)", Markdown.inline("[docs](https://x.test/a)").text)
        assertEquals("https://x.test", Markdown.inline("[https://x.test](https://x.test)").text)
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val b = Markdown.parse("one\n\ntwo")
        assertEquals(2, b.filterIsInstance<MdBlock.Paragraph>().size)
    }

    @Test
    fun `a horizontal rule is its own block`() {
        val b = Markdown.parse("above\n\n---\n\nbelow")
        assertTrue(b.any { it is MdBlock.Rule })
    }

    @Test
    fun `a blockquote is recognised`() {
        val q = Markdown.parse("> quoted text").first() as MdBlock.Quote
        assertEquals("quoted text", q.text.text)
    }

    @Test
    fun `plain prose with no markup survives unchanged`() {
        val text = "Disk is at 62% and the daemon is healthy."
        assertEquals(text, (Markdown.parse(text).first() as MdBlock.Paragraph).text.text)
    }
}
